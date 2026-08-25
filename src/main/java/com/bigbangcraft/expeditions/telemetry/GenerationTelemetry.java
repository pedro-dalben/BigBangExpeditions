package com.bigbangcraft.expeditions.telemetry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Generation-scoped expedition telemetry (Goal 05).
 *
 * <p>Durable identity contract: a record is bound to exactly one lifecycle
 * {@link #generation}. Ingest paths must refuse facts whose generation differs
 * — metrics from a previous generation can never influence the current one.
 *
 * <p>Bounded by design (no unbounded event ledger):
 * <ul>
 *   <li>cumulative counters saturate via {@link Saturation};</li>
 *   <li>first-entry chunk set caps at {@link #CHUNK_CAP} with overflow counting;</li>
 *   <li>unique explorers cap at {@link #UNIQUE_CAP};</li>
 *   <li>per-structure section sets cap inside {@link StructureSighting};</li>
 *   <li>day buckets roll on a bounded trailing window ({@code trimDays}).</li>
 * </ul>
 *
 * <p>Privacy: only aggregate facts and opaque UUIDs are stored — no chat, no
 * positions, no per-player timelines.
 */
public final class GenerationTelemetry {
    public static final int SCHEMA_VERSION = 1;
    public static final int CHUNK_CAP = 131_072;      // 8192 regions worth of chunks
    public static final int UNIQUE_CAP = 10_000;
    public static final int STRUCTURE_TYPE_CAP = 256;
    public static final int DAY_WINDOW_MAX = 90;

    public int schemaVersion = SCHEMA_VERSION;

    /** Lifecycle generation this record belongs to. Never mutated after open. */
    public int generation;
    public long openedAtEpochMs;
    public Long closedAtEpochMs; // null while generation is live

    public long entriesTotal;
    public long exitsTotal;
    public long evacuationsTotal;
    public long deathsTotal;
    public long containerOpensTotal;
    public long playerMobKillsTotal;

    public Set<String> uniqueExplorers = new HashSet<>();
    public long uniqueExplorerOverflow;

    /** Packed chunk coords (ChunkPos.asLong) with first-entry semantics. */
    public Set<Long> firstEntryChunks = new HashSet<>();
    public long firstEntryOverflow;

    public Map<String, StructureSighting> structures = new TreeMap<>();

    /** UTC-day buckets, newest last; trimmed to DAY_WINDOW_MAX. */
    public Map<String, DayActivity> days = new TreeMap<>();

    public int peakConcurrentInside;
    public long lastActivityEpochMs;

    /** Chunks actually probed for structure references (ingest coverage counter). */
    public long probeChunks;

    /** Data-quality flags: CHUNK_SET_SATURATED, UNIQUE_SET_SATURATED, STRUCTURE_TYPES_SATURATED. */
    public List<String> qualityFlags = new ArrayList<>();

    public GenerationTelemetry() {}

    public GenerationTelemetry(int generation, long openedAtEpochMs) {
        this.generation = generation;
        this.openedAtEpochMs = openedAtEpochMs;
    }

    // ---------------------------------------------------------------- facts

    /** @return true when the fact was accepted for THIS generation. */
    public boolean acceptsGeneration(int eventGeneration) {
        return eventGeneration == this.generation && closedAtEpochMs == null;
    }

    /** @return true when the boundary fact was accepted for THIS generation. */
    public boolean recordEntry(UUID playerId, int eventGeneration, long nowEpochMs) {
        if (!acceptsGeneration(eventGeneration)) return false;
        markUnique(playerId);
        entriesTotal = Saturation.inc(entriesTotal);
        day(nowEpochMs).entries = Saturation.inc(day(nowEpochMs).entries);
        day(nowEpochMs).recordPlayer(playerId);
        touchActivity(nowEpochMs);
        return true;
    }

    public void recordExit(UUID playerId, int eventGeneration, long nowEpochMs) {
        if (!acceptsGeneration(eventGeneration)) return;
        exitsTotal = Saturation.inc(exitsTotal);
        touchActivity(nowEpochMs);
    }

    public void recordEvacuation(int eventGeneration, long nowEpochMs) {
        if (!acceptsGeneration(eventGeneration)) return;
        evacuationsTotal = Saturation.inc(evacuationsTotal);
        touchActivity(nowEpochMs);
    }

    public void recordDeath(UUID playerId, int eventGeneration, long nowEpochMs) {
        if (!acceptsGeneration(eventGeneration)) return;
        deathsTotal = Saturation.inc(deathsTotal);
        DayActivity d = day(nowEpochMs);
        d.deaths = Saturation.inc(d.deaths);
        d.recordPlayer(playerId);
        touchActivity(nowEpochMs);
    }

    /**
     * Container interaction (open). Deduplication across repeated opens of the
     * same container within a short window is an ingest-layer concern; here
     * every accepted interaction counts once.
     */
    public void recordContainerOpen(UUID playerId, int eventGeneration, long nowEpochMs) {
        if (!acceptsGeneration(eventGeneration)) return;
        containerOpensTotal = Saturation.inc(containerOpensTotal);
        DayActivity d = day(nowEpochMs);
        d.containerOpens = Saturation.inc(d.containerOpens);
        d.recordPlayer(playerId);
        touchActivity(nowEpochMs);
    }

    public void recordPlayerMobKill(UUID playerId, int eventGeneration, long nowEpochMs) {
        if (!acceptsGeneration(eventGeneration)) return;
        playerMobKillsTotal = Saturation.inc(playerMobKillsTotal);
        DayActivity d = day(nowEpochMs);
        d.playerMobKills = Saturation.inc(d.playerMobKills);
        d.recordPlayer(playerId);
        touchActivity(nowEpochMs);
    }

    /** @return true when this chunk was a genuine first entry (dedup by set). */
    public boolean recordChunkFirstEntry(long packedChunk, int eventGeneration, long nowEpochMs) {
        if (!acceptsGeneration(eventGeneration)) return false;
        probeChunks = Saturation.inc(probeChunks);
        if (firstEntryChunks.contains(packedChunk)) return false;
        if (firstEntryChunks.size() >= CHUNK_CAP) {
            firstEntryOverflow = Saturation.inc(firstEntryOverflow);
            ensureFlag("CHUNK_SET_SATURATED");
            DayActivity d = day(nowEpochMs);
            d.chunkDiscoveries = Saturation.inc(d.chunkDiscoveries);
            return false;
        }
        firstEntryChunks.add(packedChunk);
        DayActivity d = day(nowEpochMs);
        d.chunkDiscoveries = Saturation.inc(d.chunkDiscoveries);
        touchActivity(nowEpochMs);
        return true;
    }

    /** @return true when this structure placement was a genuine discovery. */
    public boolean recordStructure(String structureId, long packedSection, int eventGeneration, long nowEpochMs) {
        if (!acceptsGeneration(eventGeneration)) return false;
        if (structureId == null || structureId.isBlank()) return false;
        StructureSighting s = structures.get(structureId);
        if (s == null) {
            if (structures.size() >= STRUCTURE_TYPE_CAP) {
                ensureFlag("STRUCTURE_TYPES_SATURATED");
                return false;
            }
            s = new StructureSighting(structureId, nowEpochMs);
            structures.put(structureId, s);
        }
        boolean fresh = s.recordSection(packedSection, nowEpochMs);
        if (fresh) {
            DayActivity d = day(nowEpochMs);
            d.structureDiscoveries = Saturation.inc(d.structureDiscoveries);
            touchActivity(nowEpochMs);
        }
        return fresh;
    }

    public void observeConcurrentInside(int concurrent, int eventGeneration, long nowEpochMs) {
        if (!acceptsGeneration(eventGeneration)) return;
        if (concurrent > peakConcurrentInside) peakConcurrentInside = Math.min(concurrent, Saturation.CEILING > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Saturation.CEILING);
        touchActivity(nowEpochMs);
    }

    public void markClosed(long epochMs) {
        if (closedAtEpochMs == null) closedAtEpochMs = epochMs <= 0 ? System.currentTimeMillis() : epochMs;
    }

    public boolean isClosed() {
        return closedAtEpochMs != null;
    }

    // ------------------------------------------------------------- queries

    public long distinctExplorers() {
        return uniqueExplorers.size() + uniqueExplorerOverflow;
    }

    public long distinctChunks() {
        return firstEntryChunks.size() + firstEntryOverflow;
    }

    public long totalStructurePlacements() {
        long n = 0;
        for (StructureSighting s : structures.values()) n += s.distinctSections();
        return n;
    }

    public DayActivity day(long epochMs) {
        String key = Instant.ofEpochMilli(epochMs).toString().substring(0, 10);
        DayActivity d = days.computeIfAbsent(key, DayActivity::new);
        if (days.size() > DAY_WINDOW_MAX) { // in-memory bound, mirrors persisted trim
            String oldest = days.keySet().iterator().next();
            if (!oldest.equals(key)) days.remove(oldest);
        }
        return d;
    }

    /** Trims day buckets to the newest {@code maxDays} entries. */
    public void trimDays(int maxDays) {
        int limit = Math.max(1, Math.min(maxDays, DAY_WINDOW_MAX));
        while (days.size() > limit) {
            String oldest = days.keySet().iterator().next();
            days.remove(oldest);
        }
    }

    private boolean markUnique(UUID id) {
        if (id == null) return false;
        String key = id.toString();
        if (uniqueExplorers.contains(key)) return false;
        if (uniqueExplorers.size() >= UNIQUE_CAP) {
            uniqueExplorerOverflow = Saturation.inc(uniqueExplorerOverflow);
            ensureFlag("UNIQUE_SET_SATURATED");
            return false;
        }
        uniqueExplorers.add(key);
        return true;
    }

    private void ensureFlag(String flag) {
        if (!qualityFlags.contains(flag)) qualityFlags.add(flag);
    }

    private void touchActivity(long nowEpochMs) {
        if (nowEpochMs > lastActivityEpochMs) lastActivityEpochMs = nowEpochMs;
    }
}
