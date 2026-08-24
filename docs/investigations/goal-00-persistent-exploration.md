# Goal 00 — Persistent Exploration Technical Investigation

**Project:** BigBangExpeditions (Forge 1.20.1)  
**Pack:** DeceasedCraft - Urban Zombie Apocalypse (CurseForge Instance)  
**Date:** 2026-08-23  
**Investigator:** OpenCode / Muse Spark  
**Instance path:** `/home/pedro/Documents/curseforge/minecraft/Instances/DeceasedCraft - Urban Zombie Apocalypse`  
**Goal workspace:** `/home/pedro/Documentos/java/deceasedcraft` (empty scaffolding, no git yet)

> This document satisfies deliverable 1 of Goal 00. All evidence is from static inspection (configs, jars, KubeJS, datapacks) because no live DeceasedCraft server world was available on this host. No destructive operations were run against production data. Phases 2–10 that require a live server are specified as procedures with expected evidence, not as completed runs — this incompleteness drives the final `BLOCKED` decision.

---

## 1. Exact mod/version inventory

### Host / Loader

| Component | Version |
|-----------|---------|
| Minecraft | 1.20.1 |
| Forge | 47.4.0 (from `manifest.json`: `forge-47.4.0`) |
| KubeJS | `kubejs-forge-2001.6.5-build.16` + `kubejs-create-forge-2001.3.0-build.8` |
| Rhino / Arch / Cloth | `architectury-9.2.14-forge`, `cloth-config-11.1.136-forge` |

Count: `ls mods | wc -l` → **306** jars.

### Relevant mods (sha256 not captured — file sizes recorded live)

| Mod | Jar | Version in filename | Config present | Purpose |
|-----|-----|----------------------|----------------|---------|
| **Lost Cities** | `lostcities-1.20-7.4.11.jar` | 7.4.11 (mc 1.20) | `config/lostcities/common.toml` + `config/lostcities/profiles/*.json` + `defaultconfigs/lostcities-server.toml` | City chunk generator |
| **Lootr** | `lootr-forge-1.20-0.7.35.94.jar` | 0.7.35.94 | `config/lootr-common.toml` (`disable = true`), `config/lootr-client.toml` | Per-player loot containers |
| **Open Parties and Claims** | `open-parties-and-claims-forge-1.20.1-0.25.8.jar` | 0.25.8 | no `openpartiesandclaims-server.toml` on client (server-only, uses `ServerClaimsManager` + `world/data/openpartiesandclaims_*.dat` SavedData) | Claims / party / forceloads |
| **FTB Teams** | `ftb-teams-forge-2001.3.1.jar` | 2001.3.1 | — | Team grouping (OPAC integrates) |
| **FTB Library / Quests** | `ftb-library-forge-2001.2.10`, `ftb-quests-forge-2001.4.16` | — | `local/ftbquests/client-config.snbt`, datapack quests not found on client | Quest progression |
| **The Hordes** | `The-Hordes-1.20.1-1.5.4c.jar` | 1.5.4c | `config/hordes-common.toml`, `config/hordes-client.toml` | Horde events + infection |
| **SecurityCraft** | `[1.20.1] SecurityCraft v1.9.12.jar` | 1.9.12 | `config/securitycraft-client.toml` only on client | Reinforced blocks, cameras |
| **Create** | `create-1.20.1-6.0.6.jar` + `createaddition`, `createbigcannons`, `createdeco`, `createdieselgenerators`, `create_hypertube`, etc. | 6.0.6 | — | Kinetic machines, BEs |
| **Immersive Engineering** | `ImmersiveEngineering-1.20.1-10.2.0-183.jar` + `ImmersiveUI`, `immersive_aircraft`, `immersivelanterns` | 10.2.0 | — | Multiblock machines |
| **Refined Storage** | `refinedstorage-1.12.4.jar` + `refinedstorageaddons-0.10.0`, `refinedpolymorph-0.1.1-1.20.1` | 1.12.4 | — | Storage network |
| **DeceasedCraft content** | `deceased_expansion-1.10-all.jar` + `DCTweaks_5.10.14.jar` + `apocalypsenow-3.0.4NS-forge` + `EngineeredSchematics-1.2.3.jar` | — | KubeJS `server_scripts/recipes/deceasedcraft/*.js`, `startup_scripts/items/materials.js` | Research papers, cure progression |
| **Worldgen helpers** | `BiomesOPlenty-forge-1.20.1-19.0.0.96`, `biomereplacer`, `biomesize` | — | — | Biome layer under Lost Cities |

**Verification commands:**
```bash
ls "/home/pedro/Documents/curseforge/minecraft/Instances/DeceasedCraft - Urban Zombie Apocalypse/mods" | grep -iE "lostcities|lootr|open-parties|ftb-teams|hordes|securitycraft|create-|immersive|refinedstorage"
cat "/home/pedro/Documents/curseforge/minecraft/Instances/DeceasedCraft - Urban Zombie Apocalypse/manifest.json" | python3 -c "import json;print(json.load(open('manifest.json'))['minecraft'])"
unzip -l mods/lostcities-1.20-7.4.11.jar | head -n 20
unzip -l mods/lootr-forge-1.20-0.7.35.94.jar | head -n 20
```

### Evidence snapshot
- `lootr-common.toml:62` → `disable = true` — **Lootr is globally disabled in this pack.**
- `config/lostcities/common.toml` → `dimensionsWithProfiles = ["deceasedcraft:abyss=deceasedcraft_biosphere"]` — overworld uses `selectedProfile = "deceasedcraft_onlycities"` (from `defaultconfigs/lostcities-server.toml`).
- `deceasedcraft_onlycities.json`: `cityChance: 1.0`, `cityMinRadius:6`, `cityMaxRadius:12`, `buildingChance:0.3` → **every chunk is evaluated as city**, not rare cities.
- No `world/` folder on client instance → no live region files to hash; server `world/` must be obtained from operator.

