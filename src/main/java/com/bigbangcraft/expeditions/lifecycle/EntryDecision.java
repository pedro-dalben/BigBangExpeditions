package com.bigbangcraft.expeditions.lifecycle;

/**
 * Pure decision: may a player enter the expedition dimension right now?
 * Only an explicitly OPEN lifecycle admits players — every other state is a
 * maintenance/destructive window.
 */
public final class EntryDecision {
    public final boolean allowed;
    public final String reason;

    private EntryDecision(boolean allowed, String reason) {
        this.allowed = allowed;
        this.reason = reason;
    }

    public static EntryDecision check(LifecycleState state) {
        if (state == null) {
            return new EntryDecision(false, "lifecycle state unknown (fail-closed)");
        }
        if (state.playersMayEnter()) {
            return new EntryDecision(true, "");
        }
        return new EntryDecision(false, "expedition is " + state + " — entry blocked until OPEN");
    }
}
