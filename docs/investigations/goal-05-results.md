# Goal 05 Results — Autonomous Expedition Lifecycle, Depletion Intelligence & Live Operations

**Branch:** `feat/goal-05-expedition-automation`
**Starting commit:** `9a3bc62` (master, Goal 04 final)
**Ending commit:** see git log — 20 modular commits on this branch.

## Initial assessment

`docs/investigations/goal-05-initial-assessment.md` (commit `9776f53`, before
any implementation). Verified facts: zero telemetry existed; the only periodic
code was the Goal 04 closing sampler; structure knowledge was manual census
only; the reset pipeline ends live-side at RESET_READY with destructive
execution offline; proven automation seams were DryRunEngine + RCON-driven
Brigadier. Gaps classified CRITICAL×3 / HIGH×4 / MEDIUM×5 / LOW×2 with a
workstream plan (WS1–WS11).

## Implementation plan → what shipped

| WS | Delivered |
|---|---|
| WS1 telemetry core | GenerationTelemetry (saturating counters, capped sets, rolling day buckets), schema-versioned store w/ quarantine, CycleSummary + bounded archive |
| WS2 ingestion | TelemetryService on Forge bus: staggered dimension-gated sampling, chunk first-entry, StructureProbe (chunk-ref reads only), container dedupe, deaths/kills, boot catch-up archival, dirty-flag flush fast-path |
| WS3 coverage/structures | sector-derived area denominator + pinned censuses; STRUCTURE_SIGNAL_ABSENT degradation honesty |
| WS4 depletion engine | pure deterministic evaluator, weighted components w/ renormalization, UNKNOWN contract (BLOCK/FALLBACK), explain() lines, hysteresis anti-flap w/ dead zone, min/max age gates incl. abandonment-guarded backstop |
| WS5 automation core | authority ladder (MANUAL→ADVISORY→SCHEDULED_WITH_APPROVAL→AUTOMATIC_CLOSURE), durable scheduler state, maintenance windows (wrap-aware), clock guard, pending/approval/TTL, postpone/cancel/pause/resume/reload/clock-clear, policy fingerprints, shadow ring, failure escalation to self-pause |
| WS6 lifecycle integration | single execution path = ClosureService.beginTimedClosing; staging-only authenticated offline reset route mirroring production guarantees (flock/journal/VerifyAuth/manifest/consume) |
| WS7 summaries | archival on completion + validation stamping; admin history view |
| WS8 surface | `/expedition automation` subtree (perm2 read / perm3 audited mutations), player-facing zone phase line, i18n pt_br/en_us |
| WS9 simulation | deterministic harness + property/load/exploit/fairness/calibration suites + 200-generation soak |
| WS10 staging | 13-cycle autonomy campaign + failure injections (below) |
| WS11 docs+audit | this set + adversarial audit |

## Architectural decisions (with reasons)

* **Wall-clock schedules** (matches closingDeadlineEpochMs discipline) with
  ClockGuard anomaly suspension; ticks never schedule.
* **Telemetry is advisory-grade**: different durability than lifecycle safety
  data; ≤1-flush-interval loss window documented; corruption quarantines,
  never guesses.
* **Unknown ≠ zero ≠ full**: unknown components drop out of scoring and can
  BLOCK under policy; spatial evidence is load-bearing.
* **Automation's only lifecycle verb is beginTimedClosing** — Goal 04 UX owns
  warnings/extraction; authorization/destruction remain operator+offline
  (Goal 03 untouched).
* **Policy fingerprints** bind pendings so config edits can't resurrect stale
  recommendations as destructive acts.
* **Staging seed-sim command** (environment-gated fail-closed) enables live
  automation campaigns without clients; production installs refuse it.

## Depletion model (defaults)

weights coverage30/structures25/loot20/activity15/age10; DEPLETED at ≥80;
stability = 3 consecutive evals spanning ≥6 h with 5-pt recovery band;
minAge 3 d; maxAge 21 d w/ abandonment guard; loot confidence floor 50 opens;
abandonment horizon 14 d. All operator-tunable via validated config.

Explainability example (live):

```
Expedition health: DEPLETED (score 75.5 ...)
Components:
  coverage     29.5/30.0 KNOWN observed=1024/1024 chunks (100.0%) threshold>=70%
  structures   12.5/25.0 KNOWN observed=400/800 placements (50.0%) ...
  loot         20.0/20.0 KNOWN observed=decay 0.00 over 7d windows ...
  activity      0.0/15.0 KNOWN observed=active today ...
Sustained condition: 1/1 sustained evaluations (matured)
RECOMMENDATION: expedition renewal recommended
```

## Scheduler design

Persisted state machine in `automation-state.json`; 1 Hz armed tick gate
(zero idle IO); due-evaluation math with run-at-first-opportunity catch-up;
windows `[start,end)` server-local w/ overnight wrap; restart-safe streaks,
pendings, postponements; boot fail-safe pause on corrupt state.

## Failure & retry model

Bounded: 3 consecutive evaluation failures ⇒ AUTOMATION_PAUSED (audited +
event). No in-process destructive retries exist at all. Persistence hiccups
retry next interval (advisory grade). Clock anomalies suspend automatic
execution until audited `clock-clear`.

## Migration strategy

schemaVersion upgrade-on-touch for telemetry; future schemas refuse w/o
quarantine; absent state/files ⇒ conservative defaults (MANUAL); rollback
matrix tested (`UpgradeMigrationTest`).

