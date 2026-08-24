# Goal 03 Initial Assessment — Production Readiness & Controlled Expedition Lifecycle

**Date:** 2026-08-24
**Branch:** `feat/goal-03-production-readiness` (base = master `7f533bb`)
**Starting evidence:** 82/82 unit tests pass, full build passes, working tree clean.

Method: direct code inspection of every main-source package, all staging scripts,
docs and Goal 02 git history (38 commits). Documentation was treated as claims;
only what is verifiably in code/scripts is reported as fact.

---

## 1. Current architecture (as it actually exists)

### 1.1 Package map

| Package | Contents |
|---|---|
| `command` | `ExpeditionCommand` (doctor/world/sector probe+baseline+compare), `SectorCommand` (sector lifecycle + `reset-plan`), `DimensionStatusCommand`, `ExpeditionTeleportCommand` (enter/leave), `OpacSelfTestCommand`. All gated at permission level **2**. |
| `sector` | `SectorRegistry` (atomic-write JSON at `<server>/bigbangexpeditions/sectors.json`, outside world dir), `SectorRecord`, `SectorState` (8-state machine: OPEN→DEPLETED→COOLDOWN→LOCKED→RESET_PLANNED→RESETTING→VALIDATING→FAILED), `SectorBounds` (region-aligned validation, max 16 regions), `SectorTopology`, `SectorProbeResult`. |
| `reset` | `ResetPlanService` (manifest creation + `loadVerified` checksum/dimension gate), `ResetPlanManifest` (deterministic JSON → sha-256 `manifestChecksum`; stores coordinates only, never paths), `PathConfinement` (`confine()` segment validator; hardcoded dimension dir derivation). |
| `safety` | `ProductionGuard` (`fromConfig` refuses production+destructive combo; defaults false/false), `ResetPreflightEngine` (fixed-order aggregation of validators), `PreflightChecks` (12 pure checks: guard, dimension, alignment, state, baseline, scan completeness, players, claims, forceload-warn, player-additions-vs-baseline, loot-policy anchors, SavedData owners), `ResetPreflightResult` (ERROR/WARN aggregation, never short-circuits), `SectorLiveState` (interface for testability), `ValidationIssue`. |
| `validation` | `BaselineService` (live probe: players via AABB, OPAC claim inspection, loaded-chunk BE census by type/namespace, container/spawner counts, mod-namespace warnings; baseline write/read/compare as JSON under `<server>/bigbangexpeditions/baselines/`). |
| `integration.lostcities` | Reflective adapter: profile resolution via `mcjty.lostcities.setup.Config#getProfileForDimension`, sha-256 profile fingerprint from `config/lostcities/profiles/<name>.json`, expected-profile validation. Fail-closed. |
| `integration.opac` | Public-API adapter (`isClaimable`, claim intersection inspection). Fail-closed when absent. |
| `loot` | `LootPolicy`: embedded JSON policy, fail-closed loader, UNKNOWN blocks reset duplication. |
| `diagnostics` | `DoctorService`/`DoctorReport`: mod presence/version matrix, LC profile hint, Lootr config probe, seed status. Read-only. |
| `teleport` | `ReturnPosition` (persistent-data round trip with overworld-spawn fallback). |

### 1.2 Destructive path (offline executor)

`scripts/staging/execute-reset.sh` (bash + embedded python):

1. sentinel `.bigbangexpeditions-staging` required; server must be stopped (PID file).
2. Plan id format check → manifest structural check (all fields present).
3. Manifest checksum re-derived in Python (mirrors Java canonicalization) — REFUSE on mismatch.
4. Region targets derived from manifest bounds; each realpath must be inside
   `realpath(<server>/world/dimensions/bigbangexpeditions/expedition)`.
5. Disk-space guard (~2× dim size + 10 MB).
6. Immutable backup to `.staging/backups/<planId>/` incl. `SHA256SUMS`, self-verified.
7. Delete exactly the listed region/entities/poi files.
8. Mark sector RESETTING in registry (post-deletion).

`scripts/staging/rollback-reset.sh`: sentinel + stopped + SHA256SUMS verify → copy back.

### 1.3 What Goal 02 proved (git history rationale)

* Whole-dimension regeneration (B3) validated over 15 soak cycles, byte-identical
  structure censuses, no drift; sector-scoped (B2) executor kept as staging tooling.