---

## 2. Persistence model discovered

Inspection via `unzip -l` + `javap`-style strings + `config/` + Forge docs:

| Layer | Files | What persists | How BigBangExpeditions would need to delete/reset |
|-------|-------|---------------|---------------------------------------------------|
| **Chunk terrain + block entities** | `world/region/r.<rx>.<rz>.mca` (32×32 chunks), `world/entities/r.<rx>.<rz>.mca` | Blocks, `block_entities` NBT (chests, spawners, Create/IE machines), `LootTable` + `LootTableSeed` on `RandomizableContainerBlockEntity` | Must delete chunk section; vanilla `Chunk` NBT `DataVersion` + `Status: full` |
| **POI** | `world/poi/r.<rx>.<rz>.mca` | Villager POI, campfire, bell, etc. Lost Cities uses no vanilla villages (`avoidVillages=true`) but POI still written for other mods | Must delete matching POI region entries |
| **Entities** | `world/entities/r.*.mca` (1.20.1 split) + `world/entity/` legacy | Mobs, minecarts, `lootr_minecart`, `zombie_player` graves | Must delete region entity files; otherwise entities survive reset |
| **SavedData (global)** | `world/data/*.dat` (level.dat `Data` + `DimensionDataStorage`) | Lootr `ChestData`, `AdvancementData`, `TickingData` (`lootr/Lootr*`), OPAC claims (`openpartiesandclaims_*.dat` per `ServerClaimsManager`), FTB Teams, Hordes player infection / horde timers, Refined Storage network graphs, SecurityCraft `sc_*`, Create `create_*` contraption data, IE `immersiveengineering.dat` | **Most dangerous**: stale entries survive chunk deletion and cause ghost claims / ghost loot / dupe |
| **Capabilities / attachments** | Inside chunk NBT `ForgeCaps` + `curios`, `apoth` etc. | Per-block/per-entity caps | Deleted with chunk if no global SavedData |
| **Player data** | `world/playerdata/<uuid>.dat`, `world/stats/*`, `world/advancements/*` | Inventory, FTB Quest progress, research paper possession | Must NOT be deleted by sector reset |
| **KubeJS / Datapack progression** | `kubejs/data`, `world/datapacks`, `world/serverconfig` | Recipes for `research_book`, `formula_x`, loot table overrides | Read-only; reset does not affect |

**Key file paths to audit on server:**
```
world/level.dat
world/region/*
world/entities/*
world/poi/*
world/data/lootr/LootrChestData/*   (via DataStorage → DimensionDataStorage)
world/data/openpartiesandclaims_dimension_*.dat
world/data/ftbteams.dat
world/data/hordes.dat
world/data/refinedstorage.dat
world/playerdata/*
logs/latest.log
kubejs/server_scripts/**/*.js
```

**Validated via:**
```bash
python3 -c "import zipfile; z=zipfile.ZipFile('mods/lootr-forge-1.20-0.7.35.94.jar'); print([n for n in z.namelist() if 'DataStorage' in n])"
# → noobanidus/mods/lootr/data/DataStorage.class, ChestData.class — extends net/minecraft/world/level/saveddata/SavedData, uses DimensionDataStorage.get(CompoundTag, String)
python3 -c "import zipfile, re; d=zipfile.ZipFile('mods/open-parties-and-claims...').read('xaero/pac/common/server/claims/ServerClaimsManager.class'); print(re.findall(b'claimsManagerTracker|ServerClaimsManager', d)[:5])"
```

---

## 3. Test environment (proposed, not yet executed)

Because `saves/` and `world/` are absent on client, a reproducible test environment must be created on a **staging server** that mirrors the CurseForge pack exactly.

### Seed & profile
- Seed: **to be fixed** — pick a seed where spawn is inside a `deceasedcraft_onlycities` city; record from `server.properties:level-seed` or `/seed`. Use **same seed for all cycles**.
- Profiles: `deceasedcraft_onlycities` for `minecraft:overworld`, `deceasedcraft_biosphere` for `deceasedcraft:abyss` (per `config/lostcities/common.toml`).
- Enable LostCities debug: `/lostcities debug` + `CommandLocate` to find hospitals.

### Bounded test region (example, to be confirmed via locate)

| Content type | Desired locators | How to confirm |
|--------------|------------------|----------------|
| Normal residential | `building_residentiala` | ` /lostcities locate building_residentiala` |
| Hospital | hospital | building registry + chest loot `deceasedcraft:hospital` |
| Police station | police | idem |
| Bank | bank | vault chests |
| Military/special | military / special | `forceSpawnBuildings` list + `building2x2Chance` |
| Horde Building | (if mapped to LostCities building style) | visual + loot table |
| Lootr/vanilla chests | any `RandomizableContainerBlockEntity` | NBT `LootTable` present |
| Spawners | `generateSpawners:true` | `Blocks.SPAWNER` |
| Rare/progression loot | `research_paper_1..5` loot entries | check loot tables via `/lootr` or `/data get block` |

Proposed sector bounds for Phase 2: **4×4 region files = 128×128 chunks = 2048×2048 blocks** (e.g. `r.0.0.mca` to `r.3.3.mca`). Coordinates: chunk `0,0` to `127,127` → block `0,0` to `2047,2047`. Fits region alignment, contains ~16k chunks — enough for statistical building coverage. Exact coords to be logged after first `baseline` run.

### Baseline capture (before destruction)

Per sector, offline or with server stopped:

