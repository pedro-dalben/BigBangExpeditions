package com.bigbangcraft.expeditions.lifecycle;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Dimension-level expedition lifecycle (Goal 03).
 *
 * Conceptual flow:
 * <pre>
 * OPEN → CLOSING → EVACUATING → LOCKED → PREFLIGHT → BACKUP → RESET_READY
 *      → (offline) RESETTING → (boot) BOOTING → VALIDATING → OPEN
 * </pre>
 *
 * Failure semantics:
 * - Any phase may be forced into {@link #FAILED} by its owning step.
 * - {@link #RECOVERY_REQUIRED} is the automatic fail-closed sink for interrupted
 *   operations detected at startup; it is reachable from ANY state and may only
 *   be left via explicit operator recovery ({@code -> LOCKED}) after inspection.
 * - Unknown transitions are rejected (fail-closed), same discipline as SectorState.
 */
public enum LifecycleState {
    OPEN,
    CLOSING,
    EVACUATING,
    LOCKED,
    PREFLIGHT,
    BACKUP,
    RESET_READY,
    /** Destructive filesystem work claimed by the OFFLINE executor. */
    RESETTING,
    /** Server restarted after a completed destructive phase. */
    BOOTING,
    VALIDATING,
    FAILED,
    RECOVERY_REQUIRED;

    private static final Map<LifecycleState, Set<LifecycleState>> ALLOWED = Map.ofEntries(
            Map.entry(OPEN, Set.of(CLOSING)),
            Map.entry(CLOSING, Set.of(EVACUATING, OPEN)),
            Map.entry(EVACUATING, Set.of(LOCKED, OPEN)),
            Map.entry(LOCKED, Set.of(PREFLIGHT, OPEN)),
            Map.entry(PREFLIGHT, Set.of(BACKUP, RESET_READY, LOCKED)),
            Map.entry(BACKUP, Set.of(RESET_READY, FAILED)),
            Map.entry(RESET_READY, Set.of(RESETTING, LOCKED)),
            Map.entry(RESETTING, Set.of(BOOTING)),
            Map.entry(BOOTING, Set.of(VALIDATING)),
            Map.entry(VALIDATING, Set.of(OPEN, FAILED)),
            Map.entry(FAILED, Set.of(LOCKED)),
            Map.entry(RECOVERY_REQUIRED, Set.of(LOCKED)));

    /** Error message when illegal; empty when allowed. */
    public static Optional<String> rejectTransition(LifecycleState from, LifecycleState to) {
        if (from == null || to == null) return Optional.of("null state");
        // fail-closed sink: entering RECOVERY_REQUIRED is always legal (audited)
        if (to == RECOVERY_REQUIRED) return Optional.empty();
        if (from == to) return Optional.empty(); // idempotent no-op
        Set<LifecycleState> targets = ALLOWED.getOrDefault(from, Set.of());
        if (!targets.contains(to)) {
            return Optional.of("illegal transition " + from + " -> " + to
                    + " (allowed: " + targets + ")");
        }
        return Optional.empty();
    }

    public boolean playersMayEnter() {
        return this == OPEN;
    }

    /** States owned by the offline executor window (destructive work possible). */
    public boolean destructiveWindow() {
        return this == RESET_READY || this == RESETTING || this == BOOTING;
    }
}
