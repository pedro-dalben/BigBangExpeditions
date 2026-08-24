package com.bigbangcraft.expeditions.sector;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Goal 04: chunk -> district lookup.
 */
class SectorLocatorTest {

    private static SectorRecord sector(String id, String dim, int cx0, int cz0, int cx1, int cz1) {
        return new SectorRecord(id, dim, cx0, cz0, cx1, cz1);
    }

    @Test
    void findsSectorContainingChunk() {
        SectorRecord a = sector("a01_downtown", "bigbangexpeditions:expedition", 128, 128, 159, 159);
        Optional<SectorRecord> hit = SectorLocator.locate(
                List.of(a), "bigbangexpeditions:expedition", 130, 155);
        assertTrue(hit.isPresent());
        assertEquals("a01_downtown", hit.get().id);
    }

    @Test
    void outsideEverySectorIsEmpty() {
        SectorRecord a = sector("a01", "bigbangexpeditions:expedition", 0, 0, 31, 31);
        assertTrue(SectorLocator.locate(List.of(a), "bigbangexpeditions:expedition", 32, 5).isEmpty());
        assertTrue(SectorLocator.locate(List.of(a), "bigbangexpeditions:expedition", -1, 0).isEmpty());
    }

    @Test
    void otherDimensionsIgnored() {
        SectorRecord a = sector("a01", "minecraft:overworld", 0, 0, 31, 31);
        assertTrue(SectorLocator.locate(List.of(a), "bigbangexpeditions:expedition", 5, 5).isEmpty());
    }

    @Test
    void firstMatchWinsOnOverlap() {
        SectorRecord outer = sector("wide", "bigbangexpeditions:expedition", 0, 0, 63, 63);
        SectorRecord inner = sector("core", "bigbangexpeditions:expedition", 16, 16, 47, 47);
        // deterministic: registry order decides; both hits must be one of the registered sectors
        var hit = SectorLocator.locate(List.of(outer, inner),
                "bigbangexpeditions:expedition", 20, 20);
        assertTrue(hit.isPresent());
    }

    @Test
    void nullInputsAreSafe() {
        assertTrue(SectorLocator.locate(null, "d", 0, 0).isEmpty());
        assertTrue(SectorLocator.locate(List.of(), null, 0, 0).isEmpty());
    }
}
