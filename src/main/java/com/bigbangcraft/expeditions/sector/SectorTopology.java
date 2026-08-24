package com.bigbangcraft.expeditions.sector;

import com.bigbangcraft.expeditions.util.RegionAlignment;
import net.minecraft.resources.ResourceLocation;

/**
 * Deterministic sector topology (Goal 02 Phase 9).
 * Sectors are addressed in REGION units: sector (rx, rz) with size N covers
 * regions [rx*N .. rx*N+N-1] x [rz*N .. rz*N+N-1], i.e. chunk bounds
 * [rx*N*32 .. (rx*N+N)*32-1]. Region alignment removes all destructive-path
 * ambiguity: one sector == a whole set of region files.
 */
public final class SectorTopology {
    /** Candidate sizes evaluated in Phase 9; final choice is evidence-driven. */
    public enum Size {
        R1(1),   // 32x32 chunks, 512x512 blocks
        R2(2),   // 64x64 chunks, 1024x1024 blocks
        R4(4);   // 128x128 chunks, 2048x2048 blocks

        public final int regionsPerSide;

        Size(int n) { this.regionsPerSide = n; }
    }

    private SectorTopology() {}

    public static int chunksPerSide(Size size) {
        return size.regionsPerSide * RegionAlignment.REGION_CHUNKS;
    }

    /** Chunk-space bounds for the sector at region coordinates (rx, rz). */
    public static int[] chunkBounds(Size size, int rx, int rz) {
        int n = size.regionsPerSide * RegionAlignment.REGION_CHUNKS;
        return new int[]{rx * n, rz * n, rx * n + n - 1, rz * n + n - 1};
    }

    /**
     * Builds validated chunk bounds for a named sector.
     * Returns error message via err[0] (empty string on success).
     */
    public static SectorBounds build(String id, ResourceLocation dimension, Size size, int rx, int rz, String[] err) {
        int[] b = chunkBounds(size, rx, rz);
        SectorBounds bounds = new SectorBounds(id, dimension, b[0], b[1], b[2], b[3]);
        String problem = bounds.validate();
        err[0] = problem == null ? "" : problem;
        return bounds;
    }

    /** Inverse mapping: which sector contains this chunk? */
    public static long containingSectorChunk(Size size, int chunkX, int chunkZ) {
        int n = chunksPerSide(size);
        return (((long) Math.floorDiv(chunkX, n)) << 32) ^ (Math.floorDiv(chunkZ, n) & 0xffffffffL);
    }
}
