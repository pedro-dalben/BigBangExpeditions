package com.bigbangcraft.expeditions.telemetry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InteractionDeduperTest {

    @Test
    void firstAcceptThenWindowSuppression() {
        InteractionDeduper d = new InteractionDeduper(60_000, 1024);
        assertTrue(d.tryAccept("p1:100", 1000));
        assertFalse(d.tryAccept("p1:100", 2000));      // spam inside window
        assertFalse(d.tryAccept("p1:100", 60_999));
        assertTrue(d.tryAccept("p1:100", 61_001));     // window elapsed
    }

    @Test
    void differentKeysIndependent() {
        InteractionDeduper d = new InteractionDeduper(60_000, 1024);
        assertTrue(d.tryAccept("p1:1", 1000));
        assertTrue(d.tryAccept("p1:2", 1000));
        assertTrue(d.tryAccept("p2:1", 1000));
        assertEquals(3, d.trackedCount());
    }

    @Test
    void capacityBoundShedsOldest() {
        InteractionDeduper d = new InteractionDeduper(60_000, 16);
        for (int i = 0; i < 64; i++) {
            d.tryAccept("k" + i, 1000 + i);
        }
        assertTrue(d.trackedCount() <= 16);
        // oldest keys were shed: re-accepted as new facts (bounded loss, advisory-grade)
        assertTrue(d.tryAccept("k0", 5000));
    }

    @Test
    void expiredEntriesEvictedBeforeShed() {
        InteractionDeduper d = new InteractionDeduper(10_000, 32);
        for (int i = 0; i < 40; i++) d.tryAccept("old" + i, 100 + i);
        // all old entries now expired relative to a much later timestamp
        assertTrue(d.tryAccept("fresh", 1_000_000));
        assertTrue(d.trackedCount() <= 2);
    }

    @Test
    void clearResetsState() {
        InteractionDeduper d = new InteractionDeduper(60_000, 1024);
        d.tryAccept("a", 1);
        d.clear();
        assertEquals(0, d.trackedCount());
        assertTrue(d.tryAccept("a", 2));
    }
}
