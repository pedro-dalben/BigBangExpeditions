package com.bigbangcraft.expeditions.safety;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Goal 04: command-spam protection for player-facing transitions.
 */
class RateLimiterTest {

    @Test
    void allowsUpToLimitThenRefuses() {
        RateLimiter rl = new RateLimiter(3, 10_000);
        assertTrue(rl.tryAcquire("p1", 1_000));
        assertTrue(rl.tryAcquire("p1", 2_000));
        assertTrue(rl.tryAcquire("p1", 3_000));
        assertFalse(rl.tryAcquire("p1", 4_000), "4th action inside window must refuse");
    }

    @Test
    void windowSlidesByTimestamp() {
        RateLimiter rl = new RateLimiter(2, 5_000);
        assertTrue(rl.tryAcquire("p1", 0));
        assertTrue(rl.tryAcquire("p1", 1_000));
        assertFalse(rl.tryAcquire("p1", 2_000));
        // first hit aged out of the 5s window at t=5000
        assertTrue(rl.tryAcquire("p1", 5_001));
    }

    @Test
    void subjectsAreIndependent() {
        RateLimiter rl = new RateLimiter(1, 10_000);
        assertTrue(rl.tryAcquire("alice", 0));
        assertFalse(rl.tryAcquire("alice", 1));
        assertTrue(rl.tryAcquire("bob", 1));
    }

    @Test
    void clearResetsHistory() {
        RateLimiter rl = new RateLimiter(1, 60_000);
        assertTrue(rl.tryAcquire("p1", 0));
        assertFalse(rl.tryAcquire("p1", 1));
        rl.clear();
        assertTrue(rl.tryAcquire("p1", 2));
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new RateLimiter(0, 1000));
        assertThrows(IllegalArgumentException.class, () -> new RateLimiter(3, 0));
        assertThrows(IllegalArgumentException.class, () -> new RateLimiter(-1, -1));
    }
}
