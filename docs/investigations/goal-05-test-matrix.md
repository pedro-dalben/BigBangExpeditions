# Goal 05 Test Matrix — acceptance criteria → evidence

Baseline: `9a3bc62` (292 tests). Branch `feat/goal-05-expedition-automation`.
Suite at audit time: **372 tests / 0 failures** (`./gradlew test`).

| # | Criterion | Evidence |
|---|---|---|
| 1 | Assessment before implementation | commit `9776f53` precedes all feat commits |
| 2 | Plan derived from repository | assessment §6 maps workstreams to inspected files |
| 3 | Durable generation identity | lifecycle generation + telemetry binding; `GenerationTelemetryTest.generationIsolation*`, live gens 1→15 across pipeline |
| 4 | Generation-scoped telemetry | `acceptsGeneration` refusals; rollover tests; campaign history shows per-gen files/archives |
| 5 | Bounded persistence | caps+saturation tests; `LongRunSoakTest` (200 gens, 0 surviving files, archive <512 KB) |
| 6 | Migration/versioning safe | `UpgradeMigrationTest` (legacy normalize, future refuse, quarantine) |
| 7 | Corrupted telemetry fails safe | store CORRUPT outcomes + live failure injection (`evidence/goal-05/failure-injection/`) |
| 8 | Coverage w/o full scans | sampled first-entry set only; StructureProbe reads entered chunk refs |
| 9 | Structure activity contributes | census-pinned component + signal-absent degradation; engine tests |
| 10 | Player activity measured | day buckets + recency; AFK non-refresh property test |
| 11 | Deterministic evaluation | clock-as-parameter design; `deterministicSameInputsSameOutput` |
| 12 | Explainability | `explain()` component lines; `/expedition automation explain` live output |
| 13 | Missing signals explicit | UNKNOWN contract + BLOCK/FALLBACK policies; `fallbackModeStillRequiresMinimumKnownWeight` |
| 14 | Policy separated from facts | `DepletionPolicy` vs snapshot; fingerprint binding tests |
| 15/16 | Advisory mode + maturity levels | authority ladder implemented; ADVISORY exercised in staging (shadow log 18/29 would-recommend) |
| 17/18 | Stability/hysteresis tested | `oscillationAroundThresholdNeverMaturesNorResets`, `dropBelowRecoveryBandResetsStreak` |
| 19/20 | Min/max age policy | min-age blocker test; max-age backstop test (+ abandonment guard) |
| 21/22 | Scheduler survives restart; missed schedules deterministic | state persistence roundtrips; `missedScheduleCatchesUpDeterministically`; live restart dance ×26 boots |
| 23 | Maintenance windows | window math incl. overnight wrap; execution gate `canAct` |
| 24 | Player-aware scheduling | players-inside NOTE + Goal-04 timed closing owns warnings/extraction |
| 25 | Goal 04 closure UX reused | single path: `ClosureService.beginTimedClosing` from automation |
| 26 | No bypass of Goal 03 safety | automation never touches auth artifacts; AUTH_ISSUED actor=Rcon vs AUTOMATION_CLOSED_STARTED actor=automation:* in same cycle audits |
| 27 | Backup failure blocks renewal | executor exits non-zero on backup verify fail (script guard chain); campaign resets all backed up first |
| 28 | Offline handoff safe | destructive phase only via stopped-server authenticated script (flock/journal/consume) |
| 29 | Validation gates reopening | record-validation PASS required; live VALIDATING→PASS→open sequence |
| 30 | Retry bounded | evaluation-failure counter ⇒ self-pause; no destructive retries exist in-process |
| 31–34 | Escalation/pause/postpone/cancel/approve audited | service methods + audit events; corrupt-state fail-safe pause test |
| 35/36 | Summaries produced; bounded history | CycleSummary on completion; cap 50 soak-proven |
| 37 | Admin health/explain tools | automation status/explain/history/shadow/dryrun commands (live transcripts in evidence) |
| 38/39 | Dry-run + shadow | dryrun verdict logic; shadow ring always-on |
| 40 | Calibration via simulation | calibration scenario ordering assertions + printed scores |
| 41 | Deterministic harness | SimulationHarness seeded RNG |
| 42/43 | Exploit scenarios tested | sprinter/AFK/alt-swarm/death-spam tests |
| 44 | Restart replay no duplicates | pending consumed-once semantics; streak persistence; live 26-boot campaign |
| 45 | Clock anomalies handled | ClockGuard tests; live anomaly suspend/clear path exists |
| 46 | Concurrent scheduler/admin safe | synchronized service + single-thread tick discipline; deduper bounded |
| 47 | Upgrade from Goal 04 tested | upgrade/migration suite + default-MANUAL |
| 48/49 | Goal 03/04 regressions | full pre-existing suites green inside 372 |
| 50 | Real staging multiplayer lifecycle | staging executed end-to-end (synthetic activity seeding documented as staging-only instrumentation; client-session precondition remains Goal 04 F1) |
| 51 | ≥10 automation-driven cycles | **13 PASS cycles** (`evidence/goal-05/automation-cycle-*`, gens 2→15), zero manual close orders |
| 52 | Soak clean | LongRunSoakTest + 13-cycle live campaign (no drift, bounded files) |
| 53 | Performance acceptable | load test (~40k events fast); idle zero-IO fast-path; live resets 1 s backup+delete |
| 54 | Audit no CRITICAL/HIGH open | see goal-05-final-audit.md |
| 55/56 | Suite green; build green | 372/0; `./gradlew build` PASS |
| 57/58 | Tree clean; modular history | git status clean; 20+ modular commits |
| 59 | Production auto-renewal off by default | mode MANUAL absent-file default (tested); production env untouched |
| 60 | No production world used | all testing in `.staging/` sentinel-gated environment |

## Defects found by campaigns → fixes → regressions

| Finding | Fix | Regression |
|---|---|---|
| Saturation.add negative-current overflowed to CEILING | clamp-before-add | counter tests |
| In-memory day buckets grew past window between flushes | trim-on-create | trim test |
| lateJoinDay default skipped all sim players | default 0 | harness usage |
| Ghost gen-file bound to dying generation at mid-renewal boot | defer-bind during RESETTING/BOOTING/VALIDATING | boot() branch + live re-check |
| probe/baseline/compare misnested under attach-baseline (Goal 04 refactor) | paren restoration | live baseline+compare transcript (cycle-013-regression) |
| stale jar masked command fix during verification | process discipline noted | n/a |
| record-qualification tooling absent for REQUIRE_REVALIDATION | new perm-3 audited subcommand | live DRIFT→revalidate recovery in campaign logs |
