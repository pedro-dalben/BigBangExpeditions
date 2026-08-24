package com.bigbangcraft.expeditions.lifecycle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Persistent dimension-lifecycle record (JSON at
 * {@code <server>/bigbangexpeditions/lifecycle.json}, OUTSIDE the world dir so
 * regeneration cannot destroy the bookkeeping — same decision as SectorRegistry).
 */
public final class LifecycleRecord {
    public LifecycleState status = LifecycleState.OPEN;
    /** Increments exactly when VALIDATING → OPEN completes a reset cycle. */
    public int generation = 0;

    public long createdAtEpochMs;
    public long updatedAtEpochMs;
    public long lastOpenedAtEpochMs;
    public long lastResetAtEpochMs; // set on validated reopen after a reset

    public String activeAuthId = "";      // bound authorization artifact, if any
    public String lastValidationResult = "";
    public String failureReason = "";
    public String lastChangeReason = "";

    /** True from VALIDATING entry until validated reopen — marks a reset cycle in progress. */
    public boolean resetInFlight = false;
    /** Generation snapshot taken when VALIDATING began; -1 when not applicable. */
    public int generationBeforeReset = -1;

    /** Recent transitions for post-crash diagnosis (audit trail is separate). */
    public List<TransitionEvent> recent = new ArrayList<>();

    public static final class TransitionEvent {
        public long atEpochMs;
        public String from;
        public String to;
        public String by;
        public String reason;

        public TransitionEvent() {}

        public TransitionEvent(long atEpochMs, String from, String to, String by, String reason) {
            this.atEpochMs = atEpochMs;
            this.from = from;
            this.to = to;
            this.by = by == null ? "" : by;
            this.reason = reason == null ? "" : reason;
        }
    }

    private static final int RECENT_CAP = 50;

    public void recordTransition(long now, LifecycleState from, LifecycleState to, String by, String reason) {
        recent.add(new TransitionEvent(now, from.name(), to.name(), by, reason));
        while (recent.size() > RECENT_CAP) {
            recent.remove(0);
        }
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new TreeMap<>();
        m.put("status", status.name());
        m.put("generation", generation);
        return m;
    }
}
