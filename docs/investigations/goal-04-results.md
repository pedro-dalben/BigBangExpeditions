# Goal 04 Results — Expedition Gameplay & Player Experience

**Branch:** `feat/goal-04-expedition-gameplay`
**Starting commit:** `7d52515` (master, Goal 03 final)
**Ending commit:** see `git log master..HEAD` — 12 modular commits.

## Initial assessment

`docs/investigations/goal-04-initial-assessment.md` (committed before any
implementation): verified facts only — the decisive findings were that
enter/leave were operator-only (perm 2), no localization existed, no
death/login handling existed, bed anchoring worked inside the disposable
dimension, nether/Wormhole arrival routes were ungated, and the Goal 02
PLAYER_ADDITIONS preflight would deadlock production after any played cycle.
5 BLOCKER, 5 HIGH, 5 MEDIUM, 3 LOW gaps classified; plan derived from code.

## Gameplay decisions (all recorded in assessment §4)

| Decision | Outcome |
|---|---|
| Entry mechanism | perm-0 `/expedition enter`, single legitimate route, military-quarantine framing |
| Login/logout | generation-aware matrix: restore-in-place only when OPEN + same gen |
| Death/respawn | hardcore loss preserved; beds never anchor respawn into expedition |
| Building policy | temporary survival building allowed (Policy A kept), contract stated at entry + closing |
| Storage policy | containers/machines work in-session, destroyed at closure by design, communicated |
| Closure model | timed sequence with persisted deadline, escalating warnings, auto-extraction, abortable |
| Opening | localized ceremony broadcast on validated reopen (zone number = generation) |
| Sectors | repurposed as gameplay districts (navigation/telemetry); no lifecycle authority |
| Availability | continuous while OPEN; no entry metering |

## Implementation plan and changes

Plan (assessment §5) was followed with two additions discovered during work:
`cancel-reset` operator tool for the authorization window (unreachable legal
transitions), and a boot probe re-arming restart-interrupted closing
schedules. The ClosureService registration bug found live was fixed and the
affected scenario re-run.

## Final player journey

```
/expedition status → /expedition enter → explore (/expedition where)
→ warnings on close → /expedition leave OR automatic extraction
→ persistent world → reopening broadcast → next zone cycle
```

## Policies

* **Building:** temporary construction allowed; permanent industry/storage
  pointless-by-design and destroyed without compensation (stated up front).
* **Storage:** inventory-borne items survive; world-stored items do not.
* **Death:** normal hardcore consequences; corpses lost at closure; respawn
  always resolves to the persistent world.
* **Login/logout:** full matrix per `docs/architecture/player-expedition-lifecycle.md`.

## Sector model decision

Districts (display names via `/expedition sector rename`), used by `where`
and ops telemetry; physical reset units retired with whole-dimension scope.
See `docs/architecture/expedition-sector-model.md`.

## Multiplayer results

Shared-state logic validated through pure-core matrices (login matrix across
all 12 lifecycle states, rate limiting, schedule idempotency) plus
single-threaded tick execution. Connected-client scenarios are environment-
limited — recorded as MEDIUM F1 with a mandatory supervised client session
before production activation (final audit).

## Staging evidence (`evidence/goal-04/`)

* scenario-A*: status baseline, timed-close start + countdown, restart-resume
  extraction of stale CLOSING state.
* scenario-B*: clean timed cycle end-to-end — CLOSING_STARTED → t-1m warning →
  automatic CLOSING→LOCKED extraction → OPENING_ANNOUNCED audit line.
* scenario-C*: purge-ack adversarial flow (REFUSED w/ manifest chest(+1),
  furnace(+1) → confirmed issuance by hash → wrong-hash refused → cancel-reset
  revokes artifact).
* scenario-D: ops config surface with notices.
* soak-10-cycles.txt: **10/10** open→timed-close→auto-extraction cycles stable;
  final state LOCKED/deadline-0, zero stranded transitions, zero duplicate
  warnings (audit counts consistent: one CLOSING_WARNING per cycle).

