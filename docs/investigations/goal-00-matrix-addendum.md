# Goal 00 Test Matrix — Live Addendum (Goal 02)

**Status:** addendum only; historical Goal 00 entries above are unchanged.
Live evidence gathered on the Goal 02 staging environment (pinned seed
`bigbangexpeditions-goal02`, expedition dimension r.4.4, Forge 47.4.0).

| Test | Goal 00 status | Live result | Evidence |
|------|----------------|-------------|----------|
| **T02 — Baseline capture** | BLOCKED | **PASS** | `b04base2/base3/c4base…c11base` full-coverage JSON baselines + region sha256 in backups; methodology lesson recorded: baseline requires full-region forceload + ≥90 s settle |
| **T06 — Full offline wipe+scrub (E5)** | BLOCKED | **PARTIAL PASS** | offline delete of region+entities+poi executed 11× (`execute-reset.sh`); city content regenerates; no ghost claims observed (OPAC selftest clean). SavedData *scrub* intentionally limited to policy: random_sequences left in place, no UNKNOWN mutation |
| **T08 — Structure layout determinism** | BLOCKED | **PASS (with boundary caveat)** | settled consecutive captures identical; spawner count 480 stable across 10 resets; small cross-region variance exists when neighbor regions persist (cycle-003) |
| **T09 — Spawner/BE return** | BLOCKED | **PASS** | spawnerCount 480 → 480 across every cycle; BE census stable at full coverage after settle |
| **T11 — Vanilla loot duplication (Lootr disabled)** | BLOCKED | **CONFIRMED RISK / MITIGATED BY POLICY** | Lootr disabled confirmed (`lootr-common.toml disable=true`, no Lootr SavedData); regenerated chests re-roll ⇒ progression items would duplicate on naive reset; mitigated by preflight baseline-delta rule + loot-policy.json classification (research_paper_1..4 sources identified) |
| **T19 — Prohibit building (dimension mode)** | BLOCKED | **PASS** | OPAC `claimableDimensionsList` gate + runtime verification: `isClaimable=false`, validated `tryToClaim → UNCLAIMABLE_DIMENSION`; raw internal claim() bypass documented as mods-only risk |
| **T20 — Boundary seam** | BLOCKED | **OBSERVED** | sector reset with persistent neighbors shows small per-cycle deltas at chest-NBT level (cycle-003; lootr ±1, furnace/campfire swaps) while structure-class markers stayed byte-identical across 5 further resets (soak). Visual inspection still pending (no client) |
| **T23 — Determinism cycles ×3** | BLOCKED | **PASS (×10)** | cycles 2–11 full-coverage: all PASS vs settled reference (spawners exact; BE/container stable) |
| **T29 — Fail-closed validation fails** | BLOCKED | **PASS** | adversarial suite: running server/sentinel missing/tampered checksum/player-inside/wrong state/foreign dim/baseline missing/UNKNOWN SavedData/incomplete scan → all REFUSE (automated PreflightEngineTest + live executor exits 42/43/47) |
| **T30 — Unexpected mod data** | BLOCKED | **PASS (snapshot)** | saved-data-inventory.md: zero UNKNOWN owners at capture; preflight refuses new UNKNOWN automatically |

## Remaining open from Goal 00

* Visual seam inspection (client screenshots) — no display server in this
  environment; count-based evidence provided instead.
* FTB Teams party-claim attempt against the expedition dimension — requires a
  connected client player; API-level personal claim covered.
