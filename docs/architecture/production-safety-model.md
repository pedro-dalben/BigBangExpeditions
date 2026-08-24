# Production Safety Model

Status: Goal 03.

## Environments

`env/EnvironmentProfile`: **STAGING** (default), **PRODUCTION_DRY_RUN**,
**PRODUCTION**. Resolution (`EnvironmentConfig.resolve`) is fail-closed:

1. Missing/blank/unknown config ⇒ STAGING (with a notice).
2. `environment=production` additionally requires
   `config/bigbangexpeditions/production.enabled` whose trimmed content equals
   the 12-hex-char short hash of the CURRENT installation fingerprint.
3. A missing fingerprint makes PRODUCTION activation impossible — two
   independent, install-bound signals are always required, and a single
   flipped boolean can never enable destruction.

Only `PRODUCTION.destructiveAllowed()` is true; the executor scripts refuse
unless both signals hold (re-checked offline, not trusted from the mod).

## Independent barriers between admin intent and filesystem destruction

```text
admin intent
 └─(1) environment.properties: environment=production
 └─(2) production.enabled == shortHash(currentInstallFingerprint)
 └─(3) lifecycle machine: OPEN→…→RESET_READY with validated transitions
 └─(4) full preflight engine (players, claims, additions, loot, SavedData)
 └─(5) drift policy vs qualification fingerprint (ALLOW/WARN/REVALIDATE/REFUSE)
 └─(6) authorization artifact v2: checksummed, TTL-expiring, generation-bound
 └─(7) ledger single-use consumption (replay impossible)
 └─(8) offline VerifyAuthCli re-checks 6+7 + current fingerprint export
 └─(9) OS flock + persistent ResetLock (no concurrent execution, ever)
 └─(10) phase journal: DELETION_INTENT precedes any rm; confined targets only
```

## Authorization artifacts

`reset/ResetAuthorization` (schemaVersion 2):

* deterministic JSON → sha-256 `authChecksum`; tamper-evident;
* binds dimension, scope (`DIMENSION` = production shape per the Goal 02 B3
  decision; sector bounds still validated at issue time), lifecycle
  `generationAtIssue`, full `InstallFingerprint`, creator;
* `expiresAtEpochMs` (default TTL 6 h) — stale plans die on their own;
* single-use via `AuthorizationLedger` (ISSUED→CONSUMED|REVOKED, atomic);
* re-issuing supersedes prior ISSUED artifacts for the same sector
  (`supersedePriorIssued`) so exactly one live authorization exists.

The offline verifier is ONE Java implementation (`VerifyAuthCli`); shell code
never re-implements canonicalization (Goal 02 divergence risk removed).

## Filesystem confinement

* Deletion targets derive ONLY from the hardcoded dimension id through
  `PathConfinement.expeditionDimensionDir` / region-name regexes.
* The executor re-derives the level name from `server.properties`, realpaths
  the dimension dir and refuses when it escapes `<level>/`.
* Backup manifests reject paths escaping the backup directory; journal ids are
  strictly sanitized. Path traversal or wrong-world deletion is a release
  blocker by definition (see goal-03-final-audit.md for adversarial results).

## Backups

`backup/BackupManifest` (formatVersion 1): per-file sha-256+size, totals,
environment context; checksummed itself; `BackupVerifier` fails on missing /
resized / rehashed files, zero-file backups, or traversal paths. Retention
(`BackupRetentionPolicy`) NEVER deletes the newest verified backup — the sole
rollback point is protected by construction.

## Failure semantics (non-negotiables mapping)

| Rule | Enforcement |
|---|---|
| Destructive disabled by default | env resolution defaults STAGING; scripts double-check |
| Unknown destructive state fails closed | StartupGate → RECOVERY_REQUIRED |
| Validation failure prevents reopening | LifecycleService validation gate |
| Backup failure prevents reset | disk guard + verified manifest before DELETION_INTENT |
| Wrong fingerprint blocks execution | VerifyAuthCli FINGERPRINT_MISMATCH |
| Plans never silently adapt | artifact immutability (checksum) + expiry |
| No concurrent resets | flock + persistent lock + journal active-op detection |
| Recovery prefers manual intervention | RECOVERY_REQUIRED leaves only via operator command |

## Audit

Every accepted AND refused operation writes an `audit/AuditEvent` line to the
append-only rotated JSONL log (`bigbangexpeditions/audit/`): actor, action,
subject, state transition, outcome, reason, duration. Audit write failures are
loud exceptions, never silent drops.
