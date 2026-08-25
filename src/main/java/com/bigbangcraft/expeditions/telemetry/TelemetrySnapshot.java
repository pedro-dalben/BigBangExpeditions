package com.bigbangcraft.expeditions.telemetry;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Immutable read-model over current expedition telemetry — the internal API
 * surface other subsystems (depletion engine, commands, future integrations)
 * consume without touching persistence internals (Goal 05 requirement 58).
 */
public final class TelemetrySnapshot {
    public enum Availability { AVAILABLE, MISSING, UNSUPPORTED_SCHEMA, CORRUPT, DISABLED }

    public final int generation;
    public final long openedAtEpochMs;
    public final Availability availability;

    public final long distinctExplorers;
    public final long distinctChunks;
    public final long entriesTotal;
    public final long exitsTotal;
    public final long deathsTotal;
    public final long containerOpensTotal;
    public final long playerMobKillsTotal;
    public final long structurePlacements;
    public final Map<String, Long> structurePlacementsByType;
    public final int peakConcurrentInside;
    public final long lastActivityEpochMs;
    /** UTC day -> bucket; unmodifiable, bounded to the rolling window. */
    public final Map<String, DayActivityView> days;
    public final List<String> qualityFlags;

    public static final class DayActivityView {
        public final String day;
        public final long entries;
        public final long chunkDiscoveries;
        public final long structureDiscoveries;
        public final long containerOpens;
        public final long deaths;
        public final long playerMobKills;
        public final int uniquePlayers;

        DayActivityView(DayActivity d) {
            this.day = d.day;
            this.entries = d.entries;
            this.chunkDiscoveries = d.chunkDiscoveries;
            this.structureDiscoveries = d.structureDiscoveries;
            this.containerOpens = d.containerOpens;
            this.deaths = d.deaths;
            this.playerMobKills = d.playerMobKills;
            this.uniquePlayers = d.uniqueCount();
        }
    }

    public static TelemetrySnapshot unavailable(int generation, Availability why) {
        return new TelemetrySnapshot(generation, 0L, why,
                0, 0, 0, 0, 0, 0, 0, 0, Collections.emptyMap(), 0, 0,
                Collections.emptyMap(), List.of());
    }

    public static TelemetrySnapshot of(GenerationTelemetry t, Availability availability) {
        Map<String, DayActivityView> days = new TreeMap<>();
        for (Map.Entry<String, DayActivity> e : t.days.entrySet()) {
            days.put(e.getKey(), new DayActivityView(e.getValue()));
        }
        Map<String, Long> byType = new TreeMap<>();
        for (Map.Entry<String, StructureSighting> e : t.structures.entrySet()) {
            byType.put(e.getKey(), e.getValue().distinctSections());
        }
        return new TelemetrySnapshot(t.generation, t.openedAtEpochMs, availability,
                t.distinctExplorers(), t.distinctChunks(),
                t.entriesTotal, t.exitsTotal, t.deathsTotal, t.containerOpensTotal,
                t.playerMobKillsTotal, t.totalStructurePlacements(), byType,
                t.peakConcurrentInside, t.lastActivityEpochMs, days,
                List.copyOf(t.qualityFlags));
    }

    private TelemetrySnapshot(int generation, long openedAtEpochMs, Availability availability,
                              long distinctExplorers, long distinctChunks,
                              long entriesTotal, long exitsTotal, long deathsTotal,
                              long containerOpensTotal, long playerMobKillsTotal,
                              long structurePlacements, Map<String, Long> structurePlacementsByType,
                              int peakConcurrentInside, long lastActivityEpochMs,
                              Map<String, DayActivityView> days, List<String> qualityFlags) {
        this.generation = generation;
        this.openedAtEpochMs = openedAtEpochMs;
        this.availability = availability;
        this.distinctExplorers = distinctExplorers;
        this.distinctChunks = distinctChunks;
        this.entriesTotal = entriesTotal;
        this.exitsTotal = exitsTotal;
        this.deathsTotal = deathsTotal;
        this.containerOpensTotal = containerOpensTotal;
        this.playerMobKillsTotal = playerMobKillsTotal;
        this.structurePlacements = structurePlacements;
        this.structurePlacementsByType = Collections.unmodifiableMap(structurePlacementsByType);
        this.peakConcurrentInside = peakConcurrentInside;
        this.lastActivityEpochMs = lastActivityEpochMs;
        this.days = Collections.unmodifiableMap(days);
        this.qualityFlags = qualityFlags;
    }

    /**
     * Sum of a day-bucket field over the newest {@code windowDays} buckets.
     * Pure calendar arithmetic on stored keys — no clock reads.
     */
    public long sumOverTrailingDays(java.util.function.ToIntFunction<DayActivityView> field, int windowDays) {
        if (windowDays <= 0 || days.isEmpty()) return 0;
        List<String> keys = new java.util.ArrayList<>(days.keySet());
        long total = 0;
        int from = Math.max(0, keys.size() - windowDays);
        for (int i = from; i < keys.size(); i++) {
            DayActivityView v = days.get(keys.get(i));
            total += field.applyAsInt(v);
        }
        return total;
    }
}