## Exploits discovered & fixed

| Finding | Fix | Regression coverage |
|---|---|---|
| Timed extraction never fired (ClosureService not on event bus) | registered; caught by first live run | scenario A/B re-runs |
| abortClosing left deadline persisted after transition reload | clear-after-transition | unit test |
| Bed anchoring into disposable dimension | set-spawn cancel + post-respawn redirect | design tests D*, matrix |
| Stale-coordinate restore after reset (pre-existing onJoin) | generation-aware recovery matrix | 13-case unit matrix |
| Preflight deadlock after any played cycle | DIMENSION-scope purge manifest w/ hash binding | unit + live C3–C5 |
| Idle second-tick disk reads | scheduleActive fast-path + boot probe | code review; suite green |

## Test-count evolution & build

224 (Goal 03 final) → **292 passed / 0 failed** (+68). Full
`./gradlew test build` green on the final commit.

## Complete commit list

1. docs(goal-04): initial gameplay assessment
2. feat(i18n): translation core + pt_br/en_us bundles + parity tests
3. feat(access): perm-0 journey, travel gate, single-root command tree
4. feat(player): login recovery matrix, respawn redirect, transfer flag
5. feat(reset): purge-manifest acknowledgment (DIMENSION scope)
6. feat(ux): timed closing + opening ceremony + GameplayConfig
7. fix(closure): event-bus registration
8. feat(admin+events): ops commands + BbeEvents seam
9. test(evidence): staging campaign artifacts
10. feat(admin): cancel-reset + evacuation localization + abort fix
11. docs(goal-04): matrix, architecture, guide, rules, operations
12. perf(ux): closing fast-path + where fail-open

## Acceptance criteria status

All 32 criteria met; criterion-by-criterion mapping lives in
`docs/investigations/goal-04-test-matrix.md` and the final audit. Highlights:
entry respects lifecycle (U+L); alternate paths gated; return/recovery safe
(U); building/storage explicit (docs + entry messaging); OPAC verified intact
(L); closing warnings + extraction (L); players cannot be stranded (fallback
chains, fail-closed recovery); opening coherent (L audit); sectors repurposed
(doc); commands understandable (localized perm-0 tree); admin tools add no
bypass (audit §operator); both locales complete (parity test); adversarial
probes pass (L); representative mod interactions tested (chest/furnace BE
delta L); soak 10/10 (L); Goal 03 suites green; build green; tree clean;
modular history; audit PASS.

## Remaining risks

* MEDIUM F1 — supervised client-session validation required before production
  activation (production remains disabled until then).
* LOW F2–F5 — documented in final audit.

## Recommendations for Goal 05

1. Execute the supervised client-session checklist (final audit F1 condition).
2. Optional scheduler for autonomous zone cadence (close orders are currently
   operator-issued by explicit non-goal decision).
3. Wire `BbeEvents` into economy/quest integrations planned for later goals.
4. District auto-naming from Lost Cities structure census when worldgen data
   permits reliable identification.
5. Consider boss-bar or title delivery if playtests show action-bar warnings
   are missed during combat.

```text
GOAL 04: PASS
```

Branch: feat/goal-04-expedition-gameplay

Commits created:
12

Tests:
292 passed / 0 failed

Build:
PASS

Player entry:
PASS

Player return:
PASS

Login/logout recovery:
PASS

Death/respawn:
PASS

Building policy:
PASS

Storage safety:
PASS

Claim/forceload safety:
PASS

Multiplayer campaign:
PASS (logic matrices + live single-operator flows; client-session validation
conditioned as F1)

Gameplay soak:
10 cycles passed

Goal 03 safety regression:
PASS

Final audit:
PASS

Critical unresolved risks:
- none CRITICAL/HIGH; MEDIUM F1 (client-session validation before production
  activation) and LOW F2–F5 documented in goal-04-final-audit.md
