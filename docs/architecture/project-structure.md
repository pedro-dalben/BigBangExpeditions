# Project Structure — BigBangExpeditions

**Mod:** `bigbangexpeditions` (Forge 1.20.1-47.4.0, Java 17, Gradle 8.8, ForgeGradle 6.0.21)  
**Package:** `com.bigbangcraft.expeditions`  
**Repo:** `/home/pedro/Documentos/java/deceasedcraft/BigBangExpeditions` (git)

## Layout

```
BigBangExpeditions/
├── build.gradle                 # ForgeGradle, mappings official 1.20.1, junit 5.10.0
├── settings.gradle
├── gradle.properties            # mc 1.20.1, forge 47.4.0, mod 1.0.0
├── gradle/wrapper/              # 8.8
├── gradlew / gradlew.bat
├── src/main/java/com/bigbangcraft/expeditions/
│   ├── BigBangExpeditions.java          # @Mod, registers /expedition
│   ├── command/
│   │   └── ExpeditionCommand.java       # /expedition doctor/world/sector probe/baseline/compare
│   ├── diagnostics/
│   │   ├── DoctorReport.java
│   │   └── DoctorService.java           # mod presence, LostCities profile, Lootr enabled, seed
│   ├── integration/
│   │   ├── lostcities/                  # (reserved, probe via DoctorService)
│   │   └── opac/
│   │       ├── ClaimInspectionResult.java
│   │       └── OpacAdapter.java         # reflective, fail-closed
│   ├── sector/
│   │   ├── SectorBounds.java            # id, dimension, min/max chunk, region-aligned
│   │   └── SectorProbeResult.java       # Verdict PASS/WARN/REFUSED + metrics
│   ├── validation/
│   │   ├── BaselineData.java            # JSON, deterministic TreeMap
│   │   └── BaselineService.java         # probe, toBaseline, write/read, compare
│   └── util/
│       └── RegionAlignment.java         # 32x32 chunks = 512 blocks
├── src/main/resources/
│   ├── META-INF/mods.toml
│   └── pack.mcmeta
├── src/test/java/com/bigbangcraft/expeditions/
│   ├── util/RegionAlignmentTest.java
│   ├── sector/SectorBoundsTest.java + ProbeResultTest.java
│   ├── integration/opac/OpacAdapterTest.java
│   └── validation/BaselineSerializationTest.java
├── scripts/
│   ├── baseline.sh                      # read-only hash collector
│   └── inspect-world-data.sh            # read-only world/data enumerator
└── docs/
    ├── investigations/goal-00*.md + goal-01-results.md
    ├── architecture/project-structure.md (this)
    └── operations/staging-validation.md
```

## Rules

- Read-only only in Goal 01. No `region`/`data` mutation, no reset logic.
- OPAC adapter is reflective to avoid hard compile dep; all failures → unavailable → REFUSED.
- Bounds must be region-aligned (32×32 chunks). Valid examples: `0 0 31 31` (1 region), `0 0 63 63` (4 regions), `32 32 63 63`.
- Baseline JSON uses `TreeMap` for deterministic serialization; no player inventories stored.
- Package `integration/lostcities` reserved for future dimension helpers; Goal 01 only probes profile via config file.

## Build

```bash
./gradlew build        # MCP 1.20.1 official, compiles + tests (25 tests, 0 failures)
./gradlew test
```

## Commands (operator only, permission 2)

- `/expedition doctor` — versions, integrations, warnings, structured log
- `/expedition world` — dimension + seed hash
- `/expedition sector probe <id> <dimension> <minX> <minZ> <maxX> <maxZ>`
- `/expedition sector baseline <id> <dimension> <minX> <minZ> <maxX> <maxZ>` → `bigbangexpeditions/baselines/`
- `/expedition sector compare <beforeFile> <id> <dimension> <minX> <minZ> <maxX> <maxZ>`
