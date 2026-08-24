package com.bigbangcraft.expeditions.gameplay;

import com.bigbangcraft.expeditions.audit.AuditEvent;
import com.bigbangcraft.expeditions.core.BbeLayout;
import com.bigbangcraft.expeditions.core.RuntimeServices;
import com.bigbangcraft.expeditions.i18n.Translations;
import com.bigbangcraft.expeditions.integration.lostcities.LostCitiesAdapter;
import com.bigbangcraft.expeditions.lifecycle.EvacuationService;
import com.bigbangcraft.expeditions.lifecycle.LifecycleRecord;
import com.bigbangcraft.expeditions.lifecycle.LifecycleState;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.sounds.SoundEvents;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.List;

/**
 * Drives the player-facing closing sequence and the opening ceremony (Goal 04).
 *
 * Closing: operator issues ONE close order → CLOSING with persisted deadline.
 * A throttled tick check emits escalating warnings (chat + action bar + alarm)
 * at configured offsets; at deadline the proven Goal 03 evacuation runs
 * automatically. Restart-safe: schedule state lives in the lifecycle record.
 *
 * Opening: VALIDATING→OPEN triggers the expedition-fantasy broadcast.
 */
public final class ClosureService {
    private static final Logger LOG = LogManager.getLogger("BigBangExpeditions/Closure");

    /** Extraction sequence re-entrancy guard (tick handler runs on main thread). */
    private static boolean extracting;

