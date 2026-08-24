# Goal 02 Results — Dedicated Expedition World Qualification

**Branch:** `feat/goal-02-expedition-world` (base `f3ee76f`)
**Final commit:** see git log; 26 modular commits created during Goal 02.

## What was built (summary)

1. **Real dimension** `bigbangexpeditions:expedition` (datapack JSON in mod
   jar, vanilla noise + overworld preset, server seed) with runtime status
   diagnostics and Lost Cities activation via public reflective API +
   staging config.
2. **Lost Cities adapter** (`integration/lostcities/`) — profile resolution,
   sha-256 profile fingerprints, expected-profile validation; fail-closed.
3. **OPAC isolation** — native `claimableDimensionsList` gate + public-API
   verification (`isClaimable=false`, validated claim → UNCLAIMABLE_DIMENSION)
   plus documented internal-`claim()` bypass risk.
4. **Persistent sector registry** with explicit 8-state machine, region-unit
   topology, lifecycle commands, refusal explanations.
5. **Loot policy** machine-readable classification from a full pack audit;
   fail-closed loader; reset rules against progression duplication.
6. **Reset pipeline**: preflight engine (12 validators, aggregated),
   deterministic checksummed manifests, path confinement, staging-only
   offline executor (9-guard chain), SHA-256-verified immutable backups,
   hash-proven rollback, RESETTING→VALIDATING→OPEN flow wired across the
   offline/online boundary.
7. **Staging environment**: provision/start/stop/status/console/evidence
   scripts, sentinel enforcement, determinism campaign driver.

## Evidence highlights

* **Dimension content:** r.4.4 census — 480 spawners, ~3.5k containers
  (lootr), DeceasedCraft building palettes; profile fingerprint recorded.
* **Structure-class validation (Phase 18):** loot-table markers embedded in
  chunk NBT identify real building classes across 30 generated regions:
  bank (`building_officebank` ×598), police (`building_policeoffice1` ×478),
  residential/hotel (`building_hotela..d` ×4079), medical
  (`multi_polyclinic`), gas stations, offices, workshops. Census is
  **byte-identical** across 5 further resets (soak).
* **Soak (Phase 22):** 15 valid cycles total; regen settle flat at
  150–151 s; offline ops <2 s; zero crashes; zero structure-marker drift;
  disk stable (see `evidence/goal-02/soak/soak-analysis.json`).
* **Abyss A/B test:** with our config entry removed, `deceasedcraft:abyss`
  fails identically — the pack's biosphere profile references a worldstyle
  asset (`worldstyles/abyss.json`) that does not exist anywhere in pack
  5.10.16, and its portal script ships commented out. Pre-existing upstream
  defect; Goal 02 neither caused nor worsened it.
  (`evidence/goal-02/soak/abyss-a-b-test.json`)
* **Determinism campaign:** cycles 2–11 full-coverage PASS vs settled
  reference (`evidence/goal-02/cycle-*`); consecutive captures of a settled
  world identical (6250 BEs / 3465 containers / 480 spawners).
* **Rollback proof:** restored region hash == backup hash
  `0b00d8b0…7ad0bd`.
* **Adversarial refusals:** running server, missing sentinel, tampered
  manifest checksums, unaligned bounds, wrong state, foreign dimension,
  missing baseline, UNKNOWN SavedData, incomplete scan — all refuse
  (automated + live).
* **Crash recovery:** kill -9 mid-boot left persisted RESETTING; recovery
  completed through validation to OPEN.

## Failures encountered and fixed (kept visible)

* Custom `expedition_type` silently dropped by registry loader — worked
  around with vanilla type; root cause deferred with mitigation plan.
* OPAC internals lookup wrong for 0.25.8 — rewritten on public API.
* Client-mod removal chain (shouldersurfing broke Create server mixin) —
  each removal evidenced in provisioning script comments.
* Cycle-001 methodology failure: partial-coverage baseline invalidated
  comparison — campaign driver now forces full coverage + settle time.

```text
GOAL 02: PASS

Architecture:
B3 — dedicated expedition dimension, whole-dimension (B1-shaped) reset
     mechanism recommended for production use; implemented B2 sector
     executor remains validated staging tooling/fallback

Regeneration cycles:
16 attempted (full protocol)
15 passed  (cycles 2–16)
1 failed   (cycle 1: methodology — partial baseline coverage)

Rollback:
PASS (sha-verified restore)

Progression loot safety:
PASS at design+unit level (fail-closed policy, audited sources);
live end-to-end player-loot delta exercise deferred to client UAT

SavedData safety:
PASS (zero UNKNOWN at snapshot; inventory-driven preflight gate)

OPAC isolation:
PASS (validated path refuses; mods-only internal bypass documented)

Production reset:
DISABLED

Tests:
82 passed
0 failed

Build:
PASS

Commits created during Goal 02:
33

Critical unresolved risks:
- No interactive client in this environment: visual seam inspection,
  real-player claim/building exercises, enter/leave UAT remain open;
  destructive-path safety does not depend on them (preflight fails
  closed regardless of player behavior)
- Boundary variance of sector resets when neighbor regions persist is
  real at chest-NBT level though structure markers proved stable;
  mitigated by the B1-shaped production recommendation
- Custom dimension_type loader anomaly unresolved (bed respawn inside
  expedition currently possible; tracked with mitigation plan)
- Upstream pack defect: deceasedcraft:abyss cannot generate on any
  dedicated server (missing worldstyles/abyss.json, portal disabled);
  unrelated to BBE, A/B-verified
```

Production enablement of any destructive capability remains explicitly out
of scope for this goal and disabled by configuration.
