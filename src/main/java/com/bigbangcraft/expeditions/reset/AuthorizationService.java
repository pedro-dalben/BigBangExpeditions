package com.bigbangcraft.expeditions.reset;

import com.bigbangcraft.expeditions.env.DriftPolicy;
import com.bigbangcraft.expeditions.env.EnvironmentProfile;
import com.bigbangcraft.expeditions.env.InstallFingerprint;
import com.bigbangcraft.expeditions.safety.ResetPreflightEngine;
import com.bigbangcraft.expeditions.safety.ResetPreflightResult;
import com.bigbangcraft.expeditions.sector.SectorRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Issues {@link ResetAuthorization} artifacts — the deliberate, validated,
 * single-use path to destructive execution.
 *
 * Issue flow (pure core; server wrapper collects inputs):
 *  1. full preflight engine pass (players, claims, additions, policy, SavedData…)
 *  2. drift evaluation against qualification fingerprint
 *     (missing qualification evidence refuses outside STAGING)
 *  3. artifact minted with checksum + TTL + generation binding
 *
 * Persistence (artifact file, ledger entry, lifecycle binding) is the caller's
 * responsibility so the core stays unit-testable.
 */
public final class AuthorizationService {

    public static final long DEFAULT_TTL_MS = 6L * 60 * 60 * 1000; // 6h

    private AuthorizationService() {}

    public static final class IssueInputs {
        public EnvironmentProfile env = EnvironmentProfile.STAGING;
        public String scope = ResetAuthorization.SCOPE_DIMENSION;
        public SectorRecord sector;
        public com.bigbangcraft.expeditions.sector.SectorState requiredState =
                com.bigbangcraft.expeditions.sector.SectorState.LOCKED;
        public com.bigbangcraft.expeditions.safety.SectorLiveState live;
        public java.util.Map<String, Integer> baselineByType = java.util.Map.of();
        public com.bigbangcraft.expeditions.loot.LootPolicy lootPolicy;
        public java.util.Map<String, String> savedDataClassification = java.util.Map.of();
        public InstallFingerprint currentFingerprint;
        public InstallFingerprint qualificationFingerprint;
        public int lifecycleGeneration;
        public long nowEpochMs;
        public long ttlMs = DEFAULT_TTL_MS;
        public String actor = "";
        /** Goal 04: operator confirmed the quantified purge manifest (DIMENSION scope). */
        public boolean purgeAcknowledged = false;
    }

    public static final class IssueOutcome {
        public ResetAuthorization artifact;
        public ResetPreflightResult preflight;
        public DriftPolicy.Report drift;
        public final List<String> refusals = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();

        public boolean ok() {
            return artifact != null && refusals.isEmpty();
        }
    }

