package com.bigbangcraft.expeditions.player;

import com.bigbangcraft.expeditions.lifecycle.PlayerStateMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Goal 04 mandatory login/logout matrix (prompt outcome §5).
 * Every row of docs/investigations/goal-04-initial-assessment.md §4.2.
 */
class LoginRecoveryDecisionTest {

    private static LoginRecoveryDecision.Action decide(boolean inside, int logoutGen, int gen,
                                                       com.bigbangcraft.expeditions.lifecycle.LifecycleState state) {
        return LoginRecoveryDecision.decide(inside, logoutGen, gen,
                PlayerStateMapper.categorize(state), false);
    }

    // ---- logout in expedition → login while OPEN ----------------------------

    @Test
    void openSameGenerationRestoresInPlace() {
        assertEquals(LoginRecoveryDecision.Action.RESTORE_IN_PLACE,
                decide(true, 3, 3, com.bigbangcraft.expeditions.lifecycle.LifecycleState.OPEN));
    }

    @Test
    void openAfterResetRecoversToReturnPoint() {
        assertEquals(LoginRecoveryDecision.Action.RECOVER_NEW_ZONE,
                decide(true, 3, 4, com.bigbangcraft.expeditions.lifecycle.LifecycleState.OPEN));
    }

    @Test
    void unknownGenerationNeverTrusted() {
        assertEquals(LoginRecoveryDecision.Action.RECOVER_NEW_ZONE,
                decide(true, -1, 3, com.bigbangcraft.expeditions.lifecycle.LifecycleState.OPEN));
    }

    @Test
    void generationRegressionFailsClosed() {
        // should be impossible; must not restore stale coordinates
        assertEquals(LoginRecoveryDecision.Action.RECOVER_NEW_ZONE,
                decide(true, 7, 2, com.bigbangcraft.expeditions.lifecycle.LifecycleState.OPEN));
    }

    // ---- logout in expedition → login while CLOSING / maintenance ----------

    @Test
    void closingEvicts() {
        assertEquals(LoginRecoveryDecision.Action.EVICT_MAINTENANCE,
                decide(true, 3, 3, com.bigbangcraft.expeditions.lifecycle.LifecycleState.CLOSING));
    }

    @Test
    void evacuatingEvicts() {
        assertEquals(LoginRecoveryDecision.Action.EVICT_MAINTENANCE,
                decide(true, 3, 3, com.bigbangcraft.expeditions.lifecycle.LifecycleState.EVACUATING));
    }

    @Test
    void lockedEvicts() {
        assertEquals(LoginRecoveryDecision.Action.EVICT_MAINTENANCE,
                decide(true, 3, 3, com.bigbangcraft.expeditions.lifecycle.LifecycleState.LOCKED));
    }

    @Test
    void validatingEvicts() {
        assertEquals(LoginRecoveryDecision.Action.EVICT_MAINTENANCE,
                decide(true, 3, 3, com.bigbangcraft.expeditions.lifecycle.LifecycleState.VALIDATING));
    }

    @Test
    void recoveryRequiredEvicts() {
        assertEquals(LoginRecoveryDecision.Action.EVICT_MAINTENANCE,
                decide(true, 3, 3, com.bigbangcraft.expeditions.lifecycle.LifecycleState.RECOVERY_REQUIRED));
    }

    @Test
    void destructiveWindowEvicts() {
        for (var s : new com.bigbangcraft.expeditions.lifecycle.LifecycleState[]{
                com.bigbangcraft.expeditions.lifecycle.LifecycleState.RESET_READY,
                com.bigbangcraft.expeditions.lifecycle.LifecycleState.BOOTING}) {
            assertEquals(LoginRecoveryDecision.Action.EVICT_MAINTENANCE,
                    decide(true, 3, 3, s), s.name());
        }
    }

    // ---- ordinary players ---------------------------------------------------

    @Test
    void outsidePlayerIsUntouched() {
        assertEquals(LoginRecoveryDecision.Action.NONE,
                decide(false, -1, 9, com.bigbangcraft.expeditions.lifecycle.LifecycleState.OPEN));
        assertEquals(LoginRecoveryDecision.Action.NONE,
                decide(false, -1, 9, com.bigbangcraft.expeditions.lifecycle.LifecycleState.LOCKED));
    }

    // ---- interrupted transfers ----------------------------------------------

    @Test
    void transferFlagWinsOverEverything() {
        assertEquals(LoginRecoveryDecision.Action.RESOLVE_TRANSFER,
                LoginRecoveryDecision.decide(true, 3, 3,
                        PlayerStateMapper.categorize(com.bigbangcraft.expeditions.lifecycle.LifecycleState.OPEN),
                        true));
        assertEquals(LoginRecoveryDecision.Action.RESOLVE_TRANSFER,
                LoginRecoveryDecision.decide(false, -1, 1,
                        PlayerStateMapper.categorize(com.bigbangcraft.expeditions.lifecycle.LifecycleState.LOCKED),
                        true));
    }
}
