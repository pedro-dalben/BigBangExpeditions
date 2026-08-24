package com.bigbangcraft.expeditions.safety;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Pure sliding-window rate limiter for player-facing actions (Goal 04).
 *
 * Purpose: command spam (enter/leave loops, status floods) must not turn into
 * disk IO amplification against the lifecycle store and audit log. Window is
 * wall-clock based; callers pass their own clock so tests are deterministic.
 */
public final class RateLimiter {
    private final int maxActions;
    private final long windowMs;
    private final Map<String, Deque<Long>> hits = new HashMap<>();

    public RateLimiter(int maxActions, long windowMs) {
        if (maxActions <= 0 || windowMs <= 0) {
            throw new IllegalArgumentException("maxActions and windowMs must be positive");
        }
        this.maxActions = maxActions;
        this.windowMs = windowMs;
    }

    /**
     * Records an attempt. Returns true when allowed (and records it),
     * false when the subject exceeded {@code maxActions} within the window.
     */
    public synchronized boolean tryAcquire(String subject, long nowEpochMs) {
        Deque<Long> deque = hits.computeIfAbsent(subject, k -> new ArrayDeque<>());
        while (!deque.isEmpty() && nowEpochMs - deque.peekFirst() >= windowMs) {
            deque.removeFirst();
        }
        if (deque.size() >= maxActions) return false;
        deque.addLast(nowEpochMs);
        return true;
    }

    /** Test/admin hook: drop all history. */
    public synchronized void clear() {
        hits.clear();
    }
}