    public static IssueOutcome issue(IssueInputs in) {
        IssueOutcome out = new IssueOutcome();
        long now = in.nowEpochMs > 0 ? in.nowEpochMs : System.currentTimeMillis();

        // ---- 1. full preflight -------------------------------------------------
        ResetPreflightEngine.ResetPlanInput pi = new ResetPreflightEngine.ResetPlanInput();
        pi.env = in.env;
        pi.sector = in.sector;
        pi.requiredState = in.requiredState;
        pi.live = in.live;
        pi.baselineByType = in.baselineByType;
        pi.lootPolicy = in.lootPolicy;
        pi.savedDataClassification = in.savedDataClassification;
        pi.scope = in.scope;
        pi.purgeAcknowledged = in.purgeAcknowledged;
        out.preflight = new ResetPreflightEngine().validate(pi);
        if (!out.preflight.passed()) {
            out.refusals.addAll(out.preflight.refusalReasons());
        }

        // ---- 2. drift vs qualification ----------------------------------------
        if (in.currentFingerprint == null || in.currentFingerprint.sha256().isBlank()) {
            out.refusals.add("FINGERPRINT_UNAVAILABLE: current installation fingerprint missing");
        } else if (in.qualificationFingerprint == null) {
            if (in.env != EnvironmentProfile.STAGING) {
                out.refusals.add("QUALIFICATION_MISSING: no qualification fingerprint on record — "
                        + "requalify before production authorization");
            } else {
                out.warnings.add("no qualification fingerprint on record (staging tolerated)");
            }
        } else {
            out.drift = DriftPolicy.evaluate(in.qualificationFingerprint, in.currentFingerprint);
            switch (out.drift.overall) {
                case REFUSE -> out.refusals.add("DRIFT_REFUSE: installation identity changed — "
                        + out.drift.entries.stream()
                        .filter(e -> e.verdict == DriftPolicy.Verdict.REFUSE)
                        .map(Object::toString).toList());
                case REQUIRE_REVALIDATION -> out.refusals.add("DRIFT_REVALIDATE: environment drifted — "
                        + out.drift.entries.stream()
                        .filter(e -> e.verdict != DriftPolicy.Verdict.ALLOW)
                        .map(Object::toString).toList());
                case WARN -> out.warnings.addAll(out.drift.entries.stream()
                        .filter(e -> e.verdict == DriftPolicy.Verdict.WARN)
                        .map(e -> "drift: " + e).toList());
                case ALLOW -> { }
            }
        }

        // ---- 3. mint artifact ---------------------------------------------------
        if (!in.env.destructiveAllowed() && in.scope.equals(ResetAuthorization.SCOPE_DIMENSION)) {
            // non-production environments may still authorize SECTOR-scoped staging work,
            // but DIMENSION-scope (production-shaped) artifacts require the real env.
            out.warnings.add("DIMENSION-scope artifact issued in " + in.env
                    + " — execution will still refuse without PRODUCTION");
        }

        if (!out.refusals.isEmpty()) return out;

        ResetAuthorization a = new ResetAuthorization();
        a.authId = UUID.randomUUID().toString();
        a.scope = in.scope;
        a.sectorId = in.sector.id;
        a.dimension = in.sector.dimension;
        a.minChunkX = in.sector.minChunkX;
        a.minChunkZ = in.sector.minChunkZ;
        a.maxChunkX = in.sector.maxChunkX;
        a.maxChunkZ = in.sector.maxChunkZ;
        if (ResetAuthorization.SCOPE_SECTOR.equals(in.scope)) {
            int minRx = Math.floorDiv(in.sector.minChunkX, 32);
            int maxRx = Math.floorDiv(in.sector.maxChunkX, 32);
            int minRz = Math.floorDiv(in.sector.minChunkZ, 32);
            int maxRz = Math.floorDiv(in.sector.maxChunkZ, 32);
            for (int rx = minRx; rx <= maxRx; rx++) {
                for (int rz = minRz; rz <= maxRz; rz++) {
                    a.expectedRegionFiles.add(PathConfinement.regionFileName(rx, rz));
                }
            }
        }
        a.baselineId = in.sector.lastBaselineId;
        a.generationAtIssue = in.lifecycleGeneration;
        a.installFingerprint = in.currentFingerprint;
        a.createdAtEpochMs = now;
        a.expiresAtEpochMs = now + in.ttlMs;
        a.createdBy = in.actor == null ? "" : in.actor;
        a.computeChecksum();
        out.artifact = a;
        return out;
    }

    /**
     * Idempotency policy: at most one ISSUED artifact per sector+generation.
     * Issuing a new artifact REVOKES all prior ISSUED ones for the same sector —
     * an operator can never be confused about which authorization is live, and
     * stale artifacts cannot linger.
     *
     * @return refusal message or empty on success.
     */
    public static java.util.Optional<String> supersedePriorIssued(AuthorizationLedger ledger,
                                                                  String sectorId,
                                                                  String keepAuthId,
                                                                  String by,
                                                                  long nowEpochMs) {
        try {
            for (String prior : ledger.issuedFor(sectorId)) {
                if (prior.equals(keepAuthId)) continue;
                var err = ledger.revoke(prior, by + " (superseded)", nowEpochMs);
                if (err.isPresent()) return err;
            }
            return java.util.Optional.empty();
        } catch (Exception e) {
            return java.util.Optional.of("supersede failed: " + e.getMessage());
        }
    }
}
