package com.bigbangcraft.expeditions.lifecycle;

import java.io.IOException;
import java.util.Optional;

/**
 * Validates and applies lifecycle transitions, persisting every accepted change.
 * All gates live here so commands cannot bypass them.
 */
public final class LifecycleService {
    private final LifecycleStore store;

    public LifecycleService(LifecycleStore store) {
        this.store = store;
    }

    public LifecycleRecord current() throws IOException {
        return store.load();
    }

    /**
     * Applies a validated transition. Returns error message on refusal,
     * empty on success (record already persisted).
     */
    public Optional<String> transition(LifecycleState target, String by, String reason) throws IOException {
        LifecycleRecord r = store.load();
        long now = System.currentTimeMillis();
        Optional<String> err = LifecycleState.rejectTransition(r.status, target);
        if (err.isPresent()) return err;

        LifecycleState from = r.status;
        if (from == target) return Optional.empty(); // idempotent no-op

        // side effects
        if (target == LifecycleState.FAILED || target == LifecycleState.RECOVERY_REQUIRED) {
            if (reason != null && !reason.isBlank()) r.failureReason = reason;
        }
        if (target == LifecycleState.OPEN && from == LifecycleState.VALIDATING) {
            // validation gate: PASS must be recorded for this generation
            if (!"PASS".equals(r.lastValidationResult)) {
                return Optional.of("validation gate: lastValidationResult="
                        + (r.lastValidationResult.isEmpty() ? "<none>" : r.lastValidationResult)
                        + " — expedition may not reopen without a PASS");
            }
            boolean wasResetCycle = r.resetInFlight;
            if (wasResetCycle) {
                r.generation = r.generation + 1;
                r.lastResetAtEpochMs = now;
                r.resetInFlight = false;
                r.generationBeforeReset = -1;
            }
            r.lastOpenedAtEpochMs = now;
        }
        if (target == LifecycleState.VALIDATING) {
            r.resetInFlight = true;
            r.generationBeforeReset = r.generation;
        }
        if (target == LifecycleState.RESET_READY || target == LifecycleState.RESETTING
                || target == LifecycleState.RECOVERY_REQUIRED) {
            // leaving these states requires explicit operator recovery otherwise
        }
        if (target != LifecycleState.FAILED && target != LifecycleState.RECOVERY_REQUIRED) {
            r.failureReason = "";
        }
        if (target == LifecycleState.LOCKED) {
            // fresh pipeline run: clear stale validation result only when entering from FAILED/RECOVERY/PREFLIGHT-rollback paths
        }

        r.status = target;
        r.updatedAtEpochMs = now;
        r.lastChangeReason = reason == null ? "" : reason;
        r.recordTransition(now, from, target, by, reason);
        store.save(r);
        return Optional.empty();
    }

    /** Records post-reset validation outcome. Only PASS unlocks reopening. */
    public Optional<String> recordValidationResult(String result, String by) throws IOException {
        LifecycleRecord r = store.load();
        if (r.status != LifecycleState.VALIDATING) {
            return Optional.of("validation results only accepted while VALIDATING (current: " + r.status + ")");
        }
        if (!result.equals("PASS") && !result.equals("FAIL")) {
            return Optional.of("validation result must be PASS or FAIL");
        }
        r.lastValidationResult = result;
        r.updatedAtEpochMs = System.currentTimeMillis();
        r.recordTransition(System.currentTimeMillis(), r.status, r.status, by, "validation " + result);
        if (result.equals("FAIL")) {
            r.failureReason = "post-reset validation FAIL";
        }
        store.save(r);
        return Optional.empty();
    }

    public void setFailureReason(String reason, String by) throws IOException {
        LifecycleRecord r = store.load();
        r.failureReason = reason == null ? "" : reason;
        r.updatedAtEpochMs = System.currentTimeMillis();
        r.recordTransition(System.currentTimeMillis(), r.status, r.status, by, "failure note");
        store.save(r);
    }

    public void setActiveAuth(String authId, String by) throws IOException {
        LifecycleRecord r = store.load();
        r.activeAuthId = authId == null ? "" : authId;
        r.updatedAtEpochMs = System.currentTimeMillis();
        r.recordTransition(System.currentTimeMillis(), r.status, r.status, by, "bind authorization " + r.activeAuthId);
        store.save(r);
    }

