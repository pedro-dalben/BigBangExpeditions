# Depletion Engine — Goal 05 Architecture

Status: implemented (WS4). Pure core, zero Minecraft imports.

## Contract

`DepletionEngine.evaluate(DepletionInput, DepletionPolicy, HysteresisTracker)`
is deterministic: identical inputs (including `nowEpochMs`, which is a
PARAMETER — the engine never reads a clock) produce identical outputs. This is
what makes property tests and policy calibration meaningful.

## Components and weights

| Component | Default weight | Source | Known when |
|---|---|---|---|
| coverage | 30 | distinctChunks / totalExpeditionChunks | sector census derivable or pinned (`census.totalChunks`) |
| structures | 25 | structure placements / pinned census (`census.totalStructurePlacements`) | census pinned AND signal not absent |
| loot | 20 | decay ratio of container opens over trailing 7d vs prior 7d windows | opens ≥ `lootMinAbsoluteOpens` (50) |
| activity | 15 | quiet days since last recorded event vs `inactivityAbandonDays` (14) | any activity ever recorded |
| age | 10 | ageDays / maxAgeDays (saturating) | maxAgeDays > 0 |

Scores are renormalized over KNOWN components only. An unknown component
contributes zero points and zero weight — unknown can lower confidence, never
manufacture depletion (requirement 13).

## Health mapping

```
UNKNOWN    telemetry unavailable / nothing known
DEPLETED   score ≥ closeScoreThreshold (80)
DECLINING  score ≥ threshold − 2·recoveryBand
ACTIVE     score ≥ 40
HEALTHY    below
```

## Recommendation rules

`recommendClosure` requires ALL of:

1. no hard blockers:
   * telemetry unavailable;
   * open time unknown;
   * age < minAgeDays (default 3);
   * both spatial signals UNKNOWN under `BLOCK` policy (unless backstop);
   * known evidence < `minKnownWeightFraction` (55%) under FALLBACK.
2. health DEPLETED, AND
3. hysteresis matured: `sustainedEvaluationsRequired` consecutive candidate
   evaluations spanning at least `minSustainedSpanMs` (defaults 3 / 6 h).

Independent path: **max-age + abandonment backstop**. When
age ≥ maxAgeDays AND (no activity ever OR quiet ≥ inactivityAbandonDays),
recommendation fires even with thin/unknown evidence — an abandoned ancient
zone must be renewable even if perfect depletion proof never arrives. The
result carries a NOTE explaining it ran on the ceiling alone.

Players inside never block the RECOMMENDATION; the note
"closure scheduling must be player-aware" is attached and the automation layer
handles timing (Goal 04 pipeline owns warnings/extraction).

## Anti-flap (hysteresis)

* crossing threshold → streak++ ;
* score below `threshold − recoveryBand` → reset;
* inside the band (dead zone) → neither grows nor resets: oscillation around
  80 can stay pending forever but can never flap the recommendation.

Streak state is persisted per generation by the automation layer and reset on
generation rollover.

## Visited ≠ exhausted

Structure/coverage facts are consumption PROXIES. A visited bank is not a
looted bank. The engine treats direct interaction evidence (container opens,
decay trend) as the strongest signal and documents every component's meaning
in `explain()` output — administrators see observed values, thresholds and
earned points per line (requirement 11).

## Exploit resistance properties (tested)

* road-sprinting inflates coverage but cannot close a zone alone (loot
  unknown ⇒ BLOCK/thin-evidence);
* AFK presence does not refresh activity (no events ≠ activity);
* death/mob-kill spam does not move the score at all;
* alt-account swarms with shallow footprints cannot manufacture spatial
  saturation against a real area denominator;
* repeated container spam is bounded by the ingest deduper and by windowed
  decay ratios (both windows inflate proportionally).
