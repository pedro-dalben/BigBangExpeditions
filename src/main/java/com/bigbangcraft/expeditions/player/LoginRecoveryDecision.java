package com.bigbangcraft.expeditions.player;

import com.bigbangcraft.expeditions.lifecycle.PlayerStateMapper;

/**
 * Pure login-recovery matrix (Goal 04, mandatory outcome §5 of the mission).
 *
 * A returning player must NEVER:
 * - materialize inside a deleted/regenerated zone as if nothing happened,
 * - restore into an unsafe lifecycle state,
 * - stay stranded by an interrupted transfer.
 *
 * Inputs are deliberately primitive so the whole table is unit-testable;
 * the adapter ({@code player/SessionRecovery}) maps them onto live server state.
 */
public final class LoginRecoveryDecision {

    public enum Action {
        /** Player was not tracked inside — normal login, do nothing. */
        NONE,
        /** Same generation, lifecycle OPEN — restore exactly where they logged out. */
        RESTORE_IN_PLACE,
        /** Zone regenerated while they were away — recover to their persistent-world return point. */
        RECOVER_NEW_ZONE,
        /** Maintenance window — complete the eviction that closure started. */
        EVICT_MAINTENANCE,
        /** Interrupted transfer — resolve to a safe persistent-world position. */
        RESOLVE_TRANSFER
    }

    private LoginRecoveryDecision() {}

    /**
     * @param wasInside     persistent "was inside" marker present
     * @param loggedOutGen  generation stamped when the player entered (-1 unknown)
     * @param currentGen    current dimension lifecycle generation
     * @param category      player-facing categorization of the CURRENT state
     * @param transferFlag  interrupted-transfer marker present
     */
    public static Action decide(boolean wasInside,
                                int loggedOutGen,
                                int currentGen,
                                PlayerStateMapper.Category category,
                                boolean transferFlag) {
        if (transferFlag) return Action.RESOLVE_TRANSFER;
        if (!wasInside) return Action.NONE;

        switch (category) {
            case OPEN -> {
                // Fail-closed on ANY generation ambiguity: unknown logout
                // generation (-1) or impossible regression both recover rather
                // than trust stale coordinates over regenerated terrain.
                if (loggedOutGen == currentGen) return Action.RESTORE_IN_PLACE;
                return Action.RECOVER_NEW_ZONE;
            }
            case CLOSING -> {
                return Action.EVICT_MAINTENANCE;
            }
            default -> {
                return Action.EVICT_MAINTENANCE;
            }
        }
    }
}