    // ------------------------------------------- Goal 04: timed closing ------

    /**
     * Starts the player-facing closing sequence: OPEN → CLOSING with a
     * persisted deadline. Returns error message on refusal, empty on success.
     */
    public Optional<String> startClosing(long deadlineEpochMs, String by) throws IOException {
        LifecycleRecord r = store.load();
        Optional<String> err = LifecycleState.rejectTransition(r.status, LifecycleState.CLOSING);
        if (err.isPresent()) return err;
        long now = System.currentTimeMillis();
        if (r.status != LifecycleState.CLOSING) { // idempotent re-issue tolerated
            r.status = LifecycleState.CLOSING;
        }
        r.closingDeadlineEpochMs = deadlineEpochMs;
        r.lastClosingWarnMinutes = -1;
        r.updatedAtEpochMs = now;
        r.lastChangeReason = "closing scheduled";
        r.recordTransition(now, LifecycleState.OPEN, LifecycleState.CLOSING, by,
                "closing scheduled until " + deadlineEpochMs);
        store.save(r);
        return Optional.empty();
    }

    /** Cancels a running closing sequence back to OPEN. */
    public Optional<String> abortClosing(String by) throws IOException {
        LifecycleRecord r = store.load();
        if (r.status != LifecycleState.CLOSING) {
            return Optional.of("not closing (current: " + r.status + ")");
        }
        Optional<String> err = transition(LifecycleState.OPEN, by, "closing aborted");
        if (err.isEmpty()) {
            // transition() reloaded+saved its own copy — clear schedule AFTER it
            LifecycleRecord fresh = store.load();
            fresh.closingDeadlineEpochMs = 0;
            fresh.lastClosingWarnMinutes = -1;
            store.save(fresh);
        }
        return err;
    }

    /** Clears closing bookkeeping once extraction begins or the state leaves CLOSING. */
    public void clearClosingSchedule() throws IOException {
        LifecycleRecord r = store.load();
        if (r.closingDeadlineEpochMs == 0 && r.lastClosingWarnMinutes == -1) return;
        r.closingDeadlineEpochMs = 0;
        r.lastClosingWarnMinutes = -1;
        store.save(r);
    }

    /** Persists an emitted closing warning threshold (idempotency marker). */
    public void markClosingWarned(int minutesThreshold) throws IOException {
        LifecycleRecord r = store.load();
        r.lastClosingWarnMinutes = minutesThreshold;
        r.updatedAtEpochMs = System.currentTimeMillis();
        store.save(r);
    }

    /**
     * Goal 04: operator aborts an issued-but-not-executed authorization
     * (PREFLIGHT/RESET_READY → LOCKED). The destructive phase runs offline;
     * this only unwinds the in-server preparation window.
     *
     * @return error message, or null on success
     */
    public String cancelReset(String actor, java.util.function.UnaryOperator<String> ledgerRevoker) throws IOException {
        LifecycleRecord r = store.load();
        if (r.status != LifecycleState.PREFLIGHT && r.status != LifecycleState.RESET_READY) {
            return "cancel applies to PREFLIGHT/RESET_READY only (current: " + r.status + ")";
        }
        long now = System.currentTimeMillis();
        String boundAuth = r.activeAuthId;
        String revokeError = null;
        if (!boundAuth.isEmpty() && ledgerRevoker != null) {
            revokeError = ledgerRevoker.apply(boundAuth);
        }
        r.activeAuthId = "";
        recordTransitionAndSave(r, now, LifecycleState.LOCKED, actor,
                "authorization canceled" + (revokeError == null ? "" : " (ledger revoke issue: " + revokeError + ")"));
        return null;
    }

    private void recordTransitionAndSave(LifecycleRecord r, long now, LifecycleState target,
                                         String by, String reason) throws IOException {
        LifecycleState from = r.status;
        r.status = target;
        r.updatedAtEpochMs = now;
        r.lastChangeReason = reason;
        r.recordTransition(now, from, target, by, reason);
        store.save(r);
    }
}