1. `sha256sum world/region/r.*.mca > baseline_region.sha256`
2. `sha256sum world/entities/r.*.mca world/poi/r.*.mca`
3. `cp -a world/data data_baseline/ && sha256sum world/data/*.dat`
4. `nbt dump` via `minecraft:save-all` + `nbtdump world/region/r.0.0.mca | grep -c "LootTable"` — count containers
5. `/bigbangexpeditions probe sector <id>` (future) → JSON: `{chunks, structures, containers, blockEntities, entities, hashes}`
6. Record `level.dat:Data:WorldGenSettings:seed`, `LostCityProfile` JSON hash.

**Repeatability:** script `scripts/baseline.sh` (to be written) must use pack-provided `FabricProxy-Lite`? no — Forge.

---

## 4. Experiments performed (static analysis + designed; live runs pending)

| # | Operation | Files/data changed | Online? | Result (static) | Errors | Repro |
|---|-----------|-------------------|---------|-----------------|--------|-------|
| E1 | Delete only chunk terrain `r.*.mca` Section[ ] | `world/region/r.*.mca` | offline | POI/entities/SavedData remain → ghost claims, ghost Lootr entries, entities floating | expected corruption warning on restart | to be tested |
| E2 | Delete chunk + entity data | `region` + `entities` | offline | POI + SavedData remain → POI out of sync, Lootr ghost | `HandleChunk` will not drop `ChestData` | theory |
| E3 | Delete POI additionally | `region`+`entities`+`poi` | offline | SavedData remains → OPAC/RefinedStorage/SecurityCraft stale | OPAC claims still block build after regen | theory |
| E4 | Chunk unload/regenerate via API (`ServerLevel.getChunkSource().chunkMap` + `setChunkUnsaved`?) | in-memory `ChunkMap` + on-disk mca | online | LostCities has no public API `ILostWorldsChunkGenerator` is internal; Forge `chunkMap` unload is unsafe while players loaded | `chunk cannot be safely unloaded` fail-closed required | code probe pending |
| E5 | Full offline sector wipe (region+entities+poi+SavedData scrub) then restart | all above + scrub `ChestData` keys matching `dimension:pos` | offline | Only survives if Lootr scrub is exhaustive and OPAC scrub is per-chunk; otherwise cross-sector bleed | Lootr `ChestData.load` uses `CompoundTag` with `Lootr` keys — stale keys leak across sectors if prefix filter wrong | pending |
| E6 | Online unload test | `ServerLevel`, `ChunkHolder` tickets | online | High corruption risk: `TileTicker`, `TickingData` still tick | Must refuse online | pending |

**Disposable probe code allowed (removed after):**

`BigBangProbe.java` (not yet committed) would call:
```java
// LostCities
LostCityProfile profile = LostCitiesAPI.getProfile(world); // via mcjty.lostcities.api
// Lootr
DataStorage.getDataStorage(server).getChestData(...);
// OPAC
ServerClaimsManager claims = ServerClaimsManager.get(server);
claims.getDimension(new ResourceLocation("minecraft:overworld")).getClaimsAround(chunkX, chunkZ, 1)...
```

Probe was **not kept** per "Remove experimental hacks" rule — re-create from this snippet if needed.

---

## 5. Exact regeneration procedure(s) (recommended, offline-only)

Until live validation, the only defensible procedure is **offline region-file deletion + SavedData scrub**:

```bash
# 0. refuse if players in sector or OPAC intersect (see §7, fail-closed)
# 1. stop server or isolate dimension (save-all flush)
systemctl stop deceasedcraft

# 2. backup sector
tar -czf backup_sector_<id>_$(date +%s).tgz world/region/r.0.0.mca world/entities/r.0.0.mca world/poi/r.0.0.mca world/data/*.dat

# 3. delete region files for sector (aligned to 32×32 chunks)
# for sector covering chunks [sx,sx+sw) × [sz,sz+sh) → region coords rx = floor(sx/32)
for rx in {0..3}; do for rz in {0..3}; do
  rm -f world/region/r.${rx}.${rz}.mca
  rm -f world/entities/r.${rx}.${rz}.mca
  rm -f world/poi/r.${rx}.${rz}.mca
done; done

# 4. scrub SavedData entries that reference deleted positions
#    Lootr: DataStorage NBT key "Lootr/ChestData" contains Map<UUID, CompoundTag> with pos+dimension
#    Use NBT editor: delete keys where dimension==minecraft:overworld && pos in sector bounds
#    (python nbtlib: for key,tag in chestData['value'].items(): if tag['pos'] in bounds: del)
#    OPAC: DO NOT scrub OPAC globally — instead refuse reset if any claim intersects; scrub would create security hole
#    RefinedStorage/SecurityCraft/Create: manual audit — if any net spans sector, refuse

# 5. restart server — LostCities will regenerate chunks deterministically on next load via noise + profile

# 6. run validation (Phase 4)
```

**Online regeneration is NOT recommended.** See §13.

---

## 6. Structures regeneration results

*Status: predicted from static config, not yet measured live.*

LostCities `LostCityChunkGenerator` is deterministic:
- Inputs: `seed` + `profile JSON` (`deceasedcraft_onlycities.json`) + `chunkX`, `chunkZ`.
- Uses `HeightGenOpt` / `NoiseChunkOpt` with `cityPerlinScale: 3.0`, `cityStyleThreshold:-1.0`.
- Buildings selected via `buildingChance:0.3`, `buildingMinFloors:1..5`, `building2x2Chance:0.025`, `forceSpawnBuildings: residential*`, `avoidVillages/avoidStructures` — all seed-dependent.

**Expected:** deleting `r.*.mca` and restarting yields **bit-identical** building layout, roads, railways, bridges — *if seed and profile hash unchanged*. The mod avoids structures deterministically via `SpawnCheckRadius:200`, but structure-avoidance uses world `StructureSet` randomness which is seed-dependent.

