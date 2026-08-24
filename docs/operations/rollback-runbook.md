# Rollback Runbook (Goal 03)

Rollback = restore the expedition dimension from the verified backup created
for one specific authorization, then re-validate before reopening.

## Answer first

* Which reset? The authId you pass — every backup lives at
  `bigbangexpeditions/backups/<authId>/` with `backup-manifest.json` and a copy
  of the signed authorization.
* Has it been altered? Verified by sha-256 over every file AND the manifest's
  own checksum BEFORE anything is restored.
* Is the server safe? Rollback refuses while any Minecraft process runs or the
  reset lock is held.
* Post-state: journal records ROLLBACK_DONE; run validation; only PASS may open.

## Procedure

```text
1. stop the server
2. scripts/production/rollback-reset.sh <authId>
3. start the server
4. validate (probe + baseline compare)
5. /expedition lifecycle open   # requires recorded PASS for current cycle
```

## Guarantees

* pre-restore verification: missing/resized/rehashed file ⇒ REFUSED;
* restore is confined to the expedition dimension dir;
* post-restore re-verification counts failures; non-zero ⇒ explicit failure;
* the backup directory itself is never modified by rollback.

## When no valid backup exists

There is NO rollback point. Do NOT improvise destructive repair; follow
recovery-runbook.md and treat the dimension as lost until regenerated from a
freshly issued authorization (the same pipeline as a normal reset).
