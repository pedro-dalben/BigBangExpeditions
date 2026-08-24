package com.bigbangcraft.expeditions.sector;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SectorRegistryTest {

    private static final long T0 = 1_700_000_000_000L;

    @TempDir
    Path tmp;

    private SectorRegistry newRegistry() {
        return new SectorRegistry(tmp.resolve("sectors.json"));
    }

    @Test
    void createAndPersistRoundTrip(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("sub").resolve("sectors.json");
        SectorRegistry r = new SectorRegistry(f);
        assertTrue(r.create("b04", "bigbangexpeditions:expedition", 128, 128, 159, 159, T0).isEmpty());
        r.save();
        assertTrue(Files.exists(f));

        SectorRegistry reloaded = new SectorRegistry(f);
        Optional<SectorRecord> rec = reloaded.get("b04");
        assertTrue(rec.isPresent());
        assertEquals(SectorState.OPEN, rec.get().status);
        assertEquals("bigbangexpeditions:expedition", rec.get().dimension);
        assertEquals(128, rec.get().minChunkX);
        assertEquals(159, rec.get().maxChunkZ);
    }

    @Test
    void duplicateIdRejected() {
        SectorRegistry r = newRegistry();
        assertTrue(r.create("a1", "bigbangexpeditions:expedition", 0, 0, 31, 31, T0).isEmpty());
        assertFalse(r.create("a1", "bigbangexpeditions:expedition", 32, 0, 63, 31, T0).isEmpty());
    }

    @Test
    void invalidIdRejected() {
        SectorRegistry r = newRegistry();
        assertFalse(r.create("Bad Id!", "bigbangexpeditions:expedition", 0, 0, 31, 31, T0).isEmpty());
        assertFalse(r.create("", "bigbangexpeditions:expedition", 0, 0, 31, 31, T0).isEmpty());
        assertFalse(r.create("../../evil", "bigbangexpeditions:expedition", 0, 0, 31, 31, T0).isEmpty());
    }

    @Test
    void invalidBoundsRejectedOnCreate() {
        SectorRegistry r = newRegistry();
        // not region aligned
        assertFalse(r.create("x1", "bigbangexpeditions:expedition", 5, 0, 36, 31, T0).isEmpty());
    }

    @Test
    void transitionsEnforcedAndPersisted() throws Exception {
        Path f = tmp.resolve("sectors.json");
        SectorRegistry r = new SectorRegistry(f);
        r.create("b04", "bigbangexpeditions:expedition", 0, 0, 31, 31, T0);

        // OPEN -> RESETTING must be rejected (goal's explicit example)
        assertFalse(r.transition("b04", SectorState.RESETTING, T0 + 1).isEmpty());

        assertTrue(r.transition("b04", SectorState.LOCKED, T0 + 1).isEmpty());
        assertTrue(r.transition("b04", SectorState.RESET_PLANNED, T0 + 2).isEmpty());
        assertTrue(r.transition("b04", SectorState.RESETTING, T0 + 3).isEmpty());
        assertTrue(r.transition("b04", SectorState.VALIDATING, T0 + 4).isEmpty());
        r.setValidationResult("b04", "PASS", T0 + 4);
        assertTrue(r.transition("b04", SectorState.OPEN, T0 + 5).isEmpty());
        r.save();

        SectorRecord after = new SectorRegistry(f).get("b04").orElseThrow();
        assertEquals(SectorState.OPEN, after.status);
        assertEquals(1, after.resetCount, "completed VALIDATING->OPEN counts a reset");
        assertTrue(after.lastResetAtEpochMs > 0);
        assertEquals("PASS", after.lastValidationResult);
    }

    @Test
    void unknownSectorRefused() {
        SectorRegistry r = newRegistry();
        assertFalse(r.transition("ghost", SectorState.LOCKED, T0).isEmpty());
    }

    @Test
    void failureReasonStoredAndClearedOnNewPlan() throws Exception {
        Path f = tmp.resolve("sectors.json");
        SectorRegistry r = new SectorRegistry(f);
        r.create("f1", "bigbangexpeditions:expedition", 0, 0, 31, 31, T0);
        r.transition("f1", SectorState.LOCKED, T0 + 1);
        r.transition("f1", SectorState.RESET_PLANNED, T0 + 2);
        r.transition("f1", SectorState.RESETTING, T0 + 3);
        r.setFailureReason("f1", "validation mismatch: spawner count", T0 + 4);
        r.transition("f1", SectorState.FAILED, T0 + 4);
        r.save();

        SectorRecord failed = new SectorRegistry(f).get("f1").orElseThrow();
        assertEquals(SectorState.FAILED, failed.status);
        assertEquals("validation mismatch: spawner count", failed.failureReason);

        // recovery path FAILED -> LOCKED -> RESET_PLANNED clears reason
        SectorRegistry r2 = new SectorRegistry(f);
        assertTrue(r2.transition("f1", SectorState.LOCKED, T0 + 5).isEmpty());
        assertTrue(r2.transition("f1", SectorState.RESET_PLANNED, T0 + 6).isEmpty());
        assertEquals("", r2.get("f1").orElseThrow().failureReason);
    }

    @Test
    void listIsSnapshotSorted() {
        SectorRegistry r = newRegistry();
        r.create("zz", "bigbangexpeditions:expedition", 0, 0, 31, 31, T0);
        r.create("aa", "bigbangexpeditions:expedition", 32, 0, 63, 31, T0);
        List<SectorRecord> all = r.list();
        assertEquals(2, all.size());
        assertEquals("aa", all.get(0).id, "TreeMap ordering keeps listing stable");
    }
}
