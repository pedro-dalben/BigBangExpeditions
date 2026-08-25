# Goal 05 Initial Assessment — Autonomous Expedition Lifecycle, Depletion Intelligence & Live Operations

**Branch:** `feat/goal-05-expedition-automation`
**Baseline commit:** `9a3bc62` (master, Goal 04 final — GOAL 04: PASS)
**Baseline suite:** 292 tests / 0 failures (`./gradlew test` green at assessment time)

Method: code inspected before any implementation. Facts below carry file references.
Nothing in this document is assumed from documentation alone.

---

## 1. Current Lifecycle — how OPEN becomes OPEN again

### 1.1 State machine

12 states with an explicit fail-closed transition table
(`lifecycle/LifecycleState.java:23-65`):

```
OPEN → CLOSING → EVACUATING → LOCKED → PREFLIGHT → BACKUP → RESET_READY
    → (offline) RESETTING → (boot) BOOTING → VALIDATING → OPEN
side sinks: FAILED→LOCKED, RECOVERY_REQUIRED→LOCKED (reachable from anywhere)
```

* Only `OPEN` admits players (`playersMayEnter()`, `LifecycleState.java:67-69`);
  entry is gated identically for `/expedition enter`
  (`lifecycle/EntryDecision.java:17-25`) and every alternate arrival route via
  `EntityTravelToDimensionEvent` (`gameplay/DimensionTravelGate.java:37-57`).
* Destructive window = RESET_READY|RESETTING|BOOTING (`LifecycleState.java:71-74`).
* `VALIDATING→OPEN` additionally requires recorded validation `PASS`
  (`LifecycleService.java:38-44`) — "expedition may not reopen without a PASS".

### 1.2 Who drives what today

| Step | Actor | Where it runs | Automatic? |
|---|---|---|---|
| Timed closing start (`/expedition lifecycle close`) | operator (perm 3) | live server | no |
| Closing warnings + deadline tracking | ClosureService tick (1 Hz fast-path) | live server | **yes**, once started |
| Auto-extraction at deadline → EVACUATING→LOCKED | ClosureService | live server | **yes** |
| Immediate close + evacuation | operator command | live server | no |
| Preflight + authorization issue (LOCKED→PREFLIGHT→RESET_READY) | operator `/expedition lifecycle issue-authorization [purgeHash]` | live server | no |
| Purge-manifest acknowledgment (DIMENSION scope) | operator re-run with hash bound to exact BE delta | live server | no |
| Backup + deletion + journal phases | **offline shell executor** `scripts/production/execute-reset.sh` | server stopped | external script |
| RESETTING transition during offline window | trusted local python edit inside script | offline | external script |
| Boot cross-check lifecycle↔journal → BOOTING→VALIDATING or fail-closed RECOVERY_REQUIRED | StartupGate on ServerStartedEvent | boot | **yes** |
| Baseline comparison evidence | operator `sector compare` | live server | manual tooling |
| Validation record (PASS/FAIL gate) | operator `/expedition lifecycle begin-validation` + `record-validation` | live server | no |
| Reopen (generation increment) | operator `/expedition lifecycle open` | live server | no |

Classification against the Goal 05 vocabulary:

* **automatic:** warning cadence, deadline extraction, boot recovery/resume,
  generation bump on validated reopen, closing-schedule restart re-arm.
* **administrator-driven:** close decision, authorization issuance, purge ack,
  validation recording, reopen, recover/cancel-reset overrides.
* **offline:** backup, physical deletion, journal phase markers, ledger consume,
  rollback path (`rollback-reset.sh`).
* **player-facing:** enter/leave/where/status, warnings, extraction, opening
  ceremony broadcast (`ClosureService.java:216-224` posts `ExpeditionOpened`).
* **persistent:** everything under `<server>/bigbangexpeditions/` outside the
  world dir (`core/BbeLayout.java:14-56`) so regeneration cannot destroy its own
  bookkeeping (`lifecycle/LifecycleRecord.java:9-11`); player NBT stamps
  (inside-marker, return position, generation, transfer flag).
* **recoverable:** crash anywhere in the pipeline lands in explicit
  `RECOVERY_REQUIRED` via StartupRecovery findings
  (`lifecycle/StartupRecovery.java:29-82`), never in a guess; stale CLOSING
  resumes after restart because deadline lives in lifecycle.json
  (`ClosureService.java:46-55`).

