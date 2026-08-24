# Reset Runbook (STAGING ONLY)

> **PRODUCTION RESET = DISABLED** (`allowDestructiveReset=false`).
> The executor additionally refuses any non-staging environment (sentinel).

## Preconditions

1. Sector exists and is captured/validated: `/expedition sector status <id>`
2. Full-coverage baseline captured AFTER settle
   (`forceload` all four 256×256-block quadrants, wait ≥90 s, `save-all flush`,
   then `/expedition sector baseline <label> …`, then
   `/expedition sector attach-baseline <id> <label>`).
3. No players inside; no claims (Phase 6 selftest PASS).

## Flow

```text
/expedition sector lock <id>
/expedition sector reset-plan <id>        # writes checksummed manifest
bash scripts/staging/stop.sh
bash scripts/staging/execute-reset.sh <planId>
bash scripts/staging/start.sh
/expedition sector begin-validation <id>  # RESETTING -> VALIDATING
# forceload full region, settle, save-all, then compare vs settled reference:
/expedition sector compare <reference.json> <label> bigbangexpeditions:expedition <bounds>
/expedition sector open <id>              # VALIDATING -> OPEN on PASS
```

On FAIL: keep the backup, investigate, then either
`rollback-reset.sh <planId>` or re-plan after fixing.

## Executor guard chain (all mandatory)

| # | Guard | Refusal exit |
|---|-------|--------------|
| 1 | staging sentinel present | 42 |
| 2 | server stopped | 43 |
| 3 | plan id well-formed / plan exists | 45/46 |
| 4 | manifest structure + sha-256 checksum valid | 47 |
| 5 | dimension == bigbangexpeditions:expedition | 47 |
| 6 | region files derived from bounds == manifest list | 48 |
| 7 | realpath confinement under expedition dim dir | 48 |
| 8 | disk space ≥ 2× dim size + margin | 49 |
| 9 | backup created + SHA256SUMS self-verified | 51 |

Deletion targets exactly `region/r.X.Z.mca`, `entities/r.X.Z.mca`,
`poi/r.X.Z.mca` from the validated set — nothing else. After deletion the
executor marks the sector RESETTING in `bigbangexpeditions/sectors.json`.

## SavedData policy during reset

Unknown SavedData is never mutated to make tests pass. `random_sequences.dat`
entries are position-keyed; stale entries inside a reset sector are left in
place deliberately (they only pin loot seeds for regenerated containers —
determinism-friendly). Any NEW unknown owner blocks preflight.
