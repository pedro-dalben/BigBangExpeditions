package com.bigbangcraft.expeditions.reset;

import com.bigbangcraft.expeditions.auth.SavedDataClassification;
import com.bigbangcraft.expeditions.env.EnvironmentConfig;
import com.bigbangcraft.expeditions.env.EnvironmentProfile;
import com.bigbangcraft.expeditions.env.EnvironmentProperties;
import com.bigbangcraft.expeditions.env.FingerprintCollector;
import com.bigbangcraft.expeditions.env.InstallFingerprint;
import com.bigbangcraft.expeditions.sector.SectorRegistry;
import com.bigbangcraft.expeditions.validation.BaselineData;
import com.bigbangcraft.expeditions.validation.BaselineService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.nio.file.Path;
import java.util.HashMap;

/**
 * Collects live server state into the pure pipeline inputs.
 * The only Minecraft-facing layer between commands and the testable core.
 */
public final class ProductionResetFlow {

    private ProductionResetFlow() {}

    public static EnvironmentProfile resolveEnvironment(MinecraftServer server) {
        try {
            Path configDir = com.bigbangcraft.expeditions.core.BbeLayout.configDir(server);
            var props = EnvironmentProperties.load(EnvironmentProperties.defaultFile(configDir));
            String ack = EnvironmentProperties.loadAck(EnvironmentProperties.ackFile(configDir));
            InstallFingerprint current = FingerprintCollector.collect(server);
            EnvironmentConfig cfg = EnvironmentConfig.resolve(props, ack, current.shortHash());
            return cfg.profile();
        } catch (Exception e) {
            return EnvironmentProfile.STAGING; // fail-closed
        }
    }

    public static SectorRecordView sectorView(MinecraftServer server) {
        SectorRegistry registry = new SectorRegistry(
                com.bigbangcraft.expeditions.core.BbeLayout.sectorsFile(server));
        return new SectorRecordView(registry, registry.list().isEmpty() ? null : registry.list().get(0));
    }

    public record SectorRecordView(SectorRegistry registry,
                                   com.bigbangcraft.expeditions.sector.SectorRecord first) {}

    public static AuthorizationService.IssueInputs collectInputs(MinecraftServer server) throws Exception {
        var view = sectorView(server);
        if (view.first == null) throw new IllegalStateException("no sector registered — run /expedition sector create");

        ServerLevel level = server.getLevel(
                com.bigbangcraft.expeditions.integration.lostcities.LostCitiesAdapter.expeditionDimensionKey());
        var probe = BaselineService.probe(server, level, view.first.toBounds());

        // baseline census by type (may be empty when no baseline file exists)
        HashMap<String, Integer> baselineByType = new HashMap<>();
        if (!view.first.lastBaselineId.isEmpty()) {
            try {
                Path f = newestBaselineFile(com.bigbangcraft.expeditions.core.BbeLayout
                        .baselinesDir(server), view.first.lastBaselineId);
                if (f != null && java.nio.file.Files.isRegularFile(f)) {
                    BaselineData d = BaselineService.readBaseline(f);
                    if (d != null && d.blockEntitiesByType != null) baselineByType.putAll(d.blockEntitiesByType);
                }
            } catch (Exception ignored) {
                // missing/unreadable baseline: preflight BASELINE gate still applies via lastBaselineId
            }
        }

        var live = new com.bigbangcraft.expeditions.safety.SectorLiveState() {
            @Override public int playersInside() { return probe.playersInside; }
            @Override public int claimedChunks() { return probe.opacIntersectingChunks; }
            @Override public int forceloadedChunks() { return probe.opacForceloads; }
            @Override public java.util.Map<String, Integer> blockEntitiesByType() {
                return probe.blockEntitiesByType == null ? java.util.Map.of() : probe.blockEntitiesByType;
            }
            @Override public boolean scanIncomplete() { return !probe.opacAvailable; }
        };

        AuthorizationService.IssueInputs in = new AuthorizationService.IssueInputs();
        in.env = resolveEnvironment(server);
        in.scope = ResetAuthorization.SCOPE_DIMENSION; // production shape (B3 decision)
        in.sector = view.first;
        in.requiredState = com.bigbangcraft.expeditions.sector.SectorState.LOCKED;
        in.live = live;
        in.baselineByType = baselineByType;
        in.lootPolicy = com.bigbangcraft.expeditions.loot.LootPolicy.loadEmbedded();
        in.savedDataClassification = SavedDataClassification.KNOWN_OWNERS;
        in.currentFingerprint = FingerprintCollector.collect(server);
        in.qualificationFingerprint = QualificationStore.loadQualification(
                com.bigbangcraft.expeditions.core.BbeLayout.configDir(server));

        var lifecycle = com.bigbangcraft.expeditions.core.RuntimeServices.get(server).lifecycle().current();
        in.lifecycleGeneration = lifecycle.generation;
        return in;
    }

    /**
     * Baseline files are written as {@code <id>_<dim>_<timestamp>.json}; resolve
     * the NEWEST file whose name starts with the baseline id. Returns null when
     * none matches.
     */
    static Path newestBaselineFile(Path baselinesDir, String baselineId) throws java.io.IOException {
        if (!java.nio.file.Files.isDirectory(baselinesDir)) return null;
        Path best = null;
        long bestTs = Long.MIN_VALUE;
        try (var stream = java.nio.file.Files.list(baselinesDir)) {
            for (Path p : (Iterable<Path>) stream.filter(f -> {
                String n = f.getFileName().toString();
                return n.startsWith(baselineId + "_") && n.endsWith(".json");
            }).toList()) {
                String n = p.getFileName().toString();
                int dot = n.lastIndexOf('.');
                int dash = n.lastIndexOf('_');
                long ts = dash >= 0 ? parseSafe(n.substring(dash + 1, dot)) : Long.MIN_VALUE;
                if (ts >= bestTs) { bestTs = ts; best = p; }
            }
        }
        return best;
    }

    private static long parseSafe(String s) {
        try { return Long.parseLong(s); } catch (Exception e) { return Long.MIN_VALUE; }
    }

    public static DryRunEngine.DiskProbe diskProbe(MinecraftServer server) {
        long avail = server.getServerDirectory().toPath().toFile().getUsableSpace();
        ServerLevel level = server.getLevel(
                com.bigbangcraft.expeditions.integration.lostcities.LostCitiesAdapter.expeditionDimensionKey());
        long used = 0;
        if (level != null) {
            used = DimensionSizeEstimator.estimate(level);
        }
        final long fAvail = avail;
        final long fUsed = used;
        return new DryRunEngine.DiskProbe() {
            @Override public long availableBytes() { return fAvail; }
            @Override public long usableDimensionBytes() { return fUsed; }
        };
    }

    /** Cheap recursive size estimate of the expedition dimension folder. */
    private static final class DimensionSizeEstimator {
        static long estimate(ServerLevel level) {
            try {
                var id = com.bigbangcraft.expeditions.integration.lostcities.LostCitiesAdapter
                        .expeditionDimensionId();
                Path dim = level.getServer().getServerDirectory().toPath()
                        .resolve("world/dimensions")
                        .resolve(id.getNamespace())
                        .resolve(id.getPath());
                if (!java.nio.file.Files.isDirectory(dim)) return 0;
                try (var walk = java.nio.file.Files.walk(dim)) {
                    return walk.filter(java.nio.file.Files::isRegularFile)
                            .mapToLong(p -> {
                                try { return java.nio.file.Files.size(p); } catch (Exception e) { return 0L; }
                            }).sum();
                }
            } catch (Exception e) {
                return 0;
            }
        }
    }
}
