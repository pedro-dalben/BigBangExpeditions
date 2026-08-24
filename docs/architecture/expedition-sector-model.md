# Expedition Sector Model — Goal 04 Decision

## What changed

Goal 02 introduced sectors as **physical reset units** (region-aligned probe
targets with their own state machine). Goal 03's production architecture made
whole-dimension regeneration the destructive shape, so physical sector
deletion is no longer part of production.

Goal 04 therefore **repurposes sectors as gameplay districts**:

* navigation identity (`/expedition where`);
* statistical grouping for admin telemetry (`/expedition ops players`
  distribution);
* naming authority via `/expedition sector rename <id> <display name>`
  (persisted `displayName`, max 48 chars, falls back to id).

Districts have **no lifecycle authority**: access and reset decisions are made
exclusively by the dimension lifecycle. The `SectorState` machine and registry
remain exactly as Goal 02/03 built them (staging pipeline, baselines,
preflight) — untouched semantics, no migration needed.

## Naming source of truth

Display names should reflect what Lost Cities actually generates in a region.
Observed structure classes from the Goal 02 campaign (`goal-02-sector-topology.md`):
bank, police, hotel/residential, medical (polyclinic), offices, gas station,
workshops. Suggested district vocabulary:

```text
A-01 Setor Residencial      A-02 Setor Médico
A-03 Centro                 B-01 Setor Industrial
```

Operators assign names per world; nothing in code invents building content.

## Lookup mechanics

`SectorLocator.locate(sectors, dimension, chunkX, chunkZ)` — pure, O(n) over
registered sectors, first match wins on overlap. Used by:

* `/expedition where` (player-facing, localized "unmapped area" fallback);
* `/expedition ops players` (per-player district column + distribution map).

Not used per-tick anywhere; performance impact negligible.

## Reset interaction

Preflight still probes the registered sector bounds as a proxy for the whole
dimension (SCOPE_DIMENSION), so keeping one registered district that covers
the expedition area remains an operational requirement for authorization —
unchanged from Goal 03 runbooks.
