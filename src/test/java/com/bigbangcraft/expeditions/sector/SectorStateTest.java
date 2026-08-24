package com.bigbangcraft.expeditions.sector;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SectorStateTest {

    @Test
    void happyPathChainIsValid() {
        for (SectorState[] step : new SectorState[][]{
                {SectorState.OPEN, SectorState.LOCKED},
                {SectorState.LOCKED, SectorState.RESET_PLANNED},
                {SectorState.RESET_PLANNED, SectorState.RESETTING},
                {SectorState.RESETTING, SectorState.VALIDATING},
                {SectorState.VALIDATING, SectorState.OPEN}}) {
            assertTrue(SectorState.rejectTransition(step[0], step[1]).isEmpty(),
                    step[0] + " -> " + step[1] + " must be allowed");
        }
    }

    @Test
    void illegalShortcutRejected() {
        // the explicit example from the goal: OPEN -> RESETTING must be rejected
        assertTrue(SectorState.rejectTransition(SectorState.OPEN, SectorState.RESETTING).isPresent());
        assertTrue(SectorState.rejectTransition(SectorState.OPEN, SectorState.VALIDATING).isPresent());
        assertTrue(SectorState.rejectTransition(SectorState.LOCKED, SectorState.RESETTING).isPresent());
        assertTrue(SectorState.rejectTransition(SectorState.RESET_PLANNED, SectorState.OPEN).isPresent());
        assertTrue(SectorState.rejectTransition(SectorState.FAILED, SectorState.OPEN).isPresent(),
                "FAILED requires operator review through LOCKED");
    }

    @Test
    void failedGoesToLockedOnly() {
        assertEquals(Optional.empty(), SectorState.rejectTransition(SectorState.FAILED, SectorState.LOCKED));
        assertNotEquals(Optional.empty(), SectorState.rejectTransition(SectorState.FAILED, SectorState.RESETTING));
    }

    @Test
    void idempotentTransitionAllowed() {
        assertEquals(Optional.empty(), SectorState.rejectTransition(SectorState.LOCKED, SectorState.LOCKED));
    }

    @Test
    void nullStatesRefused() {
        assertTrue(SectorState.rejectTransition(null, SectorState.OPEN).isPresent());
        assertTrue(SectorState.rejectTransition(SectorState.OPEN, null).isPresent());
    }
}