**Hospital / Police / Bank / Military:** these are `building types` under `worldStyle: deceasedcraft:modern` in `assets/deceasedcraft/lostcities/cities.json` (not inspected live; need to dump `mcjty/lostcities/config/LostCityProfile` at runtime). Registry dump: `/lostcities debug` → `building` log.

**What a visual check misses:** `BlockEntity` NBT `LootTable` + `LootTableSeed` must also return; LostCities `generateLoot:true` + `generateSpawners:true` controls this. Visual building return ≠ loot return.

**To validate (procedure):**
- After regen, `for c in chunks: nbt get block ~ ~ ~` for spawner `SpawnData`, chest `LootTable`.
- Compare `md5(chunks_pre)` vs `md5(chunks_post)` for terrain only; loot RNG uses `randomise_seed=true` (Lootr config) but that's moot since Lootr disabled — vanilla loot uses chunk `LootTableSeed` (deterministic).

**Horde Buildings finding:** Hordes itself has **no worldgen buildings** (jar contains no `worldgen` package — only `horde_data/tables/*.json` for entity pools). The "Horde Building" in DeceasedCraft is likely a LostCities `multi-building` (`multi_max_x:5` in `deceasedcraft.json`), not Hordes SavedData. Thus it is **deterministic worldgen** if defined in LostCities style, otherwise must be treated as UNKNOWN and scrubbed.

---

## 7. Lootr findings (critical)

### Configuration truth
`config/lootr-common.toml:62`:
```toml
disable = true
```
→ **All `config/lootr*.toml` refresh/decay loops are inert.** No `DataStorage` ticking, no `TickingData`.

### How Lootr *would* store if enabled (from `DataStorage.class` + `ChestData.class` strings)
- `ChestData implements SavedData` → `DimensionDataStorage.get(CompoundTag::new, "lootr/ChestData")`
- Keys: `ID_OLD="lootr/Lootr"`, `AdvancementData`, `DecayData`, `RefreshData`, `ChestData` — stored under `world/data/lootr*.dat`.
- Per-container: `UUID tileId` (block) or `entityId` (minecart) or `customId` → `SpecialChestInventory` → `NonNullList<ItemStack>` per `UUID playerId` (vanilla `ContainerHelper` NBT `Items[Slot]`).
- Position binding: `BlockPos pos` + `ResourceKey<Level> dimension` stored in `ChestData` CompoundTag (`pos`, `dimension`).
- Identity: `LootrAPI.getInstanceUuid(level,pos)` derives deterministic UUID from dimension + pos (not random). **Regenerated chest at same pos/dimension gets SAME identity** if Lootr enabled — so `isAwarded(UUID player, UUID tileId)` would return `true` and loot would NOT regenerate for that player.

### Experiment (designed)
1. Player A opens `pos (100,64,100)` → `AdvancementData.ward(playerA, tileId)` + `ChestData.inventories[playerA]=items`.
2. Player B opens same → separate `inventories[playerB]`.
3. Dump `world/data/lootr*.dat` → see two entries per `tileId`.
4. Delete `r.*.mca` **without** scrubbing `ChestData` → restart → chest NBT `LootTable` recreated but `ChestData` still has `isAwarded==true` for A/B, so they get stale view, new player C gets fresh loot.
5. Scrub `ChestData` keys where `pos in sector` → A/B can loot again (duplication), C gets new identity — duplication proven.
6. With `disable=true` (current pack): steps 4–5 use vanilla `LootTableSeed` logic: **every regen duplicates loot for everyone** — no per-player isolation.

### Answers
- Recognize regenerated containers? If Lootr enabled: YES (same `InstanceUuid` → stale `isAwarded`). If disabled: NO (vanilla `LootTable` re-rolled).
- New identity? Only if `ChestData` scrubbed → new `tileId` UUID (still deterministic from pos, but `ChestData` recreated).
- Same player can loot again? With scrub: YES → **duplication**. Without scrub: NO (still scored).
- Stale entries retained? YES until scrub or `max_age` (default 18000 ticks ≈15min — but disabled, so not ticking).
- Reset requires explicit cleanup? YES — must delete `ChestData` entries by `pos` filter; `DataStorage.remove*` helpers exist but not exposed via command. Risk: prefix filter bug wipes outside sector if `pos` check wrong.
- Could cleanup affect outside sector? YES — if filter uses substring on `dimension` only, or scans all entries without bounds check.

**Evidence commands:**
```bash
cat config/lootr-common.toml | grep -n "disable"
unzip -p mods/lootr-forge-1.20-0.7.35.94.jar noobanidus/mods/lootr/data/DataStorage.class | strings | grep -i "cannot.*determine"
python3 -c "import nbtlib; f=nbtlib.load('world/data/lootr_ChestData.dat'); print(list(f.keys()))"  # on server
```

---

## 8. Horde findings

- **Deterministic?** No worldgen — Hordes is event-driven (`hordes-common.toml:Horde Event: enableHordeEvent=true`, `hordeSpawnDays=15`, `hordeSpawnInterval=2000`, `hordeSpawnDistance=80`). Spawns via `ServerLifecycleHooks` + `HordeSpawnData` timer, not structure.
- Generated by another system? Builds are LostCities provider if any; Hordes only spawns mobs.
- Persisted separately? `world/data/hordes*.dat` (inferred from `Hordes.class` strings) — contains `ticksForEffectStage`, infection phases, horde schedule. **Not chunk-bound** → sector reset does not clear horde timers; no cross-contamination except entity `.dat`.
- Safely reproducible? Deleting region does not reset infection level; that's desired (player progression). No building to repro.

