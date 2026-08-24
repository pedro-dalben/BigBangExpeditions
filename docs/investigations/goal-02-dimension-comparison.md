# B1 vs B2 — Whole-Dimension vs Sector Reset (Goal 02 Phase 20)

**Evidence base:** cycles 1–11 on sector b04 (region r.4.4), boundary
variance analysis, SavedData inventory, executor timings.

## Measured comparison

| Criterion | B1 whole-dimension reset | B2 sector reset (implemented) |
|---|---|---|
| Corruption risk | Low: delete entire `world/dimensions/bigbangexpeditions/expedition/` tree incl. per-dim `data/` | Higher surface: must reason about neighbor regions, per-dim data left behind |
| Implementation complexity | Simpler destructive step; same guard chain minus region derivation | Region derivation + confinement + manifest file lists (implemented, working) |
| Player disruption | Everyone in expedition teleported out / world unavailable | Only players inside one 512×512 sector affected |
| Regeneration time (measured) | Full dim ≈ sum of all sectors; ~150 s settle observed for ONE region → scales linearly | ~150 s settle per region (measured); parallelizable by player demand |
| Disk usage | Whole dim replaced; single backup set | Per-sector backups (~10–25 MB/region) |
| LC determinism | **Strict**: no persistent neighbors ⇒ no cross-region variance | Boundary variance observed when neighbors persist (cycle-003) |
| SavedData cleanup | Dimension-scoped `data/*.dat` deleted together with the dimension — clean | Global SavedData still needs position-aware reasoning (random_sequences) |
| Seam risk | None internal | Real (counts prove it; visual check pending) |
| Operational burden | One command, longer window, affects all explorers | Frequent small windows; more plans/backups to manage |

## Reading

* B2 works mechanically and is safe under the guard chain, but its
  determinism guarantee degrades at sector boundaries while any neighbor
  region persists. Making B2 strict requires either 2×2+ mega-sectors with
  full-neighbor deletion or tolerating documented deltas.
* B1's destructive simplicity plus clean dimension-scoped SavedData semantics
  give the strongest guarantees for a *dedicated renewable dimension*, which
  is exactly what architecture B provides.

## Decision

**B3 hybrid, B1-shaped:** the expedition dimension is regenerated as a whole
(B1 mechanism) for resets; sectors remain as diagnostic/probe/baseline
groupings (Goal 01 lineage) but are NOT the destructive unit in production.
The implemented B2 executor stays validated as staging tooling and as the
fallback if future evidence shows whole-dimension regeneration cost is
unacceptable.

This is NOT "choose B2 because sectors exist": the measured seam variance is
the deciding factor against production sector-reset.