### 1.3 Generation identity today

`int generation` inside lifecycle.json. Incremented exactly once, only inside
the `VALIDATING→OPEN` branch and only when `resetInFlight` is armed
(`LifecycleService.java:45-53`). Copies exist in authorization artifacts
(`generationAtIssue`, checksum-bound, `ResetAuthorization.java:74`),
ledger entries, player NBT (`SessionRecovery.java:122-130`, unknown ⇒ −1 forces
recovery), and cycle completion ids (`g<gen>-<deadline>`,
`event/BbeEvents.java:95-100`). There is **no UUID**; the int plus monotonic
increment discipline is the durable identity.

### 1.4 Existing automation seams (verified, not speculative)

1. `DryRunEngine.run(...)` — pure, public, unit-tested full-pipeline evaluator;
   artifact explicitly NOT persisted/consumable (`reset/DryRunEngine.java:43-44,117-122`).
2. RCON console driving of the exact same Brigadier tree used by humans
   (`scripts/staging/console.sh` + `run-cycles.sh` proved 10/10 cycles in Goal 04).
   Every safety check executes identically for the console sender.
3. `AuthorizationService.issue()` pure core — persistence is caller-owned
   (`reset/AuthorizationService.java:18-26`), so an automation service can own
   issuance without bypassing preflight/drift/purge gates.
4. Offline destructive execution deliberately stays a human shell invocation
   (`command/LifecycleCommand.java:23-25`). Nothing in-process destroys anything
   (`env/EnvironmentProfile.destructiveAllowed()` true only for PRODUCTION).

## 2. Current Observability — what the system actually knows

Inspected; do not assume metrics exist. **There is no telemetry subsystem.**
Repo-wide search for metric/telemetry/stat/playtime surfaces nothing but the
Goal 04 closure countdown sampler (the only periodic code in the mod).

What exists today, and only today:

| Signal | Status | Source |
|---|---|---|
| players entering / leaving (boundary events) | partial — audit JSONL lines `PLAYER_ENTERED` / `PLAYER_LEFT` / `PLAYER_EVACUATED` w/ epoch ms + outcomes | `ExpeditionAccessService.java:121-128,252-258`, `EvacuationService.java:83-86`; rotation ~8 MB × 10 files then loss |
| unique participants per closed cycle | partial — participant list carried once on `ExpeditionCompleted` (TELEPORT_OUT evacuees only) | `BbeEvents.java:61-71`; no listener persists it |
| session duration | **absent** — derivable only by offline audit correlation; lost on rotation |
| chunks discovered / sector coverage | **absent** — `SectorLocator` exists but nothing records visits (`sector/SectorLocator.java:13-29`) |
| structures encountered | **absent in code** — building-type knowledge (bank ×598, police ×478, hotel ×4079, polyclinic) is manual Goal 02 census evidence only (`docs/investigations/goal-02-results.md:35-37`); vanilla structure-manager queries unused anywhere |
| container interactions | **absent** — baseline probe counts loaded-chunk block entities (`validation/BaselineService.java:76-129`) but no interaction events exist; Lootr absent by design, OPAC adapter is claims-only |
| deaths | **absent as signal** — death handled only as respawn redirect post-fact (`player/RespawnRedirect.java`) |
| mob kills / hordes | **absent** |
| players currently inside | yes — live AABB scan on demand (`EvacuationService.playersInside`, `EvacuationService.java:32-43`) + persistent boolean NBT marker; no duration, no history |
| expedition age | yes — `lastOpenedAtEpochMs` / `lastResetAtEpochMs` persisted |
| lifecycle timings | yes — full recent-transition ring (cap 50) + timestamps in lifecycle.json |
| closure schedule state | yes — deadline + warned-threshold watermark persisted |

Conclusion: telemetry must be built essentially from zero, but the event seams
(`PlayerEnteredExpedition`, `PlayerLeftExpedition`, `PlayerEvacuated`,
`ExpeditionOpened`, `ExpeditionClosingStarted`, `ExpeditionCompleted`) already
exist and are the correct ingestion points for boundary facts.

## 3. Automation Gaps (what prevents safe automatic operation today)

### CRITICAL

