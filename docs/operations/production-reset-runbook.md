# Production Reset Runbook (Goal 03)

Prereqs: read production-readiness.md; PRODUCTION signals active; a
qualification fingerprint recorded.

## Full controlled cycle

```text
 1. /expedition lifecycle dryrun          # expect WOULD RESET; read every step
 2. /expedition lifecycle close           # OPEN→CLOSING→EVACUATING→LOCKED
 3. /expedition lifecycle issue-authorization
                                          # LOCKED→PREFLIGHT→RESET_READY,
                                          # writes authorizations/<authId>.json
 4. /expedition lifecycle health          # pendingAuthorizations: exactly 1
 5. stop the server                       # scripts/staging/stop.sh in staging
 6. scripts/production/execute-reset.sh <authId>
 7. start the server                      # startup gate resumes BOOTING→VALIDATING
 8. validate: /expedition sector probe/compare against the baseline census
 9. PASS: /expedition lifecycle open      # generation increments, expedition OPEN
    FAIL: /expedition lifecycle begin-validation already FAILED path → see recovery-runbook.md
```

## What step 6 does (and refuses)

Refuses unless: environment=production + matching `production.enabled` ack +
no server process + flock acquired + persistent lock acquired +
VerifyAuthCli passes (ledger ISSUED, checksum valid, not expired, dimension
allowed, current fingerprint export equals artifact fingerprint) + disk space
≥ 2× dimension size + no pre-existing backup for the authId.

Then, in journal order:

```text
AUTH_VERIFIED → BACKUP_START → (copy+manifest) BACKUP_DONE
             → DELETION_INTENT → (confined delete of dimension dir contents)
             DELETION_DONE → lifecycle RESETTING → FINALIZED
```

Any interruption leaves the journal mid-sequence; the next boot fails closed
into RECOVERY_REQUIRED — never a guess.

## Idempotency

* re-run `issue-authorization`: supersedes the previous ISSUED artifact;
* run `execute-reset.sh` twice: second refuses (ledger CONSUMED / backup exists);
* uncertain whether step 6 ran? check `/expedition lifecycle health`
  (`lastOperation`) BEFORE re-running anything.

## Emergency stop during step 6

Ctrl-C / kill is safe by design: phases complete atomically. The journal shows
the exact last completed phase. Resume only per recovery-runbook.md.
