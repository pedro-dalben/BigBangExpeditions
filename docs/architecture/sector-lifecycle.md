# Sector Lifecycle

States, transitions and persistence are implemented in
`com.bigbangcraft.expeditions.sector` (SectorState / SectorRecord /
SectorRegistry) with an explicit transition table — see
reset-architecture.md for the diagram.

## Registry storage decision

`<server>/bigbangexpeditions/sectors.json` — deliberately **outside** the
world directory:

* survives whole-dimension regeneration experiments;
* atomic writes (temp + ATOMIC_MOVE);
* TreeMap ordering → stable listings/diffs.

## Commands

```text
/expedition sector list
/expedition sector status <id>
/expedition sector create <id> <regionX> <regionZ> [1|2|4]
/expedition sector lock <id>            # OPEN -> LOCKED
/expedition sector open <id>            # VALIDATING/LOCKED/DEPLETED -> OPEN
/expedition sector attach-baseline <id> "<baselineLabel>"
/expedition sector reset-plan <id>      # LOCKED -> RESET_PLANNED (+ manifest)
/expedition sector begin-validation <id># RESETTING -> VALIDATING
```

Refusals always state the reason and current state, e.g.
`REFUSED: illegal transition OPEN -> RESETTING [current=OPEN]`.

## Sector addressing

Region units only. `create b04 4 4 1` = region (4,4), size 1×1 regions =
chunks 128..159 × 128..159 = blocks 2048..2559. Region alignment makes one
sector equal a whole set of `.mca` files — the destructive path never slices
a region file.

## Side effects encoded in transitions

* `VALIDATING → OPEN`: increments `resetCount`, stamps `lastResetAt`,
  records validation result.
* `→ RESET_PLANNED`: clears any previous `failureReason`.
* `FAILED` requires operator review through `LOCKED`; there is no direct
  `FAILED → OPEN`.
