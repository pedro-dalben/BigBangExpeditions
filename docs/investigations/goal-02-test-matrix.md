# Goal 02 Test Matrix

Automated (JUnit, plain JVM — MC classes unbootstrapped by design):

| Area | Tests | Notes |
|------|-------|-------|
| RegionAlignment / SectorBounds | 13 | alignment, validation |
| OpacAdapter contract | 5 | fail-closed without mod |
| Baseline serialization | 4 | deterministic JSON |
| LostCitiesAdapter | 9 | profile resolution fail-closed, fingerprint traversal rejection |
| ReturnPosition codec | 5 | round-trip, traversal, NaN/Inf |
| SectorState machine | 5 | legal/illegal/idempotent/null |
| SectorRegistry | 7 | persistence round-trip, transition enforcement, failure recovery |
| SectorTopology | 4 | region-unit bounds, containment inverse |
| LootPolicy | 7 | audited anchors, fail-closed fallbacks |
| PreflightEngine | 12 | every refusal condition incl. players/claims/additions/guards |
| ResetPlanManifest | 8 | deterministic checksum, tamper detection, confinement |
| **Total** | **82** | `./gradlew test` green |

Live adversarial (staging, recorded):

| Condition | Result |
|-----------|--------|
| execute-reset with server RUNNING | REFUSED (43) |
| execute-reset / rollback without sentinel | REFUSED (42) |
| execute-reset with tampered manifest (maxChunkX→999) | REFUSED (47, checksum) |
| reset-plan without baseline | REFUSED in-command |
| sector OPEN → RESETTING attempt | rejected by state machine (unit + command layer) |
| probe with unaligned bounds | REFUSED in-command |
| validated tryToClaim in expedition dim | UNCLAIMABLE_DIMENSION |

Performance observations (Phase 25, single host):

| Operation | Measured |
|-----------|----------|
| Server boot (290 mods, warm) | ~21 s to Done |
| Region regeneration settle | ~150 s to stable census |
| Offline delete+backup per region | < 2 s file ops |
| Full-region probe (1024 chunks loaded) | seconds, inside RCON timeout; scans only loaded chunks — never mass-loads on tick thread |
| OPAC claim iteration | per-chunk map lookup; 1024-chunk sweep imperceptible |

Probe scanning deliberately reads only already-loaded chunks (read-only
guarantee); batch generation happens through vanilla `forceload` on the
server's own schedule rather than mod-driven chunk loads on the tick thread.
