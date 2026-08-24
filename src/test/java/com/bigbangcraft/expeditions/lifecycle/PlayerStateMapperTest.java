package com.bigbangcraft.expeditions.lifecycle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Goal 04: player-facing state categorization.
 */
class PlayerStateMapperTest {

    @Test
    void onlyOpenAllowsEntry() {
        for (LifecycleState s : LifecycleState.values()) {
            boolean expected = s == LifecycleState.OPEN;
            assertEquals(expected, PlayerStateMapper.categorize(s).allowsEntry(),
                    "state " + s + " mis-categorized");
        }
    }

    @Test
    void closingStatesAreDistinctFromMaintenance() {
        assertEquals(PlayerStateMapper.Category.CLOSING,
                PlayerStateMapper.categorize(LifecycleState.CLOSING));
        assertEquals(PlayerStateMapper.Category.CLOSING,
                PlayerStateMapper.categorize(LifecycleState.EVACUATING));
        assertEquals(PlayerStateMapper.Category.UNAVAILABLE,
                PlayerStateMapper.categorize(LifecycleState.LOCKED));
        assertEquals(PlayerStateMapper.Category.UNAVAILABLE,
                PlayerStateMapper.categorize(LifecycleState.VALIDATING));
    }

    @Test
    void nullIsFailClosedUnavailable() {
        assertEquals(PlayerStateMapper.Category.UNAVAILABLE,
                PlayerStateMapper.categorize(null));
        assertFalse(PlayerStateMapper.categorize(null).allowsEntry());
    }

    @Test
    void phraseKeyCoversEveryState() {
        for (LifecycleState s : LifecycleState.values()) {
            String key = PlayerStateMapper.phraseKey(s);
            assertTrue(key.startsWith("bbe.state."), key);
            assertTrue(com.bigbangcraft.expeditions.i18n.Translations.get().has("pt_br", key),
                    "missing lang entry for " + key);
        }
    }

    @Test
    void nullStateFallsBackToRecoveryWording() {
        String key = PlayerStateMapper.phraseKey(null);
        assertEquals("bbe.state.recovery_required", key);
    }
}
