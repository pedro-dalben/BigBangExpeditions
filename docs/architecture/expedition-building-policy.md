# Expedition Building Policy — Decision (Goal 02 Phase 7)

**Status:** DECIDED — Policy A with structural safety enforcement
**Date:** 2026-08-24

## Options evaluated

| Policy | Description | Assessment |
|--------|-------------|------------|
| A | Allow building; warn players territory is non-persistent | Chosen |
| B | Prevent persistent/storage blocks | Rejected |
| C | Prevent virtually all construction except whitelisted temp blocks | Rejected |

## Why A

1. **Reset safety does not come from policing placement.** With 300+ mods
   (Create multiblocks, IE, RS, SecurityCraft, furniture sets), reliable
   block-level interception is not implementable without deep per-mod hooks
   and would still miss entity-borne or item-form storage. Any blacklist is
   incomplete by construction — an UNKNOWN gap becomes silent data loss,
   which violates the governing principle harder than convenience ever could.

2. **The destructive path is already fail-closed.** Goal 02's reset pipeline
   refuses unless the sector matches its pre-reset state:
   - preflight requires zero players inside;
   - preflight compares live probe against the captured baseline
     (`BaselineService.compare`) — player-added containers, machines or
     unknown block entities appear as deltas and force
     `RESET REFUSED`;
   - OPAC claims/forceloads in the sector refuse the reset outright.
   
   Under A, a reset can therefore only ever destroy *regenerable Lost Cities
   content*, never player work — the invariant is checked at the moment it
   matters instead of being assumed at placement time.

3. **B/C break expedition gameplay** (looting needs container interaction;
   temporary camps need crafting/storage) while adding permanent complexity.

## Player-facing rule

```text
Expedition territory is non-persistent.
Everything you place or store here may vanish when a sector resets.
Claims are disabled here. Do not build anything you care about.
```

## Enforcement points

| Layer | Mechanism | Status |
|-------|-----------|--------|
| Claims | OPAC `claimableDimensionsList` + runtime selftest | DONE (Phase 6 evidence) |
| Reset-time | Preflight baseline-diff validators (Phase 13) | PLANNED |
| Respawn | `/expedition leave` fallback + post-reset validation flags beds | PLANNED (bed_works=true limitation, see goal-02-dimension-type-issue.md) |
| Messaging | enter command output warns non-persistence | DONE |

## Known residual risks

- Players who ignore warnings lose items left inside a reset sector that had
  NO baseline delta protection (e.g., dropped items — entities folder is
  deleted). Mitigation: preflight refuses when entities exist in bounds.
- Bed respawn inside expedition survives until dimension-type issue is
  root-caused; tracked in goal-02-dimension-type-issue.md.
