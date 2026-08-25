package com.bigbangcraft.expeditions.depletion;

/**
 * Anti-flap state for closure recommendations (Goal 05 requirement 17).
 *
 * <p>A recommendation only matures when the DEPLETED-candidate condition holds
 * on {@code requiredConsecutive} consecutive evaluations spanning at least
 * {@code minSpanMs}. A score below (threshold - recoveryBand) resets the
 * streak; a score inside the band but under the threshold is a dead zone — it
 * neither grows nor resets the streak, so oscillation around the threshold can
 * never mature NOR reset; reality must resolve one way.
 *
 * <p>Persisted by the automation layer; this class is pure.
 */
public final class HysteresisTracker {
    public int consecutiveHits;
    public long firstHitAtMs;
    public long lastEvaluatedAtMs;

    private final long minSpanMs;

    public HysteresisTracker(long minSpanMs) {
        this.minSpanMs = Math.max(0, minSpanMs);
    }

    /**
     * @param depletedCandidate true when this evaluation crossed the close threshold
     * @param deadZone          true when score sits inside the hysteresis band
     * @return true when the sustained condition has matured as of nowMs
     */
    public boolean record(boolean depletedCandidate, boolean deadZone,
                          int requiredConsecutive, long nowMs) {
        lastEvaluatedAtMs = Math.max(lastEvaluatedAtMs, nowMs);
        if (depletedCandidate) {
            if (consecutiveHits == 0) firstHitAtMs = nowMs;
            consecutiveHits++;
        } else if (!deadZone) {
            reset();
        }
        return matured(requiredConsecutive, nowMs);
    }

    public boolean matured(int requiredConsecutive, long nowMs) {
        if (consecutiveHits < requiredConsecutive) return false;
        return (nowMs - firstHitAtMs) >= minSpanMs;
    }

    /** True while a streak is pending but not yet matured — surfaced for explain output. */
    public boolean pending() {
        return consecutiveHits > 0;
    }

    public void reset() {
        consecutiveHits = 0;
        firstHitAtMs = 0;
    }
}
