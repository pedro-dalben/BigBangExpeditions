package com.bigbangcraft.expeditions.sector;

import java.util.List;
import java.util.Optional;

/**
 * Pure chunk -> sector lookup (Goal 04).
 *
 * Sectors are gameplay districts: navigation/statistical regions. This locator
 * is O(n) over a handful of registered sectors — cheap enough for per-command
 * use; do not call it per-tick.
 */
public final class SectorLocator {

    private SectorLocator() {}

    public static Optional<SectorRecord> locate(List<SectorRecord> sectors,
                                                String dimension,
                                                int chunkX, int chunkZ) {
        if (sectors == null || dimension == null) return Optional.empty();
        for (SectorRecord s : sectors) {
            if (!dimension.equals(s.dimension)) continue;
            if (chunkX >= s.minChunkX && chunkX <= s.maxChunkX
                    && chunkZ >= s.minChunkZ && chunkZ <= s.maxChunkZ) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }
}
