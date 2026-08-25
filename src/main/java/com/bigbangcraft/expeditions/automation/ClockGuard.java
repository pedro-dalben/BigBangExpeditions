package com.bigbangcraft.expeditions.automation;

/**
 * Pure wall-clock sanity evaluation (Goal 05 requirement 41).
 *
 * Wall-clock semantics are used for schedules because expedition lifecycles
 * span days; this guard makes clock corrections SAFE: a backward jump beyond
 * tolerance or a forward jump larger than any plausible downtime marks an
 * anomaly that SUSPENDS automatic destructive-capable actions until an
 * operator acknowledges (audited). Advisory evaluation continues.
 */
public final class ClockGuard {
    public static final long BACKWARD_TOLERANCE_MS = 5 * 60_000L;
    public static final long FORWARD_JUMP_MS = 24 * 3600_000L;

    private ClockGuard() {}

    /**
     * @param lastObservedMs last wall-clock ms accepted as sane (0 = none yet)
     * @param nowMs          candidate current wall-clock ms
     * @return true when the transition last->now looks like a clock anomaly
     */
    public static boolean isAnomalous(long lastObservedMs, long nowMs) {
        if (lastObservedMs <= 0 || nowMs <= 0) return false;
        if (nowMs < lastObservedMs - BACKWARD_TOLERANCE_MS) return true;  // stepped back
        return nowMs - lastObservedMs > FORWARD_JUMP_MS;                  // implausible jump
    }
}
