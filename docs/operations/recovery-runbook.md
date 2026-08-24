# Recovery Runbook (Goal 03)

Recovery is MANUAL-FIRST. The system never speculatively repairs destructive
operations; it detects, refuses, and waits for an operator.

## How interruptions surface

At every boot the startup gate cross-checks `lifecycle.json` against the
operation journal and can force `RECOVERY_REQUIRED`:

| Finding | Meaning |
|---|---|
| DESTRUCTIVE_STATE_WITHOUT_JOURNAL | RESETTING/BOOTING persisted but no journal — unknown destructive state |
| INTERRUPTED_DELETION | crash after DELETION_INTENT before DELETION_DONE — dimension may be half-deleted |
| STALE_OPERATION | active journal in a preparation state — executor died early |
| OPEN_WITH_UNFINISHED_OPERATION | lifecycle finalize missing |
| BOOT_WITHOUT_COMPLETED_DELETION | restart happened without proof of completed deletion |

Corrupt `lifecycle.json`, corrupt journal files, or unreadable directories are
themselves fail-closed: RECOVERY_REQUIRED (or a CRITICAL log demanding manual
repair if even that cannot persist).

## Operator procedure

```text
1. /expedition lifecycle health        # read lastOperation + failureReason
2. inspect bigbangexpeditions/journal/<authId>.op.json   # exact phases done
3. decide per table below
4. /expedition lifecycle recover "<what you found and did>"
5. re-run pipeline from LOCKED (close → dryrun → issue-authorization …)
```

### Decisions

| Journal shows | Dimension state | Action |
|---|---|---|
| < DELETION_INTENT | untouched | nothing destructive happened; recover; re-run executor with NEW authorization (old one: revoke or let expire) |
| DELETION_INTENT..DELETION_DONE missing | possibly half-deleted | prefer ROLLBACK from backup (`rollback-reset.sh <authId>`); if no backup, regenerate via fresh authorization |
| DELETION_DONE present | deletion complete | just start server; startup gate resumes BOOTING→VALIDATING automatically |
| FINALIZED present | operation complete | recover only to clear a stuck bookkeeping state |

## Rules

* Never delete journal/lock/lifecycle files by hand while any server runs.
* A corrupt reset lock must be inspected, then removed manually BEFORE the next
  acquire attempt will succeed (fail-closed forever until then).
* After ANY recovery, run validation and require PASS before reopening.