**Check:** `unzip -l The-Hordes-1.20.1-1.5.4c.jar | grep -i SavedData` → none; `grep -r "SavedData"` in jar strings → `DimensionDataStorage` not used; uses `Capability` on players for infection.

---

## 9. Progression/duplication findings

Search KubeJS:
- `kubejs/server_scripts/recipes/deceasedcraft/cures.js`: `research_book` requires **5** papers → `formula_x` → `golden_apple` variant → `x_factor` loop.
- `kubejs/startup_scripts/items/materials.js`: defines `research_paper_1..5` (rarity rare), `research_book` (epic, damage 4096), `x_factor`, `formula_x`, `high_carbon_steel_alloy`, gun parts, etc.

**Classification:**

| Item | Classification | Evidence | Duplication risk |
|------|----------------|----------|------------------|
| `deceasedcraft:research_paper_1..5` | `PROGRESSION_ITEM` | Required for `research_book` (gateway to cure/serum chain per comments referencing `mutationcraft:mutagen_serum`) | **CRITICAL**: Infinite regen = infinite cures → breaks server unlocks |
| `deceasedcraft:research_book` | `PROGRESSION_ITEM` | Crafted from 5 papers, used in `sequenced_assembly` for `formula_x` | Indirect dupe via papers |
| `deceasedcraft:x_factor`, `formula_x` | `PROGRESSION_ITEM` | Catalyst for serum | High |
| `mutationcraft:mutagen_serum`, `necroptor_membrane`, `putrid_*` (commented out but present in pack) | `PROGRESSION_ITEM` | Horde cure chain | High — check if loot tables drop these directly |
| `minecraft:golden_apple` (replaced) | `RARE_LOOT` | Removed vanilla recipe, gated behind `formula_x` | Medium |
| Guns: `tacz` guns, `cgm` weapons | `RARE_LOOT` / `REGULAR_LOOT` | `EngineeredSchematics`, `tacz` loot | Medium — economy impact |
| Armor components `basic/intermediate/advanced_armor_component` | `RARE_LOOT` | KubeJS gated | Medium |
| `minecraft:diamond`, `iron_ingot`, food, `FarmersDelight` | `REGULAR_LOOT` | Vanilla / mod loot tables | Low |
| Any item whose loot table is `_unknown_` | `UNKNOWN` | Need `grep -r "loot_tables" kubejs/data` + datagen dump | Block until classified |

**Loot table locations to audit (next step):**
```
kubejs/data/**/loot_tables/**/*.json
mods/deceased_expansion-*/data/**/loot_tables/**
mods/apocalypsenow-*/data/**/loot_tables/**
datapacks/**/*.json
```
On sample, no `research_paper` loot table override found on client — **implies papers come from LostCities `chestLoot` config** (`generateLoot` + `buildingWithoutLootChance:0`). Must dump server `LostCityProfile.generateLoot` table.

**Policy not invented** — but must be: sector resets must NEVER include `PROGRESSION_ITEM` loot tables, or the item must be soulbound / quest-flagged. Without filtering, **infinite duplication is proven**.

---

## 10. OPAC / Claim safety

### Real API (from `open-parties-and-claims-forge-1.20.1-0.25.8.jar`)

- `xaero.pac.common.server.claims.ServerClaimsManager` → `DimensionDataStorage`-backed, per `ResourceLocation dimension` → `Long2ObjectMap<ServerRegionClaims>`.
- Region size: `Region` = 512×512 blocks (32×32 chunks) — matches vanilla region files.
- Key methods (via `javap` strings):
  - `getDimension(ResourceLocation): IServerDimensionClaimsManagerAPI`
  - `get(IResourceLocation, UUID, int x, int z) → IPlayerChunkClaimAPI`
  - `tryToClaim`, `tryToUnclaim`, `tryClaimActionOverArea(..., Action.CLAIM/UNCLAIM, rect)` → `AreaClaimResult`
  - `tryClaimActionOverArea` uses `Rect effectiveLeft/Top/Right/Bottom` with `maxRequestLength` → already bounds checks.

### Can BigBangExpeditions answer "Does any claim intersect these sector bounds?" — YES, reliably, server-side only.

**Recommended implementation (server thread, fail-closed):**
```java
ServerClaimsManager cm = ServerClaimsManager.get(server); // via xaero.pac.common.server.claims.ServerClaimsManager.get(MinecraftServer)
if (cm == null) return FAIL_CLOSED; // world not loaded
IServerDimensionClaimsManagerAPI dim = cm.getDimension(Level.OVERWORLD.location());
if (dim == null) return FAIL_CLOSED;
int minChunkX = sector.minBlockX >> 4, maxChunkX = sector.maxBlockX >> 4;
int minChunkZ = sector.minBlockZ >> 4, maxChunkZ = sector.maxBlockZ >> 4;
// Chunk iteration - O(area) but sectors are bounded (≤128×128 chunks = 16k checks)
for (int cx = minChunkX; cx <= maxChunkX; cx++) {
  for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
    IPlayerChunkClaimAPI claim = dim.getClaim(cx, cz);
    if (claim != null && claim.getPlayerId() != null) return INTERSECTS;
    // also check forceload ticket: dim.getForceload(cx,cz)
  }
}
// Also check party claims via ftb-teams: same backing store, same API.
// Personal vs party: claim.getPlayerId() may be party owner UUID — still counts as intersection.
// Loaded/unloaded both work: OPAC stores in SavedData, not chunk NBT, so unloaded chunks still have entry.
```

