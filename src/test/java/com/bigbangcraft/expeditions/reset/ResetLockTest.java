package com.bigbangcraft.expeditions.reset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ResetLockTest {

    @TempDir
    Path tmp;

    private ResetLock lock() {
        return new ResetLock(tmp.resolve("locks/reset.lock"));
    }

    @Test
    void acquireAndReleaseCycle() throws IOException {
        assertTrue(lock().acquire("executor", "plan-1", 1000L, 60_000L).isEmpty());
        assertTrue(lock().isLocked(2000L));
        assertTrue(lock().release("executor", 3000L).isEmpty());
        assertFalse(lock().isLocked(4000L));
    }

    @Test
    void duplicateAcquireRefused() throws IOException {
        assertTrue(lock().acquire("first", "reset", 1000L, 60_000L).isEmpty());
        var second = lock().acquire("second", "reset", 2000L, 60_000L);
        assertTrue(second.isPresent());
        assertTrue(second.get().contains("concurrent resets are forbidden"));
        // original holder unaffected
        assertEquals("first", lock().current().holder);
    }

    @Test
    void sameHolderMayReacquire() throws IOException {
        assertTrue(lock().acquire("executor", "attempt-1", 1000L, 60_000L).isEmpty());
        assertTrue(lock().acquire("executor", "attempt-1-retry", 1500L, 60_000L).isEmpty(),
                "idempotent re-acquire by the same holder must succeed");
        assertEquals("attempt-1-retry", lock().current().purpose);
    }

    @Test
    void staleLockTakenOverAfterTtl() throws IOException {
        assertTrue(lock().acquire("dead-process", "crashed reset", 1000L, 5_000L).isEmpty());
        // before TTL: refused
        assertTrue(lock().acquire("new-executor", "reset", 4000L, 60_000L).isPresent());
        // after TTL: takeover succeeds
        assertTrue(lock().acquire("new-executor", "reset-after-crash", 10_000L, 60_000L).isEmpty());
        assertEquals("new-executor", lock().current().holder);
    }

    @Test
    void foreignReleaseRefused() throws IOException {
        assertTrue(lock().acquire("owner", "reset", 1000L, 60_000L).isEmpty());
        var err = lock().release("someone-else", 2000L);
        assertTrue(err.isPresent());
        assertTrue(err.get().contains("refusing release"));
        assertTrue(lock().isLocked(2500L));
    }

    @Test
    void releaseWithoutLockIsAnError() throws IOException {
        assertTrue(lock().release("anyone", 1000L).isPresent());
    }

    @Test
    void corruptLockFileFailsClosed() throws IOException {
        Files.createDirectories(tmp.resolve("locks"));
        Files.writeString(tmp.resolve("locks/reset.lock"), "{corrupt!!");
        // treat as held-by-unknown forever: acquisition refused even far in the future
        assertTrue(lock().isLocked(Long.MAX_VALUE / 4));
        var err = lock().acquire("anyone", "reset", Long.MAX_VALUE / 4, 60_000L);
        assertTrue(err.isPresent());
        assertTrue(err.get().contains("manual") || err.get().contains("forbidden"));
    }

    @Test
    void lockSurvivesProcessRecreation() throws IOException {
        assertTrue(new ResetLock(tmp.resolve("locks/reset.lock"))
                .acquire("p1", "offline window", 1000L, 60_000L).isEmpty());
        ResetLock freshInstance = new ResetLock(tmp.resolve("locks/reset.lock"));
        assertTrue(freshInstance.isLocked(1100L));
        assertTrue(freshInstance.acquire("p2", "x", 1200L, 60_000L).isPresent());
    }
}
