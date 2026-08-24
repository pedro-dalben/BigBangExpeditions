package com.bigbangcraft.expeditions.sector;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ProbeResultTest {
    @Test
    void verdictPassWarnRefused() {
        SectorBounds b = new SectorBounds("t", new ResourceLocation("minecraft:overworld"), 0, 0, 31, 31);
        SectorProbeResult r = new SectorProbeResult(b);
        assertEquals(SectorProbeResult.Verdict.PASS, r.verdict());
        r.warn("something");
        assertEquals(SectorProbeResult.Verdict.WARN, r.verdict());
        r.refuse("blocked");
        assertEquals(SectorProbeResult.Verdict.REFUSED, r.verdict());
    }

    @Test
    void unknownNamespaceAggregation() {
        SectorBounds b = new SectorBounds("t", new ResourceLocation("minecraft:overworld"), 0, 0, 31, 31);
        SectorProbeResult r = new SectorProbeResult(b);
        r.unknownNamespaces.add("unknownmod");
        r.warn("unknown BE namespaces: unknownmod");
        assertEquals(1, r.unknownNamespaces.size());
        assertTrue(r.warnings().contains("unknown BE namespaces: unknownmod"));
    }

    @Test
    void playerInsideDetectionLogic() {
        // pure logic: probe would set playersInside
        SectorBounds b = new SectorBounds("t", new ResourceLocation("minecraft:overworld"), 0, 0, 31, 31);
        assertTrue(b.containsBlock(0, 0));
        assertTrue(b.containsBlock(511, 511));
        assertFalse(b.containsBlock(512, 0));
        // simulate player at 100,100 inside
        int bx = 100, bz = 100;
        assertTrue(b.containsBlock(bx, bz));
        // outside
        assertFalse(b.containsBlock(1000, 1000));
    }
}
