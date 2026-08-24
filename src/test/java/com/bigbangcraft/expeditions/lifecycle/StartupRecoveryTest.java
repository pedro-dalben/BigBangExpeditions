package com.bigbangcraft.expeditions.lifecycle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StartupRecoveryTest {

    private LifecycleRecord record(LifecycleState s) {
        LifecycleRecord r = new LifecycleRecord();
        r.status = s;
        return r;
    }

    @Test
    void cleanOpenWithNoOpIsFine() {
        assertFalse(StartupRecovery.evaluate(record(LifecycleState.OPEN), StartupRecovery.JournalSummary.NONE).recoveryRequired());
    }

    @Test
    void resettingWithoutJournalFailsClosed() {
        StartupRecovery.Finding f = StartupRecovery.evaluate(record(LifecycleState.RESETTING), StartupRecovery.JournalSummary.NONE);
        assertTrue(f.recoveryRequired());
        assertEquals("DESTRUCTIVE_STATE_WITHOUT_JOURNAL", f.reason());
    }

    @Test
    void bootingWithoutJournalFailsClosed() {
        assertTrue(StartupRecovery.evaluate(record(LifecycleState.BOOTING), StartupRecovery.JournalSummary.NONE).recoveryRequired());
    }

    @Test
    void interruptedDeletionDetected() {
        StartupRecovery.Finding f = StartupRecovery.evaluate(record(LifecycleState.RESETTING),
                new StartupRecovery.JournalSummary(true, StartupRecovery.PHASE_DELETION_INTENT, false));
        assertTrue(f.recoveryRequired());
        assertEquals("INTERRUPTED_DELETION", f.reason());

        f = StartupRecovery.evaluate(record(LifecycleState.RESETTING),
                new StartupRecovery.JournalSummary(true, StartupRecovery.PHASE_BACKUP_DONE, false));
        assertTrue(f.recoveryRequired());
    }

    @Test
    void completedDeletionAllowsBootContinuation() {
        assertFalse(StartupRecovery.evaluate(record(LifecycleState.RESETTING),
                new StartupRecovery.JournalSummary(true, StartupRecovery.PHASE_DELETION_DONE, true)).recoveryRequired());
        assertFalse(StartupRecovery.evaluate(record(LifecycleState.BOOTING),
                new StartupRecovery.JournalSummary(true, StartupRecovery.PHASE_DELETION_DONE, true)).recoveryRequired());
        assertFalse(StartupRecovery.evaluate(record(LifecycleState.VALIDATING),
                new StartupRecovery.JournalSummary(true, StartupRecovery.PHASE_DELETION_DONE, true)).recoveryRequired());
        // FINALIZED journals keep the durable DELETION_DONE proof
        assertFalse(StartupRecovery.evaluate(record(LifecycleState.RESETTING),
                new StartupRecovery.JournalSummary(false, StartupRecovery.PHASE_DELETION_DONE, true)).recoveryRequired());
        assertFalse(StartupRecovery.evaluate(record(LifecycleState.BOOTING),
                new StartupRecovery.JournalSummary(false, StartupRecovery.PHASE_DELETION_DONE, true)).recoveryRequired());
        assertFalse(StartupRecovery.evaluate(record(LifecycleState.OPEN),
                new StartupRecovery.JournalSummary(false, StartupRecovery.PHASE_DELETION_DONE, true)).recoveryRequired(),
                "a finished op whose cycle already reopened is consistent");
    }

    @Test
    void staleOperationInPreparationStatesFailsClosed() {
        for (LifecycleState s : java.util.List.of(
                LifecycleState.RESET_READY, LifecycleState.BACKUP,
                LifecycleState.PREFLIGHT, LifecycleState.LOCKED)) {
            StartupRecovery.Finding f = StartupRecovery.evaluate(record(s),
                    new StartupRecovery.JournalSummary(true, null, false));
            assertTrue(f.recoveryRequired(), () -> s.toString());
            assertEquals("STALE_OPERATION", f.reason(), () -> s.toString());
        }
    }

    @Test
    void openWithUnfinishedOperationFailsClosed() {
        StartupRecovery.Finding f = StartupRecovery.evaluate(record(LifecycleState.OPEN),
                new StartupRecovery.JournalSummary(true, StartupRecovery.PHASE_DELETION_DONE, true));
        assertTrue(f.recoveryRequired());
        assertEquals("OPEN_WITH_UNFINISHED_OPERATION", f.reason());
    }

    @Test
    void failedOrRecoveryStatesStillRequireInspectionWhenOpActive() {
        // even FAILED/RECOVERY_REQUIRED with an active op keeps the op flagged via STALE rule family
        StartupRecovery.Finding f = StartupRecovery.evaluate(record(LifecycleState.FAILED),
                new StartupRecovery.JournalSummary(true, StartupRecovery.PHASE_DELETION_INTENT, false));
        assertTrue(f.recoveryRequired());
    }
}
