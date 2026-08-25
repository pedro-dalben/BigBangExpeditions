package com.bigbangcraft.expeditions.automation;

import com.bigbangcraft.expeditions.audit.AuditEvent;
import com.bigbangcraft.expeditions.core.BbeLayout;
import com.bigbangcraft.expeditions.core.RuntimeServices;
import com.bigbangcraft.expeditions.depletion.Contribution;
import com.bigbangcraft.expeditions.depletion.DepletionEngine;
import com.bigbangcraft.expeditions.depletion.DepletionInput;
import com.bigbangcraft.expeditions.depletion.DepletionPolicy;
import com.bigbangcraft.expeditions.depletion.DepletionResult;
import com.bigbangcraft.expeditions.depletion.HysteresisTracker;
import com.bigbangcraft.expeditions.event.BbeEvents;
import com.bigbangcraft.expeditions.gameplay.ClosureService;
import com.bigbangcraft.expeditions.integration.lostcities.LostCitiesAdapter;
import com.bigbangcraft.expeditions.lifecycle.LifecycleRecord;
import com.bigbangcraft.expeditions.lifecycle.LifecycleState;
import com.bigbangcraft.expeditions.sector.SectorRegistry;
import com.bigbangcraft.expeditions.telemetry.TelemetryService;
import com.bigbangcraft.expeditions.telemetry.TelemetrySnapshot;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Expedition automation orchestrator (Goal 05 WS5/WS6).
 *
 * Authority ladder (requirement 15) — the mode lives ONLY in
 * automation.properties and is applied through an explicit, audited reload:
 * <pre>
 * MANUAL                  no scheduled activity; on-demand evaluation only
 * ADVISORY                scheduled evaluation + recommendations, never acts
 * SCHEDULED_WITH_APPROVAL + pending closure proposal requiring operator approve
 * AUTOMATIC_CLOSURE       + executes approved-equivalent decisions itself,
 *                           inside maintenance windows, via the Goal 04
 *                           timed-closing pipeline (never a parallel path)
 * </pre>
 *
 * Hard boundaries preserved:
 * - automation NEVER destroys anything and never touches authorization
 *   artifacts; destructive execution stays offline-by-design (Goal 03);
 * - operator pause overrides everything (invariant 12);
 * - clock anomalies suspend automatic closure (advisory continues);
 * - policy fingerprint binds pending decisions: a config edit cannot turn a
 *   stale recommendation into a destructive action silently (requirement 63).
 */
public final class AutomationService {
    private static final Logger LOG = LogManager.getLogger("BigBangExpeditions/Automation");
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    private static volatile AutomationService instance;
    private static volatile AutomationConfig config;
    /** armed only when scheduled evaluation should run (mode > MANUAL, not paused). */
    private static volatile boolean scheduledActive;

    private final MinecraftServer server;
    private final AutomationStateStore store;
    private AutomationState state;

    private final ZoneId zone;
    private DepletionPolicy policy;
    private String policyFingerprint;
    private int consecutiveFailures;
    private String lastRecommendationKey = "";

    private AutomationService(MinecraftServer server) {
        this.server = server;
        this.store = new AutomationStateStore(BbeLayout.automationStateFile(server));
        this.zone = ZoneId.systemDefault();
    }

