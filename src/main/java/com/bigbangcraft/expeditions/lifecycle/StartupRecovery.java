package com.bigbangcraft.expeditions.lifecycle;

/**
 * Pure startup recovery scanner (unit-testable, no IO).
 *
 * Given the persisted lifecycle state and a summary of any operation journal,
 * decides whether the installation is consistent or must fail closed into
 * RECOVERY_REQUIRED. The system never guesses that "everything is fine" after
 * an interruption.
 */
public final class StartupRecovery {

    public record JournalSummary(boolean hasActiveOp, String lastCompletedPhase) {
        public static final JournalSummary NONE = new JournalSummary(false, null);
    }

    public record Finding(boolean recoveryRequired, String reason, String detail) {
        static Finding ok() { return new Finding(false, "", ""); }
        static Finding bad(String reason, String detail) { return new Finding(true, reason, detail); }
    }

    /** Phase names used by the offline executor journal (see reset package). */
    public static final String PHASE_BACKUP_DONE = "BACKUP_DONE";
    public static final String PHASE_DELETION_INTENT = "DELETION_INTENT";
    public static final String PHASE_DELETION_DONE = "DELETION_DONE";

    private StartupRecovery() {}

    public static Finding evaluate(LifecycleRecord r, JournalSummary j) {
        LifecycleState s = r.status;
        boolean activeOp = j != null && j.hasActiveOp();
        String phase = j == null ? null : j.lastCompletedPhase();

        // OPEN is only acceptable with no unfinished operation anywhere.
        if (!activeOp) {
            if (s == LifecycleState.RESETTING || s == LifecycleState.BOOTING) {
                return Finding.bad("DESTRUCTIVE_STATE_WITHOUT_JOURNAL",
                        "status=" + s + " but no operation journal present — unknown destructive state");
            }
            return Finding.ok();
        }

        switch (s) {
            case RESET_READY:
            case BACKUP:
            case PREFLIGHT:
            case LOCKED:
                // op claimed but destructive phase not yet legitimately entered:
                // stale/interrupted preparation — require inspection
                return Finding.bad("STALE_OPERATION",
                        "active journal in state " + s + " (lastCompletedPhase=" + phase + ")");
            case RESETTING:
                if (!PHASE_DELETION_DONE.equals(phase)) {
                    return Finding.bad("INTERRUPTED_DELETION",
                            "RESETTING without DELETION_DONE (lastCompletedPhase="
                                    + (phase == null ? "<none>" : phase) + ") — dimension may be half-deleted");
                }
                // deletion completed; boot should continue to BOOTING via explicit step
                return Finding.ok();
            case BOOTING:
            case VALIDATING:
                if (!PHASE_DELETION_DONE.equals(phase)) {
                    return Finding.bad("BOOT_WITHOUT_COMPLETED_DELETION",
                            "status=" + s + " requires DELETION_DONE, got "
                                    + (phase == null ? "<none>" : phase));
                }
                return Finding.ok();
            case OPEN:
                return Finding.bad("OPEN_WITH_UNFINISHED_OPERATION",
                        "op finished? lastCompletedPhase=" + (phase == null ? "<none>" : phase)
                                + " — lifecycle finalize missing");
            default:
                return Finding.bad("UNKNOWN_DESTRUCTIVE_STATE",
                        "state=" + s + " with active op — failing closed");
        }
    }
}
