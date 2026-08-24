package com.bigbangcraft.expeditions.lifecycle;

/**
 * Goal 04: maps technical lifecycle states onto player-facing categories.
 *
 * Players must never see enum names like VALIDATING or RECOVERY_REQUIRED;
 * every state collapses into one of three gameplay categories, each backed
 * by a lang key ({@code bbe.state.*}):
 *
 * <ul>
 *   <li>{@link Category#OPEN} — entry allowed</li>
 *   <li>{@link Category#CLOSING} — closure in progress, entry refused</li>
 *   <li>{@link Category#UNAVAILABLE} — maintenance/destructive window</li>
 * </ul>
 */
public final class PlayerStateMapper {

    public enum Category {
        /** bbe.state.open */
        OPEN,
        /** bbe.state.closing / bbe.state.evacuating */
        CLOSING,
        /** bbe.state.* friendly wording for every maintenance state */
        UNAVAILABLE;

        public boolean allowsEntry() {
            return this == OPEN;
        }
    }

    private PlayerStateMapper() {}

    public static Category categorize(LifecycleState state) {
        if (state == null) return Category.UNAVAILABLE; // fail-closed
        switch (state) {
            case OPEN: return Category.OPEN;
            case CLOSING:
            case EVACUATING: return Category.CLOSING;
            default: return Category.UNAVAILABLE;
        }
    }

    /** Lang key holding the player-facing phrase for this state. */
    public static String phraseKey(LifecycleState state) {
        String name = state == null ? LifecycleState.RECOVERY_REQUIRED.name() : state.name();
        return "bbe.state." + name.toLowerCase();
    }
}
