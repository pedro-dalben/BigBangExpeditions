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

    /**
     * Fired once when an expedition closes successfully (all players extracted,
     * dimension LOCKED). Posted on the main server thread, server-side only,
     * immediately after the validated LOCKED transition. Never re-fired on
     * restart or recovery.
     *
     * <p>Stable identity: {@code completionId} is unique per open/close cycle
     * within one server install and suitable as an idempotency key.
     * Participants are the players extracted via TELEPORT_OUT this cycle;
     * stale-marker evictions are not included.
     */
    public static class ExpeditionCompleted extends Event {
        public final String completionId;
        public final int generation;
        public final long closedAtEpochMs;
        public final long closingDeadlineEpochMs;
        public final java.util.List<String> participantNames;
        public final java.util.List<java.util.UUID> participantIds;

        public ExpeditionCompleted(String completionId, int generation, long closedAtEpochMs,
                                 long closingDeadlineEpochMs,
                                 java.util.List<String> participantNames,
                                 java.util.List<java.util.UUID> participantIds) {
            this.completionId = completionId;
            this.generation = generation;
            this.closedAtEpochMs = closedAtEpochMs;
            this.closingDeadlineEpochMs = closingDeadlineEpochMs;
            this.participantNames = java.util.Collections.unmodifiableList(
                    new java.util.ArrayList<>(participantNames));
            this.participantIds = java.util.Collections.unmodifiableList(
                    new java.util.ArrayList<>(participantIds));
        }
    }

    public static String completionId(int generation, long closingDeadlineEpochMs, long closedAtEpochMs) {
        if (closingDeadlineEpochMs > 0) {
            return "g" + generation + "-" + closingDeadlineEpochMs;
        }
        return "g" + generation + "-immediate-" + closedAtEpochMs;
    }

    // ------------------------------------------------------ Goal 05 events

    /** Health verdict changed for the current generation (advisory signal). */
    public static class ExpeditionHealthChanged extends Event {
        public final int generation;
        public final String health; // HEALTHY / ACTIVE / DECLINING / DEPLETED / UNKNOWN
        public final double score;
        public ExpeditionHealthChanged(int generation, String health, double score) {
            this.generation = generation;
            this.health = health;
            this.score = score;
        }
    }

    /** Matured renewal recommendation — fired once per maturation, never repeatedly. */
    public static class ExpeditionRenewalRecommended extends Event {
        public final int generation;
        public final double score;
        public final java.util.List<String> reasons;
        public final String trigger; // DEPLETION | MAX_AGE
        public ExpeditionRenewalRecommended(int generation, double score,
                                            java.util.List<String> reasons, String trigger) {
            this.generation = generation;
            this.score = score;
            this.reasons = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(reasons));
            this.trigger = trigger;
        }
    }

    /** Automation paused itself or by operator — integrations must not expect decisions. */
    public static class ExpeditionAutomationPaused extends Event {
        public final String reason;
        public ExpeditionAutomationPaused(String reason) { this.reason = reason; }
    }
}
