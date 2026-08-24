package com.bigbangcraft.expeditions.reset;

import com.bigbangcraft.expeditions.loot.LootPolicy;
import com.bigbangcraft.expeditions.sector.SectorRecord;
import com.bigbangcraft.expeditions.sector.SectorState;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds and stores reset-plan manifests under
 * <server>/bigbangexpeditions/reset-plans/<planId>.json.
 *
 * The plan step exists so the destructive offline executor consumes a
 * checksummed, reviewed artifact — never a live command intention.
 */
public final class ResetPlanService {
    private ResetPlanService() {}

    public static Path plansDir(MinecraftServer server) {
        return server.getServerDirectory().toPath().resolve("bigbangexpeditions/reset-plans");
    }

    /**
     * Result of planning: either a written manifest path or refusal reasons.
     */
    public static final class PlanOutcome {
        public final Path file;
        public final List<String> refusals;

        private PlanOutcome(Path file, List<String> refusals) {
            this.file = file;
            this.refusals = refusals;
        }

        public boolean ok() {
            return file != null;
        }
    }

    public static PlanOutcome createPlan(MinecraftServer server,
                                         SectorRecord sector,
                                         String createdBy) {
        List<String> refusals = new ArrayList<>();

        // hard gates before anything is written
        if (!"bigbangexpeditions:expedition".equals(sector.dimension)) {
            refusals.add("dimension not allowed: " + sector.dimension);
        }
        if (sector.status != SectorState.LOCKED && sector.status != SectorState.RESET_PLANNED) {
            refusals.add("sector must be LOCKED before planning (current: " + sector.status + ")");
        }
        if (sector.lastBaselineId == null || sector.lastBaselineId.isEmpty()) {
            refusals.add("no baseline captured for sector '" + sector.id + "'");
        }
        if (!refusals.isEmpty()) return new PlanOutcome(null, refusals);

        // fingerprints
        Optional<String> profileName = com.bigbangcraft.expeditions.integration.lostcities.LostCitiesAdapter
                .getProfileById(new net.minecraft.resources.ResourceLocation("bigbangexpeditions", "expedition"));
        String fingerprint = "";
        if (profileName.isPresent()) {
            Path lcConfig = server.getServerDirectory().toPath().resolve("config/lostcities");
            fingerprint = com.bigbangcraft.expeditions.integration.lostcities.LostCitiesAdapter
                    .getProfileFingerprint(lcConfig, profileName.get()).orElse("");
        }
        if (fingerprint.isEmpty()) {
            refusals.add("Lost Cities profile fingerprint unavailable — refusing to plan blind");
        }

        long overworldSeed = server.overworld().getSeed();
        String seedHash = Long.toHexString(overworldSeed);

        // expected region files from validated bounds only
        int minRx = Math.floorDiv(sector.minChunkX, 32);
        int maxRx = Math.floorDiv(sector.maxChunkX, 32);
        int minRz = Math.floorDiv(sector.minChunkZ, 32);
        int maxRz = Math.floorDiv(sector.maxChunkZ, 32);
        List<String> files = new ArrayList<>();
        for (int rx = minRx; rx <= maxRx; rx++) {
            for (int rz = minRz; rz <= maxRz; rz++) {
                files.add(PathConfinement.regionFileName(rx, rz));
            }
        }

        ResetPlanManifest m = new ResetPlanManifest();
        m.planId = UUID.randomUUID().toString();
        m.sectorId = sector.id;
        m.dimension = sector.dimension;
        m.minChunkX = sector.minChunkX;
        m.minChunkZ = sector.minChunkZ;
        m.maxChunkX = sector.maxChunkX;
        m.maxChunkZ = sector.maxChunkZ;
        m.expectedRegionFiles = files;
        m.baselineId = sector.lastBaselineId;
        m.sectorResetCountAtPlanTime = sector.resetCount;
        m.profileFingerprint = fingerprint;
        m.worldSeedHash = seedHash;
        m.createdAtEpochMs = System.currentTimeMillis();
        m.createdBy = createdBy == null ? "" : createdBy;
        m.computeChecksum();

        try {
            Path dir = plansDir(server);
            Files.createDirectories(dir);
            Path out = dir.resolve(m.planId + ".json");
            Files.writeString(out, m.toJson());
            return new PlanOutcome(out, refusals);
        } catch (IOException e) {
            refusals.add("manifest write failed: " + e.getMessage());
            return new PlanOutcome(null, refusals);
        }
    }

    /** Loads + verifies a manifest by plan id. Null-safe fail-closed. */
    public static ResetPlanManifest loadVerified(MinecraftServer server, String planId) {
        if (planId == null || !planId.matches("[0-9a-fA-F\\-]{36}")) return null;
        try {
            Path p = plansDir(server).resolve(planId + ".json");
            if (!Files.isRegularFile(p)) return null;
            ResetPlanManifest m = ResetPlanManifest.fromJson(Files.readString(p));
            if (m == null || !m.checksumValid()) return null;
            if (!"bigbangexpeditions:expedition".equals(m.dimension)) return null;
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    /** Convenience used by commands: policy load is part of every gate. */
    public static LootPolicy loadPolicy() {
        return LootPolicy.loadEmbedded();
    }
}
