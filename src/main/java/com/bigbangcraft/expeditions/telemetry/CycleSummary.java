package com.bigbangcraft.expeditions.telemetry;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Durable per-cycle summary written when a generation completes (Goal 05
 * requirement 45). Aggregates only — no event detail is retained to produce it.
 */
public final class CycleSummary {
    public int schemaVersion = 1;

    public int generation;
    public long openedAtEpochMs;
    public long closedAtEpochMs;
    public String closureReason = "";       // e.g. "manual", "depletion", "max-age", "operator"
    public String closureActor = "";        // e.g. "automation:ADVISORY", player name, "console"

    public long durationMs;

    public long distinctExplorers;
    public long distinctChunks;
    public long entriesTotal;
    public long deathsTotal;
    public long containerOpensTotal;
    public long playerMobKillsTotal;
    public long structurePlacements;
    public Map<String, Long> structureTypes = new TreeMap<>(); // structureId -> placements
    public int peakConcurrentInside;
    public List<String> qualityFlags = new java.util.ArrayList<>();

    // Post-reset evidence (filled after the cycle's reset pipeline finishes).
    public String resetResult = "PENDING";  // PENDING / PASS / FAIL / SKIPPED
    public String validationResult = "PENDING";

    public static CycleSummary of(GenerationTelemetry t, String reason, String actor, long closedAtEpochMs) {
        CycleSummary s = new CycleSummary();
        s.generation = t.generation;
        s.openedAtEpochMs = t.openedAtEpochMs;
        s.closedAtEpochMs = closedAtEpochMs;
        s.durationMs = Math.max(0, closedAtEpochMs - t.openedAtEpochMs);
        s.closureReason = reason == null ? "" : reason;
        s.closureActor = actor == null ? "" : actor;
        s.distinctExplorers = t.distinctExplorers();
        s.distinctChunks = t.distinctChunks();
        s.entriesTotal = t.entriesTotal;
        s.deathsTotal = t.deathsTotal;
        s.containerOpensTotal = t.containerOpensTotal;
        s.playerMobKillsTotal = t.playerMobKillsTotal;
        s.structurePlacements = t.totalStructurePlacements();
        for (Map.Entry<String, StructureSighting> e : t.structures.entrySet()) {
            s.structureTypes.put(e.getKey(), e.getValue().distinctSections());
        }
        s.peakConcurrentInside = t.peakConcurrentInside;
        s.qualityFlags = new java.util.ArrayList<>(t.qualityFlags);
        return s;
    }
}
