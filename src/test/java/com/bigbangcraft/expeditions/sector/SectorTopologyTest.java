package com.bigbangcraft.expeditions.sector;

import com.bigbangcraft.expeditions.util.RegionAlignment;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SectorTopologyTest {

    private static final ResourceLocation DIM =
            new ResourceLocation("bigbangexpeditions", "expedition");

    @Test
    void r1CoversExactlyOneRegion() {
        int[] b = SectorTopology.chunkBounds(SectorTopology.Size.R1, 0, 0);
        assertArrayEquals(new int[]{0, 0, 31, 31}, b);

        int[] b44 = SectorTopology.chunkBounds(SectorTopology.Size.R1, 4, -1);
        assertArrayEquals(new int[]{128, -32, 159, -1}, b44);
    }

    @Test
    void r2AndR4CoverWholeRegions() {
        assertArrayEquals(new int[]{0, 0, 63, 63}, SectorTopology.chunkBounds(SectorTopology.Size.R2, 0, 0));
        assertArrayEquals(new int[]{64, 64, 127, 127}, SectorTopology.chunkBounds(SectorTopology.Size.R2, 1, 1));
        assertArrayEquals(new int[]{0, 0, 127, 127}, SectorTopology.chunkBounds(SectorTopology.Size.R4, 0, 0));
    }

    @Test
    void builtBoundsAreRegionAligned() {
        for (SectorTopology.Size size : SectorTopology.Size.values()) {
            for (int rx = -2; rx <= 2; rx++) {
                for (int rz = -2; rz <= 2; rz++) {
                    String[] err = new String[1];
                    SectorBounds b = SectorTopology.build("t" + rx + "_" + rz, DIM, size, rx, rz, err);
                    assertEquals("", err[0], "sector bounds must validate: " + b);
                    assertTrue(RegionAlignment.isAligned(b.minChunkX()));
                    assertTrue(RegionAlignment.isAligned(b.minChunkZ()));
                }
            }
        }
    }

    @Test
    void containingSectorRoundTrip() {
        long sector = SectorTopology.containingSectorChunk(SectorTopology.Size.R1, 33, 5);
        int rx = (int) (sector >> 32);
        int rz = (int) (long) (int) sector;
        assertEquals(1, rx);
        assertEquals(0, rz);

        // negative chunk coords
        long neg = SectorTopology.containingSectorChunk(SectorTopology.Size.R1, -1, -1);
        assertEquals(-1, (int) (neg >> 32));
        assertEquals(-1, (int) (long) (int) neg);
    }
}
