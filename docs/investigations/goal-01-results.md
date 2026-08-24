# Goal 01 — Results

**Date:** 2026-08-23  
**Project:** `BigBangExpeditions` at `/home/pedro/Documentos/java/deceasedcraft/BigBangExpeditions`  
**Goal 01 status:** PASS (with staging caveats)  
**Goal 00 decision remains:** BLOCKED (static favors dimension B, but live regen not yet proven)

## 1. Phase 0 — Project bootstrap

- Created `BigBangExpeditions/` as git repo (commit `5858d10`).
- Copied Goal 00 docs to `docs/investigations/goal-00-*.md` (preserved, not rewritten). Verified:
```bash
ls -lh BigBangExpeditions/docs/investigations/
# goal-00-persistent-exploration.md 42K
# goal-00-test-matrix.md 11K
```
- `.gitignore` covers `.gradle/build/run/world/bigbangexpeditions`.

## 2. Phase 1 — Forge bootstrap

- `Minecraft 1.20.1`, `Forge 47.4.0`, `Java 17` (toolchain), `Gradle 8.8`, `ForgeGradle 6.0.21`, `mappings official 1.20.1`.
- Package `com.bigbangcraft.expeditions` with subpackages `command/diagnostics/integration/sector/validation/util`.

**Build evidence:**
```bash
./gradlew build
> Task :compileJava
> Task :processResources
> Task :reobfJar
BUILD SUCCESSFUL in 9s
7 actionable tasks: 3 executed
```

```bash
ls -lh build/libs/BigBangExpeditions-1.0.0.jar
# 37K  BigBangExpeditions-1.0.0.jar
sha256: c66710c098568d06b8b347a752a796b2c11ea848a95d7ac7bf3ccda13216d000
jar tf ... | grep mods.toml
# META-INF/mods.toml
```

**Server startup evidence (runServer, 60s timeout, truncated log):**
```
[modloading-worker-0/INFO] [bigbangexpeditions/]: BigBangExpeditions init — read-only diagnostics, no reset
[main/DEBUG] [ne.mi.fm.ja.AutomaticEventSubscriber/LOADING]: Attempting to inject @EventBusSubscriber classes into the eventbus for bigbangexpeditions
Forge Version package ... Found Forge version 47.4.0
MinecraftForge v47.4.0 Initialized
Forge Version Check ... [forge] Starting version check
[forge] Found status: OUTDATED Current: 47.4.0 Target: 47.4.10
Building unoptimized datafixer
ModLauncher running: args [--launchTarget, forgeserver, --fml.forgeVersion, 47.4.0 ...]
```
- Runs (`client/server/gameTestServer/data`) present via `gradle tasks | grep runServer`:
  - `runServer`, `runClient`, `prepareRunServer` all available.
  - `prepareRunServer BUILD SUCCESSFUL in 43s` (downloaded assets 1.20.1).
  - No destructive code: `grep -r "RegionFile\|delete\|SavedData" src/main/java` shows only read operations.

## 3. Phase 2 — Diagnostic commands

Registered `/expedition` (permission 2, operator) with:

- `/expedition doctor` — MC/Forge/mod versions, dimension, LostCities/OPAC/Lootr presence, Lootr `disable` probe, FTB Teams/Hordes/Create/IE/RS/SC presence, seed hash, warnings; logs via `BigBangExpeditions/Doctor`.
- `/expedition world` — dimension + seed.
- Read-only: no `setBlock`, no `remove`, no `save`.

**Evidence:** `src/main/java/com/bigbangcraft/expeditions/command/ExpeditionCommand.java` registers via `RegisterCommandsEvent`, `hasPermission(2)`. Doctor reads `ModList.get().isLoaded(id)` + `config/lootr-common.toml` file check + `level.getSeed()`.

## 4. Phase 3 — Sector probe

- `SectorBounds` enforces `32×32` region alignment via `RegionAlignment.isRegionAligned`. Example:
  - `0 0 31 31` → 1 region (1024 chunks) PASS
  - `0 0 63 63` → 4 regions PASS
  - `0 0 30 31` → `REFUSED: X not region-aligned`
- Probe collects: chunk count, `loadedChunks`, players inside via `AABB`, OPAC intersection, forceloads, BE counts by type/ns, `Create/IE/RS/SC` counters, spawners, containers, entities, `unknownNamespaces`. All read-only (only `level.getChunk`, `getBlockEntities`, `getEntities`).
- Verdict `PASS/WARN/REFUSED` — unknown persistence → WARN/REFUSED, never silent PASS.

**Evidence:** `SectorBoundsTest` (8 tests) + `ProbeResultTest` (3 tests) PASS.

## 5. Phase 4 — OPAC integration

- `OpacAdapter` reflective to avoid hard dep; `isOpacPresent()` checks `Class.forName("xaero.pac.common.server.claims.ServerClaimsManager")`.
- `inspectClaims(Server, Level, Bounds)` iterates `minX..maxX`, `minZ..maxZ` via `getClaim(int,int)`; fail-closed on `server null`, `level null` (dimension unavailable), `OPAC not present`, `getDimension null`, `getClaim` exception.
- Covers: fully inside, boundary, party/personal (both count as `claim != null`), unloaded (SavedData still present), dimension unavailable.
- Expected `REFUSED` when unavailable — caller `BaselineService.probe` marks `SectorProbeResult.REFUSED`.

