package com.bigbangcraft.expeditions.automation;

import com.bigbangcraft.expeditions.depletion.DepletionPolicy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/** Binds {@link DepletionPolicy} to/from {@link AutomationConfig} + stable fingerprint (requirement 63). */
public final class PolicySupport {
    private PolicySupport() {}

    public static DepletionPolicy fromConfig(AutomationConfig c, List<String> notices) {
        DepletionPolicy p = new DepletionPolicy();
        p.coverageWeight = c.coverageWeight;
        p.structureWeight = c.structureWeight;
        p.lootWeight = c.lootWeight;
        p.activityWeight = c.activityWeight;
        p.ageWeight = c.ageWeight;
        p.coverageClosePercent = c.coverageClosePercent;
        p.closeScoreThreshold = c.closeScoreThreshold;
        p.recoveryBand = c.recoveryBand;
        p.minAgeDays = c.minAgeDays;
        p.maxAgeDays = c.maxAgeDays;
        p.sustainedEvaluationsRequired = c.sustainedEvaluationsRequired;
        p.minSustainedSpanMs = c.minSustainedSpanHours * 3600_000L;
        p.lootMinAbsoluteOpens = c.lootMinAbsoluteOpens;
        p.inactivityAbandonDays = c.inactivityAbandonDays;
        p.unknownSpatialHandling = c.unknownSpatialHandling;
        p.minKnownWeightFraction = c.minKnownWeightFraction;
        return DepletionPolicy.validated(p, notices);
    }

    /** sha256 over canonical policy dump — pending decisions bind to this so a
     * config edit cannot silently make a stale recommendation destructive. */
    public static String fingerprint(DepletionPolicy p) {
        String canonical = String.join("|",
                num(p.coverageWeight), num(p.structureWeight), num(p.lootWeight),
                num(p.activityWeight), num(p.ageWeight),
                num(p.coverageClosePercent), num(p.closeScoreThreshold), num(p.recoveryBand),
                String.valueOf(p.minAgeDays), String.valueOf(p.maxAgeDays),
                String.valueOf(p.sustainedEvaluationsRequired), String.valueOf(p.minSustainedSpanMs),
                String.valueOf(p.lootMinAbsoluteOpens), String.valueOf(p.inactivityAbandonDays),
                String.valueOf(p.unknownSpatialBlocks()), num(p.minKnownWeightFraction));
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.substring(0, 16);
        } catch (Exception e) {
            return "unavailable";
        }
    }

    private static String num(double d) {
        return Double.toString(Math.round(d * 1000.0) / 1000.0);
    }
}