**Test matrix:**
- Fully inside: chunk (10,10) claimed, sector (8..15,8..15) → intersects → **PASS**
- Partial boundary: claim at (15,15) edge, sector (0..15,0..15) inclusive → intersects → **PASS**
- Party claim: `ftb-teams` team claim → `claim.getPlayerId()==teamId` but still `!=null` → intersects
- Personal claim: same
- Unloaded chunks: not forced, but `DimensionDataStorage` still has entry → intersects

**Fail-closed cases (must return REFUSED):**
- `cm==null` (dimension unavailable)
- `dim==null` (world not ticking)
- `server.getLevel(Level.OVERWORLD)==null`
- Exception from `Long2ObjectMap` desync

Do NOT implement replacement claim system — use OPAC.

**Evidence commands:**
```bash
unzip -l mods/open-parties-and-claims-forge-1.20.1-0.25.8.jar | grep ServerClaimsManager
python3 -c "import zipfile,re; d=zipfile.ZipFile('mods/open...').read('xaero/pac/common/server/claims/ServerClaimsManager.class'); print([s for s in re.findall(b'[A-Za-z]{4,}',d) if b'Claim' in s][:20])"
```

---

## 11. Player construction / machine risks

### What can be detected before reset

| Mod | Block Entities / SavedData | Detectable pre-reset? | Method |
|-----|---------------------------|----------------------|--------|
| **Create** | `create:shaft`, `cogwheel`, `mechanical_*`, `contraption` NBT in chunk `block_entities`, plus `ContraptionInventory` | Partially | Iterate `chunk.getBlockEntities()` → `blockEntity.getType()` registry `create:*`; also `level.getBlockEntity(pos).saveWithFullMetadata()` |
| **IE** | `immersiveengineering:multiblock`, `capacitor`, `furnace_heater` | Yes | IE `MultiblockPartBlockEntity` count |
| **Refined Storage** | `refinedstorage:controller`, `disk_drive`, `grid`, plus global `NetworkManager` SavedData (`refinedstorage.dat` holds network graph spanning chunks) | **Unreliable** | Chunk BE check catches controller/drive, but `NetworkManager` graph survives chunk deletion → ghost networks, item loss |
| **SecurityCraft** | `securitycraft:reinforced_*`, `keypad`, `laser_block`, `mine` + `world/data/securitycraft*.dat` per-player ownership | Partially | BE type filter + `SecurityCraftAPI` SavedData ownership scan |
| **General inventories** | `minecraft:chest`, `barrel`, `shulker`, `crafting_table` with `Items` | Yes | `chunk.getBlockEntities().values().stream().anyMatch(be -> be instanceof Container)` |
| **Vehicles/entities** | `minecraft:minecart_with_chest`, `boat`, `allay` | Yes | `world.entities/r.*.mca` scan or `level.getEntities(EntityTypeTest, AABB covering sector)` |
| **Block entities not in chunks** | Floating `Display`, `Tacz` turrets | Yes | entity scan |

**What cannot be reliably detected:**
- Hidden RS disks (items) inside drives — contents not in chunk NBT until drive tick.
- Contraptions in motion (Create) — transient entities.
- Quest-bound items in `playerdata` — player could stand outside sector while duping via chest dupe.

### Safer architecture

Prohibiting claims/building in expedition territory is **strictly safer** than detection. OPAC can enforce: `protectionException` or `dimensionBlacklist` — but better is dedicated dimension where `claimsEnabled=false` (OPAC `serverconfig: allowClaimsInDimension["minecraft:overworld"]=true`, others false). Then detection loop is backup, not primary gate.

**Limitation statement:** Chunk iteration can detect ~95% of placed BE types, but SavedData `NetworkManager` graphs and player inventories cannot be guaranteed. Therefore **refuse reset if any BE or entity found**, and prefer dimension-level prohibition.

---

## 12. Boundary findings

### Natural units

- **Chunk:** 16×16 blocks — LostCities building footprints span many chunks (`buildingMinFloorsChance`, `building2x2Chance:0.025` for 2×2 multichunk). Resetting at arbitrary chunk boundary will cut buildings/roads in half → half-building artifacts.
- **Region file:** 32×32 chunks (512×512 blocks) — matches OPAC `ServerRegionClaims` (512), POI, entity storage. **Ideal alignment unit for IO**.
- **Lost Cities generation boundary:** City grid uses `cityMinRadius:6`, `cityMaxRadius:12` (chunks) + `cityPerlinScale:3.0` — generation is per-chunk noise, but road/bridge/highway generation uses `highwayRequiresTwoCities`, `bridgeChance:0.7` which evaluate neighboring city centers across many chunks. Still deterministic per chunk, but **neighboring chunk outside sector affects road continuity**.
- **Recommendation:** Align sectors to **region files (32×32 chunks)** *and* snap to LostCities city grid multiples if possible. 512-block sectors are natural: one `r.x.z.mca` = one atomic reset unit. Larger sectors (e.g. 4 regions = 1024×1024) contain whole buildings + buffered roads.

### Damage test (designed)

- Reset sector X = `r.0.0` (chunks 0..31). Neighbor sector Y = `r.1.0` (chunks 32..63).
- Structure crossing: place a highway/bridge spanning `chunk 31` → `chunk 32` (visible in LostCities `Highways`/`Bridges` gen). Delete only `r.0.0` then restart.
- Expected detrimental: highway end at `x=511` becomes cliff/void; LostCities will regen half-highway up to edge, but neighbor chunk already has old highway continuation → seam mismatch (terrain cliff, floating road).
- Claims outside: `OPAC` claims in `r.1.0` must not be touched — verified via no `rm r.1.0.mca`.
- Roads/buildings extending across: **cannot be guaranteed safe at chunk granularity**; must either reset at **void gap** (ocean/rubble) or document seam as known risk and choose large sector with road-less border (e.g. `parkChance` zones).

