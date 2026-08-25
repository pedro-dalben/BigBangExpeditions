package com.bigbangcraft.expeditions.automation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Goal 05 operational policy configuration.
 *
 * File: config/bigbangexpeditions/automation.properties — simple key=value,
 * same discipline as gameplay.properties. Separation of concerns (requirement
 * 60): telemetry collection knobs, depletion policy, scheduler policy and the
 * automation authority level are distinct key families.
 *
 * SAFETY: the authority level can only ever be LOOSENED by an explicit,
 * syntactically valid operator line; anything invalid, unreadable or missing
 * resolves to MANUAL (fail closed). No config value here can bypass Goal 03
 * authorization/preflight guarantees — those live in code.
 */
public final class AutomationConfig {

    // ---- telemetry -------------------------------------------------------
    public static final int DEFAULT_FLUSH_SECONDS = 30;
    public static final int DEFAULT_SAMPLE_SECONDS = 5;
    public static final int DEFAULT_STRUCTURE_SIGNAL_GRACE_CHUNKS = 2000;

    private int flushIntervalSeconds = DEFAULT_FLUSH_SECONDS;   // 5..600
    private int sampleIntervalSeconds = DEFAULT_SAMPLE_SECONDS; // 1..60
    private int structureSignalGraceChunks = DEFAULT_STRUCTURE_SIGNAL_GRACE_CHUNKS;

    /** Optional operator-pinned observed-fact censuses (0 = unknown / derive from sectors). */
    private long censusTotalChunks;
    private long censusTotalStructurePlacements;

    // ---- automation authority --------------------------------------------
    /** MANUAL / ADVISORY / SCHEDULED_WITH_APPROVAL / AUTOMATIC_CLOSURE */
    private String automationMode = "MANUAL";

    // ---- scheduler -------------------------------------------------------
    public static final int DEFAULT_EVALUATE_MINUTES = 60;
    public static final String DEFAULT_WINDOW_START = "03:00";
    public static final String DEFAULT_WINDOW_END = "05:00";
    public static final int DEFAULT_APPROVAL_TTL_HOURS = 48;

    private int evaluateMinutes = DEFAULT_EVALUATE_MINUTES;   // 10..1440
    private String windowStart = DEFAULT_WINDOW_START;        // HH:MM server-local
    private String windowEnd = DEFAULT_WINDOW_END;            // equal => any time
    private int approvalTtlHours = DEFAULT_APPROVAL_TTL_HOURS; // 1..720

    // ---- depletion policy ------------------------------------------------
    public double coverageWeight = 30;
    public double structureWeight = 25;
    public double lootWeight = 20;
    public double activityWeight = 15;
    public double ageWeight = 10;
    public double coverageClosePercent = 70;
    public double closeScoreThreshold = 80;
    public double recoveryBand = 5;
    public int minAgeDays = 3;
    public int maxAgeDays = 21;
    public int sustainedEvaluationsRequired = 3;
    public long minSustainedSpanHours = 6;
    public int lootMinAbsoluteOpens = 50;
    public int inactivityAbandonDays = 14;
    public String unknownSpatialHandling = "BLOCK"; // BLOCK | FALLBACK
    public double minKnownWeightFraction = 0.55;

    private final List<String> notices = new ArrayList<>();

    public static AutomationConfig defaults() {
        return new AutomationConfig();
    }

