# Expedition Telemetry — Goal 05 Architecture

Status: implemented (WS1/WS2). Owner: BigBangExpeditions.

## Purpose

A durable, bounded, generation-scoped model of what players actually did
inside an expedition, sufficient to drive lifecycle decisions — and nothing
more. Telemetry is ADVISORY-GRADE evidence: it can recommend and explain, it
can never by itself authorize destruction.

## Data ownership & placement

All files live under `<server>/bigbangexpeditions/` OUTSIDE the world
directory (`core/BbeLayout`), so regeneration cannot destroy operational
evidence:

| File | Content | Bound |
|---|---|---|
| `telemetry/gen-<N>.json` | current generation's aggregates | deleted after archival |
| `cycle-history.json` | bounded archive of `CycleSummary` (newest 50) | hard cap |
| `automation-state.json` | scheduler/hysteresis/pending/shadow state | shadow ring cap 200 |

## Model (`telemetry/GenerationTelemetry`)

Aggregates only. No chat, no positions, no per-player timelines.

* identity: `generation` + `schemaVersion`; a record accepts facts ONLY for
  its own generation and only while not closed.
* counters (saturating via `Saturation`, ceiling 1e12): entries, exits,
  evacuations, deaths, container opens, player mob kills.
* sets: unique explorers (UUID strings, cap 10 000 + overflow counter),
  first-entry chunks (packed `ChunkPos.asLong`, cap 131 072 + overflow).
* structures: per structure-id sighting sets of section positions (cap 4096
  each) — inherently deduplicated by the reference set.
* activity: UTC day buckets (entries/chunks/structures/opens/deaths/kills +
  capped uniques), rolling window ≤ 90 days, trimmed on every mutation burst
  AND at flush time.
* quality flags: `CHUNK_SET_SATURATED`, `UNIQUE_SET_SATURATED`,
  `STRUCTURE_TYPES_SATURATED`, `STRUCTURE_SIGNAL_ABSENT`.
* `probeChunks`: how many chunks were actually probed for structure refs —
  the denominator for the structure-signal-absent verdict.

Privacy posture (requirement 2): identifiers are opaque UUIDs used solely to
deduplicate "distinct explorers". Nothing links gameplay acts to identities
beyond aggregate membership. Retention = current generation + 50 summaries.

## Persistence semantics

`TelemetryStore`: Gson JSON, atomic tmp+`ATOMIC_MOVE`, lock-guarded.

Fail-safe contract (requirements 7/43):

* corrupt / truncated / mismatched-generation file → `CORRUPT` outcome, bytes
  quarantined as `.corrupt-<ts>`, NO usable record returned;
* future schema → `UNSUPPORTED_SCHEMA`, original preserved;
* absent file → fresh record bound to the requested generation.

In every non-AVAILABLE case the engine receives an unavailable snapshot and
UNKNOWN health — corrupted telemetry can never manufacture depletion evidence.

Write amplification control (requirement 56): dirty-flag + 1 Hz fast-path tick
that persists only when `dirty && interval elapsed` (default 30 s) + flush on
server stop. Crash loss window for advisory data is therefore ≤ one flush
interval; lifecycle safety data remains under its own stricter regime
(lifecycle.json is written synchronously on every transition, unchanged from
Goal 03).

## Ingestion (`telemetry/TelemetryService`)

Registered on the Forge bus. All handlers dimension-gated to
`bigbangexpeditions:expedition`.

| Fact | Trigger | Dedup |
|---|---|---|
| entry | `BbeEvents.PlayerEnteredExpedition` | unique-set |
| exit | PlayerLeftExpedited / PlayerEvacuated | counter |
| evacuation | PlayerEvacuated(mode=TELEPORT_OUT) | counter |
| chunk discovery | sampled PlayerTickEvent (per-player staggered hash cadence, default 5 s) | first-entry set |
| structures | same sample, via `integration/structures/StructureProbe` reading ONLY the entered chunk's structure-reference table | section set |
| container open | RightClickBlock main-hand on `Container` BE | `InteractionDeduper` 60 s window/player+pos, capacity 4096 |
| death | LivingDeathEvent(ServerPlayer) | counter |
| mob kill | LivingDeathEvent(Enemy victim, ServerPlayer killer) | counter |
| peak concurrent | every ~6th sample while players inside | max |

Idle cost: zero disk IO when clean; zero world scans ever (structure probe
piggybacks chunks players already load).

Presence-vs-activity rule: concurrent-inside observation deliberately does NOT
refresh `lastActivityEpochMs`. An AFK inhabitant cannot keep a dead zone alive
(FN1) — abandonment reads from real events only.

## Generation rollover

* close: `ExpeditionCompleted` → `finalizeGeneration` marks closed, appends
  `CycleSummary` to `cycle-history.json`, deletes `gen-<N>.json` (bounded),
  unbinds.
* open: `ExpeditionOpened` → bind fresh store for the new generation.
* boot catch-up: any `gen-<N>.json` with N < lifecycle generation that is
  closed-but-unarchived (crash between close and archival) is archived with
  reason `unrecorded-restart` then removed; stale OPEN files from interrupted
  cycles are KEPT untouched (never guessed) and surfaced via audit
  `TELEMETRY_STALE_OPEN_KEPT`.

## Read model (requirement 58)

`TelemetrySnapshot.of(record)` exposes immutable aggregates plus trailing-day
sums. Consumers: depletion engine, automation commands, future integrations —
persistence internals stay private.

## Known limits (documented honestly)

Lost Cities building templates may not register as vanilla structures. When
`probeChunks` exceeds the grace threshold (default 2000) with zero sightings,
the flag `STRUCTURE_SIGNAL_ABSENT` retires the structure component for the
generation instead of pretending knowledge. The depletion engine then relies
on coverage/loot/activity under its unknown-data policy. Staging campaigns
measure which signal classes actually fire on this pack.
