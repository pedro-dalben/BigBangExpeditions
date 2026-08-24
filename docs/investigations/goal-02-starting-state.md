# Goal 02 — Starting State

**Date:** 2026-08-23
**Branch:** `feat/goal-02-expedition-world` (created from `master`)

## Git

* Base commit: `f3ee76f` — `docs: Goal 01 results + run configs for server startup`
* Prior commit: `5858d10` — `feat: Goal 01 bootstrap — Forge 1.20.1-47.4.0 read-only diagnostics harness`
* Working tree: clean at branch creation.

## Toolchain

| Component | Version |
|-----------|---------|
| Minecraft | 1.20.1 |
| Forge | 47.4.0 (`gradle.properties`) |
| Gradle | 8.8 (wrapper) |
| Java toolchain | 17 (build-time; daemon JVM is Adoptium 21) |
| Mappings | official 1.20.1 |
| Mod version | 1.0.0 |

## Baseline verification (this session)

* `./gradlew test` → **BUILD SUCCESSFUL**
* Test suites: **25 tests, 0 failures**
  * `RegionAlignmentTest` — 5
  * `SectorBoundsTest` — 8
  * `OpacAdapterTest` — 5
  * `BaselineSerializationTest` — 4
  * `ProbeResultTest` — 3
* `./gradlew build` → **BUILD SUCCESSFUL**

## Existing commands (Goal 01)

All under `/expedition`, operator-only (permission 2):

* `/expedition doctor` — versions, Lost Cities presence, OPAC/Lootr/FTB/Hordes/Create/IE/RS/SC presence, seed hash, warnings.
* `/expedition world` — current level id, seed hash, build heights.
* `/expedition sector probe <id> <dim> minX minZ maxX maxZ` — read-only probe: players inside, OPAC claims (fail-closed), loaded-chunk block entities by type/namespace, containers/spawners/entities, Create/IE/RS/SC counts, unknown namespaces. Verdict PASS/WARN/REFUSED.
* `/expedition sector baseline ...` — writes JSON baseline to `<server>/bigbangexpeditions/baselines/`.
* `/expedition sector compare <beforeFile> ...` — diff baseline vs current probe.

## Project architecture at start

```text
com.bigbangcraft.expeditions
├── BigBangExpeditions          (@Mod, command registration via Forge bus)
├── command/ExpeditionCommand   (brigadier tree)
├── diagnostics/
│   ├── DoctorReport            (POJO)
│   └── DoctorService           (ModList probes + config file reads)
├── integration/opac/
│   ├── OpacAdapter             (reflective OPAC claims inspection, fail-closed)
│   └── ClaimInspectionResult
├── sector/
│   ├── SectorBounds            (region-aligned chunk rect, validate())
│   └── SectorProbeResult       (verdict PASS/WARN/REFUSED + metrics)
├── util/RegionAlignment        (32×32 chunk region math)
└── validation/
    ├── BaselineData            (GSON serializable)
    └── BaselineService         (probe/baseline/read/write/compare)
```

Scripts:

* `scripts/baseline.sh` — read-only sha256 collector for region/entities/poi/data.
* `scripts/inspect-world-data.sh` — read-only `world/data/*.dat` enumerator with ownership hints.

No reset code exists anywhere in the mod or scripts.

## Preserved documents (unchanged)

* `docs/investigations/goal-00-persistent-exploration.md`
* `docs/investigations/goal-00-test-matrix.md` — remains BLOCKED pending live evidence (T02/T06/T08/T09/T11/T19/T20/T23/T29/T30).
* `docs/investigations/goal-01-results.md`
* `docs/architecture/project-structure.md`
* `docs/operations/staging-validation.md`

## Goal 02 scope reference

See goal definition: dedicated `bigbangexpeditions:expedition` dimension, Lost Cities integration adapter, persistent sector registry with explicit state machine, SavedData inventory, loot audit/policy, fail-closed reset preflight + plan manifest + staging-only offline executor + verified rollback, ≥10 regeneration cycles, adversarial/crash-recovery/performance validation, docs set, and evidence-backed final architecture decision (B1/B2/B3/BLOCKED).

Production destructive reset stays **DISABLED** for the entire goal.