    /**
     * Idle fast-path: the tick handler must NOT touch disk every second while
     * no closing is scheduled. The flag is armed by the close order and by
     * boot recovery (restart mid-CLOSING), disarmed when extraction finishes
     * or the schedule is aborted.
     */
    private static volatile boolean scheduleActive;

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void onServerStarted(net.minecraftforge.event.server.ServerStartedEvent e) {
        try {
            var r = RuntimeServices.get(e.getServer()).lifecycle().current();
            scheduleActive = r.status == com.bigbangcraft.expeditions.lifecycle.LifecycleState.CLOSING
                    && r.closingDeadlineEpochMs > 0;
        } catch (Exception ex) {
            LOG.warn("closing-schedule boot probe failed: {}", ex.toString());
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        if (!scheduleActive) return;
        // 1-second cadence — countdown UX does not need per-tick precision
        if ((e.getServer().getTickCount() % 20) != 0) return;
        try {
            tick(e.getServer());
        } catch (Exception ex) {
            LOG.error("closure tick failed: {}", ex.toString());
        }
    }

    private static void tick(MinecraftServer server) throws IOException {
        RuntimeServices services = RuntimeServices.get(server);
        LifecycleRecord r = services.lifecycle().current();
        if (r.status != com.bigbangcraft.expeditions.lifecycle.LifecycleState.CLOSING
                || r.closingDeadlineEpochMs <= 0) {
            scheduleActive = false;
            return;
        }

        GameplayConfig config = loadConfig(server);
        long now = System.currentTimeMillis();

        List<Integer> due = ClosingSchedule.dueWarnings(
                config.effectiveWarningOffsets(), r.closingDeadlineEpochMs, now, r.lastClosingWarnMinutes);
        for (int minutes : due) {
            Broadcast.announce(server, config,
                    minutes == 1 ? "bbe.closing.warn.one_minute" : "bbe.closing.warn.minutes", minutes);
            Broadcast.playAlarm(server, config, SoundEvents.NOTE_BLOCK_BELL.value());
            services.lifecycle().markClosingWarned(minutes);
            audit(services, "CLOSING_WARNING", "t-" + minutes + "m");
        }

        if (ClosingSchedule.extractionDue(r.closingDeadlineEpochMs, now) && !extracting) {
            extracting = true;
            try {
                runExtraction(server, services, config);
            } finally {
                extracting = false;
            }
        }
    }

    /** The Goal-03-proven evacuation chain, triggered by deadline instead of by hand. */
    private static void runExtraction(MinecraftServer server, RuntimeServices services,
                                      GameplayConfig config) throws IOException {
        long start = System.currentTimeMillis();
        String actor = "closing-schedule";

        int evacuated = EvacuationService.evacuateAll(server, services, actor);
        services.lifecycle().transition(LifecycleState.EVACUATING, actor,
                "evacuated " + evacuated + " player(s)");
        services.lifecycle().clearClosingSchedule();
        scheduleActive = false;

        var level = server.getLevel(LostCitiesAdapter.expeditionDimensionKey());
        int stillInside = level == null ? 0 : EvacuationService.playersInside(level).size();
        if (stillInside > 0) {
            String reason = stillInside + " player(s) still inside after extraction";
            services.lifecycle().transition(LifecycleState.FAILED, actor, reason);
            services.auditRefusal("LIFECYCLE_CLOSE", actor, reason);
            LOG.error("extraction incomplete: {}", reason);
            return;
        }

        var err = services.lifecycle().transition(LifecycleState.LOCKED, actor, "dimension locked");
        if (err.isPresent()) {
            services.auditRefusal("LIFECYCLE_CLOSE", actor, err.get());
            return;
        }
        syncSectorLock(server);

        Broadcast.announce(server, config, "bbe.closing.now");
        services.audit().record(AuditEvent.of("LIFECYCLE_CLOSE", actor)
                .states(LifecycleState.CLOSING.name(), LifecycleState.LOCKED.name())
                .outcome("OK").duration(System.currentTimeMillis() - start)
                .detail("evacuated", "" + evacuated));
        LOG.info("scheduled extraction complete: {} evacuated", evacuated);
    }

    /** Best-effort sector mirror of LOCKED, same discipline as the manual close path. */
    private static void syncSectorLock(MinecraftServer server) {
        try {
            var view = com.bigbangcraft.expeditions.reset.ProductionResetFlow.sectorView(server);
            if (view.first() == null) return;
            if (view.first().status == com.bigbangcraft.expeditions.sector.SectorState.LOCKED) return;
            var err = view.registry().transition(view.first().id,
                    com.bigbangcraft.expeditions.sector.SectorState.LOCKED, System.currentTimeMillis());
            if (err.isEmpty()) view.registry().save();
        } catch (Exception e) {
            LOG.warn("sector sync failed (non-fatal): {}", e.toString());
        }
    }

    // ------------------------------------------------------------- entry API

    /**
     * Begins a timed closing. Returns refusal message or empty on success.
     * Broadcasts the schedule announcement.
     */
    public static String beginTimedClosing(MinecraftServer server, RuntimeServices services,
                                           String actor, int durationMinutesOverride) {
        GameplayConfig config = loadConfig(server);
        int duration = durationMinutesOverride > 0 ? durationMinutesOverride : config.closingDurationMinutes();
        long deadline = System.currentTimeMillis() + duration * 60_000L;
        try {
            var err = services.lifecycle().startClosing(deadline, actor);
            if (err.isPresent()) return err.get();
        } catch (IOException e) {
            return "persist error: " + e.getMessage();
        }
        scheduleActive = true;
        Component line = Component.literal(Translations.t("bbe.closing.started", duration));
        for (var p : server.getPlayerList().getPlayers()) p.sendSystemMessage(line);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(
                new com.bigbangcraft.expeditions.event.BbeEvents.ExpeditionClosingStarted(deadline, duration));
        Broadcast.playAlarm(server, config, SoundEvents.NOTE_BLOCK_BELL.value());
        try {
            services.audit().record(AuditEvent.of("CLOSING_STARTED", actor)
                    .outcome("OK")
                    .detail("deadlineEpochMs", String.valueOf(deadline))
                    .detail("durationMinutes", String.valueOf(duration)));
        } catch (Exception ignored) {
        }
        return null;
    }

    public static String abortClosing(MinecraftServer server, RuntimeServices services, String actor) {
        try {
            var err = services.lifecycle().abortClosing(actor);
            if (err.isEmpty()) {
                scheduleActive = false;
                Broadcast.announce(server, loadConfig(server), "bbe.status.line",
                        Translations.t("bbe.state.open"));
            }
            return err.orElse(null);
        } catch (IOException e) {
            return "persist error: " + e.getMessage();
        }
    }

    /** Expedition-fantasy opening broadcast after a validated reopen. */
    public static void announceOpening(MinecraftServer server, int generation) {
        GameplayConfig config = loadConfig(server);
        if (!config.openingAnnouncementEnabled() || !config.announcementsEnabled()) return;
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(
                new com.bigbangcraft.expeditions.event.BbeEvents.ExpeditionOpened(generation));
        Component title = Component.literal(Translations.t("bbe.opening.title"));
        Component body = Component.literal(Translations.t("bbe.opening.body", generation));
        for (var p : server.getPlayerList().getPlayers()) {
            p.sendSystemMessage(title);
            p.sendSystemMessage(body);
        }
        Broadcast.playAlarm(server, config, SoundEvents.PLAYER_LEVELUP);
        try {
            RuntimeServices.get(server).audit().record(
                    AuditEvent.of("OPENING_ANNOUNCED", "lifecycle")
                            .outcome("OK")
                            .detail("generation", String.valueOf(generation)));
        } catch (Exception ignored) {
        }
    }

    private static GameplayConfig cached;

    private static GameplayConfig loadConfig(MinecraftServer server) {
        // cache per process; operators restart to apply changes (same as env profile)
        if (cached == null) {
            cached = GameplayConfig.load(BbeLayout.configDir(server).resolve("gameplay.properties"));
        }
        return cached;
    }

    /** Test hook. */
    public static void resetCache() {
        cached = null;
    }

    private static void audit(RuntimeServices services, String event, String detail) {
        try {
            services.audit().record(AuditEvent.of(event, "closing-schedule")
                    .outcome("OK").detail("threshold", detail));
        } catch (Exception ignored) {
        }
    }
}