## Simulation results (highlights)

* load: 100 players × 30 days ≈ 40 k chunk events + 13.5 k other facts in
  seconds; all bounds held;
* property: exploration monotonicity, generation cleanliness, no-recommend-
  before-minAge across randomized seeds, corrupted-telemetry never recommends;
* exploit: road-sprinter, AFK shells, alt-swarm, death-spam — none move the
  recommendation; AFK cannot keep a dead zone alive (max-age backstop);
* fairness: late joiners retain guaranteed runway via minAge + warning
  pipeline; weekend patterns handled by multi-day windows;
* calibration: light vs exhausted ordering asserted; scores printed for the
  tuning ledger.

## Real staging results

Environment `.staging/server` (DeceasedCraft pack, Forge 47.4.0). New tooling:
`execute-authenticated-reset.sh` (production-grade guarantees, staging gate),
`run-automation-cycles.sh` (locked driver), `record-qualification` ops command.

### Automation lifecycle campaign

**13 cycles PASS / 0 failed** (`automation-cycle-001..012` + `-013-regression`),
generations 1→15, each cycle:

```
seed(staging) → evaluate → AUTOMATIC_CLOSURE decision → timed closing
→ warnings → extraction → LOCKED → DIMENSION auth issue (purge-ack aware)
→ offline authenticated reset → boot resume → VALIDATING → record PASS
→ open(gen++) → fresh telemetry bound
```

Zero manual close orders. Zero duplicate decisions across 26 boots. Audit
actors distinguishable throughout (`Rcon` vs `automation:AUTOMATIC_CLOSURE`).

### Failures discovered & fixed during campaign (all with regressions)

1. saturation negative-current bug (unit);
2. unbounded in-memory day buckets (unit);
3. ghost generation binding at mid-renewal boots (defer-bind; live verified);
4. probe/baseline/compare misnesting from Goal 04 refactor — restored; live
   compare gate now runs (`cycle-013-regression/compare.txt`: all unchanged);
5. missing revalidation tooling → `record-qualification` command; campaign
   logs show DRIFT_REVALIDATE refusal then sanctioned recovery;
6. eternal-PENDING summaries → validation stamping;
7. config reload not reaching telemetry intervals → single source.

### Failure injection (live)

Corrupted gen-file → quarantined + TELEMETRY_UNAVAILABLE + engine UNKNOWN/
blocked; restored bytes → healthy evaluation. Transcript:
`evidence/goal-05/failure-injection/`.

## Long-run soak

200 accelerated generations through real stores: history capped at 50,
zero surviving per-gen files, archive <512 KB, reload consistent, timing
comfortable. Plus the 13-cycle live campaign showing no drift across ~26 boots.

## Performance

Load test numbers above; idle server does zero telemetry IO; evaluations are
minutes-cadence; staging reset pipeline measured 1 s (backup+verify+delete of
31 files); boot overhead negligible.

## Test count evolution

292 (Goal 04 final) → **372 passed / 0 failed** (+80). Build green; tree clean.

## Remaining risks (MEDIUM/LOW)

* MEDIUM (inherited Goal 04 F1): supervised client-session multiplayer
  validation remains a precondition for production activation — staging used
  synthetic activity seeding by design.
* LOW F6/F7/F8 in final audit (approve-vs-window semantics documented; shadow
  persist size; staging summary explorer counts).
* LOW: Lost Cities building-type classification stays coarse (structure-level
  sightings, no template names) until a reliable LC hook exists; engine
  degrades honestly via signal-absent flag.

## Recommendations for Goal 06

1. Execute the Goal 04 client-session checklist before production activation;
   then replace seed-sim evidence with organic telemetry.
2. Wire BbeEvents health/recommendation events into Discord/web integrations.
3. Consider LC template-name extraction for bank/police/hospital weighting if
   a stable reflection seam appears.
4. Add optional webhook for OPERATOR_ATTENTION_REQUIRED escalations.
5. Grow cycle-history analytics (per-district heat map) on the existing read
   model — persistence already bounded.

```text
GOAL 05: PASS
```

Branch:
feat/goal-05-expedition-automation

Commits created:
20

Tests:
372 passed / 0 failed

Build:
PASS

Telemetry:
PASS

Generation isolation:
PASS

Depletion engine:
PASS

Explainability:
PASS

Scheduler:
PASS

Advisory mode:
PASS

Automatic closure:
PASS

Automatic renewal pipeline:
PASS (through LOCKED + authenticated offline handoff; destructive execution
remains operator-invoked by design)

Crash/restart recovery:
PASS

Exploit resistance:
PASS

Simulation campaign:
deterministic harness; load 100p/30d/~40k events; property, exploit,
fairness, calibration suites green; 200-generation soak bounded

Automation lifecycle cycles:
14 attempted / 13 passed / 0 automation-failures (1 early attempt aborted by
verification gap, superseded by cycle-013 regression rerun)

Long-run soak:
PASS

Goal 03 safety regression:
PASS

Goal 04 gameplay regression:
PASS

Final audit:
PASS

Production automation default:
DISABLED / MANUAL

Production activation:
NOT PERFORMED

Critical unresolved risks:
- none CRITICAL/HIGH; inherited MEDIUM: supervised client-session validation
  required before production activation (Goal 04 F1); LOW items F6–F8 in
  goal-05-final-audit.md