* Crash mid-boot leaves persisted RESETTING and recovery completes through validation.
* Adversarial refusals (tampered manifests, wrong state, foreign dimension, missing
  baseline, running server, no sentinel) all hold.
* Rollback proven by restored-region hash equality.

---

## 2. Production-readiness gaps

Classification: CRITICAL = release blocker for enabling production; HIGH = must fix
before first real reset; MEDIUM = required for operational confidence; LOW = polish.

### CRITICAL

| # | Finding | Evidence |
|---|---|---|
| C1 | **Preflight engine not wired into the live command path.** `SectorCommand.resetPlan` calls `ResetPlanService.createPlan` directly; the 12-validator engine runs nowhere in production flow (unit tests only). Live gates (players, claims, player additions) exist only as separate probe outputs, not as a plan-time gate. | `SectorCommand.java:193-224`, `ResetPlanService.java:47-119` |
| C2 | **No crash-safe intent journal around deletion.** Executor deletes files *before* marking RESETTING. A kill in that window leaves RESET_PLANNED + deleted dimension and no persisted evidence of the destructive step → boot cannot distinguish "nothing happened" from "halfway done". Fail-closed recovery impossible without guessing. | `execute-reset.sh` step order (delete → registry update) |
| C3 | **No concurrency lock.** Two simultaneous executors both pass `require_server_stopped` and the `[ -d "$BACKUP_DIR" ]` pre-check (`mkdir -p` races); nothing prevents two destructive runs or an executor racing a boot. No `flock`, no lock artifact. | `common.sh`, `execute-reset.sh` |
| C4 | **No installation fingerprint.** Manifest binds LC profile sha + seed hash only. Copying a manifest to another install with equal seed/profile replays successfully. BBE/MC/Forge/critical-mod versions unbound. | `ResetPlanManifest` fields |
| C5 | **Stale-plan replay possible.** `sectorResetCountAtPlanTime` is recorded but never checked by the executor; plans have no expiry; plan consumption is not persisted (same plan could be executed twice across resets of later generations if states are re-aligned manually). | `execute-reset.sh` ignores field; `ResetPlanManifestTest` covers checksum only |

### HIGH

| # | Finding | Evidence |
|---|---|---|
| H1 | **No environment model.** `ProductionGuard.fromConfig` exists but nothing loads a runtime config file; commands never construct a guard. STAGING vs PRODUCTION distinction lives implicitly in script paths/sentinel, not in mod logic. Dry-run mode does not exist anywhere. | grep: `ProductionGuard` referenced only by tests + PreflightChecks |
| H2 | **Lifecycle does not protect players.** `/expedition enter` checks dimension availability + LC presence but not lifecycle state; no close/evacuate flow; no entry blocking during LOCKED..VALIDATING; disconnect-inside handling undefined. | `ExpeditionTeleportCommand.doEnter` |
| H3 | **Validation result does not gate reopening.** VALIDATING→OPEN transition allowed unconditionally; `lastValidationResult` informational only. | `SectorState.ALLOWED`, `SectorRegistry.applySideEffects` |
| H4 | **Backup metadata thin; retention undefined.** Backup holds plan copy + SHA256SUMS but no generation counter, MC/Forge versions, backup format version; rollback does not re-hash files after restore; sole-backup deletion protection is ad-hoc ("remove deliberately"). | `execute-reset.sh`, `rollback-reset.sh` |
| H5 | **No audit trail.** Lifecycle mutations log to Log4j text only; no durable structured record (who/what/transition/result/duration) that survives log rotation. | all command classes |
| H6 | **Whole-dimension (B3) production executor absent.** Production decision was B1-shaped whole-dimension reset; current executor is sector/region-file scoped (B2). The production shape needs its own guarded executor + rehearsal harness. | goal-02-results architecture decision |
| H7 | **Canonicalization duplicated between Java and Python.** Checksum logic exists twice; silent divergence would either reject valid plans (safe) or accept tampered ones if Python side drifts (unsafe). | `execute-reset.sh` PYEOF block vs `ResetPlanManifest.toDeterministicJson` |

### MEDIUM

