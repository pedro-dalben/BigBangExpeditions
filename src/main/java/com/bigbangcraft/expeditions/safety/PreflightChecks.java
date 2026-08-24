package com.bigbangcraft.expeditions.safety;

import com.bigbangcraft.expeditions.loot.LootPolicy;
import com.bigbangcraft.expeditions.sector.SectorRecord;
import com.bigbangcraft.expeditions.sector.SectorState;

import java.util.Map;
import java.util.TreeMap;

/**
 * Pure preflight checks — no Minecraft imports, fully unit-testable.
 * The engine (ResetPreflightEngine) adapts live server data into these calls.
 */
public final class PreflightChecks {
    private static final String ALLOWED_DIMENSION = "bigbangexpeditions:expedition";

    private PreflightChecks() {}

    public static void checkDimension(ResetPreflightResult r, SectorRecord sector) {
        if (!ALLOWED_DIMENSION.equals(sector.dimension)) {
            r.error("DIMENSION_NOT_ALLOWED",
                    "reset restricted to " + ALLOWED_DIMENSION + ", got " + sector.dimension);
        }
    }

    public static void checkGuard(ResetPreflightResult r, ProductionGuard guard) {
        if (guard.isProduction()) {
            r.error("PRODUCTION_ENVIRONMENT", "production environment is never a reset target");
        } else if (!guard.destructiveAllowed()) {
            // Goal 02 default: planning allowed, execution blocked at executor level
            r.warn("DESTRUCTIVE_DISABLED", "allowDestructiveReset=false — offline executor will refuse");
        }
    }

    /**
     * Goal 03 environment gate. Planning the full decision pipeline is legal in
     * every environment; STAGING additionally notes that real destruction is
     * unavailable there. PRODUCTION/DRY_RUN pass (execution still requires an
     * authorization artifact + offline executor).
     */
    public static void checkEnvironment(ResetPreflightResult r,
                                        com.bigbangcraft.expeditions.env.EnvironmentProfile env) {
        if (env == null) {
            r.error("ENV_UNRESOLVED", "environment profile unresolved — fail-closed");
            return;
        }
        switch (env) {
            case STAGING -> r.warn("ENV_STAGING",
                    "staging: full pipeline runs, destructive execution unavailable by design");
            case PRODUCTION_DRY_RUN -> { /* expected to mirror production decisions */ }
            case PRODUCTION -> { /* authorized path; execution remains offline+locked */ }
        }
    }

    public static void checkSectorState(ResetPreflightResult r, SectorRecord sector,
                                        SectorState required) {
        if (sector.status != required) {
            r.error("SECTOR_STATE", "sector '" + sector.id + "' is " + sector.status
                    + ", required: " + required);
        }
    }

    /** Region alignment re-check straight from stored bounds. */
    public static void checkBoundsAligned(ResetPreflightResult r, SectorRecord sector) {
        boolean alignedX = sector.minChunkX % 32 == 0 && (sector.maxChunkX - sector.minChunkX + 1) % 32 == 0;
        boolean alignedZ = sector.minChunkZ % 32 == 0 && (sector.maxChunkZ - sector.minChunkZ + 1) % 32 == 0;
        if (!alignedX || !alignedZ) {
            r.error("BOUNDS_UNALIGNED", "sector bounds are not region-aligned: "
                    + sector.minChunkX + ".." + sector.maxChunkX + ", " + sector.minChunkZ + ".." + sector.maxChunkZ);
        }
    }

    public static void checkLiveScanComplete(ResetPreflightResult r, SectorLiveState live) {
        if (live.scanIncomplete()) {
            r.error("SCAN_INCOMPLETE", "live sector scan failed — refusing on incomplete evidence");
        }
    }

    public static void checkPlayers(ResetPreflightResult r, SectorLiveState live) {
        int n = live.playersInside();
        if (n > 0) {
            r.error("PLAYERS_INSIDE", n + " player(s) currently inside sector");
        }
    }

    public static void checkClaims(ResetPreflightResult r, SectorLiveState live) {
        if (live.claimedChunks() > 0) {
            r.error("CLAIMS_INTERSECT", live.claimedChunks() + " claimed chunk(s) intersect sector");
        }
        if (live.forceloadedChunks() > 0) {
            r.warn("FORCELOADS", live.forceloadedChunks() + " forceloaded chunk(s) in sector");
        }
    }

    /**
     * Building policy gate (Goal 04 rework).
     *
     * SECTOR scope (staging pipeline): the live block-entity census must not
     * exceed baseline for ANY type — extras mean player construction and hard-
     * refuse, exactly as in Goal 02/03.
     *
     * DIMENSION scope (production whole-zone turnover): extras are EXPECTED
     * temporary-territory content. They never silently pass: without an
     * explicit operator acknowledgment bound to the exact delta the reset is
     * refused (PURGE_ACK_REQUIRED); with it, a quantified warning is recorded.
     */
    public static void checkNoPlayerAdditions(ResetPreflightResult r,
                                              Map<String, Integer> baselineByType,
                                              Map<String, Integer> liveByType,
                                              String scope,
                                              boolean purgeAcknowledged) {
        com.bigbangcraft.expeditions.reset.PurgeManifest manifest =
                com.bigbangcraft.expeditions.reset.PurgeManifest.of(baselineByType, liveByType);
        if (manifest.isEmpty()) return;

        if (com.bigbangcraft.expeditions.reset.ResetAuthorization.SCOPE_DIMENSION.equals(scope)) {
            if (!purgeAcknowledged) {
                r.error("PURGE_ACK_REQUIRED", "player additions vs baseline: "
                        + manifest.summarize(5)
                        + " — confirm purge with manifest " + manifest.hash().substring(0, 12));
            } else {
                r.warn("PURGE_ACKNOWLEDGED", "destroying player additions: "
                        + manifest.summarize(5));
            }
            return;
        }
        StringBuilder sb = new StringBuilder("player-added block entities vs baseline:");
        manifest.extras().forEach((k, v) -> sb.append(' ').append(k).append("(+").append(v).append(')'));
        r.error("PLAYER_ADDITIONS", sb.toString());
    }

    /** Loot classification gate: every item type seen must be classified. */
    public static void checkLootPolicy(ResetPreflightResult r, LootPolicy policy) {
        // policy load itself fails closed; here verify the audited anchors exist
        if (policy.classify("deceasedcraft:research_paper_1") == LootPolicy.ItemClass.UNKNOWN) {
            r.error("LOOT_POLICY_INVALID", "audited progression item unclassified — policy file stale");
        }
    }

    /** SavedData inventory gate: UNKNOWN owners block qualification. */
    public static void checkSavedDataOwners(ResetPreflightResult r, Map<String, String> ownerClassification) {
        ownerClassification.forEach((file, cls) -> {
            if ("UNKNOWN".equalsIgnoreCase(cls)) {
                r.error("SAVEDDATA_UNKNOWN", "unclassified SavedData owner: " + file);
            }
        });
    }

    /** Baseline presence. */
    public static void checkBaselineExists(ResetPreflightResult r, SectorRecord sector) {
        if (sector.lastBaselineId == null || sector.lastBaselineId.isEmpty()) {
            r.error("BASELINE_MISSING", "no baseline captured for sector '" + sector.id + "'");
        }
    }
}
