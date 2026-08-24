# Sector Topology Evaluation (Goal 02 Phase 9)

**Method:** R1 (1×1 region) measured live across the determinism campaign;
R2/R4 extrapolated from per-region costs and structural constraints.
Candidate sizes implemented in `SectorTopology.Size` (R1/R2/R4).

## Measured per-region numbers (expedition r.4.4, city district)

| Metric | Value |
|--------|-------|
| Chunks | 1024 |
| Settle time to stable census | ~150 s (LC GlobalTodo drain dominates) |
| Offline delete + verified backup | < 2 s file ops |
| Backup size (region+entities+poi) | ~8–25 MB/region depending on content density |
| Structure classes observed | bank, police, hotel/residential, medical(polyclinic), offices, gas station, workshops |

## Extrapolation

| Size | Regions | Chunks | Reset window (dominated by regen settle) | Boundary exposure |
|------|---------|--------|------------------------------------------|-------------------|
| R1   | 1       | 1024   | ~2–3 min player-visible regeneration     | 4 edges           |
| R2   | 4       | 4096   | ~6–12 min                                | 4 edges (longer)  |
| R4   | 16      | 16384  | ~25–50 min                               | 4 edges (longest) |

Larger sectors do NOT reduce boundary count proportionally: a reset always
exposes its perimeter to persistent neighbors. Perimeter-to-area improves,
but LC cross-border structures scale with city layout, not sector shape.

## Decision

Sector size is **moot for production resets** under the recommended B3
(B1-shaped) architecture: whole-dimension regeneration eliminates internal
boundaries entirely, and sectors remain diagnostic/probe groupings where
R1 is preferred because:

* baselines stay small and reviewable (~10 KB JSON);
* probes map 1:1 onto region files (destructive-path clarity);
* campaign iteration cost stays low.

R2/R4 remain supported in code and were validated for bounds derivation
(unit tests); they are available if future evidence shows whole-dimension
regeneration windows are operationally unacceptable.
