package com.bigbangcraft.expeditions.depletion;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Server policy for expedition depletion — SEPARATED from observed facts
 * (Goal 05 requirement 12). Changing this file never touches telemetry.
 *
 * <p>All thresholds are plain doubles in their natural unit so administrators
 * can read them. Validation clamps nonsense into documented ranges with a
 * notice; the object produced is always safe to evaluate with.
 */
public final class DepletionPolicy {

    // component weights (sum need not be exactly 100; effective weights normalize)
    public double coverageWeight = 30;
    public double structureWeight = 25;
    public double lootWeight = 20;
    public double activityWeight = 15;
    public double ageWeight = 10;

    // spatial closure threshold: coverage percent above which the zone reads saturated
    public double coverageClosePercent = 70;      // 0..100

    // score at which a single evaluation reads DEPLETED-candidate (0..100)
    public double closeScoreThreshold = 80;
    // hysteresis band below the threshold that resets the sustained counter (anti-flap)
    public double recoveryBand = 5;

    // lifecycle gates
    public int minAgeDays = 3;                    // 0 disables gate
    public int maxAgeDays = 21;                   // 0 disables backstop
    public int sustainedEvaluationsRequired = 3;  // consecutive DEPLETED-candidate evals
    public long minSustainedSpanMs = 6L * 3600_000; // span between first and latest sustained hit

    // loot signal confidence
    public int lootMinAbsoluteOpens = 50;         // below this, loot reads UNKNOWN
    public int inactivityAbandonDays = 14;        // zero-activity days reading as abandonment

    // how UNKNOWN spatial evidence behaves: BLOCK (never recommend) or FALLBACK (renormalize)
    public String unknownSpatialHandling = "BLOCK"; // BLOCK | FALLBACK

    /** Minimum KNOWN weight fraction required for any recommendation under FALLBACK. */
    public double minKnownWeightFraction = 0.55;

    // ------------------------------------------------------------------ derive

    public Map<String, Double> weights() {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("coverage", coverageWeight);
        m.put("structures", structureWeight);
        m.put("loot", lootWeight);
        m.put("activity", activityWeight);
        m.put("age", ageWeight);
        return m;
    }

    public boolean unknownSpatialBlocks() {
        return !"FALLBACK".equalsIgnoreCase(unknownSpatialHandling);
    }

    /**
     * Clamped copy with notices — invalid operator input degrades to the nearest
     * safe value instead of poisoning the engine.
     */
    public static DepletionPolicy validated(DepletionPolicy p, java.util.List<String> notices) {
        DepletionPolicy v = new DepletionPolicy();
        v.coverageWeight = clamp(p.coverageWeight, 0, 100, "coverageWeight", notices);
        v.structureWeight = clamp(p.structureWeight, 0, 100, "structureWeight", notices);
        v.lootWeight = clamp(p.lootWeight, 0, 100, "lootWeight", notices);
        v.activityWeight = clamp(p.activityWeight, 0, 100, "activityWeight", notices);
        v.ageWeight = clamp(p.ageWeight, 0, 100, "ageWeight", notices);
        if (v.coverageWeight + v.structureWeight == 0 && p.unknownSpatialBlocks()) {
            notices.add("coverage+structure weights are zero — unknown-spatial handling forced to FALLBACK");
            v.unknownSpatialHandling = "FALLBACK";
        } else {
            v.unknownSpatialHandling = p.unknownSpatialBlocks() ? "BLOCK" : "FALLBACK";
        }
        v.coverageClosePercent = clamp(p.coverageClosePercent, 1, 100, "coverageClosePercent", notices);
        v.closeScoreThreshold = clamp(p.closeScoreThreshold, 1, 100, "closeScoreThreshold", notices);
        v.recoveryBand = clamp(p.recoveryBand, 0, 40, "recoveryBand", notices);
        v.minAgeDays = (int) clamp(p.minAgeDays, 0, 3650, "minAgeDays", notices);
        v.maxAgeDays = (int) clamp(p.maxAgeDays, 0, 3650, "maxAgeDays", notices);
        if (v.maxAgeDays > 0 && v.minAgeDays > 0 && v.maxAgeDays <= v.minAgeDays) {
            notices.add("maxAgeDays <= minAgeDays — max-age backstop disabled");
            v.maxAgeDays = 0;
        }
        v.sustainedEvaluationsRequired = (int) clamp(p.sustainedEvaluationsRequired, 1, 100,
                "sustainedEvaluationsRequired", notices);
        v.minSustainedSpanMs = Math.max(0, p.minSustainedSpanMs);
        v.lootMinAbsoluteOpens = (int) clamp(p.lootMinAbsoluteOpens, 0, 1_000_000, "lootMinAbsoluteOpens", notices);
        v.inactivityAbandonDays = (int) clamp(p.inactivityAbandonDays, 1, 365, "inactivityAbandonDays", notices);
        v.minKnownWeightFraction = clamp(p.minKnownWeightFraction, 0, 1, "minKnownWeightFraction", notices);
        return v;
    }

    private static double clamp(double x, double lo, double hi, String name, java.util.List<String> notices) {
        if (Double.isNaN(x) || x < lo || x > hi) {
            notices.add(name + "=" + x + " out of [" + lo + "," + hi + "] — clamped");
            if (Double.isNaN(x)) return lo;
            return Math.max(lo, Math.min(hi, x));
        }
        return x;
    }
}
