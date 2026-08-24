package com.bigbangcraft.expeditions.sector;

import java.util.Map;
import java.util.TreeMap;

/**
 * Persistent sector record. Plain data — serialized as JSON.
 * Bounds are region-aligned chunk coordinates (see RegionAlignment).
 */
public final class SectorRecord {
    public String id;
    public String dimension;
    public int minChunkX;
    public int minChunkZ;
    public int maxChunkX;
    public int maxChunkZ;
    public SectorState status = SectorState.OPEN;

    public long createdAtEpochMs;
    public long updatedAtEpochMs;
    public long lastOpenedAtEpochMs;
    public long lastResetAtEpochMs;

    /** Number of completed resets (generation counter). */
    public int resetCount;
    /** Baseline label/id captured for the current generation, if any. */
    public String lastBaselineId = "";
    /** Result summary of the most recent post-reset validation. */
    public String lastValidationResult = "";
    /** Why the sector entered FAILED, when applicable. */
    public String failureReason = "";
    /** Free-form annotations (who created/locked etc). */
    public Map<String, String> notes = new TreeMap<>();

    public SectorRecord() {} // serializer

    public SectorRecord(String id, String dimension, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        this.id = id;
        this.dimension = dimension;
        this.minChunkX = minChunkX;
        this.minChunkZ = minChunkZ;
        this.maxChunkX = maxChunkX;
        this.maxChunkZ = maxChunkZ;
    }

    public SectorBounds toBounds() {
        return new SectorBounds(id,
                new net.minecraft.resources.ResourceLocation(dimension),
                minChunkX, minChunkZ, maxChunkX, maxChunkZ);
    }
}