| # | Finding |
|---|---|
| M1 | World folder hardcoded as `<server>/world` in scripts + `DimensionStatusCommand`; `level-name` from `server.properties` ignored. Wrong-level-name installs would confine-check against a non-existent dir (executor then refuses — safe but unusable) or, worse, `realpath` mismatch confusion. Derive level name explicitly. |
| M2 | `require_server_stopped` heuristic contains dead pgrep branch; authoritative PID check fine, but stale PID files after kill -9 make the check vacuous (file exists, process gone → passes, which is correct) while a *live unrelated java reuse* of pid is theoretically misread. Add process-name/cwd corroboration. |
| M3 | No status/health surface aggregating: lifecycle state, last reset outcome, pending plans, backup availability, drift status. Admins currently assemble this from 4+ commands. |
| M4 | Permission granularity: diagnostics and lifecycle-affecting ops share level 2. Reset planning / lifecycle transitions should require more than read-only doctor. |
| M5 | Idempotency undefined operationally: repeated `reset-plan` mints new UUID plans each call (plan spam; ambiguity about which plan binds the generation). Repeated executor invocation on same plan partially guarded by backup-dir existence only. |
| M6 | Drift detection limited to LC profile equality at plan time; no comparison against qualification-era fingerprint, no ALLOW/WARN/REVALIDATE/REFUSE classification, no mod-version drift awareness. |
| M7 | No release workflow: no changelog, artifact sha-256 procedure, upgrade/migration notes, nor tested upgrade path from Goal 02 state. |
| M8 | `ExpeditionCommand.compare` falls back to absolute paths supplied on the command line (read-only, but inconsistent with confinement discipline elsewhere). |

### LOW

| # | Finding |
|---|---|
| L1 | `SectorRegistry.load` throws on unreadable registry (fail-closed) — good, but callers (`SectorCommand`) don't catch, producing raw stack traces to admins. |
| L2 | `DoctorService.probeLostCitiesProfile` returns a static hint string (documented limitation). |
| L3 | `LootPolicy` embedded fallback logs error per load; repeated calls spam logs. |
| L4 | Scripts emit multi-line REFUSED reasons inconsistently (some single-line). |

---

## 3. Existing strengths (do NOT rewrite)

* **State machine discipline** — explicit transitions, fail-closed unknowns (`SectorState`), idempotent no-op transitions.
* **Atomic persistence pattern** — temp-file + atomic move, registry stored outside the world dir so regeneration cannot destroy bookkeeping.
* **Deterministic checksummed manifests** with coordinates-only content and runtime path re-derivation (`PathConfinement.confine`).
* **Preflight engine architecture** — pure, aggregated, ordered, unit-testable; correct place to wire the live flow into (C1 fixes wiring, not design).
* **Fail-closed adapters** (LC reflection, OPAC public API, loot policy UNKNOWN semantics).
* **Executor guard chain** — sentinel, stopped-server, checksum re-verification, realpath confinement, disk guard, verified backup before delete.
* **Evidence culture** — per-cycle JSON summaries, adversarial-refusal transcripts, soak analysis.
* **Staging isolation** — everything destructive requires the staging sentinel today.

These carry forward unchanged or with additive evolution.

---

## 4. Risk analysis

| Risk | Current mitigation | Residual gap → action (workstream) |
|---|---|---|
| Destructive filesystem operations escape intent | sentinel + plan artifact | W5 authorization v2, W9 journal protocol |
| Wrong-dimension deletion | hardcoded dim derivation + realpath confine | keep; add whole-dim executor confinement tests (W9) |
| Incomplete backup | SHA256SUMS self-verify | W7 backup manifest + restore verification + retention |
| Failed restore | hash-verified source | W7 post-restore hash proof + lifecycle ROLLBACK state |
| Server crash during lifecycle | RESETTING marker (partial) | W2 dimension lifecycle + W9 phase journal → explicit RECOVERY_REQUIRED |
| Simultaneous reset requests | none beyond backup-dir race | W6 lock (in-process + flock in executor) |
| Stale reset plans | resetCount recorded, unused | W5 expiry + generation binding + consumption ledger |
| Corrupted manifests | dual-side checksum | W5 v2 manifest + single canonicalizer (W7/H7 resolution) |
| Version mismatch | none | W1 install fingerprint |
| Profile mismatch | LC profile sha at plan time | W1 fingerprint + W6 drift policy |
| Seed mismatch | seed hash in manifest | keep + executor-side verification (W9) |
| Modpack changes | doctor listing only | W6 drift classification (ALLOW/WARN/REVALIDATE/REFUSE) |
| Partial startup | manual begin-validation | W2 startup recovery scan + gate |
| Validation failure | transition allows OPEN regardless | W10 hard gate |
| Insufficient storage | df guard in executor | port to production executor (W9) + dry-run estimate (W8) |
| Player presence | preflight refusal (unwired, C1) | W4 evacuation + entry gating; C1 wiring |
| OPAC/forceload state | probe + preflight checks | wired via C1 |
| Unknown persistence | SavedData inventory gate | wired via C1; inventory refreshed per release (W11 docs) |
| Accidental production activation | guard class exists unused | W1 env model: default STAGING, multi-signal PRODUCTION activation, dry-run default for PRODUCTION |

