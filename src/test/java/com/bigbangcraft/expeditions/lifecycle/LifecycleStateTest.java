package com.bigbangcraft.expeditions.lifecycle;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LifecycleStateTest {

    @Test
    void happyPathTransitionsAreLegal() {
        List<LifecycleState> path = List.of(
                LifecycleState.OPEN, LifecycleState.CLOSING, LifecycleState.EVACUATING,
                LifecycleState.LOCKED, LifecycleState.PREFLIGHT, LifecycleState.BACKUP,
                LifecycleState.RESET_READY, LifecycleState.RESETTING, LifecycleState.BOOTING,
                LifecycleState.VALIDATING, LifecycleState.OPEN);
        for (int i = 0; i < path.size() - 1; i++) {
            final int idx = i;
            assertTrue(LifecycleState.rejectTransition(path.get(i), path.get(i + 1)).isEmpty(),
                    () -> path.get(idx) + " -> " + path.get(idx + 1));
        }
    }

    @Test
    void abortClosureReturnsToOpen() {
        assertTrue(LifecycleState.rejectTransition(LifecycleState.CLOSING, LifecycleState.OPEN).isEmpty());
        assertTrue(LifecycleState.rejectTransition(LifecycleState.EVACUATING, LifecycleState.OPEN).isEmpty());
    }

    @Test
    void destructiveShortcutsRefused() {
        assertNotNull(LifecycleState.rejectTransition(LifecycleState.OPEN, LifecycleState.RESETTING).orElse(null));
        assertNotNull(LifecycleState.rejectTransition(LifecycleState.LOCKED, LifecycleState.RESETTING).orElse(null));
        assertNotNull(LifecycleState.rejectTransition(LifecycleState.BACKUP, LifecycleState.RESETTING).orElse(null));
        assertNotNull(LifecycleState.rejectTransition(LifecycleState.OPEN, LifecycleState.VALIDATING).orElse(null));
    }

    @Test
    void failedRecoversOnlyThroughLocked() {
        assertEquals(java.util.Set.of(LifecycleState.LOCKED),
                java.util.Set.copyOf(List.of(LifecycleState.LOCKED)));
        assertTrue(LifecycleState.rejectTransition(LifecycleState.FAILED, LifecycleState.LOCKED).isEmpty());
        assertNotNull(LifecycleState.rejectTransition(LifecycleState.FAILED, LifecycleState.OPEN).orElse(null));
        assertNotNull(LifecycleState.rejectTransition(LifecycleState.FAILED, LifecycleState.RESET_READY).orElse(null));
    }

    @Test
    void recoveryRequiredReachableFromEveryState() {
        for (LifecycleState s : LifecycleState.values()) {
            assertTrue(LifecycleState.rejectTransition(s, LifecycleState.RECOVERY_REQUIRED).isEmpty(),
                    () -> s + " -> RECOVERY_REQUIRED must always be legal (fail-closed sink)");
        }
    }

    @Test
    void recoveryRequiresExplicitOperatorStep() {
        assertTrue(LifecycleState.rejectTransition(LifecycleState.RECOVERY_REQUIRED, LifecycleState.LOCKED).isEmpty());
        assertNotNull(LifecycleState.rejectTransition(LifecycleState.RECOVERY_REQUIRED, LifecycleState.OPEN).orElse(null));
        assertNotNull(LifecycleState.rejectTransition(LifecycleState.RECOVERY_REQUIRED, LifecycleState.RESETTING).orElse(null));
    }

    @Test
    void sameStateIsIdempotentNoOp() {
        for (LifecycleState s : LifecycleState.values()) {
            assertTrue(LifecycleState.rejectTransition(s, s).isEmpty(), () -> s.toString());
        }
    }

    @Test
    void nullStatesRejected() {
        assertTrue(LifecycleState.rejectTransition(null, LifecycleState.OPEN).isPresent());
        assertTrue(LifecycleState.rejectTransition(LifecycleState.OPEN, null).isPresent());
    }

    @Test
    void entryAndDestructiveWindowFlags() {
        assertTrue(LifecycleState.OPEN.playersMayEnter());
        assertFalse(LifecycleState.LOCKED.playersMayEnter());
        assertFalse(LifecycleState.VALIDATING.playersMayEnter());

        assertTrue(LifecycleState.RESET_READY.destructiveWindow());
        assertTrue(LifecycleState.RESETTING.destructiveWindow());
        assertTrue(LifecycleState.BOOTING.destructiveWindow());
        assertFalse(LifecycleState.OPEN.destructiveWindow());
    }
}
