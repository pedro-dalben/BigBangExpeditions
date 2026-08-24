package com.bigbangcraft.expeditions.reset;

import com.bigbangcraft.expeditions.env.DriftPolicy;
import com.bigbangcraft.expeditions.env.EnvironmentProfile;
import com.bigbangcraft.expeditions.safety.ResetPreflightResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Production dry-run: executes the COMPLETE production decision pipeline with
 * every destructive sink stubbed, then reports exactly one of:
 *
 *   WOULD RESET               — all gates green; execution would proceed
 *   RESET WOULD BE REFUSED    — with the full refusal picture
 *
 * The dry-run shares code with the real path (preflight engine, drift policy,
 * authorization issuance, target derivation through PathConfinement). Only the
 * final filesystem mutation is simulated.
 */
public final class DryRunEngine {

    public enum Verdict { WOULD_RESET, RESET_WOULD_BE_REFUSED }

    public enum StepStatus { PASS, FAIL, WARN }

    public static final class Step {
        public final String name;
        public final StepStatus status;
        public final String detail;

        public Step(String name, StepStatus status, String detail) {
            this.name = name;
            this.status = status;
            this.detail = detail == null ? "" : detail;
        }
    }

    public static final class Report {
        public Verdict verdict = Verdict.WOULD_RESET;
        public final List<Step> steps = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
        /** Artifact that WOULD be issued (not persisted, not consumable). */
        public ResetAuthorization wouldIssueArtifact;
        public long estimatedBackupBytes;
        public int estimatedDeletions;

        void fail(String stepName, String detail) {
            steps.add(new Step(stepName, StepStatus.FAIL, detail));
            verdict = Verdict.RESET_WOULD_BE_REFUSED;
        }
    }

    /** Abstracts filesystem capacity so the engine stays unit-testable. */
    public interface DiskProbe {
        long availableBytes();
        long usableDimensionBytes();
    }

    private DryRunEngine() {}

    public static Report run(AuthorizationService.IssueInputs in, DiskProbe disk, boolean resetLockFree) {
        Report r = new Report();

        // 1. environment
        EnvironmentProfile env = in.env == null ? EnvironmentProfile.STAGING : in.env;
        if (env.destructiveAllowed()) {
            r.steps.add(new Step("ENVIRONMENT", StepStatus.PASS, env.name()));
        } else {
            r.steps.add(new Step("ENVIRONMENT", StepStatus.WARN,
                    env + ": full decision pipeline executes; actual deletion stays disabled"));
        }

        // 2. concurrency lock
        if (resetLockFree) {
            r.steps.add(new Step("RESET_LOCK", StepStatus.PASS, "lock available"));
        } else {
            r.fail("RESET_LOCK", "reset lock held — concurrent resets are forbidden");
        }

        // 3. lifecycle state gate (sector must be LOCKED for authorization)
        if (in.sector.status != com.bigbangcraft.expeditions.sector.SectorState.LOCKED) {
            r.fail("LIFECYCLE", "sector '" + in.sector.id + "' is " + in.sector.status + ", required LOCKED");
        } else {
            r.steps.add(new Step("LIFECYCLE", StepStatus.PASS, "sector LOCKED, generation " + in.lifecycleGeneration));
        }

        // 4. full preflight (players, claims, additions, loot policy, SavedData)
        AuthorizationService.IssueOutcome outcome = AuthorizationService.issue(in);
        ResetPreflightResult pre = outcome.preflight;
        if (pre.passed()) {
            r.steps.add(new Step("PREFLIGHT", StepStatus.PASS, pre.issues().size() + " note(s)"));
        } else {
            r.fail("PREFLIGHT", String.join("; ", pre.refusalReasons()));
        }
        outWarnings(r, pre);

        // 5. drift
        DriftPolicy.Report drift = outcome.drift;
        if (drift != null) {
            switch (drift.overall) {
                case ALLOW -> r.steps.add(new Step("DRIFT", StepStatus.PASS, "ALLOW"));
                case WARN -> {
                    r.steps.add(new Step("DRIFT", StepStatus.WARN, "WARN"));
                    for (var e : drift.entries) {
                        if (e.verdict != DriftPolicy.Verdict.ALLOW) r.warnings.add("drift: " + e);
                    }
                }
                default -> r.fail("DRIFT", String.join("; ", drift.entries.stream()
                        .filter(e -> e.verdict != DriftPolicy.Verdict.ALLOW)
                        .map(Object::toString).toList()));
            }
        } else if (in.env == EnvironmentProfile.STAGING) {
            r.steps.add(new Step("DRIFT", StepStatus.WARN, "no qualification fingerprint on record (staging tolerated)"));
        }

        // 6. authorization issuance (simulated — never persisted)
        if (outcome.ok()) {
            r.wouldIssueArtifact = outcome.artifact;
            r.steps.add(new Step("AUTHORIZATION", StepStatus.PASS,
                    "would issue " + outcome.artifact.authId + " expiring "
                            + outcome.artifact.expiresAtEpochMs));
        } else {
            boolean alreadyReported = outcome.refusals.stream().allMatch(s ->
                    s.startsWith("DRIFT") || s.contains("QUALIFICATION") || s.contains("FINGERPRINT"));
            if (!alreadyReported) {
                r.fail("AUTHORIZATION", String.join("; ", outcome.refusals));
            }
        }

        // 7. target derivation + confinement proof
        var dimDir = PathConfinement.expeditionDimensionDir(java.nio.file.Path.of("/server/world"));
        if (dimDir == null || !dimDir.endsWith(java.nio.file.Path.of("bigbangexpeditions").resolve("expedition"))) {
            r.fail("TARGETS", "dimension directory derivation failed confinement");
        } else {
            r.estimatedDeletions = SCOPE_DIMENSION_DELETIONS; // whole-dimension scope
            r.steps.add(new Step("TARGETS", StepStatus.PASS,
                    "confined to dimensions/bigbangexpeditions/expedition (whole-dimension scope)"));
        }

        // 8. backup feasibility
        long need = disk.usableDimensionBytes() * 2 + MIN_HEADROOM_BYTES;
        r.estimatedBackupBytes = need;
        if (disk.availableBytes() >= need) {
            r.steps.add(new Step("BACKUP_SPACE", StepStatus.PASS,
                    "available " + disk.availableBytes() + " >= needed " + need));
        } else {
            r.fail("BACKUP_SPACE", "insufficient disk: available " + disk.availableBytes()
                    + " < needed " + need);
        }

        // 9. simulated execution point
        if (r.verdict == Verdict.WOULD_RESET) {
            r.steps.add(new Step("EXECUTION", StepStatus.PASS,
                    "WOULD DELETE expedition dimension contents and REGENERATE on next boot"));
        } else {
            r.steps.add(new Step("EXECUTION", StepStatus.FAIL,
                    "destruction skipped — pipeline refused above"));
        }
        return r;
    }

    private static void outWarnings(Report r, ResetPreflightResult pre) {
        for (var i : pre.issues()) {
            if (i.severity == com.bigbangcraft.expeditions.safety.ValidationIssue.Severity.WARN) {
                r.warnings.add(i.code + ": " + i.message);
            }
        }
        for (String w : r.warnings) {
            if (w.startsWith("drift:")) continue;
        }
    }

    /** Whole-dimension scope deletes the dimension folder contents (region+entities+poi+level.dat-adjacent data). */
    public static final int SCOPE_DIMENSION_DELETIONS = -1; // counted at execution time by the offline executor
    public static final long MIN_HEADROOM_BYTES = 10L * 1024 * 1024;
}
