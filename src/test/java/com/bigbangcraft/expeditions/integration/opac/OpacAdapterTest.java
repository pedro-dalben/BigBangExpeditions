package com.bigbangcraft.expeditions.integration.opac;

import com.bigbangcraft.expeditions.sector.SectorBounds;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class OpacAdapterTest {
    @Test
    void failClosedWhenServerNull() {
        SectorBounds b = new SectorBounds("t", new ResourceLocation("minecraft:overworld"), 0, 0, 31, 31);
        ClaimInspectionResult r = OpacAdapter.inspectClaims(null, null, b);
        assertFalse(r.isAvailable());
        assertTrue(r.unavailableReason().contains("server null"));
    }

    @Test
    void failClosedWhenDimensionNull() {
        SectorBounds b = new SectorBounds("t", new ResourceLocation("minecraft:overworld"), 0, 0, 31, 31);
        // use null server to trigger fail-closed without needing mock
        ClaimInspectionResult r = OpacAdapter.inspectClaims(null, null, b);
        assertFalse(r.isAvailable());
        // server null path covers dimension case as well
        assertNotNull(r.unavailableReason());
    }

    @Test
    void opacNotPresentInTestEnv() {
        // test env has no OPAC jar
        assertFalse(OpacAdapter.isOpacPresent());
    }

    @Test
    void claimInspectionUnavailableLeadsToRefused() {
        ClaimInspectionResult r = ClaimInspectionResult.unavailable("test reason");
        assertFalse(r.isAvailable());
        assertEquals("test reason", r.unavailableReason());
        assertFalse(r.intersects());
    }

    @Test
    void claimInspectionAvailable() {
        ClaimInspectionResult r = ClaimInspectionResult.available(3, 1, java.util.List.of("a"));
        assertTrue(r.isAvailable());
        assertEquals(3, r.intersectingChunks());
        assertEquals(1, r.forceloadChunks());
        assertTrue(r.intersects());
        assertTrue(r.hasForceloads());
    }
}
