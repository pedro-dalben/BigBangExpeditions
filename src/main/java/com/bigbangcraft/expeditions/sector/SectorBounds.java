package com.bigbangcraft.expeditions.sector;

import com.bigbangcraft.expeditions.util.RegionAlignment;
import net.minecraft.resources.ResourceLocation;

/**
 * In-memory sector definition for diagnostics. No persistence registry yet (Goal 01).
 * Bounds are inclusive chunk coordinates, must be region-aligned (32x32).
 */
public final class SectorBounds {
    private final String id;
    private final ResourceLocation dimension;
    private final int minChunkX;
    private final int minChunkZ;
    private final int maxChunkX;
    private final int maxChunkZ;

    public SectorBounds(String id, ResourceLocation dimension, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        this.id = id;
        this.dimension = dimension;
        this.minChunkX = minChunkX;
        this.minChunkZ = minChunkZ;
        this.maxChunkX = maxChunkX;
        this.maxChunkZ = maxChunkZ;
    }

    public String id() { return id; }
    public ResourceLocation dimension() { return dimension; }
    public int minChunkX() { return minChunkX; }
    public int minChunkZ() { return minChunkZ; }
    public int maxChunkX() { return maxChunkX; }
    public int maxChunkZ() { return maxChunkZ; }

    public int chunkCount() {
        return (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);
    }

    public int minBlockX() { return minChunkX * 16; }
    public int minBlockZ() { return minChunkZ * 16; }
    public int maxBlockX() { return maxChunkX * 16 + 15; }
    public int maxBlockZ() { return maxChunkZ * 16 + 15; }

    public boolean containsChunk(int cx, int cz) {
        return cx >= minChunkX && cx <= maxChunkX && cz >= minChunkZ && cz <= maxChunkZ;
    }

    public boolean containsBlock(int bx, int bz) {
        return bx >= minBlockX() && bx <= maxBlockX() && bz >= minBlockZ() && bz <= maxBlockZ();
    }

    /**
     * Returns null if valid, else error message.
     */
    public String validate() {
        if (id == null || id.isBlank()) return "id blank";
        if (dimension == null) return "dimension null";
        if (id.length() > 64) return "id too long";
        if (!id.matches("[a-z0-9_\\-]+")) return "id must be [a-z0-9_-]";
        String align = RegionAlignment.validateBounds(minChunkX, minChunkZ, maxChunkX, maxChunkZ);
        if (align != null) return align;
        if (chunkCount() > 32 * 32 * 16) return "sector too large (max 16 region files, 16384 chunks)";
        if (chunkCount() <= 0) return "invalid size";
        return null;
    }

    @Override
    public String toString() {
        return id + "[" + dimension + " " + minChunkX + "," + minChunkZ + " -> " + maxChunkX + "," + maxChunkZ + " (" + chunkCount() + " chunks)]";
    }
}
