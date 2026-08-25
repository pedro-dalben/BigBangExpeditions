package com.bigbangcraft.expeditions.automation;

import java.util.ArrayList;
import java.util.List;

/**
 * Durable automation state (Goal 05 requirement 19/40/42/43).
 *
 * <p>Everything here survives restarts; nothing here can authorize destruction
 * by itself — it only records what the automation layer decided and saw.
 * Corrupt state fails safe: paused=true with reason, pending discarded, shadow
 * log emptied (advisory-grade), hysteresis reset. A fresh streak is always
 * safer than a stale one.
 */
public final class AutomationState {
    public static final int SCHEMA_VERSION = 1;
    public static final int SHADOW_CAP = 200;

    public int schemaVersion = SCHEMA_VERSION;

    public boolean paused;
    public String pauseReason = "";

    /** Hysteresis streak for the CURRENT generation (reset on rollover). */
    public int consecutiveHits;
    public long firstHitAtMs;
    public long lastEvaluatedAtMs;

    /** Clock-guard bookkeeping: last wall-clock ms this process observed sane. */
    public long lastObservedWallClockMs;
    public boolean clockAnomaly;

    public PendingClosure pending;

    public long postponedUntilMs;

    public static final class PendingClosure {
        public int generation;
        public double score;
        public List<String> reasons = new ArrayList<>();
        public long createdAtMs;
        public long expiresAtMs;
        public String policyFingerprint;
        public String trigger; // DEPLETION | MAX_AGE
    }

    public static final class ShadowEntry {
        public long atMs;
        public int generation;
        public double score;
        public String health;
        public boolean wouldRecommend;
        public String blockers;
    }

    public List<ShadowEntry> shadow = new ArrayList<>();

    /** Generation the hysteresis/pending belong to — mismatch forces reset. */
    public int boundGeneration = -1;

    public String lastBroadcastHealth = "";

    public static AutomationState fresh() {
        return new AutomationState();
    }
}
