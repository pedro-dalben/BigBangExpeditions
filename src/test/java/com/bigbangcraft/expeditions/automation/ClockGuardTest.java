package com.bigbangcraft.expeditions.automation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClockGuardTest {
    private static final long HOUR = 3600_000L;

    @Test
    void saneProgressionNeverAnomalous() {
        assertFalse(ClockGuard.isAnomalous(1000, 2000));
        assertFalse(ClockGuard.isAnomalous(0, 5));      // no baseline yet
        assertFalse(ClockGuard.isAnomalous(1000, -1));  // invalid now ignored
        long t = 1_700_000_000_000L;
        assertFalse(ClockGuard.isAnomalous(t, t + 23 * HOUR));
    }

    @Test
    void backwardStepBeyondToleranceFlagged() {
        long t = 1_700_000_000_000L;
        assertFalse(ClockGuard.isAnomalous(t, t - 4 * 60_000L), "small NTP correction tolerated");
        assertTrue(ClockGuard.isAnomalous(t, t - 30 * 60_000L));
        assertTrue(ClockGuard.isAnomalous(t + DAY(), t)); // full day backwards
    }

    @Test
    void forwardJumpBeyondPlausibleDowntimeFlagged() {
        long t = 1_700_000_000_000L;
        assertFalse(ClockGuard.isAnomalous(t, t + 24 * HOUR), "exactly one day is plausible downtime");
        assertTrue(ClockGuard.isAnomalous(t, t + 3 * 24 * HOUR));
    }

    private static long DAY() { return 24 * HOUR; }
}
