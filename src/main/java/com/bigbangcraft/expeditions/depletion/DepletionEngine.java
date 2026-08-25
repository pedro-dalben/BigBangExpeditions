package com.bigbangcraft.expeditions.depletion;

import com.bigbangcraft.expeditions.telemetry.TelemetrySnapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic depletion evaluator (Goal 05 requirements 10-13).
 *
 * <p>Pure: no IO, no clock reads ({@code nowEpochMs} arrives in the input),
 * no Minecraft types. Same inputs always produce the same result — property
 * tests rely on this.
 *
 * <p>Unknown-data contract (requirement 13): an unknown component NEVER
 * contributes points. Spatial evidence (coverage/structures) is load-bearing:
 * under BLOCK policy, both-spatial-unknown blocks any recommendation; under
 * FALLBACK the score renormalizes across KNOWN components but must still clear
 * {@code minKnownWeightFraction}. Loot/activity unknowns always degrade to
 * renormalized absence with a note — they can lower confidence, never
 * manufacture it.
 */
public final class DepletionEngine {
    private DepletionEngine() {}

    public static final String FLAG_STRUCTURE_SIGNAL_ABSENT = "STRUCTURE_SIGNAL_ABSENT";

    /**
     * @param hysteresis mutable streak state; updated by this call (persisted by caller)
     */
    public static DepletionResult evaluate(DepletionInput in, DepletionPolicy policy,
                                           HysteresisTracker hysteresis) {
        int gen = in.snapshot.generation;
        List<String> blockers = new ArrayList<>();
        List<Contribution> parts = new ArrayList<>();

        if (in.snapshot.availability != TelemetrySnapshot.Availability.AVAILABLE) {
            return new DepletionResult(gen, 0, DepletionResult.Health.UNKNOWN, false,
                    List.of("telemetry unavailable: " + in.snapshot.availability),
                    List.of(), 0, 0, "");
        }

        long openedAt = in.snapshot.openedAtEpochMs;
        double ageDays = openedAt > 0 ? (in.nowEpochMs - openedAt) / 86_400_000.0 : -1;
        long ageDaysX10 = ageDays < 0 ? -1 : Math.round(ageDays * 10);
        long daysQuiet = lastActivityAgeDays(in.snapshot.lastActivityEpochMs, in.nowEpochMs);
        boolean backstop =
                policy.maxAgeDays > 0 && ageDays >= policy.maxAgeDays
                        && (daysQuiet < 0 || daysQuiet >= policy.inactivityAbandonDays);

        if (openedAt <= 0 || ageDays < 0) {
            blockers.add("expedition open time unknown");
        } else if (policy.minAgeDays > 0 && ageDays < policy.minAgeDays) {
            blockers.add(String.format("minimum lifetime not reached (%.1f < %d d)",
                    ageDays, policy.minAgeDays));
        }
        boolean minAgeSatisfied = blockers.stream().noneMatch(b -> b.startsWith("minimum lifetime"))
                && blockers.stream().noneMatch(b -> b.contains("open time"));

        // ---- coverage -----------------------------------------------------
        Contribution coverage;
        boolean coverageKnown;
        if (in.totalExpeditionChunks <= 0) {
            coverage = Contribution.unknown("coverage", policy.coverageWeight,
                    "area census unavailable", "spatial signal missing");
            coverageKnown = false;
        } else {
            double pct = Math.min(100.0,
                    100.0 * in.snapshot.distinctChunks / in.totalExpeditionChunks);
            coverage = new Contribution("coverage", Contribution.Status.KNOWN,
                    String.format("%d/%d chunks (%.1f%%)", in.snapshot.distinctChunks,
                            in.totalExpeditionChunks, pct),
                    String.format(">=%.0f%%", policy.coverageClosePercent),
                    pct, policy.coverageWeight, pct / 100.0 * policy.coverageWeight, "");
            coverageKnown = true;
        }
        parts.add(coverage);

        // ---- structures ---------------------------------------------------
        Contribution structures;
        boolean structuresKnown;
        boolean structureSignalAbsent = in.snapshot.qualityFlags.contains(FLAG_STRUCTURE_SIGNAL_ABSENT);
        if (structureSignalAbsent && in.totalStructureCensus <= 0) {
            structures = Contribution.disabled("structures",
                    "no vanilla structure references observed; component retired this generation");
            structuresKnown = false;
        } else if (in.totalStructureCensus <= 0) {
            structures = Contribution.unknown("structures", policy.structureWeight,
                    "census not pinned", "operator may pin via automation census");
            structuresKnown = false;
        } else {
            double pct = Math.min(100.0,
                    100.0 * in.snapshot.structurePlacements / in.totalStructureCensus);
            structures = new Contribution("structures", Contribution.Status.KNOWN,
                    String.format("%d/%d placements (%.1f%%)", in.snapshot.structurePlacements,
                            in.totalStructureCensus, pct),
                    "contributes weight only; visitation != exhaustion",
                    pct, policy.structureWeight, pct / 100.0 * policy.structureWeight, "");
            structuresKnown = true;
        }
        parts.add(structures);

        // ---- loot ---------------------------------------------------------
        Contribution loot;
        boolean lootKnown = in.snapshot.containerOpensTotal >= policy.lootMinAbsoluteOpens;
        if (!lootKnown) {
            loot = Contribution.unknown("loot", policy.lootWeight,
                    in.snapshot.containerOpensTotal + " opens (<" + policy.lootMinAbsoluteOpens + ")",
                    "insufficient interaction volume");
        } else {
            double ratio = decayRatio(in, policy, v -> v.containerOpens);
            double raw = clamp01(1.0 - ratio) * 100.0;
            loot = new Contribution("loot", Contribution.Status.KNOWN,
                    String.format("decay %.2f over %dd windows", ratio, decayWindow(policy)),
                    "declining consumption raises score",
                    raw, policy.lootWeight, raw / 100.0 * policy.lootWeight, "");
        }
        parts.add(loot);

        // ---- activity -----------------------------------------------------
        Contribution activity;
        if (daysQuiet < 0) {
            activity = Contribution.unknown("activity", policy.activityWeight,
                    "no activity ever recorded", "empty telemetry");
        } else {
            double raw = clamp01((double) daysQuiet / policy.inactivityAbandonDays) * 100.0;
            String obs = daysQuiet == 0 ? "active today"
                    : daysQuiet + "d since last recorded activity";
            activity = new Contribution("activity", Contribution.Status.KNOWN, obs,
                    "quiet period approaches abandonment at " + policy.inactivityAbandonDays + "d",
                    raw, policy.activityWeight, raw / 100.0 * policy.activityWeight, "");
        }
        parts.add(activity);

        // ---- age ----------------------------------------------------------
        Contribution age;
        boolean maxAgeEnabled = policy.maxAgeDays > 0;
        boolean ageCeilingBreached = backstop;
        if (!maxAgeEnabled) {
            age = Contribution.disabled("age", "max-age backstop disabled by policy");
        } else {
            double raw = Math.min(100.0, ageDays < 0 ? 0 : 100.0 * ageDays / policy.maxAgeDays);
            ageCeilingBreached = ageDays >= policy.maxAgeDays;
            age = new Contribution("age", Contribution.Status.KNOWN,
                    String.format("%.1f/%d d", Math.max(ageDays, 0), policy.maxAgeDays),
                    "ceiling " + policy.maxAgeDays + "d",
                    raw, policy.ageWeight, raw / 100.0 * policy.ageWeight, "");
        }
        parts.add(age);

        // ---- aggregation ----------------------------------------------------
        boolean spatialBlocked = !coverageKnown && !structuresKnown;
        if (spatialBlocked) {
            if (policy.unknownSpatialBlocks() && !backstop) {
                blockers.add("spatial evidence unavailable (coverage+structures UNKNOWN)");
            } else if (backstop) {
                blockers.add(0, "NOTE: recommending on max-age ceiling alone — spatial evidence unavailable");
            }
        }

        double knownWeight = 0;
        double earned = 0;
        for (Contribution c : parts) {
            if (c.status != Contribution.Status.KNOWN) continue;
            knownWeight += c.weight;
            earned += c.points;
        }
        boolean anyKnown = knownWeight > 0;
        double score = anyKnown ? Math.min(100.0, earned / knownWeight * 100.0) : 0;
        double totalPossibleWeight = 0;
        for (Contribution c : parts) if (c.status != Contribution.Status.DISABLED) totalPossibleWeight += c.weight;
        double knownFraction = totalPossibleWeight <= 0 ? 0 : knownWeight / totalPossibleWeight;

        if (anyKnown && knownFraction < policy.minKnownWeightFraction && !backstop) {
            blockers.add(String.format("known evidence too thin (%.0f%% of weight)",
                    knownFraction * 100));
        }

        // ---- health + sustained recommendation -------------------------------
        DepletionResult.Health health;
        if (!anyKnown) {
            health = DepletionResult.Health.UNKNOWN;
        } else if (score >= policy.closeScoreThreshold) {
            health = DepletionResult.Health.DEPLETED;
        } else if (score >= policy.closeScoreThreshold - 2 * policy.recoveryBand) {
            health = DepletionResult.Health.DECLINING;
        } else if (score >= 40) {
            health = DepletionResult.Health.ACTIVE;
        } else {
            health = DepletionResult.Health.HEALTHY;
        }

        boolean candidate = health == DepletionResult.Health.DEPLETED && blockers.isEmpty();
        boolean deadZone = !candidate
                && score >= policy.closeScoreThreshold - policy.recoveryBand
                && score < policy.closeScoreThreshold
                && blockers.stream().allMatch(b -> b.startsWith("NOTE:"));
        boolean matured = hysteresis.record(candidate, deadZone,
                policy.sustainedEvaluationsRequired, in.nowEpochMs);

        boolean recommend = candidate && matured;
        if (!recommend && backstop && minAgeSatisfied) {
            recommend = true; // explicit max-age + abandonment/quiet backstop path
        }
        if (recommend && in.playersInsideNow > 0) {
            // presence never blocks the RECOMMENDATION itself; scheduling layer
            // handles player-aware timing (requirement 22)
            blockers.add(0, "NOTE: players inside — closure scheduling must be player-aware");
        }

        String sustained = hysteresis.consecutiveHits + "/" + policy.sustainedEvaluationsRequired
                + " sustained evaluations" + (matured ? " (matured)" : hysteresis.pending() ? " (pending)" : "");

        return new DepletionResult(gen, score, health, recommend, blockers, parts,
                knownWeight, ageDaysX10, sustained);
    }

