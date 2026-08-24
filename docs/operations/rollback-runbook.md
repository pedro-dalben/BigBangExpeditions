# Rollback Runbook

Restores the exact pre-reset bytes of `region/`, `entities/`, `poi/` for one
executed plan.

```bash
bash scripts/staging/stop.sh
bash scripts/staging/rollback-reset.sh <planId>
bash scripts/staging/start.sh
/expedition sector status <id>       # confirm state; re-validate if needed
```

## Guarantees

- Refuses without staging sentinel (exit 42) or running server.
- Verifies EVERY hash in the backup's `SHA256SUMS` **before** touching the
  world — corrupted backup aborts with exit 54 and changes nothing.
- Restores only files recorded in that backup.

## Verification performed live

Backup `cc8766d2…` restored, then:

```text
backup  region/r.4.4.mca 0b00d8b00ec4852c705e3b74404a9a87e1815dc309a46048e4ec5d587b7ad0bd
restored region/r.4.4.mca 0b00d8b00ec4852c705e3b74404a9a87e1815dc309a46048e4ec5d587b7ad0bd
```
