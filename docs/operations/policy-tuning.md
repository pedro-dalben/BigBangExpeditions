# Policy Tuning — Goal 05 Operations

How to choose thresholds with evidence instead of intuition (requirement 51).

## Principle

Facts live in telemetry; policy lives in `automation.properties`. Tuning
changes the second, never the first. Every candidate policy must be run
against the deterministic simulation scenarios BEFORE touching production.

## Calibration workflow

1. Reproduce your population in the harness
   (`src/test/java/.../simulation/SimulationHarness`): players/day,
   chunks/player-day, opens/player-day, AFK fraction, late joiners.
2. Run the scenario set (`SimulationPropertyTest`) — it prints light vs
   exhausted scores and asserts the exploit/fairness properties.
3. Pick `closeScoreThreshold` so that: normal-heavy cycles sit ≥15 points
   below it at their natural end; abandoned/exhausted patterns cross it.
4. Pick cadence math: maturation needs `sustainedEvaluations` evaluations
   spanning ≥ `minSustainedSpanHours`. Defaults (3 × 60 min spanning 6 h)
   mean fastest closure ≈ 6 h after first DEPLETED reading — plus minAge and
   maintenance window.
5. Validate on staging with compressed values ONLY (see campaign config in
   `evidence/goal-05`), never by weakening production safety gates.

## Guardrails baked into validation

* weights clamp to 0..100; coverage+structures both zero forces FALLBACK;
* maxAge ≤ minAge disables the backstop (with notice) rather than creating an
  instant-close window;
* recoveryBand > threshold/2 would neuter stability — clamped to ≤40 and
  semantically capped by dead-zone logic;
* FALLBACK still refuses recommendations under 55% known weight.

## Observed reference points (staging, gen-1 seed pattern)

| Pattern | Score | Health |
|---|---|---|
| fresh zone, no activity | ~1 | HEALTHY |
| seeded heavy exploration + loot + quiet prior windows (compressed staging policy) | ~75 | DEPLETED → auto-closed |
| empty telemetry after archival | 0 UNKNOWN-safe | blocked from automation |

Record YOUR production observations here as they accumulate; treat this file
as the tuning ledger.
