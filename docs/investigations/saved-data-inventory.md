# SavedData Inventory — Staging World (Goal 02 Phase 10)

**Source:** live staging world `.staging/server/world` (seed `bigbangexpeditions-goal02`)
**Collected:** 2026-08-24, after expedition dimension generation + OPAC selftest
**Method:** `scripts/inspect-world-data.sh` + filesystem enumeration + NBT string
inspection (`strings`) + mod jar verification.

## Classes

```text
SAFE_GLOBAL        world-scoped, no position/dimension coupling; survives resets
PLAYER_PROGRESS    keyed by player identity; must never be touched by resets
DIMENSION_SCOPED   lives under world/dimensions/<dim>/data; dies with dimension
POSITION_SCOPED    keyed by block/chunk position; stale entries possible after reset
NETWORK_SCOPED     graph state spanning positions (RS networks etc.)
UNKNOWN            unclassified — BLOCKS automatic reset qualification
```

## Global `world/data/*`

| Entry | Size | Class | Reset-critical? | Evidence / notes |
|-------|------|-------|-----------------|------------------|
| `capabilities.dat` | 121B | SAFE_GLOBAL | no | Forge capability registry stub |
| `customportalapi.dat` | 63B | POSITION_SCOPED | **verify before abyss changes** | CustomPortalAPI portal links (abyss portal uses it); expedition dim has no portals |
| `InControlData.dat` | 102B | SAFE_GLOBAL | no | InControl spawn rules global flag |
| `raids.dat` | 87B | POSITION_SCOPED | no | vanilla raid waves; expedition has no villages; stale entries harmless |
| `random_sequences.dat` | 112B | POSITION_SCOPED | **YES — scrub required** | vanilla 1.20 loot-table seed sequences keyed by position. Stale expedition-sector entries must be removed by offline scrub before validation; otherwise regenerated chests reuse old seeds (determinism aid but also a dupe-analysis hazard). Goal 00 T30 confirmed present. |
| `ritchiesprojectilelib_chunk_manager.dat` | 64B | DIMENSION_SCOPED-ish | low risk | chunk-tracked projectiles; empty at capture |
| `scoreboard.dat` | 249B | PLAYER_PROGRESS | never touch | objectives only at capture |
| `seasons.dat` | 68B | SAFE_GLOBAL | no | global season time |
| `starterkit/tracking.json` | json | PLAYER_PROGRESS | never touch | `{singleplayer:{}, multiplayer:{}}` at capture |
| `openpartiesandclaims/` (dir) | — | PLAYER_PROGRESS + NETWORK_SCOPED | **gate, don't delete** | `player-claims/*.nbt`, `parties/`, `server-info.nbt`. Expedition is unclaimable (Phase 6), so claim entries inside expedition bounds must be absent — preflight asserts via API. NOTE: selftest UUID file exists with an EMPTY dimension entry structure after unclaim cleanup (verified `get()==null`). |

## Dimension-scoped data (`world/dimensions/bigbangexpeditions/expedition/data/`)

| Entry | Class | Notes |
|-------|-------|-------|
| `capabilities.dat` | DIMENSION_SCOPED | per-level capability stubs |
| `chunks.dat` | DIMENSION_SCOPED | Forge forced-chunk bookkeeping (forceload tickets persist here!) |
| `raids.dat` | DIMENSION_SCOPED | empty at capture |
| `ritchiesprojectilelib_chunk_manager.dat` | DIMENSION_SCOPED | empty at capture |
| `seasons.dat` | DIMENSION_SCOPED | per-level season state |

**Key structural finding:** each dimension keeps its own `data/` directory.
A *whole-expedition-dimension* regeneration (Model B1) that deletes the
entire `world/dimensions/bigbangexpeditions/expedition/` tree also removes
these files cleanly. A *sector* reset (B2) cannot touch them without a
position-aware NBT scrub → B1 has materially simpler SavedData semantics.
This is direct Phase 20 comparison input.

## Mods of special concern (Goal 00 list)

| Mod | Found in staging world? | Classification |
|-----|-------------------------|----------------|
| Lootr | **NO SavedData present** — `config/lootr-common.toml: disable = true`; jar contains DataStorage/ChestData classes but they stay inactive | T10/T11 revert to vanilla-loot duplication analysis |
| FTB Teams | not in staging mods subset? — pack HAS ftb-teams; no `world/data` entry observed yet | re-check after team creation in UAT |
| Refined Storage | no `refinedstorage*.dat` yet (no networks placed) | NETWORK_SCOPED when present; preflight must refuse on any RS BE in sector |
| SecurityCraft | absent | same |
| Create | absent globally; 6 Create BEs exist as WORLDGEN content in expedition r.4.4 | baseline-compare handles |
| IE | absent | same |
| The Hordes | no world/data entry; hordes config via KubeJS/datapacks | SAFE_GLOBAL behavior |
| DeceasedCraft customs | customportalapi (above) | verify scope |

## UNKNOWN count: 0

Every entry observed at capture time is classified above. This satisfies
acceptance #8 **for this snapshot**; the preflight engine (Phase 13) will
re-run this inventory automatically and refuse on any new UNKNOWN.

## Actions feeding later phases

1. Offline scrub step (Phase 15) must remove `random_sequences.dat` entries
   whose positions fall inside the reset region set (position-keyed map).
2. Preflight validator: refuse when `openpartiesandclaims/player-claims`
   contains any non-empty expedition-bound claim (belt-and-braces with API).
3. B1 comparison memo: dimension-folder deletion simplifies SavedData to a
   single global scrub target (`random_sequences.dat`).
