package com.bigbangcraft.expeditions.reset;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Goal 04: quantified purge manifests for whole-dimension turnover.
 */
class PurgeManifestTest {

    @Test
    void emptyWhenNothingExceedsBaseline() {
        PurgeManifest m = PurgeManifest.of(
                Map.of("minecraft:chest", 5),
                Map.of("minecraft:chest", 5, "minecraft:furnace", 0));
        assertTrue(m.isEmpty());
        assertEquals(0, m.totalExtra());
    }

    @Test
    void countsOnlyExtras() {
        PurgeManifest m = PurgeManifest.of(
                Map.of("minecraft:chest", 5),
                Map.of("minecraft:chest", 8));
        assertFalse(m.isEmpty());
        assertEquals(3, m.totalExtra());
        assertEquals(3, m.extras().get("minecraft:chest"));
    }

    @Test
    void newTypesAreExtras() {
        PurgeManifest m = PurgeManifest.of(
                Map.of(),
                Map.of("create:mechanical_mixer", 2));
        assertEquals(2, m.totalExtra());
        assertTrue(m.summarize(5).contains("mechanical_mixer(+2)"));
    }

    @Test
    void missingLiveTypesAreNotNegatives() {
        // baseline had 10 chests, world now shows none (players looted them) — legal
        assertTrue(PurgeManifest.of(Map.of("minecraft:chest", 10), Map.of()).isEmpty());
    }

    @Test
    void hashBindsExactCounts() {
        PurgeManifest a = PurgeManifest.of(Map.of(), Map.of("minecraft:chest", 2));
        PurgeManifest b = PurgeManifest.of(Map.of(), Map.of("minecraft:chest", 2));
        PurgeManifest c = PurgeManifest.of(Map.of(), Map.of("minecraft:chest", 3));
        assertEquals(a.hash(), b.hash());
        assertNotEquals(a.hash(), c.hash());
    }

    @Test
    void summarizeTruncatesLongLists() {
        PurgeManifest m = PurgeManifest.of(Map.of(), Map.of(
                "a:x", 1, "b:y", 2, "c:z", 3, "d:w", 4, "e:v", 5, "f:u", 6, "g:t", 7));
        String s = m.summarize(5);
        assertTrue(s.contains("more types"));
        assertEquals(28, m.totalExtra()); // 1+2+3+4+5+6+7 items across 7 types
    }

    @Test
    void nullLiveMapIsEmpty() {
        assertTrue(PurgeManifest.of(Map.of("minecraft:chest", 1), null).isEmpty());
    }
}
