package com.bigbangcraft.expeditions.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class RegionAlignmentTest {
    @Test
    void isAligned() {
        assertTrue(RegionAlignment.isAligned(0));
        assertTrue(RegionAlignment.isAligned(32));
        assertTrue(RegionAlignment.isAligned(64));
        assertFalse(RegionAlignment.isAligned(1));
        assertFalse(RegionAlignment.isAligned(31));
        assertFalse(RegionAlignment.isAligned(-1));
        assertTrue(RegionAlignment.isAligned(-32));
    }

    @Test
    void isRegionAligned_valid() {
        assertTrue(RegionAlignment.isRegionAligned(0, 31));
        assertTrue(RegionAlignment.isRegionAligned(0, 63));
        assertTrue(RegionAlignment.isRegionAligned(32, 63));
        assertTrue(RegionAlignment.isRegionAligned(-32, -1));
        assertTrue(RegionAlignment.isRegionAligned(-64, -33));
    }

    @Test
    void isRegionAligned_invalid() {
        assertFalse(RegionAlignment.isRegionAligned(0, 30)); // size 31
        assertFalse(RegionAlignment.isRegionAligned(1, 32)); // min not aligned
        assertFalse(RegionAlignment.isRegionAligned(0, 32)); // size 33
        assertFalse(RegionAlignment.isRegionAligned(0, 0)); // size 1
        assertFalse(RegionAlignment.isRegionAligned(16, 47)); // min not aligned
    }

    @Test
    void validateBounds() {
        assertNull(RegionAlignment.validateBounds(0, 0, 31, 31));
        assertNull(RegionAlignment.validateBounds(0, 0, 63, 63));
        assertNotNull(RegionAlignment.validateBounds(0, 0, 30, 31));
        assertNotNull(RegionAlignment.validateBounds(1, 0, 32, 31));
        assertNotNull(RegionAlignment.validateBounds(0, 0, 31, 30));
        assertNotNull(RegionAlignment.validateBounds(10, 10, 5, 5)); // min>max not checked here but size negative
    }

    @Test
    void regionCoord() {
        assertEquals(0, RegionAlignment.regionCoord(0));
        assertEquals(0, RegionAlignment.regionCoord(31));
        assertEquals(1, RegionAlignment.regionCoord(32));
        assertEquals(1, RegionAlignment.regionCoord(63));
        assertEquals(-1, RegionAlignment.regionCoord(-1));
        assertEquals(-1, RegionAlignment.regionCoord(-32));
    }
}