    // ------------------------------------------------------------- lifecycle

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent e) {
        try {
            config = AutomationConfig.load(BbeLayout.configDir(e.getServer())
                    .resolve("automation.properties"));
            instance = new AutomationService(e.getServer());
            instance.boot();
        } catch (Exception ex) {
            LOG.error("automation boot failed — MANUAL behavior this session: {}", ex.toString());
            instance = null;
            scheduledActive = false;
        }
        recomputeArmed();
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent e) {
        AutomationService s = instance;
        if (s != null) {
            s.persistQuietly();
            instance = null;
        }
    }

    private void boot() {
        var loaded = store.load();
        if (loaded.ok()) {
            state = loaded.state();
        } else {
            state = AutomationState.fresh();
            state.paused = true;
            state.pauseReason = "automation state unreadable (" + loaded.detail() + ") — fail-safe pause";
            audit("AUTOMATION_STATE_FAILSAFE", loaded.detail());
        }
        applyConfig(config);
        clockGuard(System.currentTimeMillis());
        expireStalePending(System.currentTimeMillis());
        persistQuietly();
        audit("AUTOMATION_BOOT", "mode=" + config.automationMode()
                + " paused=" + state.paused + " fp=" + policyFingerprint);
    }

    private void applyConfig(AutomationConfig c) {
        List<String> notices = new ArrayList<>();
        this.policy = PolicySupport.fromConfig(c, notices);
        this.policyFingerprint = PolicySupport.fingerprint(this.policy);
        for (String n : notices) LOG.info("policy: {}", n);
    }

    private static void recomputeArmed() {
        AutomationConfig c = config;
        AutomationService s = instance;
        scheduledActive = s != null && c != null
                && !"MANUAL".equals(c.automationMode())
                && !s.state.paused;
    }

    // ------------------------------------------------------------- tick loop

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        if (!scheduledActive) return;
        if ((e.getServer().getTickCount() % 20) != 0) return; // 1 Hz fast-path gate
        AutomationService s = instance;
        if (s == null) return;
        try {
            s.tick();
        } catch (Exception ex) {
            LOG.error("automation tick failed: {}", ex.toString());
        }
    }

    private synchronized void tick() {
        long now = System.currentTimeMillis();
        clockGuard(now);
        expireStalePending(now);

        // AUTOMATIC_CLOSURE executor: a matured decision fires at its window.
        if ("AUTOMATIC_CLOSURE".equals(mode()) && state.pending != null && canAct(now)) {
            executePending("automation:AUTOMATIC_CLOSURE");
            return;
        }

        if (!SchedulerMath.dueForEvaluation(state.lastEvaluatedAtMs,
                config.evaluateMinutes(), now)) return;
        evaluateNow("scheduler", now);
        persistQuietly();
    }

    // ------------------------------------------------------------- evaluation

    /**
     * Full deterministic evaluation pass. Also the entry point for manual
     * dry-run/inspection commands. Returns the result for display.
     */
    public synchronized EvaluationOutcome evaluateNow(String trigger, long nowMs) {
        try {
            LifecycleRecord lc = RuntimeServices.get(server).lifecycle().current();
            int gen = lc.generation;
            bindGeneration(gen);

            TelemetrySnapshot snap = TelemetryService.snapshotCurrentOr(gen);
            long totalChunks = resolveTotalChunks();
            long census = config.censusTotalStructurePlacements();
            int playersInside = countPlayersInside();

            DepletionInput input = new DepletionInput(snap, totalChunks, census, nowMs, playersInside);
            HysteresisTracker tracker = trackerFromState();
            DepletionResult result = DepletionEngine.evaluate(input, policy, tracker);
            trackerToState(tracker);
            state.lastEvaluatedAtMs = nowMs;
            consecutiveFailures = 0;

            recordShadow(result, nowMs);
            broadcastHealthChange(result);

            handleRecommendation(result, nowMs, playersInside);
            return new EvaluationOutcome(result, mode(), state.pending != null,
                    state.paused, state.clockAnomaly, state.postponedUntilMs);
        } catch (Exception ex) {
            consecutiveFailures++;
            LOG.error("evaluation failed ({}/{}): {}", consecutiveFailures, MAX_CONSECUTIVE_FAILURES, ex.toString());
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                pause("repeated evaluation failures: " + ex.getMessage());
            }
            return null;
        }
    }

    public record EvaluationOutcome(DepletionResult result, String mode, boolean hasPending,
                                    boolean paused, boolean clockAnomaly, long postponedUntilMs) {}

    private void bindGeneration(int gen) {
        if (state.boundGeneration == gen) return;
        // rollover isolation: streaks/pending from previous generation are void
        state.boundGeneration = gen;
        state.consecutiveHits = 0;
        state.firstHitAtMs = 0;
        if (state.pending != null && state.pending.generation != gen) {
            audit("AUTOMATION_PENDING_VOIDED", "generation rollover " + state.pending.generation + "->" + gen);
            state.pending = null;
        }
        lastRecommendationKey = "";
        persistQuietly();
    }

    private HysteresisTracker trackerFromState() {
        HysteresisTracker t = new HysteresisTracker(policy.minSustainedSpanMs);
        t.consecutiveHits = state.consecutiveHits;
        t.firstHitAtMs = state.firstHitAtMs;
        t.lastEvaluatedAtMs = state.lastEvaluatedAtMs;
        return t;
    }

    private void trackerToState(HysteresisTracker t) {
        state.consecutiveHits = t.consecutiveHits;
        state.firstHitAtMs = t.firstHitAtMs;
        state.lastEvaluatedAtMs = t.lastEvaluatedAtMs;
    }

    private void recordShadow(DepletionResult r, long nowMs) {
        AutomationState.ShadowEntry e = new AutomationState.ShadowEntry();
        e.atMs = nowMs;
        e.generation = r.generation;
        e.score = r.score;
        e.health = r.health.name();
        e.wouldRecommend = r.recommendClosure;
        e.blockers = String.join("; ", r.blockers);
        state.shadow.add(e);
        SchedulerMath.cap(state.shadow, AutomationState.SHADOW_CAP);
    }

    private void broadcastHealthChange(DepletionResult r) {
        String h = r.health.name();
        if (!h.equals(state.lastBroadcastHealth)) {
            state.lastBroadcastHealth = h;
            post(new BbeEvents.ExpeditionHealthChanged(r.generation, h, r.score));
            audit("AUTOMATION_HEALTH_CHANGED", "gen=" + r.generation + " health=" + h
                    + " score=" + String.format("%.1f", r.score));
        }
    }

    private void handleRecommendation(DepletionResult r, long nowMs, int playersInside) {
        if (!r.recommendClosure) {
            if (!r.sustainedSummary.isEmpty() && state.pending == null) {
                lastRecommendationKey = ""; // allow re-recommend after cancel/postpone expiry
            }
            return;
        }
        String trigger = r.blockers.stream().anyMatch(b -> b.contains("max-age ceiling"))
                ? "MAX_AGE" : "DEPLETION";
        String key = r.generation + ":" + trigger + ":" + Math.round(r.score);
        switch (mode()) {
            case "MANUAL" -> { /* shadow only */ }
            case "ADVISORY" -> fireRecommendation(r, trigger, key);
            case "SCHEDULED_WITH_APPROVAL" -> {
                fireRecommendation(r, trigger, key);
                if (state.pending == null) {
                    AutomationState.PendingClosure p = new AutomationState.PendingClosure();
                    p.generation = r.generation;
                    p.score = r.score;
                    p.reasons = new ArrayList<>(r.blockers.isEmpty()
                            ? List.of(String.format("depletion score %.1f >= threshold", r.score))
                            : r.blockers.stream().filter(b -> !b.startsWith("NOTE:")).toList());
                    if (p.reasons.isEmpty()) p.reasons.add("depletion sustained");
                    p.createdAtMs = nowMs;
                    p.expiresAtMs = nowMs + config.approvalTtlHours() * 3600_000L;
                    p.policyFingerprint = policyFingerprint;
                    p.trigger = trigger;
                    state.pending = p;
                    audit("AUTOMATION_PENDING_CREATED", "gen=" + r.generation + " trigger=" + trigger
                            + " ttlHours=" + config.approvalTtlHours());
                    persistQuietly();
                }
            }
            case "AUTOMATIC_CLOSURE" -> {
                fireRecommendation(r, trigger, key);
                if (state.pending == null) {
                    AutomationState.PendingClosure p = new AutomationState.PendingClosure();
                    p.generation = r.generation;
                    p.score = r.score;
                    p.reasons = new ArrayList<>(List.of(
                            String.format("%s matured (score %.1f)", trigger, r.score)));
                    p.createdAtMs = nowMs;
                    p.expiresAtMs = nowMs + config.approvalTtlHours() * 3600_000L;
                    p.policyFingerprint = policyFingerprint;
                    p.trigger = trigger;
                    state.pending = p;
                    long windowStart = SchedulerMath.nextWindowStart(nowMs,
                            SchedulerMath.parseHHMM(config.windowStart()),
                            SchedulerMath.parseHHMM(config.windowEnd()), zone);
                    audit("AUTOMATION_CLOSURE_ARMED", "gen=" + r.generation
                            + " executes within window (next open "
                            + java.time.Instant.ofEpochMilli(windowStart) + ")");
                    persistQuietly();
                }
            }
            default -> { /* unknown mode treated as MANUAL by config validation */ }
        }
    }

    private void fireRecommendation(DepletionResult r, String trigger, String key) {
        if (key.equals(lastRecommendationKey)) return;
        lastRecommendationKey = key;
        post(new BbeEvents.ExpeditionRenewalRecommended(r.generation, r.score,
                r.blockers.isEmpty() ? List.of("sustained depletion evidence") : r.blockers, trigger));
        audit("AUTOMATION_RENEWAL_RECOMMENDED", "gen=" + r.generation
                + " score=" + String.format("%.1f", r.score) + " trigger=" + trigger);
    }

    // ------------------------------------------------------------- execution

    /** Guards that must hold before ANY automated lifecycle action (req 16-22). */
    private boolean canAct(long now) {
        if (state.paused || state.clockAnomaly) return false;
        if (state.pending == null) return false;
        if (state.postponedUntilMs > now) return false;
        if (state.boundGeneration != state.pending.generation) return false;
        if (!state.pending.policyFingerprint.equals(policyFingerprint)) {
            audit("AUTOMATION_EXECUTION_REFUSED", "policy drift since pending creation");
            state.pending = null;
            persistQuietly();
            return false;
        }
        int start = SchedulerMath.parseHHMM(config.windowStart());
        int end = SchedulerMath.parseHHMM(config.windowEnd());
        return SchedulerMath.inWindow(now, start, end, zone);
    }

    /**
     * The ONLY automated lifecycle action: begin the Goal 04 timed closing via
     * ClosureService. Warnings, player-aware notice period, extraction and all
     * downstream safety belong to that proven pipeline.
     */
    private void executePending(String actor) {
        AutomationState.PendingClosure p = state.pending;
        if (p == null) return;
        LifecycleRecord lc;
        try {
            lc = RuntimeServices.get(server).lifecycle().current();
        } catch (Exception e) {
            auditRefused("AUTOMATION_CLOSE", "lifecycle unreadable");
            return;
        }
        if (lc.status != LifecycleState.OPEN) {
            auditRefused("AUTOMATION_CLOSE", "lifecycle not OPEN (" + lc.status + ")");
            state.pending = null;
            persistQuietly();
            return;
        }
        String err = ClosureService.beginTimedClosing(server, RuntimeServices.get(server), actor, 0);
        if (err != null) {
            auditRefused("AUTOMATION_CLOSE", err);
            return;
        }
        audit("AUTOMATION_CLOSED_STARTED", "gen=" + p.generation + " trigger=" + p.trigger
                + " score=" + String.format("%.1f", p.score));
        state.pending = null;
        state.consecutiveHits = 0;
        state.firstHitAtMs = 0;
        persistQuietly();
    }

    // ------------------------------------------------------------- overrides

    /**
     * Staging-only synthetic activity seeding for live automation campaigns.
     * FAIL-CLOSED: refuses unless environment.properties explicitly declares
     * environment=staging (missing file / production / dry-run all refuse).
     */
    public synchronized String seedSyntheticActivity(int chunks, int opens, int deaths, int structures) {
        String envErr = requireStaging();
        if (envErr != null) return envErr;
        if (chunks < 0 || opens < 0 || deaths < 0 || structures < 0
                || chunks > 200_000 || opens > 200_000 || deaths > 100_000 || structures > 50_000) {
            return "seed magnitudes out of range";
        }
        return com.bigbangcraft.expeditions.telemetry.TelemetryService.stagingInjectStatic(
                chunks, opens, deaths, structures);
    }

    private String requireStaging() {
        try {
            java.nio.file.Path envFile = BbeLayout.configDir(server).resolve("environment.properties");
            if (!java.nio.file.Files.isRegularFile(envFile)) {
                return "refused: no environment.properties (not an explicit staging install)";
            }
            for (String line : java.nio.file.Files.readAllLines(envFile)) {
                String t = line.trim();
                if (t.startsWith("#") || !t.contains("=")) continue;
                String[] kv = t.split("=", 2);
                if (kv[0].trim().equals("environment")) {
                    if (kv[1].trim().equalsIgnoreCase("staging")) return null;
                    return "refused: environment=" + kv[1].trim() + " (staging only)";
                }
            }
            return "refused: environment key absent";
        } catch (IOException e) {
            return "refused: environment unreadable";
        }
    }

    public synchronized String approve(String actor) {
        if (state.pending == null) return "no pending automation decision";
        if (!state.pending.policyFingerprint.equals(policyFingerprint)) {
            state.pending = null;
            persistQuietly();
            return "pending decision stale under current policy — dropped; wait for re-maturation";
        }
        executePending("operator:" + actor);
        return null;
    }

    public synchronized String postpone(int days, String actor) {
        if (days < 1 || days > 365) return "postpone days out of range 1..365";
        state.postponedUntilMs = System.currentTimeMillis() + days * 86_400_000L;
        audit("AUTOMATION_POSTPONED", "days=" + days + " until="
                + java.time.Instant.ofEpochMilli(state.postponedUntilMs));
        persistQuietly();
        return null;
    }

    public synchronized String cancelPending(String actor) {
        if (state.pending == null && state.consecutiveHits == 0) return "nothing to cancel";
        state.pending = null;
        state.consecutiveHits = 0;
        state.firstHitAtMs = 0;
        lastRecommendationKey = "";
        audit("AUTOMATION_CANCELLED", "by=" + actor);
        persistQuietly();
        return null;
    }

    public synchronized String pause(String reason) {
        if (state.paused) return "already paused";
        state.paused = true;
        state.pauseReason = reason == null ? "" : reason;
        recomputeArmedStatic();
        post(new BbeEvents.ExpeditionAutomationPaused(state.pauseReason));
        audit("AUTOMATION_PAUSED", state.pauseReason);
        persistQuietly();
        return null;
    }

    public synchronized String resume(String actor) {
        if (!state.paused) return "not paused";
        state.paused = false;
        state.pauseReason = "";
        recomputeArmedStatic();
        audit("AUTOMATION_RESUMED", "by=" + actor);
        persistQuietly();
        return null;
    }

    /** Operator clears a clock anomaly after verifying system time (audited). */
    public synchronized String clearClockAnomaly(String actor) {
        if (!state.clockAnomaly) return "no clock anomaly recorded";
        state.clockAnomaly = false;
        state.lastObservedWallClockMs = System.currentTimeMillis();
        audit("AUTOMATION_CLOCK_ANOMALY_CLEARED", "by=" + actor);
        persistQuietly();
        return null;
    }

    /**
     * Reload configuration without restart (requirement 61). Validates fully
     * BEFORE applying; failure keeps the previous valid policy. Pending
     * decisions bound to a different policy fingerprint are invalidated.
     */
    public synchronized String reload(String actor) {
        AutomationConfig freshCfg = AutomationConfig.load(BbeLayout.configDir(server)
                .resolve("automation.properties"));
        List<String> notices = new ArrayList<>();
        DepletionPolicy freshPolicy = PolicySupport.fromConfig(freshCfg, notices);
        String freshFp = PolicySupport.fingerprint(freshPolicy);
        String oldFp = policyFingerprint;
        this.config = freshCfg;
        this.policy = freshPolicy;
        this.policyFingerprint = freshFp;
        if (!freshFp.equals(oldFp) && state.pending != null
                && !freshFp.equals(state.pending.policyFingerprint)) {
            audit("AUTOMATION_PENDING_INVALIDATED", "policy fingerprint changed " + oldFp + "->" + freshFp);
            state.pending = null;
        }
        recomputeArmedStatic();
        audit("AUTOMATION_RELOADED", "fp=" + freshFp + " mode=" + freshCfg.automationMode());
        persistQuietly();
        StringBuilder sb = new StringBuilder("reloaded: mode=").append(freshCfg.automationMode())
                .append(" fingerprint=").append(freshFp);
        for (String n : freshCfg.notices()) sb.append("\nnotice: ").append(n);
        return sb.toString();
    }

    private void recomputeArmedStatic() {
        scheduledActive = instance != null && config != null
                && !"MANUAL".equals(config.automationMode())
                && !instance.state.paused;
    }

    // ------------------------------------------------------------- helpers

    private void clockGuard(long now) {
        if (!state.clockAnomaly
                && ClockGuard.isAnomalous(state.lastObservedWallClockMs, now)) {
            state.clockAnomaly = true;
            audit("AUTOMATION_CLOCK_ANOMALY", "observed jump; automatic actions suspended"
                    + " (last=" + state.lastObservedWallClockMs + " now=" + now + ")");
            post(new BbeEvents.ExpeditionAutomationPaused("clock anomaly"));
        }
        if (!state.clockAnomaly) state.lastObservedWallClockMs = now;
    }

    private void expireStalePending(long now) {
        AutomationState.PendingClosure p = state.pending;
        if (p != null && p.expiresAtMs > 0 && now > p.expiresAtMs) {
            audit("AUTOMATION_PENDING_EXPIRED", "gen=" + p.generation);
            state.pending = null;
            state.consecutiveHits = 0;
            persistQuietly();
        }
    }

    private long resolveTotalChunks() {
        long pinned = config.censusTotalChunks();
        if (pinned > 0) return pinned;
        try {
            SectorRegistry reg = new SectorRegistry(BbeLayout.sectorsFile(server));
            reg.load();
            long total = 0;
            String dim = LostCitiesAdapter.expeditionDimensionId().toString();
            boolean any = false;
            for (var r : reg.list()) {
                if (dim.equals(r.dimension)) {
                    total += (long) (r.maxChunkX - r.minChunkX + 1) * (r.maxChunkZ - r.minChunkZ + 1);
                    any = true;
                }
            }
            return any ? total : -1;
        } catch (Exception e) {
            return -1; // unknown stays unknown
        }
    }

    private int countPlayersInside() {
        try {
            ServerLevel level = server.getLevel(LostCitiesAdapter.expeditionDimensionKey());
            return level == null ? 0
                    : com.bigbangcraft.expeditions.lifecycle.EvacuationService.playersInside(level).size();
        } catch (Exception e) {
            return 0;
        }
    }

    private String mode() {
        AutomationConfig c = config;
        return c == null ? "MANUAL" : c.automationMode();
    }

    private void persistQuietly() {
        try {
            store.save(state);
        } catch (IOException io) {
            LOG.warn("automation state persist failed: {}", io.toString());
        }
    }

    private void post(net.minecraftforge.eventbus.api.Event e) {
        try {
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(e);
        } catch (Exception ignored) {
        }
    }

    private void audit(String event, String detail) {
        try {
            RuntimeServices.get(server).audit().record(
                    AuditEvent.of(event, "automation").outcome("OK").detail("detail", detail));
        } catch (Exception ignored) {
        }
    }

    private void auditRefused(String event, String reason) {
        try {
            RuntimeServices.get(server).auditRefusal(event, "automation", reason);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------- reads

    public static AutomationService get() {
        return instance;
    }

    /**
     * Player-facing expedition phase (requirement 33): derived ONLY from real
     * observable automation state; null when nothing observable exists
     * (MANUAL mode with no evaluations, or automation unavailable).
     */
    public static String playerPhase() {
        AutomationService s = instance;
        if (s == null) return null;
        return switch (s.state.lastBroadcastHealth) {
            case "HEALTHY" -> "FRESH";
            case "ACTIVE" -> "ACTIVE";
            case "DECLINING" -> "DECLINING";
            case "DEPLETED" -> "FINAL_DAYS";
            default -> null;
        };
    }

    public static AutomationConfig config() {
        return config;
    }

    public synchronized AutomationState stateView() {
        return state;
    }

    public synchronized String policyFingerprint() {
        return policyFingerprint;
    }

    public synchronized DepletionPolicy policyView() {
        return policy;
    }

    /** Test hook. */
    public static void resetForTests() {
        instance = null;
        config = null;
        scheduledActive = false;
    }
}
