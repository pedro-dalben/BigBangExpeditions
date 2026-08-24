# Production Readiness — Operator Brief

Status: Goal 03.

## What is enabled where

| Capability | STAGING | PRODUCTION_DRY_RUN | PRODUCTION |
|---|---|---|---|
| Full decision pipeline (preflight, drift, auth simulation) | yes | yes | yes |
| Dry-run report (`/expedition lifecycle dryrun`) | yes | yes | yes |
| Issue authorization artifact | sector-scope only | issues but executor refuses | yes |
| Offline destructive execution | REFUSED | REFUSED | requires signals below |
| Rollback | staging scripts | REFUSED | production scripts |

## Activating PRODUCTION (deliberate, three artifacts)

1. `config/bigbangexpeditions/environment.properties`:
   ```properties
   environment=production
   ```
2. Start the server once in any env; run `/expedition lifecycle dryrun` — this
   exports `config/bigbangexpeditions/current-fingerprint.json`.
3. Copy the 12-char `sha256` prefix from that file into
   `config/bigbangexpeditions/production.enabled`.

Until ALL THREE agree, every destructive path refuses. Default installs are
STAGING and can never destroy anything.

## Health inspection

* `/expedition lifecycle health` — playersMayEnter, lock state, last
  operation journal phase, pending authorizations, last validation result,
  backup availability.
* `/expedition lifecycle status` — full record incl. recent transitions.
* `/expedition doctor`, `/expedition dimension status` — unchanged diagnostics.

## Permissions (Goal 03 split)

* level **2**: read-only (`status`, `health`, `dryrun`, doctor/dimension).
* level **3**: mutations (`close`, `abort-close`, `open`, `begin-validation`,
  `recover`, `issue-authorization`).
* level **4**/console+filesystem: the offline scripts themselves (server file
  access is inherently root-level; no in-game command destroys anything).

## Qualification & drift

Record the fingerprint your reset pipeline was qualified against:

```
/expedition lifecycle dryrun     # after a good qualification cycle
cp config/bigbangexpeditions/current-fingerprint.json \
   config/bigbangexpeditions/qualification-fingerprint.json
```

Drift verdicts on later authorizations:
* ALLOW — proceed.
* WARN — proceed with warning (e.g. OPAC/Lootr/Hordes minor updates, new mods).
* REQUIRE_REVALIDATION — authorization refused; requalify (LC update, BBE
  upgrade, loot-policy content change, LC profile change).
* REFUSE — installation identity changed (MC/Forge/seed/dimension/mod removed);
  never execute against old evidence.

## Storage sizing

Backups ≈ 2× the expedition dimension folder + 10 MB headroom; the executor
refuses when unavailable. Retention keeps the newest verified backups and
never deletes the newest verified rollback point.

## Upgrade notes

From Goal 02-era installs: existing `sectors.json`, baselines, plans remain
valid staging tooling. Production lifecycle starts at OPEN with generation 0;
no migration of world data occurs. See release-process.md for the artifact
hashing procedure.
