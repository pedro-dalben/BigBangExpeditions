package com.bigbangcraft.expeditions.telemetry;

/**
 * Saturating arithmetic for cumulative telemetry counters (Goal 05).
 *
 * <p>Cumulative metrics must never go negative, overflow into wrong magnitudes,
 * or decrease. All mutation paths funnel through these helpers so a malformed
 * or hostile input degrades to "no effect" rather than corrupting the ledger.
 */
public final class Saturation {
    private Saturation() {}

    /** Practical ceiling for counters; far above any real server lifetime. */
    public static final long CEILING = 1_000_000_000_000L; // 1e12

    /** Adds {@code delta} to {@code current}, saturating at {@link #CEILING}. */
    public static long add(long current, long delta) {
        long cur = clamp(current);
        if (delta <= 0) return cur;
        long sum = cur + delta;
        if (sum < 0 || sum > CEILING) return CEILING;
        return sum;
    }

    /** Increments by one, saturating at {@link #CEILING}. */
    public static long inc(long current) {
        return add(current, 1);
    }

    /** Clamps any long into [0, CEILING]. */
    public static long clamp(long value) {
        if (value < 0) return 0;
        if (value > CEILING) return CEILING;
        return value;
    }

    /**
     * Ratio numerator/denominator as 0..100 with two implied decimals
     * (percent*100). Unknown denominators must be handled by callers as
     * UNKNOWN, never coerced to 0 or 100.
     */
    public static long percentX100(long numerator, long denominator) {
        if (denominator <= 0 || numerator < 0) return -1; // caller-visible UNKNOWN marker
        if (numerator >= denominator) return 10_000;
        return Math.min(10_000, (numerator * 10_000) / denominator);
    }
}
