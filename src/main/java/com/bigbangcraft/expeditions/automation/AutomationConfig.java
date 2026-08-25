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
 *
 * WS2 note: this initial version carries the telemetry section; depletion
 * policy, scheduler policy and authority keys are added with their consumers.
 */
public final class AutomationConfig {

    // ---- telemetry -------------------------------------------------------
    public static final int DEFAULT_FLUSH_SECONDS = 30;
    public static final int DEFAULT_SAMPLE_SECONDS = 5;
    public static final int DEFAULT_STRUCTURE_SIGNAL_GRACE_CHUNKS = 2000;

    private int flushIntervalSeconds = DEFAULT_FLUSH_SECONDS;   // 5..600
    private int sampleIntervalSeconds = DEFAULT_SAMPLE_SECONDS; // 1..60
    private int structureSignalGraceChunks = DEFAULT_STRUCTURE_SIGNAL_GRACE_CHUNKS; // 100..100000

    // ---- automation authority (present from day one so defaults are explicit) ----
    /** MANUAL / ADVISORY / SCHEDULED_WITH_APPROVAL / AUTOMATIC_CLOSURE */
    private String automationMode = "MANUAL";

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
            // defensive: apply() already guards, but never trust a mutated field
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
            case "automation.mode" -> {
                if (isKnownMode(value.toUpperCase())) automationMode = value.toUpperCase();
                else notices.add("automation.mode invalid ('" + value + "') — keeping " + automationMode);
            }
            default -> { /* unknown keys ignored silently */ }
        }
    }

    private static boolean isKnownMode(String mode) {
        return switch (mode) {
            case "MANUAL", "ADVISORY", "SCHEDULED_WITH_APPROVAL", "AUTOMATIC_CLOSURE" -> true;
            default -> false;
        };
    }

    private static Integer parseIntSafe(String v, int min, int max) {
        try {
            int i = Integer.parseInt(v.trim());
            return (i >= min && i <= max) ? i : null;
        } catch (Exception e) {
            return null;
        }
    }

    public int flushIntervalSeconds() {
        return flushIntervalSeconds;
    }

    public int sampleIntervalSeconds() {
        return sampleIntervalSeconds;
    }

    public int structureSignalGraceChunks() {
        return structureSignalGraceChunks;
    }

    public String automationMode() {
        return automationMode;
    }

    public List<String> notices() {
        return notices;
    }

    public Map<String, String> snapshot() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("telemetry.flushSeconds", String.valueOf(flushIntervalSeconds));
        m.put("telemetry.sampleSeconds", String.valueOf(sampleIntervalSeconds));
        m.put("telemetry.structureSignalGraceChunks", String.valueOf(structureSignalGraceChunks));
        m.put("automation.mode", automationMode);
        return m;
    }
}
