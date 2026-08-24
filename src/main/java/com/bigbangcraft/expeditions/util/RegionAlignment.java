package com.bigbangcraft.expeditions.util;

/**
 * Region alignment helpers. Region = 32x32 chunks = 512x512 blocks.
 * Validated per Goal 00 §9 & §12: sectors must align to region files.
 */
public final class RegionAlignment {
    public static final int REGION_CHUNKS = 32;
    public static final int CHUNK_SIZE = 16;
    public static final int REGION_BLOCKS = REGION_CHUNKS * CHUNK_SIZE; // 512

    private RegionAlignment() {}

    public static boolean isAligned(int chunkCoord) {
        return chunkCoord % REGION_CHUNKS == 0;
    }

    public static boolean isRegionAligned(int minChunk, int maxChunk) {
        // bounds are inclusive: e.g. 0..31, 0..63, 32..63 etc.
        // Valid if size is multiple of 32 and min aligned.
        if (!isAligned(minChunk)) return false;
        int size = maxChunk - minChunk + 1;
        if (size <= 0) return false;
        if (size % REGION_CHUNKS != 0) return false;
        // max must be aligned to region end: (max+1) %32 ==0
        return (maxChunk + 1) % REGION_CHUNKS == 0;
    }

    public static String validateBounds(int minX, int minZ, int maxX, int maxZ) {
        if (minX > maxX) return "minX > maxX";
        if (minZ > maxZ) return "minZ > maxZ";
        if (!isRegionAligned(minX, maxX)) return "X not region-aligned (must be 32-chunk blocks, e.g. 0..31, 0..63, 32..63)";
        if (!isRegionAligned(minZ, maxZ)) return "Z not region-aligned (must be 32-chunk blocks)";
        return null;
    }

    public static int regionCoord(int chunkCoord) {
        return Math.floorDiv(chunkCoord, REGION_CHUNKS);
    }
}
