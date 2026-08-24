# Expedition Dimension Architecture

## Dimension

`bigbangexpeditions:expedition` — a real, dedicated Minecraft dimension
(datapack JSON inside the mod jar), not an overworld teleport range.

* **Type:** `minecraft:overworld` (see goal-02-dimension-type-issue.md for why
  the custom type is deferred)
* **Generator:** vanilla noise, `minecraft:overworld` settings + multi_noise
  overworld preset — the layout proven by LC's own built-in `lostcity`
  dimension; `forge:use_server_seed = true`
* **Persistence:** standard region files under
  `world/dimensions/bigbangexpeditions/expedition/{region,entities,poi,data}`

## Lost Cities integration

City generation is delivered by LC's `lostcities:lostcities` biome feature,
which consults `Config.getProfileForDimension(dimension)` at generation time.
Two activation paths (belt-and-braces):

1. `LostCitiesRegistration` hooks `ServerAboutToStartEvent` and calls
   `Config.registerLostCityDimension(expedition → deceasedcraft_onlycities)`
   reflectively (fail-closed).
2. Staging provisioning appends the mapping to
   `config/lostcities/common.toml:dimensionsWithProfiles`.

Runtime verification lives in `/expedition dimension status`: availability,
type, seed hash vs overworld, profile name + sha-256 fingerprint of
`config/lostcities/profiles/<profile>.json`, expected-profile check, OPAC
presence and claimability gate, world-folder marker.

**Verified live:** profile `deceasedcraft_onlycities` ACTIVE; sector r.4.4
contains 480 spawners, ~3.5k containers (lootr chests), DeceasedCraft building
palettes (`buildersdelight`), furniture/apocalypse mods' block entities.

### Spawn-area caveat

LC clears/flattens terrain near world spawn (spawnRadius logic). Region
r.0.0 is therefore NOT representative city content — reset experiments and
baselines must use distant sectors (e.g. r.4.4).

## Access

`/expedition enter [x z]` / `/expedition leave` (operator-only) store the
return position in player PersistentData with a traversal-rejecting codec;
leave falls back to overworld spawn if storage or target dimension is gone.