| Gap | Consequence if unaddressed |
|---|---|
| G1. No depletion intelligence — nothing measures exploration, loot consumption, or activity | Any automatic close decision would be arbitrary (timer-only), violating the depletion premise |
| G2. No recommendation/advisory surface | Operators cannot answer "should we renew?" without reading raw world state manually |
| G3. No durable scheduler beyond single closing deadline | Recurring evaluation/maintenance scheduling impossible; nothing survives restarts except one deadline |

### HIGH

| Gap | Consequence |
|---|---|
| H1. Post-close pipeline requires 5 sequential manual commands (issue-auth → stop → script → begin-validation → record-validation → open) | "Autonomous renewal" is unreachable; multi-day unattended cadence impossible |
| H2. Validation recording is honor-system (`record-validation PASS` accepts any word while VALIDATING) | An automation flow could record PASS without comparing baselines; must keep human/automated separation auditable |
| H3. No policy/config layer separating observed facts from thresholds | Threshold tuning would require code changes; config corruption could masquerade as depletion |
| H4. No hysteresis/stability model | A future threshold rule would flap around boundary values |

### MEDIUM

| Gap | Consequence |
|---|---|
| M1. Audit rotation loses old entries (~80 MB cap) | Long-horizon "why did gen N close?" reconstruction incomplete |
| M2. Structure census absent — coverage vs. value cannot be weighted without new detection work | Depletion would lean on chunk counts alone |
| M3. No bounded persistence pattern precedent for high-frequency counters (audit rotates by size; lifecycle ring capped at 50 transitions) | Risk of unbounded growth if naively appended |
| M4. Clock semantics undocumented for multi-day schedules (existing deadlines are wall-clock epoch ms; DST/clock-step behavior untested) | Clock anomaly could trigger early/late destructive windows |
| M5. Offline players' session end is inferred only at next join (stale-marker pattern) | Session-duration accounting needs explicit logout handling to stay honest |

### LOW

| Gap | Consequence |
|---|---|
| L1. Unused lang key `bbe.closing.warn.seconds` | cosmetic cleanup opportunity while touching i18n |
| L2. Sector mutating verbs sit at perm 2 (`SectorCommand lock/open/reset-plan`) | tightening candidates documented; unchanged here unless automation touches them |

## 4. The Depletion Problem — what "depleted" can mean in DeceasedCraft

Ground truth from prior goals:

* The dimension regenerates identically (same server seed, pinned LC profile
  fingerprint; drift REFUSE on seed/profile change). Content availability is
  therefore deterministic per generation: the *same* banks/police/hotels spawn
  each cycle at the same coordinates.
* Goal 02 soak evidence: progression items (research papers) DO generate each
  cycle (Strategy A); reset-time purge manifest handles leftovers. So "loot
  exhausted" means **picked clean by players during this generation**, not
  world-gen absence.
* Sectors are region-aligned districts (max 16k chunks each) repurposed as
  navigation/telemetry units in Goal 04; they have **no lifecycle authority**
  anymore — whole-dimension regeneration replaced sector resets
  (`docs/architecture/expedition-sector-model.md`).

Realistic depletion definition candidates, ranked by evidential value:

1. **High-value structure saturation** — fraction of discovered meaningful
   buildings (bank/police/medical/hotel/gas station/office class) that players
   have demonstrably entered. Strongest proxy for "useful content consumed".
2. **Exploration coverage plateau** — rate of first-entry chunk discoveries has
   collapsed relative to earlier windows while players remain active: they are
   revisiting, not exploring.
3. **Loot interaction trend** — container-open volume decaying across trailing
   windows despite presence: pickings thinning.
4. **Population decay** — unique active explorers declining generation-over-age
   curve (soft signal only).
5. **Age ceiling** — policy maximum lifetime regardless of signals (fairness:
   guarantees new-player access cadence).

Explicitly rejected as sole criteria:

* Raw chunk % visited (a player sprinting roads inflates it; empty outskirts
  dominate area);
* death count (measures danger, not consumption; farming deaths must not force
  renewal);
* AFK hours (must never keep an exhausted zone open nor close a fresh one).

**Visited ≠ exhausted**: entering a bank does not mean its loot was taken
(players may lack keys/desire). Structure visitation is treated as a
consumption *proxy*, weighted below direct container-interaction evidence where
both exist. Documented in the engine, not hidden.