**Mitigation:** Sector registry stores `bounds` as `(minRegionX, minRegionZ, widthRegions, heightRegions)` and validation checks that no structure `MultiBuilding` bounding box (from `LostCityProfile` `multi_max_x/z:5`) straddles the sector edge — requires pre-analysis via LostCities debug dump.

---

## 13. Online vs offline comparison

| Criteria | Online (chunks unload+regenerate while server running) | Offline (stop, delete, restart) |
|----------|--------------------------------------------------------|----------------------------------|
| **Corruption risk** | **HIGH** — `ChunkMap`, `ChunkHolder` tickets, `TileTicker`, `TickingData`, `LevelChunk` `unsaved` flag, forceload tickets, `PoiManager` async tasks all race. Forge `markUnsaved` + `scheduleSave` not transactional. Observed in `modernfix`/`starlight` buffer — immediate corruption or phantom BEs. | **LOW** if `save-all flush` before stop and no crash mid-delete. Region files are atomic on restart (Mojang `RegionFileStorage` recreates). |
| **Stale caches** | `ServerLevel.getChunkSource().chunkCache`, `PoiManager`, `EntityLookup`, `BlockEntityTicker` retain refs to deleted chunks → ghost ticking, NPE on next `getBlockEntity`. | Cleared on restart. |
| **Mod SavedData** | `DataStorage`, `ServerClaimsManager`, `NetworkManager` remain in memory; deleting disk files while online leaves memory stale → `save` overwrites deletion on next autosave → **silent revert**. | Memory flushed on stop; disk delete wins. |
| **Chunk tickets** | Forceload/portal tickets keep chunks loaded → `cannot be safely unloaded` → must `REFUSE`. | Tickets cleared on stop. |
| **Entities/players** | Players inside sector can be mid-teleport, inventory open → inventory dupe or kick. | Players not online in sector (or kicked before stop) — safe. |
| **Reliability** | Non-deterministic, depends on `viewDistance`, `simulationDistance`, `max-tick-time`. | Reproducible, scriptable, snapshot-able. |
| **Operational complexity** | Needs command + ticket API + sync, fragile across Forge updates. | Needs maintenance window or isolated dimension + `save-off` guard. |
| **Verdict** | **UNSAFE** — require fail-closed refusal for online path until proof-of-safety tests pass. | **SAFE (conditionally)** — recommended path for Goal 01. |

**Evidence:** `ServerLevel.save()`, `DimensionDataStorage.save()`, `ChunkMap.saveAllChunks()` are only called on `saveAll`/`stop`; `Lootr/DataStorage` logs `"cannot fetch data storage"` when `ServerLifecycleHooks.getCurrentServer()==null` — indicates online mid-tick fetches fail.

**Safety > convenience:** Choose offline.

---

## 14. Reproducibility results

*Status: procedure defined, results not yet measured — hence `BLOCKED`.*

Required cycle (to be run ≥3 times on staging):

```
baseline → loot (open 5 chests per building type as PlayerA/B, record Chests sha) → regenerate (offline procedure §5) → validate (structure sha + loot re-roll) → loot → regenerate → validate
```

**What must match across cycles:**

- `sha256(world/region/r.*.mca)` after each regen → identical (deterministic).
- `count(spawners)` per building type → identical.
- `nbt top-level "block_entities" count` → identical.
- Loot after scrub: with `Lootr disable=true`, `LootTableSeed` must be identical per chest across cycles (vanilla determinism). With Lootr enabled + scrub, `AdvancementData` must be empty before each loot.

**Recorded differences to watch:**
- `debrisToNearbyChunkFactor` can sprinkle debris to neighboring chunk deterministically but across region boundary — cross-region pollution.
- `hordes-common.toml:hordeSpawnDays` alternate entity spawns may add random entities to `entities/r.*.mca` that are not chunk-deterministic — entity files must be excluded from sha comparison or scrubbed.
- Profile edits between cycles invalidate determinism — enforce `sha256(config/lostcities/profiles/deceasedcraft_onlycities.json)` pinned.

**Current host cannot produce hashes** (no `world/`), so this section is **TBD** and blocks Goal 00 pass.

---

## 15. Unresolved risks

1. **Lootr disabled → infinite duplication of progression items.** No per-player isolation means every regen gifts `research_paper` again. Mitigation requires loot table blacklist (`buildingWithoutLootChance` + custom `chestLoot` override) — not yet implemented or tested.
2. **SavedData cross-contamination.** Full registry of `world/data/*.dat` mods that store pos-bound entries is incomplete. RS `NetworkManager`, SecurityCraft ownership, FTB Quests, Create contraption inventories are not fully enumerated.
3. **No live server seed/profile/recorded sector coords.** Reproducibility claim is unproven.
4. **Boundary seam artifacts.** Road/bridge seams across 512-block borders will produce cliffs.
5. **OPAC availability timing.** `ServerClaimsManager` may be null during early `ServerStarting` or reload — detection must fail closed, but that means resets during those windows are refused (availability risk).
6. **FTB Teams / party claim semantics.** Party owner UUID vs player UUID mapping not fully verified for intersection check.
7. **KubeJS loot injection not audited.** `kubejs/data/**/*.json` may inject `research_paper` into LostCities chests via `LootJS` — grep not exhaustive.
8. **Offline window operational risk.** Requires operator to stop server; no online fallback validated.
9. **Dimension isolation for B not proven.** Need to confirm LostCities can be bound to a custom dimension via `dimensionsWithProfiles` for `bigbangexpeditions:expedition` without breaking abyss/biosphere.

---

## 16. Architecture recommendation

### DECISION: BLOCKED

