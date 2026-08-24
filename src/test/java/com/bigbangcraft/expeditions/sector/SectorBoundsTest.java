package com.bigbangcraft.expeditions.sector;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class SectorBoundsTest {
    @Test
    void validBounds() {
        SectorBounds b = new SectorBounds("test", new ResourceLocation("minecraft:overworld"), 0, 0, 31, 31);
        assertNull(b.validate());
        assertEquals(1024, b.chunkCount());
        assertEquals(0, b.minBlockX());
        assertEquals(511, b.maxBlockX());
    }

    @Test
    void rejectsNonAligned() {
        SectorBounds b = new SectorBounds("test", new ResourceLocation("minecraft:overworld"), 0, 0, 30, 31);
        assertNotNull(b.validate());
        assertTrue(b.validate().contains("region-aligned"));
    }

    @Test
    void rejectsInvalidId() {
        SectorBounds b = new SectorBounds("Bad Id!", new ResourceLocation("minecraft:overworld"), 0, 0, 31, 31);
        assertNotNull(b.validate());
    }

    @Test
    void containsChunk() {
        SectorBounds b = new SectorBounds("a", new ResourceLocation("minecraft:overworld"), 0, 0, 63, 63);
        assertTrue(b.containsChunk(0, 0));
        assertTrue(b.containsChunk(63, 63));
        assertFalse(b.containsChunk(64, 0));
        assertFalse(b.containsChunk(-1, 0));
    }

    @Test
    void containsBlock() {
        SectorBounds b = new SectorBounds("a", new ResourceLocation("minecraft:overworld"), 0, 0, 31, 31);
        assertTrue(b.containsBlock(0, 0));
        assertTrue(b.containsBlock(511, 511));
        assertFalse(b.containsBlock(512, 512));
        assertFalse(b.containsBlock(-1, -1));
    }

    @Test
    void intersectionMath() {
        SectorBounds sector = new SectorBounds("s", new ResourceLocation("minecraft:overworld"), 0, 0, 63, 63);
        // claim at 10,10 inside
        assertTrue(sector.containsChunk(10, 10));
        // claim at 64,64 outside
        assertFalse(sector.containsChunk(64, 64));
        // boundary
        assertTrue(sector.containsChunk(63, 63));
        assertFalse(sector.containsChunk(64, 63));
    }

    @Test
    void rejectsTooLarge() {
        // 32*32*16 = 16384 max, try 32*32*17
        SectorBounds b = new SectorBounds("big", new ResourceLocation("minecraft:overworld"), 0, 0, 511, 511); // 512x512 chunks = 262k > limit
        assertNotNull(b.validate());
        assertTrue(b.validate().contains("too large"));
    }

    @Test
    void minMaxValidation() {
        SectorBounds b = new SectorBounds("x", new ResourceLocation("minecraft:overworld"), 32, 32, 0, 0);
        assertNotNull(b.validate());
    }
}
