package com.bigbangcraft.expeditions.telemetry;

import com.bigbangcraft.expeditions.automation.AutomationConfig;
import com.bigbangcraft.expeditions.core.BbeLayout;
import com.bigbangcraft.expeditions.event.BbeEvents;
import com.bigbangcraft.expeditions.integration.lostcities.LostCitiesAdapter;
import com.bigbangcraft.expeditions.integration.structures.StructureProbe;
import com.bigbangcraft.expeditions.lifecycle.EvacuationService;
import com.bigbangcraft.expeditions.lifecycle.LifecycleRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.Container;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Live ingestion of expedition telemetry (Goal 05 WS2).
 *
 * <p>Discipline inherited from Goal 04's closing fast-path: the tick handler
 * performs ZERO disk IO unless a flush is due on a dirty record; sampling is
 * per-player staggered and hard-gated on the expedition dimension.
 *
 * <p>Generation contract: every fact is stamped with the generation it
 * belonged to; facts from any other generation are refused by the model.
 * Rollover happens only through lifecycle reality (ExpeditionOpened /
 * boot-time binding against lifecycle.json), never through timers.
 */
public final class TelemetryService {
    private static final Logger LOG = LogManager.getLogger("BigBangExpeditions/Telemetry");
    private static final int CONTAINER_DEDUPE_WINDOW_MS = 60_000;
    private static final int CONTAINER_DEDUPE_CAPACITY = 4096;
    private static final int CONCURRENCY_SAMPLE_EVERY = 6; // samples (~30s at 5s cadence)

    private static volatile TelemetryService instance;
    private static volatile AutomationConfig config;

    private final MinecraftServer server;
    private final TelemetryStore store;
    private final CycleArchiveStore archiveStore;
    private final InteractionDeduper containerDedupe =
            new InteractionDeduper(CONTAINER_DEDUPE_WINDOW_MS, CONTAINER_DEDUPE_CAPACITY);

    private GenerationTelemetry current;
    private TelemetrySnapshot.Availability availability = TelemetrySnapshot.Availability.MISSING;
    private String unavailableDetail = "";
    private boolean dirty;
    private long lastFlushMs;
    private long tickCursor;

    private TelemetryService(MinecraftServer server) {
        this.server = server;
        this.store = new TelemetryStore(BbeLayout.telemetryDir(server));
        this.archiveStore = new CycleArchiveStore(BbeLayout.cycleArchiveFile(server));
    }

