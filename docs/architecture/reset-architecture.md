# Reset Architecture (Goal 02)

## Principles

1. **Never destroy player work.** Resets are only allowed when a fail-closed
   pipeline proves the sector contains nothing beyond regenerable content.
2. **Plan, then execute.** Deletion consumes a checksummed manifest, never a
   live command intention.
3. **Paths are derived, never provided.** The destructive path sees region
   coordinates from a validated manifest; `PathConfinement` re-derives and
   confines every filesystem target at run time.
4. **Offline deletion.** Region files are removed with the server stopped —
   never from the live process (Goal 00 T07 predicted corruption; online
   reset remains REFUSED by design).
5. **Immutable backup before any deletion**, SHA-256 self-verified.
6. **Rollback must be proven**, not assumed.

## Component map

```text
command layer            persistence              offline executor
/expedition sector …  -> SectorRegistry      +    scripts/staging/
  lock/reset-plan/       (sectors.json outside     execute-reset.sh
  begin-validation/open   the world dir)           rollback-reset.sh
        |                     |                        |
        v                     v                        v
ResetPlanService         SectorStateMachine       guard chain (9 checks)
 -> manifest+checksum     explicit transitions     derived targets only
```

## State machine

`OPEN → LOCKED → RESET_PLANNED → RESETTING → VALIDATING → OPEN`
with side exits: `VALIDATING→FAILED`, `RESETTING→FAILED`,
`FAILED→LOCKED` (review), `LOCKED→OPEN`, `DEPLETED/COOLDOWN` optional holds.
Illegal transitions (e.g. `OPEN→RESETTING`) are rejected by code and tests.

## Determinism findings (evidence-driven)

- Spawner counts across ≥10 full-coverage cycles: **exactly stable** (480).
- Small boundary variance exists for sector resets when neighbor regions
  persist (cross-region LC structures; see cycle-003 evidence) — this is the
  core B2 seam risk and pushes the architecture toward whole-dimension reset
  (B1/B3) for strict determinism.
- Consecutive probes of an undisturbed settled world are identical;
  premature capture (LC GlobalTodo not drained) produces false deltas —
  always settle ≥90 s after generation before baselining.