    // ------------------------------------------------------------------ helpers

    private static int decayWindow(DepletionPolicy p) {
        return 7; // fixed observation windows keep evaluations comparable over time
    }

    /** recent-window total / prior-window total, capped at 1 (growth reads as 0 depletion pressure). */
    static double decayRatio(DepletionInput in, DepletionPolicy policy,
                             java.util.function.ToLongFunction<TelemetrySnapshot.DayActivityView> field) {
        List<String> keys = new ArrayList<>(in.snapshot.days.keySet());
        int w = decayWindow(policy);
        int n = keys.size();
        if (n == 0) return 0.5; // no data: neutral midpoint, neither fresh nor exhausted
        int priorEnd = Math.max(0, n - w);
        int priorStart = Math.max(0, priorEnd - w);
        double recent = 0;
        for (int i = priorEnd; i < n; i++) recent += field.applyAsLong(in.snapshot.days.get(keys.get(i)));
        double prior = 0;
        for (int i = priorStart; i < priorEnd; i++) prior += field.applyAsLong(in.snapshot.days.get(keys.get(i)));
        if (prior <= 0) return recent <= 0 ? 0.5 : 0.0; // brand-new activity: zero decay pressure
        return Math.min(1.0, recent / prior);
    }

    static long lastActivityAgeDays(long lastActivityEpochMs, long nowEpochMs) {
        if (lastActivityEpochMs <= 0) return -1;
        return Math.max(0, (nowEpochMs - lastActivityEpochMs) / 86_400_000L);
    }

    private static double clamp01(double x) {
        if (Double.isNaN(x)) return 0;
        return Math.max(0, Math.min(1, x));
    }
}
