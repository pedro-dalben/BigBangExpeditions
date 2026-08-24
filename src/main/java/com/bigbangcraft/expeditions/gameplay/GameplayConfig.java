package com.bigbangcraft.expeditions.gameplay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Player-experience configuration (Goal 04).
 *
 * File: config/bigbangexpeditions/gameplay.properties — simple key=value.
 * Philosophy: convenience knobs are configurable; SAFETY rules are NOT
 * (nothing here can bypass lifecycle gating, evacuation or purge discipline —
 * those live in code and Goal 03 guarantees).
 *
 * Invalid values fall back to defaults with a notice; a missing file is normal
 * (first boot). Load failures never block server start.
 */
public final class GameplayConfig {

    public static final List<Integer> DEFAULT_WARN_OFFSETS = List.of(15, 5, 1);
    public static final int DEFAULT_CLOSE_MINUTES = 15;

    private List<Integer> closingWarningOffsetsMinutes = DEFAULT_WARN_OFFSETS;
    private int closingDurationMinutes = DEFAULT_CLOSE_MINUTES;
    private boolean announcementsEnabled = true;
    private boolean soundEnabled = true;
    private boolean openingAnnouncementEnabled = true;
    private final List<String> notices = new ArrayList<>();

    public static GameplayConfig defaults() {
        return new GameplayConfig();
    }

    public static GameplayConfig load(Path file) {
        GameplayConfig c = new GameplayConfig();
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
            c.notices.add("gameplay.properties unreadable (" + e.getMessage() + ") — defaults active");
        }
        return c;
    }

    void apply(String key, String value) {
        switch (key) {
            case "closingWarningOffsetsMinutes" -> {
                try {
                    List<Integer> list = new ArrayList<>();
                    for (String part : value.split(",")) {
                        int v = Integer.parseInt(part.trim());
                        if (v < 0 || v > 24 * 60) throw new NumberFormatException("offset out of range");
                        list.add(v);
                    }
                    if (list.isEmpty()) throw new NumberFormatException("empty");
                    Collections.sort(list);
                    Collections.reverse(list);
                    closingWarningOffsetsMinutes = List.copyOf(list);
                } catch (Exception e) {
                    notices.add("closingWarningOffsetsMinutes invalid ('" + value + "') — default " + DEFAULT_WARN_OFFSETS);
                }
            }
            case "closingDurationMinutes" -> {
                Integer v = parseIntSafe(value, 1, 24 * 60);
                if (v == null) notices.add("closingDurationMinutes invalid ('" + value + "') — default " + DEFAULT_CLOSE_MINUTES);
                else closingDurationMinutes = v;
            }
            case "announcementsEnabled" -> announcementsEnabled = parseBool(value, announcementsEnabled);
            case "soundEnabled" -> soundEnabled = parseBool(value, soundEnabled);
            case "openingAnnouncementEnabled" -> openingAnnouncementEnabled = parseBool(value, openingAnnouncementEnabled);
            default -> { /* unknown keys ignored silently */ }
        }
    }

    private Boolean parseBool(String v, boolean dflt) {
        if (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false")) return Boolean.parseBoolean(v);
        notices.add("invalid boolean '" + v + "' — keeping " + dflt);
        return dflt;
    }

    private Integer parseIntSafe(String v, int min, int max) {
        try {
            int i = Integer.parseInt(v.trim());
            return (i >= min && i <= max) ? i : null;
        } catch (Exception e) {
            return null;
        }
    }

    public List<Integer> closingWarningOffsetsMinutes() {
        return closingWarningOffsetsMinutes;
    }

    /** Offsets filtered to be <= configured total duration. */
    public List<Integer> effectiveWarningOffsets() {
        List<Integer> out = new ArrayList<>();
        for (int t : closingWarningOffsetsMinutes) {
            if (t <= closingDurationMinutes) out.add(t);
        }
        return out.isEmpty() ? List.of(0) : out;
    }

    public int closingDurationMinutes() {
        return closingDurationMinutes;
    }

    public boolean announcementsEnabled() {
        return announcementsEnabled;
    }

    public boolean soundEnabled() {
        return soundEnabled;
    }

    public boolean openingAnnouncementEnabled() {
        return openingAnnouncementEnabled;
    }

    public List<String> notices() {
        return notices;
    }

    /** Snapshot for admin display/tests. */
    public Map<String, String> snapshot() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("closingWarningOffsetsMinutes", closingWarningOffsetsMinutes.toString());
        m.put("closingDurationMinutes", String.valueOf(closingDurationMinutes));
        m.put("announcementsEnabled", String.valueOf(announcementsEnabled));
        m.put("soundEnabled", String.valueOf(soundEnabled));
        m.put("openingAnnouncementEnabled", String.valueOf(openingAnnouncementEnabled));
        return m;
    }
}