## 5. False Positive / False Negative Risks

False positives (reset while value remains):

| # | Scenario | Mitigation direction |
|---|---|---|
| FP1 | One speedrunner sprints every road → chunk coverage ≈100% while interiors untouched | weight structure-entry + container signals above chunk coverage; require corroboration |
| FP2 | Census unavailable → naive engine treats "0% known visited" as vacuous 100% | explicit UNKNOWN handling — unknown must block, never imply depleted |
| FP3 | Imported/corrupted telemetry from previous generation triggers instant close of fresh zone | generation-scoped stores + minimum-age policy + rollover isolation tests |
| FP4 | Clock jump forward fabricates "age" and staleness | wall-clock sanity guard; clock regression/anomaly pauses destructive automation |
| FP5 | Weekend crowd spike misread as sustained heavy exploitation | observation windows over trailing days, not instantaneous ratios |

False negatives (exhausted zone stays open):

| # | Scenario | Mitigation direction |
|---|---|---|
| FN1 | All nearby banks looted, players idle-AFK in base for weeks keeping "activity" alive | activity measured as distinct active explorers + new-discovery rate, not presence-hours; max-age backstop |
| FN2 | Telemetry flush window crashes lose the final evidence that crossed threshold | flush-on-stop + small loss window acceptable for *advisory*, but hysteresis uses consecutive evaluations spanning flush boundaries |
| FN3 | Small server: absolute numbers tiny so ratios never reach thresholds | policy supports absolute floors OR ratio mode; calibration scenarios include low population |
| FN4 | Structure detection fails silently (LC reflection unavailable) → structure component UNKNOWN forever → automation blocked forever | degrade to coverage+activity components with WARN; document expected staging evidence |
| FN5 | Max-age disabled + signals all UNKNOWN → zone never renews | conservative default: unknown-heavy evaluations escalate to OPERATOR_ATTENTION_REQUIRED rather than silent infinity |

## 6. Proposed Implementation Plan

### 6.1 Workstreams (dependency order)

```
WS1 Telemetry core          (model, generation scoping, bounded persistence,
                             saturation, migration/versioning)           [no deps]
WS2 Ingestion adapters      (Forge events -> WS1; sampling; dedup)       [WS1]
WS3 Coverage & structures   (chunk first-entry sets; structure-start
                             discovery via chunk refs; LC piece naming)  [WS2]
WS4 Depletion engine        (pure evaluator + explainability + missing-
                             data semantics + policy config)             [WS1(+WS3)]
WS5 Automation core         (modes, scheduler math, hysteresis, maintenance
                             windows, overrides, escalation, shadow log) [WS4]
WS6 Lifecycle integration   (advisory->timed-close via ClosureService;
                             auto-issue seam; handoff note for offline
                             executor; audit actor "automation:<mode>")  [WS5]
WS7 Summaries & history     (cycle summaries on completion; bounded
                             archive; admin/player views)                [WS1]
WS8 Command + i18n surface  (/expedition automation ...; localized)     [WS5..WS7]
WS9 Simulation harness      (deterministic scenario generator; calibration/
                             exploit/fairness/property/load campaigns)   [WS4]
WS10 Staging campaigns      (multiplayer pass; >=10 autonomous cycles;
                             soak; failure injection)                    [WS6,WS9]
WS11 Documentation + audit  (architecture/ops/gameplay docs; adversarial
                             final audit; results report)                [all]
```

Risk order: WS4/WS5 carry the decision correctness risk → pure-core first with
property-style tests; WS6 carries the safety risk → smallest possible surface,
reuses Goal 03/04 seams verbatim, default mode MANUAL; WS10 validates reality.

### 6.2 Data model changes (new files, all outside world dir per BbeLayout)

```
bigbangexpeditions/telemetry/gen-<N>.json      current-generation telemetry store
bigbangexpeditions/telemetry/archive.json      bounded completed-cycle summaries (schemaVersioned)
config/bigbangexpeditions/automation.properties  policy + scheduler + authority (validated; invalid critical => fail closed to MANUAL)
bigbangexpeditions/automation/state.json       durable scheduler/automation state (mode, pause, pending decisions, shadow log, clock guard)
```

