package com.bigbangcraft.expeditions.lifecycle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class LifecycleServiceTest {

    @TempDir
    Path tmp;

    private LifecycleService service() {
        return new LifecycleService(new LifecycleStore(tmp.resolve("lifecycle.json")));
    }

    private void drive(LifecycleService s, String by) throws IOException {
        s.transition(LifecycleState.CLOSING, by, "close");
        s.transition(LifecycleState.EVACUATING, by, "evacuated");
        s.transition(LifecycleState.LOCKED, by, "locked");
        s.transition(LifecycleState.PREFLIGHT, by, "preflight ok");
        s.transition(LifecycleState.BACKUP, by, "backup ok");
        s.transition(LifecycleState.RESET_READY, by, "ready");
        s.transition(LifecycleState.RESETTING, by, "offline executor");
        s.transition(LifecycleState.BOOTING, by, "boot");
        s.transition(LifecycleState.VALIDATING, by, "validating");
    }

    @Test
    void reopenWithoutValidationPassRefused() throws IOException {
        LifecycleService s = service();
        drive(s, "op");
        Optional<String> err = s.transition(LifecycleState.OPEN, "op", "try open");
        assertTrue(err.isPresent());
        assertTrue(err.get().contains("validation gate"));
        // still VALIDATING
        assertEquals(LifecycleState.VALIDATING, s.current().status);
    }

    @Test
    void failValidationBlocksReopenAndEntersFailed() throws IOException {
        LifecycleService s = service();
        drive(s, "op");
        assertEquals(Optional.empty(), s.recordValidationResult("FAIL", "validator"));
        assertEquals(Optional.empty(), s.transition(LifecycleState.FAILED, "system", "validation FAIL"));
        assertEquals(LifecycleState.FAILED, s.current().status);
        assertNotNull(s.transition(LifecycleState.OPEN, "op", "sneak").orElse(null));
    }

    @Test
    void fullResetCycleIncrementsGenerationExactlyOnce() throws IOException {
        LifecycleService s = service();
        assertEquals(0, s.current().generation);
        drive(s, "op");
        assertEquals(Optional.empty(), s.recordValidationResult("PASS", "validator"));
        assertEquals(Optional.empty(), s.transition(LifecycleState.OPEN, "op", "validated"));
        assertEquals(1, s.current().generation);
        assertFalse(s.current().resetInFlight);
        assertNotEquals(0L, s.current().lastResetAtEpochMs);

        // a plain close/reopen without a reset must NOT bump the generation
        s.transition(LifecycleState.CLOSING, "op", "");
        s.transition(LifecycleState.OPEN, "op", "");
        assertEquals(1, s.current().generation);
    }

    @Test
    void validationResultsOnlyWhileValidating() throws IOException {
        LifecycleService s = service();
        assertTrue(s.recordValidationResult("PASS", "v").isPresent());
        drive(s, "op");
        assertTrue(s.recordValidationResult("MAYBE", "v").isPresent());
    }

    @Test
    void illegalTransitionRefusedAndNotPersisted() throws IOException {
        LifecycleService s = service();
        assertTrue(s.transition(LifecycleState.RESETTING, "op", "").isPresent());
        assertEquals(LifecycleState.OPEN, s.current().status);
    }

    @Test
    void failureReasonClearedWhenLeavingFailedStates() throws IOException {
        LifecycleService fresh = new LifecycleService(new LifecycleStore(tmp.resolve("l2.json")));
        fresh.transition(LifecycleState.RECOVERY_REQUIRED, "scanner", "interrupted");
        assertEquals(LifecycleState.RECOVERY_REQUIRED, fresh.current().status);
        fresh.setFailureReason("half-deleted dimension suspected", "scanner");
        assertFalse(fresh.current().failureReason.isEmpty());
        fresh.transition(LifecycleState.LOCKED, "operator", "inspected and cleared");
        assertEquals("", fresh.current().failureReason);
    }

    @Test
    void recordPersistsAcrossStoreInstances() throws IOException {
        LifecycleService s = service();
        drive(s, "op");
        s.recordValidationResult("PASS", "validator");

        LifecycleRecord reloaded = new LifecycleStore(tmp.resolve("lifecycle.json")).load();
        assertEquals(LifecycleState.VALIDATING, reloaded.status);
        assertTrue(reloaded.resetInFlight);
        // 9 real transitions + 1 validation event
        assertEquals(10, reloaded.recent.size());
        assertEquals("VALIDATING", reloaded.recent.get(reloaded.recent.size() - 1).to);
        assertEquals("validation PASS", reloaded.recent.get(reloaded.recent.size() - 1).reason);
    }

    @Test
    void corruptFileFailsClosed() throws IOException {
        Files.writeString(tmp.resolve("corrupt.json"), "{ not json !!");
        assertThrows(IOException.class,
                () -> new LifecycleStore(tmp.resolve("corrupt.json")).load());
    }

    @Test
    void truncatedFileFailsClosed() throws IOException {
        Files.writeString(tmp.resolve("trunc.json"), "{\"status\":\"OPEN\"");
        assertThrows(IOException.class,
                () -> new LifecycleStore(tmp.resolve("trunc.json")).load());
    }

    @Test
    void recentHistoryCappedAt50() throws IOException {
        LifecycleService s = service();
        for (int i = 0; i < 60; i++) {
            s.transition(LifecycleState.CLOSING, "op", "ping" + i);
            s.transition(LifecycleState.OPEN, "op", "pong" + i);
        }
        assertEquals(50, s.current().recent.size());
    }
}