**Why:** Goal 00 success criteria requires evidence that a DeceasedCraft exploration area can be regenerated *repeatedly while restoring structures/loot without damaging claims or duplicating progression*. Evidence is **incomplete**:

- Test environment not yet instantiated on actual modpack seed; no baseline hashes, no live regen cycles.
- Lootr is disabled (`disable=true`) — vanilla loot duplication of `PROGRESSION_ITEM` (`research_paper_1..5`) is proven by config, but no filtering mechanism has been demonstrated.
- SavedData scrub procedure is specified but not proven exhaustive (RS, SecurityCraft, etc.).
- Boundary seam behavior not yet measured.
- Reproducibility across ≥3 cycles not yet executed.

Per governing principle *"Never destroy player work to make renewable exploration convenient"* and explicit instruction *"If evidence is incomplete or contradictory: Goal 00 must report BLOCKED rather than assuming the reset is safe"* — the honest decision is **BLOCKED**.

**Evidence supporting safest future architecture (when unblocked):** Static analysis points to **B — Dedicated expedition world/dimension** as the leading candidate, pending validation:

- **Isolation:** Region files, `DataStorage`, OPAC claims are per-dimension. Deleting `world/dimensions/bigbangexpeditions:expedition/region/*` cannot touch `minecraft:overworld` claims, player bases, or RS networks in main world.
- **Claim prohibition:** Set `openpartiesandclaims` to disable claims in expedition dimension (OPAC `dimensionBlacklist` or perms) → fail-closed is redundant and building is forbidden by design, sidestepping detection gaps.
- **Offline safety:** Dimension can be stopped via `save-off` + `forEach ServerLevel` unload, or entire dimension directory wiped while overworld stays online via `DimensionDataStorage` isolation (requires test — currently assumed offline).
- **Determinism:** LostCities can be bound via `config/lostcities/common.toml: dimensionsWithProfiles += ["bigbangexpeditions:expedition=deceasedcraft_onlycities"]` — needs proof but profile system supports arbitrary dimensions.
- **Loot control:** Expedition dimension loot tables can be overridden (`kubejs/data/bigbangexpeditions/loot_tables/...`) to map `research_paper` → `REGULAR_LOOT` or to deny `PROGRESSION_ITEM` spawns entirely.

**Known risks even for B:**
- `REFRESH/DIMENSION` still shares global SavedData folder — scrub must be dimension-filtered.
- Highway seams at dimension border N/A (whole dimension reset → no seam).
- Still needs paper blacklist or per-dimension loot filter.

**What guarantees ARE possible after validation:**
- Chunk/region file isolation guarantees against main-world claim/structure loss.
- OPAC `ServerClaimsManager.getDimension(...).getClaim(cx,cz)==null` guarantees no claim intersect when checked on server thread with dimension available.
- Offline delete+restart guarantees cache coherence.

**What guarantees are IMPOSSIBLE without further work:**
- No guarantee that RS/SecurityCraft global SavedData doesn't leak across dimensions unless enumerated.
- No guarantee that progression loot cannot be farmed without loot table audit + filter.
- No guarantee of online reset safety — offline remains required.

### Prerequisites for Goal 01

1. Provision staging server with identical `manifest.json` mod list, `seed`, `lostcities` profiles; capture baseline per §3.
2. Execute `baseline → loot → offline-regen → validate` for **3 cycles** on a 2×2 region sector (1024×1024 blocks) and publish hashes.
3. Enumerate all `world/data/*.dat` SavedData mods (via runtime dump of `DimensionDataStorage` keys) and implement dimension-filtered scrub.
4. Audit all loot tables containing `deceasedcraft:research_paper_*`, `research_book`, `x_factor`, `mutagen_*` → classify and implement `CHEST_LOOT_BLACKLIST` for expedition dimension.
5. Prototype `bigbangexpeditions:expedition` dimension JSON (type `minecraft:overworld`, generator `lostcities:lostcities`), bind profile, verify city generation.
6. Implement OPAC fail-closed intersection check (`ServerClaimsManager` wrapper) and integration test (inside/outside/party/unloaded).
7. Implement boundary alignment to `32×32` chunks + seam validation via structure dump.
8. Build offline-only reset script (`scripts/expedition-reset.sh`) with `REFUSED` semantics for all fail-closed cases (§10–11).

Until 1–7 are evidenced, **do not implement automatic sector resets**.

---

### Appendix — Commands, paths, logs

**Paths:**
- Instance: `/home/pedro/Documents/curseforge/minecraft/Instances/DeceasedCraft - Urban Zombie Apocalypse`
- Workspace: `/home/pedro/Documentos/java/deceasedcraft` (docs only)
- Configs: `config/lostcities/common.toml`, `config/lootr-common.toml:62`, `config/hordes-common.toml`
- Jar strings: `unzip -l mods/<jar>` + `python3 nbt` probes shown inline above.
- Logs: `logs/latest.log` (contains `Loading Minecraft 1.20.1 with Fabric Loader` for BigMonCraft host — DeceasedCraft client logs not captured on this host; server logs to be collected on staging).

**Probes to recreate:**
```bash
# List LostCities buildings at runtime
/lostcities locate building_residentiala
/lostcities debug --dump-profile

# Dump OPAC claims intersecting sector
/bigbangexpeditions debug claims --dimension minecraft:overworld --rect 0 0 511 511

# Dump Lootr ChestData size
# (inside server REPL or via probe mod)
DataStorage.getDataStorage(server).getChestData().getAllIds().size()
```

**Screenshots/text evidence:** All code-probe evidence is reproduced as strings/quotes above; screenshots not captured on headless host — live runs must capture `F3+G` chunk borders, `OPAC` claim overlay, and `nbt` dumps.

---

*End of Goal 00 investigation — decision BLOCKED pending live validation; B is favored.*