**Evidence:**
```bash
unzip -l open-parties-and-claims-forge-1.20.1-0.25.8.jar | grep ServerClaimsManager
# ServerClaimsManager.class present
```
Tests: `OpacAdapterTest` (5 tests) PASS — including `failClosedWhenServerNull`, `claimInspectionAvailable`.

## 6. Phase 5 — Baseline export

- `BaselineData` deterministic JSON (TreeMap), fields: `id/dimension/bounds/timestamp/worldSeedHash/lostCitiesProfile/chunkCount/BE counts/containers/spawners/entities/loadedChunks/playersInside/BEbyType/BEbyNs/entitiesByType/opacStatus/warnings/unknownNamespaces`. No player inventories.
- Command writes to `bigbangexpeditions/baselines/<id>_<dim>_<epoch>.json` via `BaselineService.writeBaseline`.

**Evidence:** `BaselineSerializationTest` (4 tests) PASS — `deterministicSerialization` proves TreeMap sorting, `noSensitiveData` ensures no inventory.

## 7. Phase 6 — Comparison

- `BaselineService.compare(before, after)` diffs BE counts/types/containers/spawners/claims/entities/seed; used for T08/T09/T23/T29.

**Evidence:** `comparisonDetectsDiff` test PASS.

## 8. Phase 7 — Scripts

- `scripts/baseline.sh` — hashes `world/region/*.mca`, `entities`, `poi`, `data/*.dat`, `level.dat`, LostCities profiles; read-only.
- `scripts/inspect-world-data.sh` — lists `world/data/*.dat` with mod hints; read-only.

```bash
chmod +x scripts/*.sh
./scripts/baseline.sh world test
./scripts/inspect-world-data.sh world
```

## 9. Phase 8 — Tests

**All 25 tests PASS, 0 failures:**

```
./gradlew test
RegionAlignmentTest: 5
SectorBoundsTest: 8
ProbeResultTest: 3
OpacAdapterTest: 5
BaselineSerializationTest: 4
BUILD SUCCESSFUL
```

Covers: bounds validation, region alignment (32), intersection math, player-inside via `containsBlock`, namespace aggregation, deterministic JSON, comparison, fail-closed `REFUSED`, OPAC unavailable/available branches. No test requires `world/` data.

JAR evidence: `build/test-results/test/TEST-*.xml` — each suite 0 failures.

## 10. Acceptance criteria

| # | Criterion | Evidence |
|---|-----------|----------|
|1|BigBangExpeditions exists with complete project| `ls BigBangExpeditions/src` + `git log --oneline 5858d10`|PASS|
|2|Git initialized|`git status`, commit 5858d10|PASS|
|3|`./gradlew build` passes|`BUILD SUCCESSFUL in 9s`, jar `37K`|PASS|
|4|Forge 1.20.1 starts with mod|`runServer` log `BigBangExpeditions init — read-only diagnostics` + `Found Forge version 47.4.0`|PASS|
|5|Diagnostic commands read-only, no mutation|`ExpeditionCommand.java` only `getChunk/getBlockEntities/getEntities`, no `setBlock/remove`|PASS|
|6|OPAC fail-closed|`OpacAdapter.inspectClaims` returns `unavailable` on null/dim absent/exception → probe `REFUSED`|PASS|
|7|Probe detects players/claims/BE/unknown|`BaselineService.probe` AABB+OPAC+BE scan + unknown ns WARN|PASS|
|8|Baselines export/compare|`BaselineService.writeBaseline` + `compare` + tests + `bigbangexpeditions/baselines/` dir|PASS|
|9|Tests pass|`./gradlew test` 25 tests 0 failures|PASS|
|10|Goal00 preserved|`docs/investigations/goal-00-*.md` copied, `git add` includes|PASS|
|11|goal-01-results.md with evidence|this file|PASS|

**No destructive code:** `grep -R "rm \|delete\|RegionFile" src/main/java` finds no region deletion; `BaselineService` only reads; mod description `read-only diagnostics, no reset`.

## 11. Staging mapping (see `docs/operations/staging-validation.md`)

- T02 via `baseline` + `baseline.sh`
- T06 preparation only (hashes), not executed
- T08/T09/T23/T29 via `probe` + `compare` + `baseline.sh` diffs
- T11 observation via `doctor` Lootr `disabled` probe
- T20 via two adjacent `probe` + seam visual
- T30 via `inspect-world-data.sh` + `unknownNamespaces`

## Verdict

```
GOAL 01: PASS
```

All infrastructure is read-only, tested, and built. Destructive regeneration remains intentionally unimplemented. Goal 00 stays `BLOCKED` until staging executes the full `baseline → (offline rm) → validate` cycles with the harness.

## Next steps (Goal 02, not now)

- Provision staging server with fixed seed/profile.
- Run 3 cycles of `baseline.sh` + `probe` + `compare` and publish hashes.
- Audit loot tables for `research_paper_*` and implement filter before any regen.
- Create expedition dimension prototype and re-run doctor/probe in that dimension.

