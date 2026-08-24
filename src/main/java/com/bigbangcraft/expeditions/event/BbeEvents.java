package com.bigbangcraft.expeditions.event;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.Event;

/**
 * Goal 04 internal event seam.
 *
 * NOT a public API yet (explicit non-goal): these exist so later goals
 * (economy/quests/integrations) can hook the expedition lifecycle without
 * coupling to internals. Posted on the main Forge bus at the exact points the
 * gameplay state changes — never speculatively.
 */
public final class BbeEvents {
    private BbeEvents() {}

    /** Fired AFTER a successful teleport into the expedition. */
    public static class PlayerEnteredExpedition extends Event {
        public final ServerPlayer player;
        public final int generation;
        public PlayerEnteredExpedition(ServerPlayer player, int generation) {
            this.player = player;
            this.generation = generation;
        }
    }

    /** Fired after a voluntary `/expedition leave` completes. */
    public static class PlayerLeftExpedition extends Event {
        public final ServerPlayer player;
        public PlayerLeftExpedition(ServerPlayer player) { this.player = player; }
    }

    /** Fired per player removed by closure extraction or join-time recovery. */
    public static class PlayerEvacuated extends Event {
        public final ServerPlayer player; // may be null for offline-marker cases
        public final String playerName;
        public final String mode; // TELEPORT_OUT / EVICT_ON_JOIN / RECOVERED / RESPAWN_REDIRECT
        public PlayerEvacuated(ServerPlayer player, String playerName, String mode) {
            this.player = player;
            this.playerName = playerName;
            this.mode = mode;
        }
    }

    /** Fired when a validated reopen makes the expedition available again. */
    public static class ExpeditionOpened extends Event {
        public final int generation;
        public ExpeditionOpened(int generation) { this.generation = generation; }
    }

    /** Fired once when the timed closing sequence starts. */
    public static class ExpeditionClosingStarted extends Event {
        public final long deadlineEpochMs;
        public final int durationMinutes;
        public ExpeditionClosingStarted(long deadlineEpochMs, int durationMinutes) {
            this.deadlineEpochMs = deadlineEpochMs;
            this.durationMinutes = durationMinutes;
        }
    }
}
