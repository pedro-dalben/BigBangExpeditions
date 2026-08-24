# Goal 03 Final Audit — Adversarial Review

Date: 2026-08-24. Reviewer stance: attempt to break every destructive path as
if the feature were written by someone else. Findings fixed during this audit
are marked FIXED; residual risks are explicitly listed.

## Destructive paths reviewed

1. `scripts/production/execute-reset.sh` — guard chain, journal, backup, delete.
2. `scripts/production/rollback-reset.sh` — restore + re-verification.
3. `scripts/staging/execute-reset.sh` (Goal 02 tooling) — retained for staging.
4. `VerifyAuthCli` / `OperationJournalCli` / `AuthorizationLedgerCli`.
5. In-game lifecycle commands (no destruction possible in-game — verified:
   no file deletion code reachable from any command class).

## Findings

| # | Severity | Finding | Status |
|---|---|---|---|
| F1 | HIGH | Offline executor never CONSUMED the ledger entry; replay was only blocked incidentally by backup-dir existence. A deleted backup dir would have allowed re-execution of a stale auth. | **FIXED** (`6ef6b2d`): executor consumes ledger after FINALIZED; live refusal of non-ISSUED consume demonstrated (adversarial-02). |
| F2 | HIGH | A SECTOR-scope artifact could drive whole-dimension execution (scope never checked offline). | **FIXED**: VerifyAuthCli `expectedScope=DIMENSION` enforced by production scripts; unit test `sectorScopeArtifactRefusedForDimensionExecution`. |
| F3 | MEDIUM | Backup manifests carried placeholder versions/generation. | **FIXED**: executor derives metadata from the signed authorization JSON. |
| F4 | MEDIUM | `QualificationStore.loadQualification/loadCurrentExported` failed on the exported wrapper format → false DRIFT_REFUSE / CURRENT_FINGERPRINT_UNREADABLE (found live during rehearsals 13/08). | **FIXED** (`f126224`): tolerant parser; regression covered by rehearsal reruns. |
| F5 | MEDIUM | Baseline census lookup missed `<id>_<dim>_<ts>.json` naming ⇒ empty baseline ⇒ spurious PLAYER_ADDITIONS refusals (found live, rehearsal-02). Fail-closed direction, but blocked legitimate flow. | **FIXED**: newest-matching-file resolution. |
| F6 | MEDIUM | Sector registry vs dimension lifecycle desync after close (dry-run LIFECYCLE FAIL, rehearsal-04). | **FIXED**: close/open sync sector state. |
| F7 | MEDIUM | Startup gate rejected a FINALIZED journal with DELETION_DONE (false RECOVERY_REQUIRED, rehearsal-09) and later a post-rollback ROLLBACK_DONE marker. | **FIXED**: canonical phase ordering + durable deletionReached proof (rehearsal-17 confirms resume works). |
| F8 | LOW | `LOCKED→OPEN` does not demand a fresh PASS (needed for legit aborts/post-rollback reopen); relies on operator evidence discipline. Documented in recovery-runbook; audit records every open. | Accepted (documented). |
| F9 | LOW | `require_server_stopped` pgrep pattern is heuristic; PID-file based staging check remains authoritative for that environment. | Accepted (defense-in-depth layering: flock+journal cover races). |
| F10 | LOW | Executor writes `lifecycle.json` directly (python) rather than through LifecycleService — trusted local tooling, atomic write, status-guarded (`RESET_READY→RESETTING` only). | Accepted; noted in architecture doc. |

## Adversarial evidence (live)

* Concurrency: lock held by foreign process ⇒ executor REFUSED
  (`adversarial-02-refusals.txt`).
* Replay: consumed/revoked authorization ⇒ `LEDGER_REVOKED`
  (`adversarial-02`, `adversarial-01-concurrent-lock.txt`).
* Tampering: edited artifact ⇒ `CHECKSUM_INVALID`; original restored ⇒ AUTH_OK
  (`adversarial-03-tamper.txt`).
* Interrupted deletion simulation (journal stops at DELETION_INTENT):
  boot ⇒ `INTERRUPTED_DELETION` ⇒ RECOVERY_REQUIRED; operator recover works
  (`adversarial-04/05`).
* Server running ⇒ REFUSED before any lock/auth work (rehearsal log).
* Wrong scope: unit-tested SCOPE_MISMATCH.
* Traversal/wrong-world: unit tests (PathConfinement, BackupVerifier escape,
  manifest traversal, journal id sanitization, plan id regexes).

## Configuration defaults review

Fresh install without `config/bigbangexpeditions/*`: env=STAGING, destructive
impossible at mod level AND script level (missing signals exit 42–46).
Verified by EnvironmentConfigTest + live refusal before signals existed.

## Verdict

No unresolved CRITICAL/HIGH findings. All fixes carry regression coverage
(unit tests or committed live evidence under `evidence/goal-03/`).