---

## 5. Proposed implementation plan (derived from this repository)

Design decisions:

* **D2.1 — Dimension-level lifecycle is a new, small subsystem** (`lifecycle/`)
  persisting to `<server>/bigbangexpeditions/lifecycle.json` (outside world dir),
  mirroring the proven SectorRegistry pattern. Sector machinery remains untouched
  as the staging/B2 tool. States:
  `OPEN, CLOSING, EVACUATING, LOCKED, PREFLIGHT, BACKUP, RESET_READY, RESETTING,
  BOOTING, VALIDATING, FAILED, RECOVERY_REQUIRED`.
  Transitions validated fail-closed; every mutation emits an audit event.
* **D2.2 — Environment model** (`env/EnvironmentProfile`): STAGING (default),
  PRODUCTION_DRY_RUN, PRODUCTION. Loaded from
  `config/bigbangexpeditions/environment.properties`. PRODUCTION additionally
  requires an operator-created acknowledgment file (`production.enabled` containing
  the current install fingerprint's short hash) — two independent signals plus
  default-off satisfy the safety boundary. Destructive behavior enabled ONLY in
  PRODUCTION; PRODUCTION_DRY_RUN executes the identical pipeline with destructive
  sinks stubbed.
* **D2.3 — Authorization artifact v2**: `ResetAuthorization` manifest adds
  `installFingerprint`, `lifecycleGeneration`, `expiresAtEpochMs`, `backupId`
  binding; single-use consumption recorded in a persisted ledger
  (`authorization-ledger.json`). Canonicalization lives in ONE Java class; the
  executor verifies via a shipped Java CLI (`VerifyPlan` runner through
  `gradlew`-independent jar? — resolved in W5; likely `java -cp modjar
  VerifyPlanCli`) instead of duplicating Python logic.
* **D2.4 — Locking**: in-process `ReentrantLock` + persisted lock record
  (`locks/reset.lock`: holder, purpose, startedAt, pid) for online ops; executor
  uses `flock -n` on the same file + lock-record protocol. Stale lock = lock older
  than configurable TTL AND holder pid dead → recoverable only via explicit
  `recover-lock` op that writes an audit event.
* **D2.5 — Crash safety**: phase journal (`bigbangexpeditions/journal/<authId>.json`,
  appended+atomically moved per phase completion: LOCKED→BACKUP_START→BACKUP_DONE→
  DELETION_INTENT→DELETION_DONE). Startup scans journal + lifecycle: any incomplete
  sequence ⇒ FAILED/RECOVERY_REQUIRED with precise last-known-phase; recovery is
  manual-first (runbook), never speculative auto-repair.

### Workstreams (dependency order)

| WS | Scope | Depends on |
|---|---|---|
| W1 | `env/EnvironmentProfile` + config loading + `InstallFingerprint` (BBE/MC/Forge/LC/OPAC/Lootr/Hordes versions, dimension id, LC profile sha, seed hash, loot-policy sha) + `DriftPolicy` classification | — |
| W2 | Dimension `LifecycleState` machine + persistence + startup recovery scan + journal reader | W1 (audit ids) |
| W3 | `AuditLog` append-only JSONL + event schema + rotation-by-size | — (parallel) |
| W4 | Entry gating (`enter` consults lifecycle), evacuation service (identify/prevent/evacuate/disconnect handling), return-position preservation | W2 |
| W5 | `ResetAuthorization` v2 (fingerprint/generation/expiry/single-use) + wiring `createPlan` through `ResetPreflightEngine` + guard + drift gate + Java CLI verifier consumed by scripts | W1,W2,W3 |
| W6 | Locking (online + offline contract) + idempotency rules (plan mint idempotent per generation; duplicate execute rejected; repeat validate safe) | W2,W5 |
| W7 | Backup manifest v2 (metadata, format version), verified-restore hashing, retention policy (keep N, never-delete-last-valid), rollback state integration | W5 |
| W8 | Dry-run engine driving the REAL pipeline with stubbed destructive sink + `WOULD RESET / RESET WOULD BE REFUSED` report | W1–W6 |
| W9 | Production executor scripts v2 (whole-dimension scope option, journal protocol, flock, level-name derivation, seed/profile/fingerprint re-verification at execution time) + rollback v2 | W5–W7 |
| W10 | Status/health commands, permission split (view=2, mutate=3, destructive stays offline), validation-gate enforcement on OPEN | W2,W5 |
| W11 | Docs set (architecture ×2, operations ×5) + runbook updates | rolling |
| W12 | Release process: changelog, sha-256 artifacts, upgrade notes, upgrade rehearsal from Goal 02 state | W9 |
| W13 | Rehearsals: dry-run, full production-like reset, controlled rollback; evidence captured under `evidence/goal-03/` | all |
| W14 | Final adversarial audit + fixes + results doc | W13 |

### What changes / what stays

* **Changed:** `SectorCommand.resetPlan` path (goes through engine), teleport enter gating, executor scripts replaced by v2 counterparts (staging ones retained, updated to shared verifier), `ProductionGuard` becomes part of loaded env profile.
* **Untouched:** sector registry/state machine internals, PathConfinement core, LootPolicy, adapters, BaselineService, DoctorService, existing test suite (extended, not rewritten).

### Major risks to the plan itself

1. ForgeGradle build times constrain iteration → prefer pure-Java modules with unit tests (pattern already established by `safety/`).
2. Offline/online boundary: scripts cannot import Java easily → ship a tiny CLI main-class inside the mod jar for manifest/lifecycle verification (`W5`), keeping one canonical implementation.
3. Rehearsal realism vs safety: rehearsals run exclusively against the staging sentinel environment (`run/` + `.staging`), never the production world; enforced by env model + sentinels.

### Per-workstream validation

Every WS lands with: unit tests (new coverage listed in commit), suite green,
build green. W13 adds end-to-end evidence artifacts. W14 adversarial pass
re-attempts every refusal scenario from Goal 02 plus new surfaces (lock, journal,
drift, expiry, replay).

---

## 6. Acceptance mapping

| # Criterion | Where satisfied |
|---|---|
| 1 Assessment first | this document, committed before implementation |
| 2 Plan from actual repo | §5 derived from inspected code, cites file:line |
| 3 Destructive disabled by default | W1 env defaults STAGING + guard wiring (C1/H1) |
| 4 Env distinguishability | W1 three profiles + acknowledgment artifact |
| 5 Dry-run works | W8 |
| 6 Deliberate validated intent | W5 authorization v2 |
| 7 Stale/replay rejected | W5 expiry/generation/ledger + tests |
| 8 Concurrency impossible | W6 locks + W9 flock + tests |
| 9 Lifecycle persists restart | W2 lifecycle.json + recovery scan |
| 10 No unsafe entry | W4 gating + W10 |
| 11 Evacuation works | W4 + failure-case tests |
| 12 Backup mandatory+validated | W7 + executor chain |
| 13 Rollback proven | W7 + W13 rehearsal |
| 14 Interruption detect/recover | W2+W9 journal + W13 crash points |
| 15 Idempotency defined/tested | W6 |
| 16 Fingerprint exists | W1 InstallFingerprint |
| 17 Drift detected | W1 DriftPolicy |
| 18 Validation gates reopening | W10 |
| 19 Audit history | W3 |
| 20 Health inspectable | W10 status/health |
| 21 Permissions appropriate | W10 split |
| 22 Destructive confined | PathConfinement + W9 v2 executor |
| 23 Traversal/wrong-world tests | extended PathConfinement/executor tests (W9) |
| 24 Disk failures refuse | W9 port + W8 dry-run estimate |
| 25 Release documented+tested | W12 |
| 26 Upgrade tested | W12 |
| 27 Full rehearsal succeeds | W13 |
| 28 Dry-run rehearsal succeeds | W13 |
| 29 Rollback rehearsal succeeds | W13 |
| 30 Suite passes | every WS + final |
| 31 Build passes | every WS + final |
| 32 Clean tree | commit discipline |
| 33 Modular history | per-WS commits |
| 34 Audit clean | W14 iterate-to-green |
| 35 Production world untouched | rehearsals staging-only; env model enforces |

---

## Findings requiring plan evolution will be appended as decision records below.

*(none yet)*