    public static AutomationConfig load(Path file) {
        AutomationConfig c = new AutomationConfig();
        if (file == null || !Files.isRegularFile(file)) return c;
        try {
            for (String line : Files.readAllLines(file)) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                int eq = t.indexOf('=');
                if (eq <= 0) continue;
                c.apply(t.substring(0, eq).trim(), t.substring(eq + 1).trim());
            }
        } catch (IOException | RuntimeException e) {
            c.notices.add("automation.properties unreadable (" + e.getMessage() + ") — safe defaults active");
        }
        if (!isKnownMode(c.automationMode)) {
            c.notices.add("automationMode invalid ('" + c.automationMode + "') — forced MANUAL");
            c.automationMode = "MANUAL";
        }
        return c;
    }

    void apply(String key, String value) {
        switch (key) {
            case "telemetry.flushSeconds" -> {
                Integer v = parseIntSafe(value, 5, 600);
                if (v == null) notices.add("telemetry.flushSeconds invalid ('" + value + "') — default " + DEFAULT_FLUSH_SECONDS);
                else flushIntervalSeconds = v;
            }
            case "telemetry.sampleSeconds" -> {
                Integer v = parseIntSafe(value, 1, 60);
                if (v == null) notices.add("telemetry.sampleSeconds invalid ('" + value + "') — default " + DEFAULT_SAMPLE_SECONDS);
                else sampleIntervalSeconds = v;
            }
            case "telemetry.structureSignalGraceChunks" -> {
                Integer v = parseIntSafe(value, 100, 100_000);
                if (v == null) notices.add("telemetry.structureSignalGraceChunks invalid ('" + value + "') — default " + DEFAULT_STRUCTURE_SIGNAL_GRACE_CHUNKS);
                else structureSignalGraceChunks = v;
            }
            case "census.totalChunks" -> {
                Long v = parseLongSafe(value, 0, 1_000_000_000L);
                if (v == null) notices.add("census.totalChunks invalid ('" + value + "')");
                else censusTotalChunks = v;
            }
            case "census.totalStructurePlacements" -> {
                Long v = parseLongSafe(value, 0, 1_000_000_000L);
                if (v == null) notices.add("census.totalStructurePlacements invalid ('" + value + "')");
                else censusTotalStructurePlacements = v;
            }
            case "automation.mode" -> {
                if (isKnownMode(value.toUpperCase())) automationMode = value.toUpperCase();
                else notices.add("automation.mode invalid ('" + value + "') — keeping " + automationMode);
            }
            case "scheduler.evaluateMinutes" -> {
                Integer v = parseIntSafe(value, 10, 1440);
                if (v == null) notices.add("scheduler.evaluateMinutes invalid ('" + value + "')");
                else evaluateMinutes = v;
            }
            case "scheduler.windowStart" -> {
                if (validHHMM(value)) windowStart = value;
                else notices.add("scheduler.windowStart invalid ('" + value + "') — HH:MM expected");
            }
            case "scheduler.windowEnd" -> {
                if (validHHMM(value)) windowEnd = value;
                else notices.add("scheduler.windowEnd invalid ('" + value + "') — HH:MM expected");
            }
            case "scheduler.approvalTtlHours" -> {
                Integer v = parseIntSafe(value, 1, 720);
                if (v == null) notices.add("scheduler.approvalTtlHours invalid ('" + value + "')");
                else approvalTtlHours = v;
            }
            case "depletion.coverageWeight" -> coverageWeight = parseDouble(value, coverageWeight, 0, 100, "depletion.coverageWeight");
            case "depletion.structureWeight" -> structureWeight = parseDouble(value, structureWeight, 0, 100, "depletion.structureWeight");
            case "depletion.lootWeight" -> lootWeight = parseDouble(value, lootWeight, 0, 100, "depletion.lootWeight");
            case "depletion.activityWeight" -> activityWeight = parseDouble(value, activityWeight, 0, 100, "depletion.activityWeight");
            case "depletion.ageWeight" -> ageWeight = parseDouble(value, ageWeight, 0, 100, "depletion.ageWeight");
            case "depletion.coverageClosePercent" -> coverageClosePercent = parseDouble(value, coverageClosePercent, 1, 100, "depletion.coverageClosePercent");
            case "depletion.closeScoreThreshold" -> closeScoreThreshold = parseDouble(value, closeScoreThreshold, 1, 100, "depletion.closeScoreThreshold");
            case "depletion.recoveryBand" -> recoveryBand = parseDouble(value, recoveryBand, 0, 40, "depletion.recoveryBand");
            case "depletion.minAgeDays" -> minAgeDays = parseIntBounded(value, minAgeDays, 0, 3650, "depletion.minAgeDays");
            case "depletion.maxAgeDays" -> maxAgeDays = parseIntBounded(value, maxAgeDays, 0, 3650, "depletion.maxAgeDays");
            case "depletion.sustainedEvaluationsRequired" -> sustainedEvaluationsRequired = parseIntBounded(value, sustainedEvaluationsRequired, 1, 100, "depletion.sustainedEvaluationsRequired");
            case "depletion.minSustainedSpanHours" -> minSustainedSpanHours = parseIntBounded(value, (int) minSustainedSpanHours, 0, 720, "depletion.minSustainedSpanHours");
            case "depletion.lootMinAbsoluteOpens" -> lootMinAbsoluteOpens = parseIntBounded(value, lootMinAbsoluteOpens, 0, 1_000_000, "depletion.lootMinAbsoluteOpens");
            case "depletion.inactivityAbandonDays" -> inactivityAbandonDays = parseIntBounded(value, inactivityAbandonDays, 1, 365, "depletion.inactivityAbandonDays");
            case "depletion.unknownSpatialHandling" -> {
                if (value.equalsIgnoreCase("BLOCK") || value.equalsIgnoreCase("FALLBACK")) unknownSpatialHandling = value.toUpperCase();
                else notices.add("depletion.unknownSpatialHandling invalid ('" + value + "')");
            }
            case "depletion.minKnownWeightFraction" -> minKnownWeightFraction = parseDouble(value, minKnownWeightFraction, 0, 1, "depletion.minKnownWeightFraction");
            default -> { /* unknown keys ignored silently */ }
        }
    }

    public static boolean isKnownMode(String mode) {
        return switch (mode) {
            case "MANUAL", "ADVISORY", "SCHEDULED_WITH_APPROVAL", "AUTOMATIC_CLOSURE" -> true;
            default -> false;
        };
    }

    static boolean validHHMM(String v) {
        try {
            String[] parts = v.split(":");
            if (parts.length != 2) return false;
            int h = Integer.parseInt(parts[0]);
            int m = Integer.parseInt(parts[1]);
            return h >= 0 && h <= 23 && m >= 0 && m <= 59;
        } catch (Exception e) {
            return false;
        }
    }

    private Double parseDouble(String v, double dflt, double lo, double hi, String key) {
        try {
            double d = Double.parseDouble(v.trim());
            if (Double.isNaN(d) || d < lo || d > hi) throw new NumberFormatException();
            return d;
        } catch (Exception e) {
            notices.add(key + " invalid ('" + v + "') — kept " + dflt);
            return dflt;
        }
    }

    private int parseIntBounded(String v, int dflt, int lo, int hi, String key) {
        Integer i = parseIntSafe(v, lo, hi);
        if (i == null) {
            notices.add(key + " invalid ('" + v + "') — kept " + dflt);
            return dflt;
        }
        return i;
    }

    private static Integer parseIntSafe(String v, int min, int max) {
        try {
            int i = Integer.parseInt(v.trim());
            return (i >= min && i <= max) ? i : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Long parseLongSafe(String v, long min, long max) {
        try {
            long l = Long.parseLong(v.trim());
            return (l >= min && l <= max) ? l : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- reads

    public int flushIntervalSeconds() { return flushIntervalSeconds; }
    public int sampleIntervalSeconds() { return sampleIntervalSeconds; }
    public int structureSignalGraceChunks() { return structureSignalGraceChunks; }
    public long censusTotalChunks() { return censusTotalChunks; }
    public long censusTotalStructurePlacements() { return censusTotalStructurePlacements; }
    public String automationMode() { return automationMode; }
    public int evaluateMinutes() { return evaluateMinutes; }
    public String windowStart() { return windowStart; }
    public String windowEnd() { return windowEnd; }
    public int approvalTtlHours() { return approvalTtlHours; }
    public List<String> notices() { return notices; }

    public Map<String, String> snapshot() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("telemetry.flushSeconds", String.valueOf(flushIntervalSeconds));
        m.put("telemetry.sampleSeconds", String.valueOf(sampleIntervalSeconds));
        m.put("telemetry.structureSignalGraceChunks", String.valueOf(structureSignalGraceChunks));
        m.put("census.totalChunks", String.valueOf(censusTotalChunks));
        m.put("census.totalStructurePlacements", String.valueOf(censusTotalStructurePlacements));
        m.put("automation.mode", automationMode);
        m.put("scheduler.evaluateMinutes", String.valueOf(evaluateMinutes));
        m.put("scheduler.windowStart", windowStart);
        m.put("scheduler.windowEnd", windowEnd);
        m.put("scheduler.approvalTtlHours", String.valueOf(approvalTtlHours));
        m.put("depletion.coverageWeight", String.valueOf(coverageWeight));
        m.put("depletion.structureWeight", String.valueOf(structureWeight));
        m.put("depletion.lootWeight", String.valueOf(lootWeight));
        m.put("depletion.activityWeight", String.valueOf(activityWeight));
        m.put("depletion.ageWeight", String.valueOf(ageWeight));
        m.put("depletion.coverageClosePercent", String.valueOf(coverageClosePercent));
        m.put("depletion.closeScoreThreshold", String.valueOf(closeScoreThreshold));
        m.put("depletion.recoveryBand", String.valueOf(recoveryBand));
        m.put("depletion.minAgeDays", String.valueOf(minAgeDays));
        m.put("depletion.maxAgeDays", String.valueOf(maxAgeDays));
        m.put("depletion.sustainedEvaluationsRequired", String.valueOf(sustainedEvaluationsRequired));
        m.put("depletion.minSustainedSpanHours", String.valueOf(minSustainedSpanHours));
        m.put("depletion.lootMinAbsoluteOpens", String.valueOf(lootMinAbsoluteOpens));
        m.put("depletion.inactivityAbandonDays", String.valueOf(inactivityAbandonDays));
        m.put("depletion.unknownSpatialHandling", unknownSpatialHandling);
        m.put("depletion.minKnownWeightFraction", String.valueOf(minKnownWeightFraction));
        return m;
    }
}
