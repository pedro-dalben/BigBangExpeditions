package com.bigbangcraft.expeditions.sector;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Explicit sector lifecycle. Transitions are validated; anything not listed
 * here is rejected (fail-closed).
 *
 *   OPEN        sector available to players
 *   DEPLETED    loot extracted / content consumed, still safe
 *   COOLDOWN    temporary hold before reopening
 *   LOCKED      frozen for reset preparation (no players expected)
 *   RESET_PLANNED  a valid reset-plan manifest exists for this generation
 *   RESETTING   destructive operation in progress (offline executor)
 *   VALIDATING  post-reset comparison against baseline running
 *   FAILED      post-reset validation failed OR interruption detected
 */
public enum SectorState {
    OPEN,
    DEPLETED,
    COOLDOWN,
    LOCKED,
    RESET_PLANNED,
    RESETTING,
    VALIDATING,
    FAILED;

    private static final Map<SectorState, Set<SectorState>> ALLOWED = Map.of(
            OPEN, Set.of(LOCKED, DEPLETED),
            DEPLETED, Set.of(LOCKED, OPEN),
            COOLDOWN, Set.of(OPEN, LOCKED),
            LOCKED, Set.of(RESET_PLANNED, OPEN),
            RESET_PLANNED, Set.of(RESETTING, LOCKED),
            RESETTING, Set.of(VALIDATING, FAILED),
            VALIDATING, Set.of(OPEN, FAILED),
            FAILED, Set.of(LOCKED));

    /** Error message when the transition is illegal; empty when allowed. */
    public static Optional<String> rejectTransition(SectorState from, SectorState to) {
        if (from == null || to == null) return Optional.of("null state");
        if (from == to) return Optional.empty(); // idempotent no-op is fine
        Set<SectorState> targets = ALLOWED.getOrDefault(from, Set.of());
        if (!targets.contains(to)) {
            return Optional.of("illegal transition " + from + " -> " + to
                    + " (allowed: " + targets + ")");
        }
        return Optional.empty();
    }

    /** States from which a reset-plan may be executed offline. */
    public static boolean resettable(SectorState s) {
        return s == RESETTING || s == RESET_PLANNED;
    }
}
