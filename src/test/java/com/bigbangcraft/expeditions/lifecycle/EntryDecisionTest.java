package com.bigbangcraft.expeditions.lifecycle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EntryDecisionTest {

    @Test
    void onlyOpenAdmitsPlayers() {
        assertTrue(EntryDecision.check(LifecycleState.OPEN).allowed);

        for (LifecycleState s : LifecycleState.values()) {
            if (s == LifecycleState.OPEN) continue;
            EntryDecision d = EntryDecision.check(s);
            assertFalse(d.allowed, () -> s + " must block entry");
            assertFalse(d.reason.isBlank(), () -> s + " must explain the refusal");
        }
    }

    @Test
    void nullStateFailsClosed() {
        assertFalse(EntryDecision.check(null).allowed);
        assertTrue(EntryDecision.check(null).reason.contains("fail-closed"));
    }

    @Test
    void refusalNamesTheBlockingState() {
        String reason = EntryDecision.check(LifecycleState.RESETTING).reason;
        assertTrue(reason.contains("RESETTING"));
    }
}
