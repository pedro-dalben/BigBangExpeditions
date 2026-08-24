# Player Expedition Lifecycle — Architecture (Goal 04)

Status: implemented on `feat/goal-04-expedition-gameplay`.
Companion: `expedition-sector-model.md`, `production-lifecycle.md` (Goal 03).

## The canonical journey

```text
Persistent world
   │  /expedition status        (anywhere, perm 0)
   │  /expedition enter         (only while lifecycle OPEN)
   ▼
Expedition territory  ── explore / combat / loot / temporary camp
   │  /expedition where        (district + coordinates)
   │  /expedition leave        (any time, safe return)
   ▼
Persistent world (stored return point → central-shelter fallback)
```

Escalating warnings announce closure; extraction at the deadline is automatic.
Nobody needs operator help for a normal cycle.

## Layers

```text
command/JourneyCommand (perm 0)          command/OpsCommand (2/3), LifecycleCommand (2/3)
            │                                        │
gameplay/ExpeditionAccessService ───────────┘    (single legitimate route in/out)
  │ EntryDecision · PlayerStateMapper · RateLimiter · ReturnLocationPolicy
  │
player/SessionRecovery (login matrix) · player/RespawnRedirect (death policy)
gameplay/DimensionTravelGate (alternate arrival routes: portals, teleport items)
gameplay/ClosureService + ClosingSchedule (timed closing, warnings, extraction)
event/BbeEvents (internal seam for future goals)
i18n/Translations (pt_br default, en_us fallback; server-resolved)
```

Pure decision cores are unit-tested without Minecraft; thin adapters touch the
server. This preserves the Goal 02/03 architecture discipline.

## State knowledge players receive

| Technical state | Player wording (`bbe.state.*`) | Entry |
|---|---|---|
| OPEN | ABERTA / OPEN | allowed |
| CLOSING, EVACUATING | FECHANDO / EM EVACUAÇÃO | refused (specific message) |
| every other state | TEMPORARIAMENTE INDISPONÍVEL | refused |

Raw enum names never reach players (regression-tested).

## Closing sequence

1. Operator `/expedition lifecycle close` → `LifecycleService.startClosing`
   persists `closingDeadlineEpochMs` (default 15 min, `closingDurationMinutes`).
2. Tick check (1 s cadence) emits due thresholds from
   `closingWarningOffsetsMinutes` (default 15/5/1): chat + action bar + alarm.
   Emission idempotency is persisted (`lastClosingWarnMinutes`) so restarts
   never duplicate or skip warnings.
3. Deadline ⇒ proven Goal-03 extraction runs automatically:
   evacuateAll → EVACUATING → verify empty → LOCKED, fully audited.
4. `close immediate` keeps the legacy synchronous path; `abort-close` cancels.
5. A restart mid-CLOSING resumes the schedule; an already-past deadline
   extracts immediately at boot (validated live).

## Opening ceremony

VALIDATING→OPEN (validation-gated as in Goal 03) triggers the localized
broadcast (`bbe.opening.title/body`, zone number = generation) plus an
`OPENING_ANNOUNCED` audit record and an `ExpeditionOpened` event.

## Recovery matrix (login/logout/death)

Implemented by `LoginRecoveryDecision` (pure) + `SessionRecovery` (adapter):

| Was inside | Login state | Action |
|---|---|---|
| no | any | untouched |
| yes | OPEN, same generation | restore in place |
| yes | OPEN, generation changed | recover to stored return point → shelter |
| yes | CLOSING/EVACUATING/LOCKED/…/RECOVERY_REQUIRED | complete eviction, localized message, audited |
| transfer flag set | any | resolve to safe persistent-world position |

Generation ambiguity (unknown `-1`, regression) always fails closed to
recovery — stale coordinates are never trusted over regenerated terrain.

Death policy: hardcore consequences unchanged (drops/Corpse mod behave
normally). Beds/sleeping bags may be placed and slept in, but spawn anchoring
inside expedition is cancelled (`PlayerSetSpawnEvent`); any respawn that lands
inside expedition is redirected immediately (`PlayerRespawnEvent`). If the
zone closes between death and respawn the redirect still applies — respawn
never enters a closed dimension.

## Alternate-route gate

`DimensionTravelGate` subscribes `EntityTravelToDimensionEvent` (HIGHEST):
arrival INTO `bigbangexpeditions:expedition` is cancelled unless the lifecycle
is OPEN — covering nether-portal round trips started inside the zone, Wormhole
portals, and future travel mods. Refusals are audited with a 5 s per-player
quiet window against portal re-fire spam. Admin routes (`/execute in …`)
remain available by design.

## Building & storage contract (player-facing)

Temporary survival building allowed; permanent storage/industry pointless by
design. The contract is stated at entry (`bbe.entry.warning.build/death/claim`)
and repeated during closing warnings ("nothing you leave survives").
Enforcement truth table:

| Concern | Mechanism |
|---|---|
| Claims | OPAC config excludes the dimension; preflight CLAIMS_INTERSECT hard-refuses resets |
| Forceload backdoor | OPAC forbids existing forceloads in unclaimable dims; preflight FORCELOADS warns |
| Player-built BEs vs reset | DIMENSION-scope purge manifest with explicit operator acknowledgment (`PurgeManifest`, hash-bound) |
| Progression items | Goal 02 loot-policy gates unchanged |
| Confinement | Goal 03 executor confinement/backup verification unchanged |

## Performance notes

* tick handler: one lifecycle-record read per second while CLOSING only;
* sector lookup: O(n) over a handful of districts, invoked per command only;
* rate limiter bounds enter/leave IO amplification;
* login validation: O(1) persistent-data reads + one lifecycle load.
