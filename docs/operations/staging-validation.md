# Staging Validation — How to Run Goal 00 Blocked Tests

**Goal:** Use BigBangExpeditions read-only harness to execute the 10 blocked tests from Goal 00 without writing destructive code.

**Pack:** DeceasedCraft instance at `/home/pedro/Documents/curseforge/minecraft/Instances/DeceasedCraft - Urban Zombie Apocalypse` or staging server clone.

## Prereqs

- Forge server with BigBangExpeditions 1.0.0 installed.
- Fixed seed (from `server.properties` or `level.dat`) and LostCities profile `deceasedcraft_onlycities` (overworld) pinned.
- Op: `/op <player>`.

## Quick Doctor

```
/expedition doctor
/expedition world
```

Check: LostCities + OPAC + Lootr versions, `lootr enabled/disabled`, `worldSeedHash`, warnings.

Logs: `logs/latest.log` grep `BigBangExpeditions`.

## Mapping to Goal 00 Tests

| Goal 00 Test | BigBangExpeditions tooling | Procedure (read-only) |
|--------------|----------------------------|------------------------|
| **T02 Baseline** | `sector baseline` + `scripts/baseline.sh` | `/expedition sector baseline test minecraft:overworld 0 0 63 63` then `./scripts/baseline.sh world test` — collects `world/region/*.mca`, `entities`, `poi`, `data` sha256 + profile hash. No mutation. |
| **T06 Preparation (offline regen)** | **NOT executed** in Goal 01. Only prepare hashes for manual operator to later do `rm region/r.*.mca` offline. Harness documents pre-state. |
| **T08 Structure determinism** | `probe` + `baseline` + `compare` | Baseline → (operator offline deletes region → restart) → `probe` → `compare beforeFile test ...`. Check `blockEntityCount`, `spawnerCount`, `BE by type`. Visual: `/lostcities debug`. |
| **T09 Spawner/BE return** | Same as T08 | Compare `spawnerCount`, `containerCount`, `blockEntitiesByType` in JSON. |
| **T11 Vanilla loot duplication** | `probe` container count + Lootr doctor | `doctor` shows `Lootr disabled` → expect vanilla duplicate. Open chests before regen, record `containerCount`, after regen compare. Classify loot via `kubejs` audit — no code yet. |
| **T20 Boundary seam** | `probe` two adjacent sectors | `/expedition sector probe adj1 minecraft:overworld 0 0 31 31` and `adj2 32 0 63 31` — check `Create`/`BE` counts. After regen of one, inspect seam via `F3+G` and `nbtdump`. |
| **T23 Determinism cycles** | `baseline` ×3 + `compare` | Run `baseline → (offline regen) → baseline → compare → repeat 3×`. Hashes in `bigbangexpeditions/baselines/*.sha256` must match. |
| **T29 Validation fails → REFUSED** | `probe` verdict | If `probe` reports WARN/REFUSED (players inside, claims intersect, BEs present) → **do not regen**. Manual `compare` mismatch also REFUSED. |
| **T30 Unknown mod data** | `scripts/inspect-world-data.sh` + `probe unknownNamespaces` | `./scripts/inspect-world-data.sh world` lists `world/data/*.dat` with hints; `probe` flags `unknownNamespaces`. If unknown → REFUSED. |

## Fail-Closed Probes

Each `probe`/`baseline` runs:

- Bounds validation (region-aligned, `32×32` chunks). Invalid → `REFUSED`.
- Player inside AABB → WARN/REFUSED.
- OPAC inspect via `OpacAdapter` reflective. If `ServerClaimsManager` null/dim unavailable/exception → `REFUSED: OPAC unavailable`.
- Loaded chunk BE scan (Create/IE/RS/SC) → WARN if present; unknown ns → WARN.
- Entity scan → WARN.

If verdict `REFUSED`, operator must not proceed to destructive `rm`.

## Offline Hash Workflow (operator, outside mod)

```bash
# 1. Baseline
/expedition sector baseline run1 minecraft:overworld 0 0 63 63
./scripts/baseline.sh world run1
# 2. Validate no claims/players via probe
/expedition sector probe run1 minecraft:overworld 0 0 63 63
# → must be PASS or WARN with explicit operator ack, never REFUSED
# 3. Stop server, operator manually (Goal 02) would rm region files
#   systemctl stop deceasedcraft
#   tar czf backup.tgz world/region/r.0.0.mca ...
#   rm world/region/r.0.0.mca world/entities/r.0.0.mca world/poi/r.0.0.mca
#   # scrub world/data/lootr*.dat by pos if needed
#   systemctl start deceasedcraft
# 4. Probe again + compare
/expedition sector probe run1 minecraft:overworld 0 0 63 63
/expedition sector compare run1_minecraft_overworld_*.json run1 minecraft:overworld 0 0 63 63
./scripts/baseline.sh world run1_post
diff bigbangexpeditions/baselines/run1_*.sha256 bigbangexpeditions/baselines/run1_post_*.sha256
```

Goal 01 provides steps 1,2,4; step 3 is intentionally not implemented.

## Scripts

- `scripts/baseline.sh [world_dir] [label]` — hashes `region/entities/poi/data` and profiles.
- `scripts/inspect-world-data.sh [world_dir]` — lists `world/data/*.dat` with mod hints.

Both are read-only; they never write to `world/`.

## Evidence for Goal 01 PASS

- `./gradlew test` (25 tests)
- `logs/latest.log` showing `Registered /expedition commands` and `doctor` output (see goal-01-results.md)
