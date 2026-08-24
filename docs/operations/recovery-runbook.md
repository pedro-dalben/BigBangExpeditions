# Recovery Runbook (crash at any boundary)

The sector registry is written outside the world and survives every crash
point, so the system always knows its position in the lifecycle:

| Crash point | Observable state | Required action |
|-------------|------------------|-----------------|
| after backup, before delete | registry: RESET_PLANNED; backup exists; regions intact | re-run executor or discard plan |
| after delete, before restart | registry: RESETTING | restart → begin-validation → compare → open / rollback |
| after restart, during validation | registry: VALIDATING | finish compare → open / rollback |
| validation failed | registry: FAILED + failureReason | operator review → LOCKED → re-plan or rollback |

**Verified by kill -9 test:** a server killed mid-boot after deletion left
status RESETTING persisted across restart; recovery completed through
`begin-validation` → compare (`spawnerCount 480 unchanged`) → `open`.

Rule: recovery never deletes more data to "repair" uncertainty. Uncertainty
resolves to rollback (restore verified bytes) or FAILED + human decision.
