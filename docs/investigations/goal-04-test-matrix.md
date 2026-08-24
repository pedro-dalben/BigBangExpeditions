# Goal 04 Test Matrix

**Branch:** `feat/goal-04-expedition-gameplay`
**Suite state at writing:** 292 tests, 0 failures (`./gradlew test`), build green.
Baseline before Goal 04: 224 tests.

Legend: **U** = automated unit/pure-core test · **L** = live staging validation
(RCON against the real 290-mod DeceasedCraft server) · **D** = documented design
limitation with residual-risk entry in the final audit.

## Access gating

| # | Scenario | Level | Evidence |
|---|----------|-------|----------|
| A1 | Entry allowed only while OPEN (all 12 states mapped) | U | `PlayerStateMapperTest`, `EntryDecisionTest` |
| A2 | Unreadable lifecycle refuses fail-closed | U | `EntryDecisionTest` + service guard code path |
| A3 | Alternate dimension arrival gated (portals/teleport) | D* | `DimensionTravelGate` — same `EntryDecision` call site; event-level trigger requires a live client |
| A4 | Command spam rate-limited (6 / 10 s per action) | U | `RateLimiterTest` |
| A5 | `/expedition status` reflects state + localized wording | L | boot evidence: `Expedição: ABERTA` via RCON |

## Journey (enter / leave / where)

| # | Scenario | Level | Evidence |
|---|----------|-------|----------|
| J1 | Return-position codec round-trip + traversal rejection | U | `ReturnPositionTest` |
| J2 | Stored position validated (bounds/stale dim/missing) | U | `ReturnLocationPolicyTest` |
| J3 | Fallback chain ends at persistent-world spawn | U+D | policy unit-tested; adapter teleport needs live player |
| J4 | Chunk→district lookup | U | `SectorLocatorTest` |
| J5 | Player teleport execution (enter/leave) | D* | requires a connected client — operator validation step before production |

## Login / logout / death recovery

| # | Scenario | Level | Evidence |
|---|----------|-------|----------|
| R1 | logout OPEN gen N → login OPEN gen N ⇒ restore in place | U | `LoginRecoveryDecisionTest` |
| R2 | logout gen N → login gen N+1 (post-reset) ⇒ recover to return point | U | matrix |
| R3 | unknown/regressed generation fails closed to recovery | U | matrix |
| R4 | login during CLOSING/EVACUATING/LOCKED/VALIDATING/RECOVERY_REQUIRED/destructive window ⇒ eviction | U | matrix (all states) |
| R5 | interrupted transfer flag resolves safely on join | U | matrix |
| R6 | bed-as-respawn-anchor denied; sleep still allowed | D* | `PlayerSetSpawnEvent` cancel — needs client for live confirmation |
| R7 | post-respawn redirect out of expedition | D* | `PlayerRespawnEvent` handler — needs client |

## Lifecycle integration

| # | Scenario | Level | Evidence |
|---|----------|-------|----------|
| L1 | Timed closing schedule math (due/idempotent/desc order) | U | `ClosingScheduleTest` |
| L2 | Closing schedule persists across restarts | U+L | record fields tested; live restart-resume extracted stale CLOSING → LOCKED at boot |
| L3 | abort-close returns to OPEN and clears deadline/warn markers | U+L | `timedClosingSchedulePersistsAndAbortsCleanly` + scenario B |
| L4 | Full timed cycle: close → warning t-1m → auto-extraction → LOCKED | L | `evidence/goal-04/scenario-B2-timed-extraction-complete.txt` |
| L5 | Soak: 10 consecutive timed cycles stable | L | `evidence/goal-04/soak-10-cycles.txt` (10/10) |
| L6 | Opening announcement on validated reopen | L | audit `OPENING_ANNOUNCED gen 1` |
| L7 | Goal 03 transition table unchanged (no new unsafe edges) | U | full `LifecycleStateTest` suite re-run |

## Reset / building policy

| # | Scenario | Level | Evidence |
|---|----------|-------|----------|
| P1 | SECTOR scope keeps strict PLAYER_ADDITIONS refusal | U | `PreflightEngineTest.playerAdditionsVsBaselineRefuse` |
| P2 | DIMENSION scope without ack refuses w/ quantified manifest | U+L | unit + `scenario-C3-purge-required.txt` (chest(+1), furnace(+1)) |
| P3 | DIMENSION scope with ack passes, claims/players still hard-refuse | U | engine tests |
| P4 | Manifest hash binds exact counts; wrong hash refused | U+L | `PurgeManifestTest` + `scenario-C5-wrong-hash-refused.txt` |
| P5 | cancel-reset unwinds window + revokes ledger artifact | U+L | service test + `scenario-C2-cancel-reset.txt` |
| P6 | OPAC claim prohibition config intact | L | serverconfig `claimableDimensionsList` verified this goal; runtime selftest command retained |

## Localization & UX

| # | Scenario | Level | Evidence |
|---|----------|-------|----------|
| I1 | pt_br/en_us key parity | U | `TranslationsTest.localesHaveIdenticalKeySets` |
| I2 | Every lifecycle state has player wording; no enum leaks | U | `PlayerStateMapperTest` + lang parity |
| I3 | Missing keys are loud (`!key!`) | U | translations test |
| I4 | GameplayConfig fallbacks never break boot, notices visible | U+L | config test + `scenario-D-ops-config.txt` |
| I5 | Closing/opening broadcasts use pt_br by default | L | soak cycles ran with localized announcements configured |

## Multiplayer note

True multi-client scenarios (simultaneous entry, party flows, vehicle carry)
require connected clients unavailable in this environment. All shared-state
logic (lifecycle transitions, evacuation planning, rate limiting, closing
schedule idempotency) is implemented as pure cores covered by the unit suite;
single-threaded server-tick execution removes intra-tick races by construction.
Residual risk recorded in the final audit (MEDIUM).