Telemetry store contents (aggregates, not player histories):
counters (entries/exits/deaths/container opens/mob kills by players), unique
explorer set (UUIDs — necessary minimum identifier, documented), first-entry
chunk set (saturating, capped), structure discovery map (structureId ->
{sections seen, named-class}), activity windows (per-day buckets, rolling),
peak concurrent, data-quality flags, schemaVersion, generation binding,
hysteresis tracker state. Retention: current-gen file deleted after summary
archival; archive keeps last N=50 cycles.

Migration: `schemaVersion` field; unknown version ⇒ load refused ⇒ telemetry
marked UNAVAILABLE (fail-safe: engine reports unknown, blocks destructive
recommendations). Empty install, Goal 04 upgrade (no telemetry dir ⇒ fresh
store, automation mode defaults MANUAL), corrupted/truncated file ⇒ quarantine
copy + UNAVAILABLE flag + audit.

### 6.3 Time semantics (explicit)

* Wall-clock epoch ms for: expedition age, maintenance windows, scheduler
  deadlines, inactivity measurement — consistent with existing
  `closingDeadlineEpochMs`. Administrator-facing windows interpreted in server
  local timezone, documented.
* Tick-based sampling only for in-session micro-events (per-player sample
  cadence); never for schedules.
* Monotonic guard: persisted lastObservedWallClock; backward jump > 5 min or
  forward jump > 24 h ⇒ clock-anomaly flag ⇒ destructive-capable automation
  paused pending operator review (fail-safe), advisory continues.

### 6.4 Test strategy

* Pure-core unit tests mirroring repo convention (decision classes free of MC
  imports): counters/saturation/dedup, rollover isolation, migration matrix,
  corruption handling, depletion determinism + explainability snapshots,
  missing-signal semantics, hysteresis/flapping, min/max age, scheduler
  persistence + missed-schedule catch-up + DST-like anomalies, modes/authority
  boundaries, override auditing, summary generation/bounds.
* Property-style randomized scenario testing via WS9 harness (invariants:
  cumulative exploration never decreases; new generation starts clean; corrupted
  telemetry cannot yield DEPLETED-with-confidence; automation cannot act while
  paused; no closure before minimum notice/age).
* Load: 30k+ synthetic events ingested in-process within generous budget;
  assertions on O(1)/O(capped-set) behavior, zero per-tick disk IO when idle
  (matching Goal 04 fast-path discipline).
* Regression: full existing 292-test suite must stay green throughout.

### 6.5 Staging strategy

* Reuse proven RCON console harness (`scripts/staging/console.sh`): drive
  telemetry-generating flows, advisory recommendations, scheduled closure,
  approval, and repeated cycles.
* Multi-cycle autonomy campaign: ≥10 complete OPEN→activity→DEPLETED→warn→
  LOCKED→(offline reset via existing staging executor)→boot→validate→OPEN
  cycles driven by automation in SCHEDULED/AUTOMATIC modes inside staging,
  no hidden manual corrections.
* Failure-injection passes: telemetry file corruption mid-cycle, scheduler
  state truncation, backup-dir collision, restart storms during countdown,
  clock stepping (where environment allows), concurrent admin+scheduler ops.
* Evidence captured under `evidence/goal-05/`.

### 6.6 Performance concerns & answers

* No continuous world scanning ever: structure discovery piggybacks on chunks
  players already load (structure references read from the entered chunk);
  coverage grows only from real movement samples.
* Player tick sampling throttled (seconds cadence, per-player stagger) and
  hard-gated on dimension + dirty-state; idle cost target zero disk IO
  (pattern proven by `scheduleActive` fast-path).
* Persistence batched: dirty-flag + flush interval (default 30 s) + flush on
  stop; crash loss window documented as acceptable for advisory-grade data —
  lifecycle safety data remains under its own stricter regime (unchanged).
* Evaluation cadence minutes-scale, not tick-scale; single-threaded server-tick
  execution with synchronized stores (repo-wide pattern already
  single-threaded-by-design).

### 6.7 Explicit non-goals retained

No Discord/web/REST, no per-player behavior profiles beyond aggregate
necessity, no chat capture, no personalized loot restoration, no in-process
destructive execution, no sector-level physical resets resurfacing.

---

Assessment complete. Implementation proceeds on this branch following §6.1
order, committing modularly per workstream with tests before commits.