    // ------------------------------------------------------------- lifecycle

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent e) {
        try {
            config = AutomationConfig.load(BbeLayout.configDir(e.getServer())
                    .resolve("automation.properties"));
            instance = new TelemetryService(e.getServer());
            instance.boot();
        } catch (Exception ex) {
            LOG.error("telemetry boot failed — collection disabled this session: {}", ex.toString());
            instance = null;
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent e) {
        TelemetryService s = instance;
        if (s != null) {
            s.flushNow();
            instance = null;
        }
    }

    /** Boot binding: catch up unarchived generations, then bind to lifecycle generation. */
    void boot() throws IOException {
        catchUpUnarchivedGenerations();
        LifecycleRecord r = RuntimeLifecycle.get(server);
        if (r.status == com.bigbangcraft.expeditions.lifecycle.LifecycleState.RESETTING
                || r.status == com.bigbangcraft.expeditions.lifecycle.LifecycleState.BOOTING
                || r.status == com.bigbangcraft.expeditions.lifecycle.LifecycleState.VALIDATING) {
            // mid-renewal boot: generation will advance at validated reopen;
            // binding NOW would attach a ghost record to the dying generation.
            // ExpeditionOpened binds the fresh one at open; until then facts are
            // refused and evaluations read unavailable — the safe direction.
            audit("TELEMETRY_BIND_DEFERRED", "status=" + r.status);
            return;
        }
        bind(r.generation, r.lastOpenedAtEpochMs);
    }

    private void catchUpUnarchivedGenerations() {
        try {
            CycleArchive archive = archiveStore.loadTolerant();
            Path dir = BbeLayout.telemetryDir(server);
            if (!Files.isDirectory(dir)) return;
            LifecycleRecord r = RuntimeLifecycle.get(server);
            try (Stream<Path> files = Files.list(dir)) {
                for (Path f : files.filter(p -> p.getFileName().toString().startsWith("gen-")).toList()) {
                    String name = f.getFileName().toString(); // gen-<N>.json
                    if (!name.endsWith(".json")) continue;
                    int gen;
                    try {
                        gen = Integer.parseInt(name.substring(4, name.length() - 5));
                    } catch (NumberFormatException nfe) {
                        continue;
                    }
                    if (gen >= r.generation) continue;      // current or future — untouched
                    if (archive.byGeneration(gen) != null) { // already summarized
                        tryDelete(f);
                        continue;
                    }
                    // closed but never archived (crash between close and archive)
                    TelemetryStore.LoadResult lr = store.load(gen);
                    if (lr.status == TelemetryStore.Status.AVAILABLE && lr.record.isClosed()) {
                        archive.append(CycleSummary.of(lr.record, "unrecorded-restart", "telemetry-catchup",
                                lr.record.closedAtEpochMs == null ? System.currentTimeMillis() : lr.record.closedAtEpochMs));
                        archiveStore.save(archive);
                        tryDelete(f);
                        audit("TELEMETRY_CATCHUP", "gen=" + gen + " archived after restart");
                    } else if (lr.status == TelemetryStore.Status.AVAILABLE) {
                        // stale OPEN file from an interrupted cycle: keep bytes, do not guess
                        audit("TELEMETRY_STALE_OPEN_KEPT", "gen=" + gen);
                    }
                }
            }
        } catch (Exception ex) {
            LOG.warn("telemetry catch-up failed (non-fatal): {}", ex.toString());
        }
    }

    /** Bind in-memory record to a generation. Refuses cross-generation mutation implicitly. */
    synchronized void bind(int generation, long openedAtEpochMs) {
        TelemetryStore.LoadResult r = store.load(generation);
        switch (r.status) {
            case AVAILABLE -> {
                current = r.record;
                availability = TelemetrySnapshot.Availability.AVAILABLE;
                if (current.openedAtEpochMs <= 0 && openedAtEpochMs > 0) {
                    current.openedAtEpochMs = openedAtEpochMs;
                    dirty = true;
                }
            }
            case MISSING -> {
                GenerationTelemetry t = new GenerationTelemetry(generation,
                        Math.max(openedAtEpochMs, 0L));
                current = t;
                dirty = true;
                availability = TelemetrySnapshot.Availability.AVAILABLE;
                flushNow();
            }
            case UNSUPPORTED_SCHEMA -> {
                current = null;
                availability = TelemetrySnapshot.Availability.UNSUPPORTED_SCHEMA;
                unavailableDetail = r.detail;
                audit("TELEMETRY_UNAVAILABLE", "unsupported schema: " + r.detail);
            }
            case CORRUPT -> {
                current = null;
                availability = TelemetrySnapshot.Availability.CORRUPT;
                unavailableDetail = r.detail;
                audit("TELEMETRY_UNAVAILABLE", "corrupt: " + r.detail);
            }
        }
    }

    /**
     * Close-out used by the completion path: mark closed, archive summary,
     * delete the per-generation file (bounded persistence). Idempotent.
     */
    public synchronized void finalizeGeneration(int generation, long closedAtEpochMs,
                                                String reason, String actor) {
        GenerationTelemetry t = current;
        if (t != null && t.generation != generation) {
            auditRefused("TELEMETRY_FINALIZE", "generation mismatch: have="
                    + t.generation + " asked=" + generation);
            return;
        }
        if (t == null) {
            // re-open from disk when possible so archival still happens after partial restarts
            TelemetryStore.LoadResult r = store.load(generation);
            if (r.status != TelemetryStore.Status.AVAILABLE) {
                auditRefused("TELEMETRY_FINALIZE", "no usable telemetry: " + r.status);
                return;
            }
            t = r.record;
        }
        if (!t.isClosed()) {
            t.markClosed(closedAtEpochMs);
            dirty = true;
        }
        try {
            CycleArchive archive = archiveStore.loadTolerant();
            archive.append(CycleSummary.of(t, reason, actor, t.closedAtEpochMs));
            archiveStore.save(archive);
            tryDelete(store.fileFor(generation));
            audit("TELEMETRY_CYCLE_ARCHIVED", "gen=" + generation + " reason=" + reason);
        } catch (IOException e) {
            LOG.warn("cycle archival failed for gen {} (will retry at next close/boot): {}",
                    generation, e.toString());
            flushNow(); // keep closed state on disk even if archival failed
        }
        current = null; // unbind until next open binds a fresh generation
        availability = TelemetrySnapshot.Availability.MISSING;
    }

    /** Rollover on validated reopen: bind fresh generation telemetry. */
    @SubscribeEvent
    public synchronized static void onOpened(BbeEvents.ExpeditionOpened e) {
        TelemetryService s = instance;
        if (s == null) return;
        GenerationTelemetry cur = s.current;
        if (cur != null && cur.generation == e.generation && !cur.isClosed()) return; // idempotent
        try {
            LifecycleRecord r = RuntimeLifecycle.get(s.server);
            s.bind(e.generation, r.lastOpenedAtEpochMs);
        } catch (Exception ex) {
            LOG.warn("telemetry rollover bind failed: {}", ex.toString());
        }
    }

    @SubscribeEvent
    public synchronized static void onCompleted(BbeEvents.ExpeditionCompleted e) {
        TelemetryService s = instance;
        if (s == null) return;
        s.finalizeGeneration(e.generation, e.closedAtEpochMs, "expedition-closed", "closure-service");
    }

    // ------------------------------------------------------------- ingestion

    @SubscribeEvent
    public static void onEntered(BbeEvents.PlayerEnteredExpedition e) {
        TelemetryService s = instance;
        if (s == null || !s.usable()) return;
        synchronized (s) {
            s.current.recordEntry(e.player.getUUID(), e.generation, System.currentTimeMillis());
            s.dirty = true;
        }
    }

    @SubscribeEvent
    public static void onLeft(BbeEvents.PlayerLeftExpedition e) {
        recordExit(e.player.getUUID());
    }

    @SubscribeEvent
    public static void onEvacuated(BbeEvents.PlayerEvacuated e) {
        TelemetryService s = instance;
        if (s == null || !s.usable()) return;
        synchronized (s) {
            if (!s.usable() || s.current == null) return;
            long now = System.currentTimeMillis();
            UUID id = e.player == null ? null : e.player.getUUID();
            if ("TELEPORT_OUT".equals(e.mode)) {
                s.current.recordEvacuation(s.current.generation, now);
            }
            if (id != null) s.current.recordExit(id, s.current.generation, now);
            s.dirty = true;
        }
    }

    private static void recordExit(UUID id) {
        TelemetryService s = instance;
        if (s == null || !s.usable()) return;
        synchronized (s) {
            if (!s.usable() || s.current == null || id == null) return;
            s.current.recordExit(id, s.current.generation, System.currentTimeMillis());
            s.dirty = true;
        }
    }

    /** Player movement/act sampler — staggered, dimension-gated, cheap. */
    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || e.side.isClient()) return;
        TelemetryService s = instance;
        if (s == null || !s.usable()) return;
        if (!(e.player instanceof ServerPlayer sp)) return;
        ServerLevel level = sp.serverLevel();
        if (!level.dimension().equals(LostCitiesAdapter.expeditionDimensionKey())) return;

        AutomationConfig cfg = config();
        int periodTicks = Math.max(20, cfg.sampleIntervalSeconds() * 20);
        long tick = sp.getServer().getTickCount();
        int offset = (sp.getUUID().hashCode() & 0x7fffffff) % periodTicks;
        if ((tick + offset) % periodTicks != 0) return;

        synchronized (s) {
            if (!s.usable() || s.current == null) return;
            long now = System.currentTimeMillis();
            int gen = s.current.generation;
            ChunkPos cp = sp.chunkPosition();
            boolean freshChunk = s.current.recordChunkFirstEntry(cp.toLong(), gen, now);
            if (freshChunk) {
                List<StructureProbe.Sighting> sightings = StructureProbe.probe(level, cp);
                for (StructureProbe.Sighting sight : sightings) {
                    s.current.recordStructure(sight.structureId, sight.packedSection, gen, now);
                }
                raiseStructureSignalFlagIfNeeded(s);
            }
            s.tickCursor++;
            if (s.tickCursor % CONCURRENCY_SAMPLE_EVERY == 0) {
                s.current.observeConcurrentInside(EvacuationService.playersInside(level).size(), gen, now);
            }
            s.dirty = true;
        }
    }

    private static void raiseStructureSignalFlagIfNeeded(TelemetryService s) {
        GenerationTelemetry t = s.current;
        if (t.probeChunks >= config().structureSignalGraceChunks()
                && t.totalStructurePlacements() == 0
                && !t.qualityFlags.contains("STRUCTURE_SIGNAL_ABSENT")) {
            t.qualityFlags.add("STRUCTURE_SIGNAL_ABSENT");
            s.audit("TELEMETRY_STRUCTURE_SIGNAL_ABSENT",
                    "probeChunks=" + t.probeChunks + " — structure component will read UNKNOWN");
        }
    }

    /** Container interactions via main-hand right-click with dedupe window. */
    @SubscribeEvent
    public static void onRightClick(PlayerInteractEvent.RightClickBlock e) {
        if (e.getHand() != InteractionHand.MAIN_HAND) return;
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        if (!level.dimension().equals(LostCitiesAdapter.expeditionDimensionKey())) return;
        TelemetryService s = instance;
        if (s == null || !s.usable()) return;
        BlockPos pos = e.getPos();
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof Container)) return;

        synchronized (s) {
            if (!s.usable() || s.current == null) return;
            String key = e.getEntity().getUUID() + ":" + pos.asLong();
            if (s.containerDedupe.tryAccept(key, System.currentTimeMillis())) {
                s.current.recordContainerOpen(e.getEntity().getUUID(), s.current.generation,
                        System.currentTimeMillis());
                s.dirty = true;
            }
        }
    }

    /** Deaths and player kills inside the expedition. */
    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent e) {
        TelemetryService s = instance;
        if (s == null || !s.usable()) return;
        if (!(e.getEntity().level() instanceof ServerLevel level)) return;
        if (!level.dimension().equals(LostCitiesAdapter.expeditionDimensionKey())) return;

        synchronized (s) {
            if (!s.usable() || s.current == null) return;
            long now = System.currentTimeMillis();
            int gen = s.current.generation;
            if (e.getEntity() instanceof ServerPlayer sp) {
                s.current.recordDeath(sp.getUUID(), gen, now);
                s.dirty = true;
                return;
            }
            if (e.getEntity() instanceof Enemy
                    && e.getSource().getEntity() instanceof ServerPlayer killer) {
                s.current.recordPlayerMobKill(killer.getUUID(), gen, now);
                s.dirty = true;
            }
        }
    }

    // ------------------------------------------------------------- flushing

    /** 1 Hz fast-path: no IO unless dirty AND interval elapsed. */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        TelemetryService s = instance;
        if (s == null || !s.dirty) return;
        if ((e.getServer().getTickCount() % 20) != 0) return;
        long now = System.currentTimeMillis();
        if (now - s.lastFlushMs < config().flushIntervalSeconds() * 1000L) return;
        s.flushNow();
    }

    public synchronized void flushNow() {
        GenerationTelemetry t = current;
        if (t == null) {
            dirty = false;
            return;
        }
        try {
            t.trimDays(GenerationTelemetry.DAY_WINDOW_MAX);
            store.save(t);
            dirty = false;
            lastFlushMs = System.currentTimeMillis();
        } catch (IOException io) {
            // advisory-grade data: keep dirty, retry next interval; log once per failure burst
            LOG.warn("telemetry flush failed (will retry): {}", io.toString());
        }
    }

    // ------------------------------------------------------------- reads

    /**
     * STAGING-ONLY synthetic activity injection for live automation campaigns.
     * Stamps first-entry chunks / container opens / deaths / structure
     * placements into the CURRENT generation through the exact ingest path.
     * Callers must have already refused non-staging environments.
     */
    public synchronized String stagingInject(int chunks, int opens, int deaths, int structures) {
        if (!usable() || current == null) return "telemetry unavailable";
        long now = System.currentTimeMillis();
        int gen = current.generation;
        java.util.UUID synthetic = java.util.UUID.nameUUIDFromBytes(
                ("staging-seed-" + gen).getBytes());
        for (int i = 0; i < chunks; i++) {
            current.recordChunkFirstEntry(java.util.concurrent.ThreadLocalRandom.current().nextLong(),
                    gen, now);
        }
        for (int i = 0; i < opens; i++) {
            current.recordContainerOpen(synthetic, gen, now);
        }
        for (int i = 0; i < deaths; i++) {
            current.recordDeath(synthetic, gen, now);
        }
        for (int i = 0; i < structures; i++) {
            current.recordStructure("staging:simulated_building",
                    java.util.concurrent.ThreadLocalRandom.current().nextLong(), gen, now);
        }
        dirty = true;
        flushNow();
        audit("TELEMETRY_STAGING_SEED", "gen=" + gen + " chunks=" + chunks
                + " opens=" + opens + " deaths=" + deaths + " structures=" + structures);
        return null;
    }

    public static boolean usable() {
        TelemetryService s = instance;
        return s != null && s.availability == TelemetrySnapshot.Availability.AVAILABLE && s.current != null;
    }

    /** Static staging seam: delegates to the live instance. */
    public static String stagingInjectStatic(int chunks, int opens, int deaths, int structures) {
        TelemetryService s = instance;
        if (s == null) return "telemetry service unavailable";
        return s.stagingInject(chunks, opens, deaths, structures);
    }

    /** Internal read model for engine/commands (requirement 58). */
    public static TelemetrySnapshot snapshot(int generation) {
        TelemetryService s = instance;
        if (s == null) return TelemetrySnapshot.unavailable(generation, TelemetrySnapshot.Availability.DISABLED);
        synchronized (s) {
            if (s.availability != TelemetrySnapshot.Availability.AVAILABLE || s.current == null) {
                return TelemetrySnapshot.unavailable(generation, s.availability);
            }
            return TelemetrySnapshot.of(s.current, TelemetrySnapshot.Availability.AVAILABLE);
        }
    }

    public static TelemetrySnapshot snapshotCurrentOr(int fallbackGeneration) {
        TelemetryService s = instance;
        int gen = (s != null && s.current != null) ? s.current.generation : fallbackGeneration;
        return snapshot(gen);
    }

    static CycleArchive loadArchive(MinecraftServer server) {
        return new CycleArchiveStore(BbeLayout.cycleArchiveFile(server)).loadTolerant();
    }

    private static void tryDelete(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
        }
    }

    private void audit(String event, String detail) {
        try {
            RuntimeServicesAudit.record(server, event, detail);
        } catch (Exception ignored) {
        }
    }

    private void auditRefused(String event, String reason) {
        try {
            RuntimeServicesAudit.refusal(server, event, reason);
        } catch (Exception ignored) {
        }
    }

    /** Test hooks. */
    public static void resetForTests() {
        instance = null;
        config = null;
    }

    static AutomationConfig config() {
        AutomationConfig c = config;
        if (c == null) c = AutomationConfig.defaults();
        return c;
    }

    static void configForTests(AutomationConfig c) {
        config = c;
    }

    /** Indirection seams so the service stays compile-testable without MC runtime. */
    private static final class RuntimeLifecycle {
        static LifecycleRecord get(MinecraftServer server) throws IOException {
            return com.bigbangcraft.expeditions.core.RuntimeServices
                    .get(server).lifecycle().current();
        }
    }

    private static final class RuntimeServicesAudit {
        static void record(MinecraftServer server, String event, String detail) throws java.io.IOException {
            com.bigbangcraft.expeditions.core.RuntimeServices.get(server).audit().record(
                    com.bigbangcraft.expeditions.audit.AuditEvent.of(event, "telemetry")
                            .outcome("OK").detail("detail", detail));
        }

        static void refusal(MinecraftServer server, String event, String reason) {
            com.bigbangcraft.expeditions.core.RuntimeServices.get(server)
                    .auditRefusal(event, "telemetry", reason);
        }
    }
}
