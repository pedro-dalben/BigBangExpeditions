# Scheduler — Goal 05 Architecture

Status: implemented (WS5).

## Time semantics (requirement 20 — explicit)

| Purpose | Clock |
|---|---|
| expedition age, inactivity, scheduler cadence, maintenance windows, pending TTL | wall-clock epoch ms (matches existing `closingDeadlineEpochMs` discipline) |
| per-player sampling stagger, tick fast-paths | server ticks (micro-cadence only) |
| anti-manipulation of schedules | `ClockGuard` wall-clock sanity |

Administrator-facing windows are interpreted in the SERVER's local timezone
(`ZoneId.systemDefault()`) and printed with offsets. Multi-day expedition
schedules on wall-clock are intentional: they align with human/ops rhythms and
survive restarts without tick accounting.

## Durability

`automation-state.json` (atomic writes) carries: lastEvaluatedAtMs,
hysteresis streak + first-hit timestamp, pending decision + expiry +
fingerprint, postponedUntilMs, pause flag/reason, clock guard bookkeeping,
shadow ring. Nothing depends on process uptime; every deadline survives
restarts.

## Cadence & catch-up

* 1 Hz tick gate (`scheduledActive` armed only when mode ≠ MANUAL ∧ not
  paused) → zero cost in MANUAL and while idle — same discipline as the Goal 04
  closing fast-path.
* evaluation due when `now − lastEvaluatedAt ≥ evaluateMinutes` (default 60,
  clamp 10..1440).
* missed schedules (server down): deterministic run-at-first-opportunity —
  `dueForEvaluation` has no retroactive replay and cannot double-fire; the
  next due instant is always "now or later", never a backlog burst.

## Maintenance windows (requirement 21)

`scheduler.windowStart` / `scheduler.windowEnd` HH:MM server-local.
Semantics: `[start,end)`; start==end ⇒ window disabled = any time allowed;
overnight wrap supported. Automatic execution waits for the window:
`SchedulerMath.nextWindowStart` computes the next opening for status display.
Recommendations may fire any time; EXECUTION is window-gated.

## Restart behavior (requirements 19/27/40)

Boot order: load state (corrupt ⇒ fail-safe pause) → apply config → clock
guard vs persisted observation → expire stale pendings → persist. Repeated
restarts therefore: never reset streaks, never duplicate pendings, never lose
postponements, never replay recommendations (maturation keys are remembered),
and never act while paused.

## Player-awareness & fairness hooks (requirements 22/36)

The engine attaches "players inside" notes to results; execution timing adds:
minimum notice = Goal 04 timed-closing duration (operator-configured warnings
+ extraction), maintenance windows concentrate disruptive turnover in low-
traffic hours, and max-age guarantees a fresh zone within a bounded horizon
for new players. There is no instant-close path anywhere in automation.
