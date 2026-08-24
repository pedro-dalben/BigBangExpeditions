package com.bigbangcraft.expeditions.safety;

import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Runs all preflight checks in a fixed order and aggregates every finding.
 * Validators never short-circuit: operators see the full refusal picture.
 */
public final class ResetPreflightEngine {

    public ResetPreflightResult validate(ResetPlanInput input) {
        ResetPreflightResult r = new ResetPreflightResult();

        if (input.env != null) {
            PreflightChecks.checkEnvironment(r, input.env);
        } else {
            PreflightChecks.checkGuard(r, input.guard);
        }
        PreflightChecks.checkDimension(r, input.sector);
        PreflightChecks.checkBoundsAligned(r, input.sector);
        PreflightChecks.checkSectorState(r, input.sector, input.requiredState);
        PreflightChecks.checkBaselineExists(r, input.sector);

        if (input.live != null) {
            PreflightChecks.checkLiveScanComplete(r, input.live);
            if (r.passed() || true) { // always aggregate; order fixed for readability
                PreflightChecks.checkPlayers(r, input.live);
                PreflightChecks.checkClaims(r, input.live);
                PreflightChecks.checkNoPlayerAdditions(r, input.baselineByType,
                        input.live.blockEntitiesByType(), input.scope, input.purgeAcknowledged);
            }
        } else {
            r.warn("NO_LIVE_SCAN", "live state not attached — offline-only validation");
        }

        if (input.lootPolicy != null) {
            PreflightChecks.checkLootPolicy(r, input.lootPolicy);
        } else {
            r.error("LOOT_POLICY_MISSING", "loot policy could not be loaded — fail closed");
        }

        if (input.savedDataClassification != null) {
            PreflightChecks.checkSavedDataOwners(r, input.savedDataClassification);
        } else {
            r.error("SAVEDDATA_INVENTORY_MISSING", "SavedData inventory not supplied — fail closed");
        }

        return r;
    }

    /** All inputs for one validation pass. */
    public static final class ResetPlanInput {
        public ProductionGuard guard;
        /** Goal 03 environment; when set, takes precedence over legacy guard. */
        public com.bigbangcraft.expeditions.env.EnvironmentProfile env;
        public com.bigbangcraft.expeditions.sector.SectorRecord sector;
        public com.bigbangcraft.expeditions.sector.SectorState requiredState;
        public SectorLiveState live;
        public Map<String, Integer> baselineByType = Map.of();
        public com.bigbangcraft.expeditions.loot.LootPolicy lootPolicy;
        public Map<String, String> savedDataClassification;
        /** Goal 04: reset scope — SECTOR keeps strict no-additions, DIMENSION allows purge-ack. */
        public String scope = com.bigbangcraft.expeditions.reset.ResetAuthorization.SCOPE_SECTOR;
        /** Goal 04: operator confirmed destroying quantified player additions (DIMENSION scope). */
        public boolean purgeAcknowledged = false;
    }
}
