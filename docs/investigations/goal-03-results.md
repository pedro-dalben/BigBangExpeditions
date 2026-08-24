# Goal 03 Results — Production Readiness & Controlled Expedition Lifecycle

**Branch:** `feat/goal-03-production-readiness`
**Starting commit:** `7f533bb` (master, Goal 02 final)
**Ending commit:** see `git log` — 30 modular commits created during Goal 03.

## Architecture changes

New subsystems (all pure-core + thin Minecraft adapters, mirroring the proven
Goal 02 patterns):

* `env/` — STAGING / PRODUCTION_DRY_RUN / PRODUCTION profiles with fail-closed
  activation (config value + install-bound acknowledgment file); deterministic
  installation fingerprint; drift policy (ALLOW/WARN/REQUIRE_REVALIDATION/REFUSE);
  runtime fingerprint collector.
* `lifecycle/` — dimension lifecycle state machine
  (OPEN→CLOSING→EVACUATING→LOCKED→PREFLIGHT→RESET_READY→RESETTING→BOOTING→
  VALIDATING→OPEN, FAILED + RECOVERY_REQUIRED sinks), atomic persistence outside
  the world dir, validation-gated reopening, generation counters, entry policy,
  evacuation planning/service with disconnect handling.
* `audit/` — append-only rotated JSONL evidence log (refusals included).
* `reset/` v2 — checksummed/expiring/generation-bound authorization artifacts,
  single-use ledger with supersede-on-reissue, persistent reset lock + OS flock,
  crash-safe phase journal, offline VerifyAuthCli / journal / ledger CLIs,
  dry-run engine driving the real pipeline with stubbed destruction,
  QualificationStore for qualification vs current fingerprints.
* `scripts/production/` — guarded whole-dimension executor + verified rollback.
* `core/StartupGate` — boot-time cross-check of lifecycle vs journal.

## Initial assessment

`goal-03-initial-assessment.md` (committed before implementation): 5 CRITICAL,
8 HIGH, 8 MEDIUM, 4 LOW findings; strengths preserved; plan derived from code.

## Plan evolution

Recorded in commit history + final audit: rehearsal-driven fixes for baseline
file resolution, sector/lifecycle sync, fingerprint export parsing, canonical
journal ordering, offline ledger consumption and scope enforcement. Details in
`goal-03-final-audit.md`.

## Commit history

30 commits (`git log master..HEAD --oneline`): 1 assessment doc, env×3+2,
lifecycle×3+1, recovery×2, audit×1, reset auth×5+idempotency+lock+dry-run+journal+cli,
backup×1, commands×2, scripts×1, docs×1, evidence×1, fix rounds×3.

## Tests & build

* Before Goal 03: **82 passed**.
* After: **224 passed / 0 failed** (+142), full `./gradlew test build` green.

## Production dry-run

PASS — `evidence/goal-03/rehearsal-05-dryrun-would-reset.txt`:
full pipeline WOULD RESET with per-step PASS/WARN and simulated artifact;
refusal variant captured in `rehearsal-02/-04` (stale baseline, wrong state).

## Production-like reset rehearsal

PASS — cycle 1 (`rehearsal-06..12`):
close → issue auth `71e3d653…` → production signals → stop →
`execute-reset.sh` (98 files backed up 52 MB verified, 96 deleted, confined) →
boot resume BOOTING→VALIDATING → forced regeneration → census match
(6181 BEs / 3469 containers / **480=480 spawners** vs qualification baseline) →
record-validation PASS → **open, generation 0→1**.

Cycle 2 (`13–15`) repeated close→issue `05e5d0ce…`→execute for the rollback leg.

## Rollback rehearsal

PASS — `rehearsal-15..17`: pre-restore hash verification (33 files), confined
restore, post-restore re-verification, boot, census re-comparison identical
(6181/3469/480, entities unchanged), operator recovery acknowledged, reopened.

## Crash / interruption tests

* Live simulation of mid-deletion crash ⇒ `INTERRUPTED_DELETION` ⇒
  RECOVERY_REQUIRED ⇒ manual recover (`adversarial-04/05`).
* False-resume cases covered by StartupRecoveryTest (12 scenarios).
* Unit coverage: corrupt lifecycle/ledger/journal/lock all fail closed.

## Concurrency tests

ResetLockTest (duplicate acquire, stale takeover, foreign release, corrupt lock,
process recreation) + live flock contention refusal (`adversarial-02`).

## Path-safety tests

PathConfinement traversal/segment rejection; backup manifest escape; journal id
sanitization; executor realpath confinement live (`AUTH_OK … target confined`).

## Environment-drift tests

DriftPolicyTest (13 cases) + live QUALIFICATION_MISSING and DRIFT_REFUSE
refusals once production signals were active (`rehearsal-13`).

## Bugs discovered & fixed (regression-tested)

Baseline resolution (F5), lifecycle desync (F6), wrapper parsing (F4),
journal phase ordering (F7), missing ledger consumption (F1), scope enforcement
(F2) — each fixed in dedicated fix commits with unit or live-evidence coverage.

## Final audit

`goal-03-final-audit.md`: no unresolved CRITICAL/HIGH findings;
3 LOW accepted-with-documentation items (F8–F10).

## Unresolved LOW/MEDIUM risks

* LOCKED→OPEN relies on operator validation discipline when used after
  recovery/rollback (documented; every open audited).
* Staging B2 executor retains its Goal 02 shape (sentinel-gated, staging-only).
* pgrep-based running-server heuristic is best-effort (flock/journal are the
  authoritative race guards).

## Production activation prerequisites

1. Operator review of this report + final audit.
2. Record qualification fingerprint after a satisfactory qualification cycle.
3. Create the three PRODUCTION signals (production-readiness.md §Activating).
4. First real player-facing reset remains an explicit operator decision — NOT
   performed during Goal 03.

```text
GOAL 03: PASS
```

Branch: feat/goal-03-production-readiness
Commits created: 30
Tests: 224 passed / 0 failed
Build: PASS
Production dry-run: PASS
Production-like reset rehearsal: PASS
Rollback rehearsal: PASS
Crash recovery: PASS
Concurrent reset protection: PASS
Filesystem confinement: PASS
Environment drift protection: PASS
Final audit: PASS
Production destructive reset default: DISABLED
Production activation: NOT PERFORMED
Critical unresolved risks:
- none CRITICAL/HIGH; documented LOW items F8–F10 in goal-03-final-audit.md
