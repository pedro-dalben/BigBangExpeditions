# Goal 04 Initial Assessment — Expedition Gameplay & Player Experience

**Date:** 2026-08-24
**Branch:** `feat/goal-04-expedition-gameplay`
**Baseline:** master @ `7d52515` (Goal 03 final, GOAL 03: PASS, 224 tests green)
**Method:** every claim below was verified against actual source, configs, staging
server state and pack content on 2026-08-24. No behavior is inferred.

---

## 1. Current Player Experience (as-is facts)

### 1.1 Who can actually use the system today

Nobody except operators. Every entry point requires permission level ≥ 2
(`ExpeditionTeleportCommand.java:37`, all other commands likewise).
An ordinary survivor **cannot enter the expedition dimension at all**.
The player journey required by Goal 04 does not exist yet; only its technical
skeleton does.

### 1.2 Entry (`/expedition enter [x z]`)

`command/ExpeditionTeleportCommand.java:60-105`:

* Lifecycle gate: `EntryDecision.check(state)` — only `OPEN` admits;
  unreadable lifecycle refuses fail-closed and audits `EXPEDITION_ENTER`
  refusal (`RuntimeServices.auditRefusal`).
* Refuses when expedition dimension not loaded or Lost Cities profile absent.
* Stores return position in player PersistentData key
  `bigbangexpeditions_return_position` (`teleport/ReturnPosition`, string codec
  `dim|x|y|z|yaw|pitch`, traversal-safe deserialization).
* Sets persistent marker `bigbangexpeditions_inside`
  (`EvacuationService.INSIDE_MARKER`).
* Teleports with heightmap surface placement (Y+1), same x/z as current
  position by default; optional x/z args.
* Feedback: single hardcoded English chat line ("Entered expedition at …").

### 1.3 Leave (`/expedition leave`)

`ExpeditionTeleportCommand.java:107-139`:

* Only works while inside the dimension; reads stored return position.
* Stored dimension missing → overworld spawn fallback (never traps player).
* Clears marker + return data, marks outside, audits nothing for leave
  (no audit event on voluntary exit today).

### 1.4 Death / respawn

* **No death or respawn handling exists anywhere in the mod**
  (grep: no `PlayerRespawnEvent`, no respawn logic).
* Pack uses **Corpse mod** (`corpse-forge-1.20.1`) — items go into corpse
  entities spawned where the player died, including inside the expedition
  dimension. Corpses live in the entities folder → destroyed by whole-dimension
  regeneration without warning to players.
* Dimension type workaround (`goal-02-dimension-type-issue.md`): expedition uses
  vanilla `minecraft:overworld` type ⇒ **beds work inside expedition**.
  A bed in expedition creates a real respawn anchor into disposable territory.

### 1.5 Login / logout

* Logout inside expedition: marker + return position persist in player NBT.
* Login handler exists (`BigBangExpeditions.onPlayerJoin` →
  `EvacuationService.onJoin`, `EvacuationService.java:90-110`):
  * marker present + lifecycle OPEN → **player restores in place**, even across
    a reset cycle (generation is NOT checked — stale-coordinate restore onto
    freshly regenerated terrain is possible);
  * marker present + any other state → evicted to overworld spawn, message,
    audited `PLAYER_EVACUATED/EVICT_ON_JOIN`;
  * exceptions swallowed (login never crashes) but silently.
* No logout handler at all. Disconnect mid-teleport is untracked (teleport is
  synchronous within one tick today, so window is small but non-zero).

### 1.6 Building / storage / claims / machines

* **Policy A decided in Goal 02** (`docs/architecture/expedition-building-policy.md`):
  building allowed, territory non-persistent by warning only. No placement
  interception exists (and Goal 02 explicitly rejected per-mod block policing).
* Reset-time protection: preflight `checkNoPlayerAdditions`
  (`safety/PreflightChecks.java:101-116`) refuses authorization when ANY block
  entity type exceeds baseline census — i.e., **after any player places a chest,
  furnace or machine, production reset authorization REFUSES**
  (`PLAYER_ADDITIONS`). This collides head-on with Policy A gameplay under
  whole-dimension resets and must be resolved this goal (see §4.7).
* OPAC: staging serverconfig already excludes the dimension from claims
  (`claimableDimensionsList = ["bigbangexpeditions:expedition"]`,
  `ALL_BUT`; `allowExistingClaimsInUnclaimableDimensions = false`;
  `allowExistingForceloadsInUnclaimableDimensions = false`).
  Runtime verification via reflective `OpacAdapter` (public API verified vs
  open-parties-and-claims 0.25.8) + `/expedition opac-selftest`.
* Create/IE/RS/SecurityCraft all present in pack; nothing stops players from
  deploying them inside expedition once entry exists.

### 1.7 Closure / evacuation experience

* `/expedition lifecycle close` (perm 3): CLOSING → EVACUATING executes
  `EvacuationService.evacuateAll` → everyone to overworld spawn with one
  English maintenance line, per-player audit, refuses LOCKED while anyone
  remains. No warnings before close, no countdown, no escalation, no sounds,
  no titles. Players get zero advance notice.
* No broadcast of opening either — reopening after VALIDATING→OPEN is silent.

### 1.8 What an administrator must do today (manual burden)

To run one expedition cycle an operator must, by hand:
create sector (`sector create`), attach baseline, `lifecycle status` checks,
`close`, wait for evacuation, `begin-validation` after boot, capture fresh
baseline, `record-validation PASS`, `open`. There is no "session" concept,
no scheduling, no automatic countdown — every transition is manual Brigadier
input. Acceptable for ops, unworkable as a player-facing service.

### 1.9 Localization / feedback

* **Zero lang files exist** (`src/main/resources/assets/…/lang/` absent).
  All player-visible strings are hardcoded English `Component.literal`.
  Technical states (`VALIDATING`, `RECOVERY_REQUIRED`) leak verbatim into
  refusal text (`EntryDecision.reason` interpolates enum names).

### 1.10 Access routes that exist in the pack (bypass surface recon)

Verified in `.staging/server/mods` + kubejs:

| Route | Present? | Gated today? |
|---|---|---|
| BBE `/expedition enter` | yes | yes (perm 2 + EntryDecision) |
| Vanilla nether portals built inside expedition | yes (overworld-type dim) | **NO** — two-way portal re-entry returns into expedition regardless of lifecycle |
| Wormhole mod (portal_frame/target_device, cross-dim) | yes (`wormhole-1.1.16`), recipes partly gated by kubejs | **NO** |
| customportalapi | jar present; no expedition usage found in kubejs | latent |
| `/execute in bigbangexpeditions:expedition run tp …` | any perm-2 source | acceptable (admin route) |
| Death respawn via bed placed in expedition | yes (bed_works) | **NO** |
| EntityTravelToDimensionEvent hook | — | **not subscribed anywhere** |

### 1.11 Sector system today

* `SectorRegistry` persists `<server>/bigbangexpeditions/sectors.json` outside
  world dir. Sectors are region-aligned probe/diagnostic units (R1 preferred),
  used by baseline/preflight machinery and mirrored from dimension lifecycle on
  close/open (`LifecycleCommand.syncSector`).
* No display names, no player-facing identity, no location lookup.
* Whole-dimension production scope (`SCOPE_DIMENSION`) means sectors are NOT
  physical reset units anymore — free to become gameplay districts.

### 1.12 Environment / safety context carried from Goal 03

* Production destructive path disabled by default (env signals + ack file +
  fingerprint); offline executor with flock/journal/auth ledger; startup gate
  fail-closed to RECOVERY_REQUIRED; validation-gated reopen; audit log JSONL
  rotated; backups verified; generation counter increments exactly once per
  validated cycle. None of this may weaken (acceptance criterion 27/32).

---

## 2. Gameplay Gaps (classified)

### BLOCKER

* **B1 — No player-accessible journey.** enter/leave are operator-only; the
  core deliverable cannot happen without a permission-0 access surface.
* **B2 — Respawn can target expedition during maintenance.** Bed anchored in
  expedition + death → respawn INTO a dimension that may be CLOSING/LOCKED/
  freshly regenerated. Player can end up inside a locked maintenance window
  with no exit command they can use (enter/leave are perm 2).
* **B3 — Post-reset restore-in-place.** `onJoin` accepts marker when OPEN
  without comparing generation → player materializes onto regenerated terrain
  at stale coordinates (suffocation/fall/buried risk, "ghost memory" of old
  geography).

### HIGH

* **H1 — Alternate access paths ungated.** Nether-portal round trips and
  Wormhole portals can place players into the dimension during non-OPEN
  states. Needs a dimension-travel gate keyed on `EntryDecision`.
* **H2 — No closure communication.** Forced evacuation arrives unannounced;
  escalation model absent entirely (prompt §10/§11 mandatory outcomes).
* **H3 — Zero localization infrastructure.** pt_br/en_us mandated; all text
  currently hardcoded; enum states leak to players.
* **H4 — Preflight PLAYER_ADDITIONS deadlock.** With Policy A building, every
  real expedition makes later authorization refuse — production loop jams
  after first played cycle until someone hand-deletes player builds or the
  gate is redesigned (decision §4.7).
* **H5 — Voluntary leave unaudited & unsafe-position-blind.** Return position
  restored blind (no suffocation/void/lava check beyond dimension existence).

### MEDIUM

* **M1 — No opening/closing ceremony** (fantasy language requirement §12).
* **M2 — Sectors have no gameplay meaning** (naming/where-am-I absent).
* **M3 — No telemetry surfaces** (players-inside count, refusals, distribution)
  beyond raw audit JSONL.
* **M4 — Gameplay config externalization** (warning intervals, announcement
  toggles, permission levels) — everything constant-coded.
* **M5 — Vehicles/entities left in expedition**: DragN_Vehicles /
  immersive_aircraft parked inside are entity-folder casualties; no messaging.

### LOW

* **L1 — Terminology polish** ("farm world" tone absent but messages dry).
* **L2 — `/expedition where` niceties** (compass/action-bar transitions).
* **L3 — Duplicate-notification risks** when both join-evict and closure-
  evacuation could theoretically hit same login (ordering guard needed).

---

## 3. Abuse / Exploit Surface

| # | Vector | Current exposure | Planned counter |
|---|--------|------------------|-----------------|
| E1 | Logout to dodge evacuation | Marker evicts on next join unless OPEN; but if reopened meanwhile, stale restore (B3) | Generation-aware recovery |
| E2 | Logout right before reset, return to loot regen'd area early | Same as E1 + no penalty concept | Recovery lands you at your return point ONLY if same generation; otherwise spawn-side |
| E3 | Item duplication via reset timing | Loot policy: PROGRESSION_ITEM delta refuses reset (preflight); corpses/containers destroyed | Keep progression gates; add purge-ack visibility (§4.7) |
| E4 | Safe storage inside expedition | Chests survive indefinitely while OPEN — fine (temporary by definition); contents die at reset | Messaging + closure warnings; no change needed |
| E5 | Permanent machines (Create/IE/RS) | Allowed by Policy A; destroyed at reset; PLAYER_ADDITIONS currently blocks reset (H4 deadlock) | Purge-ack redesign; explicit loss messaging |
| E6 | Claims in expedition | OPAC config blocks new claims; existing disallowed | Verify each cycle (opac selftest evidence) |
| E7 | Forceload backdoor | OPAC forceloads disabled in unclaimable dims; smoothchunk dynamic loading follows players only; Chunky is admin pregen | Preflight FORCELOADS warn retained; Chunky scope documented admin-only |
| E8 | Portal bypass (nether round-trip, Wormhole) | UNGATED (H1) | EntityTravelToDimensionEvent gate refusing travel INTO expedition unless OPEN |
| E9 | Respawn anchor (bed in expedition) | Works (B2) | Respawn redirect policy (§4.3) |
| E10 | Death-drop smuggling between cycles | Corpses destroyed at reset = loss, not dupe; items carried out legitimately | By design; document |
| E11 | Command spam enter/leave | Each call does IO (lifecycle load/save, audit append) | Rate-limit/idempotency in access service; cheap cached state read |
| E12 | Disconnect during teleport | Unobserved today | Transfer-in-progress flag cleared/resolved on join |
| E13 | Trapped after maintenance | B2/B3 cover worst cases | Login matrix (§4.2) + always-available perm-0 leave |

---

## 4. Gameplay Design Decisions Required (with recommendations)

### 4.1 Entry mechanism — **recommendation: lifecycle-gated command + fixed entry point semantics**

Options weighed: physical portal (customportalapi/Wormhole infra exists but
placing frame blocks inside expedition would create internal loops), NPC
(CustomNPCs present but adds content authoring dependency), interaction block
(needs block+model+placement story), plain command.

Recommendation: **permission-0 `/expedition enter`** styled as an official
"Military Evacuation Protocol" action, with entry feedback communicating
status/risks. Rationale: zero worldgen coupling, fully testable, respects
hardcore tone (the fantasy is military quarantine logistics, not magic portals),
and matches existing architecture (EntryDecision already central). Physical
flavor (NPC terminal) can be layered later by third-party content without
changing the gate. Not a warp plugin: single destination semantics, no coords
argument exposed to players, no arbitrary waypoints.

### 4.2 Login/logout matrix (mandatory behaviors)

| Logout state | Login state | Behavior |
|---|---|---|
| inside, gen N | OPEN, gen N | Restore in place (validate position safe first) |
| inside, gen N | OPEN, gen N+1 (post-reset) | Recover to stored return pos → fallback chain; message about lost zone |
| inside | CLOSING/EVACUATING | Complete eviction to return pos/spawn; message |
| inside | LOCKED..VALIDATING | Same eviction; friendly "temporarily unavailable" wording |
| inside | RECOVERY_REQUIRED | Evict like other non-OPEN states (fail-closed) |
| mid-transfer flag set | any | Resolve to safe persistent-world location |
| outside | anything | Normal login; never touched |

Return-position fallback chain: stored pos (dimension loaded + y in bounds +
feet/head non-solid checked) → overworld shared spawn. Never overwrite an
existing stored destination except at explicit enter time.

### 4.3 Death/respawn — **recommendation: preserve hardcore loss; never respawn into expedition**

* Items: vanilla drops + Corpse mod behave normally inside expedition. Corpses
  lost when territory regenerates — communicated at entry ("your body will not
  be recoverable after the zone closes").
* Respawn: intercept `PlayerRespawnEvent`; if respawn position resolves inside
  expedition (bed/sleeping bag placed there), redirect to the player's stored
  return position or persistent-world fallback with clear message. Keeps beds
  useful as camp flavor (sleep skips night) but kills the anchoring exploit and
  satisfies B2.
* If expedition closes between death and respawn: same redirect handles it —
  respawn never enters a closed dimension.
* Re-entry after death: allowed immediately while OPEN (no cooldown) — pack
  difficulty comes from the trip and lost gear, not artificial timers.

### 4.4 Building policy — **final rule: TEMPORARY SURVIVAL BUILDING ALLOWED**

Keep Goal 02 Policy A (interception of Create/IE/RS/etc is unreliable by
construction and Goal 02 documented why). Player-facing rule localized at entry
and in closure warnings: everything placed/stored vanishes when the zone
closes. No whitelist policing, no silent destruction surprises — the contract
is stated up front and repeated during closing escalation.

### 4.5 Storage policy — containers/machines work during session; destroyed at reset; communicated

Backpacks (sophisticatedbackpacks) and Carry-On are inventory-borne → survive
by nature. Parked vehicles (DragN, immersive_aircraft) are entities in the
dimension → destroyed at reset → covered by the same "nothing survives"
messaging. Dropped items likewise. Storage networks (RS) inside expedition are
pointless-by-design; messaging covers intent rather than blocking.

### 4.6 OPAC/claims UX — keep OPAC as single claim authority

Verify unclaimability each qualification cycle (already in preflight
CLAIMS_INTERSECT + opac selftest). Player attempts get OPAC's own denial;
BBE adds explanation in entry message ("territory cannot be claimed — it is
temporary"). No BBE claim code ever.

### 4.7 Preflight PLAYER_ADDITIONS redesign — **recommendation: purge acknowledgment instead of hard refusal for DIMENSION scope**

The baseline-delta gate was built when sectors were surgical staging targets.
Under whole-dimension production regeneration with Policy A, ANY played cycle
produces deltas, so hard-refusal deadlocks production (H4). Redesign:

* STAGING sector pipeline: unchanged strict refusal (regression-protected).
* PRODUCTION/DRY_RUN DIMENSION scope: deltas become a quantified
  **purge manifest** (extra BEs by type); authorization requires an explicit
  operator acknowledgment binding those counts (like existing ack discipline);
  backup verification already guarantees rollback. Claims-intersect and
  players-inside remain HARD refusals. Loot-policy progression gates remain.
This preserves the invariant "destruction is never accidental" while making
temporary-territory turnover operable.

### 4.8 Closure model — scheduled sessions with escalating warnings

Recommendation: expedition stays OPEN until an operator (or future scheduler,
out of scope) issues close; close becomes a **timed sequence**:
CLOSING announces T-minus schedule (defaults 15/5/1 min + immediate), delivered
chat + action bar + sound, configurable intervals/toggles; EVACUATING runs the
proven evacuation; abort-close still possible until LOCKED. Countdown state
persists in lifecycle record so restarts don't lose it. Boss bar rejected —
pack UI noise (Xaero/HUD mods) outweighs benefit.

### 4.9 Opening experience — broadcast ceremony on VALIDATING→OPEN

Localized broadcast (chat + title) using the expedition fantasy (new zone
opened, recon language), generation number included ("Zone 3 opened").
Config toggle. Never phrased as reset/farm-world jargon.

### 4.10 Sector semantics — **recommendation: districts (navigation/statistical), not reset units**

Registry gains optional displayName/description; chunk-math lookup maps
players to districts; `/expedition where` reports district + coordinates;
entry telemetry records distribution. Names derive from what Lost Cities
actually generates in staging evidence (residential/medical/downtown/
industrial classes were observed — see goal-02-sector-topology.md structure
classes). Districts have NO lifecycle authority: dimension lifecycle stays the
only truth for access/reset.

### 4.11 Access availability — continuous OPEN availability (no session tickets)

Players enter whenever OPEN; scarcity comes from closure cadence, not entry
metering. Simpler, abuse-resistant, matches "leave settlements behind and
venture out" fantasy.

---

## 5. Proposed Goal 04 Plan

Order chosen by dependency: localization foundation → access/journey core →
recovery (login/logout/death) → policy/preflight rework → UX announcements →
sectors → commands/admin/telemetry → events API seam → campaigns/docs.

| WS | Workstream | Depends on | Key outputs |
|----|-----------|------------|-------------|
| 1 | i18n core + lang files | — | TranslationService (en_us fallback), lang keys, refactor literals incrementally per module as touched |
| 2 | Access gate + journey | 1 | Permission-0 enter/leave/status/where, EntityTravelToDimensionEvent gate, rate-limit, audits |
| 3 | Login/logout/death recovery | 2 | Generation-aware join matrix, respawn redirect, transfer flag, safe-position validator |
| 4 | Policy + preflight rework | — | Purge-manifest ack (DIMENSION scope), staging strictness preserved, tests |
| 5 | Closure/opening UX | 1,2 | Timed closing sequence persisted in record, escalations, opening broadcast |
| 6 | Sector districts | 2 | displayName fields, where lookup, telemetry counts |
| 7 | Admin gameplay cmds | 2,5 | players-inside, distribution, countdown view, force-evacuate one, diagnostics — perm 2/3 split preserved |
| 8 | Events seam | 2–6 | Internal Forge events (entered/left/evacuated/opened/closing/entered-sector) posted, no public API sprawl |
| 9 | Config | 1–7 | gameplay.properties (intervals, toggles, perm levels); safety rules not disableable |
| 10 | Tests + campaigns + docs | all | Unit suites per module; multiplayer/adversarial scenarios; soak ≥10 cycles; evidence/goal-04/; docs set |

Validation strategy: pure-core unit tests for every decision table above
(matrix-driven), then staging RCON-driven scenario scripts capturing evidence
per label (goal-03 convention), then repeated open/close/access soak. True
multi-client play is exercised as far as the environment allows (operator +
second account/RCON bots); races additionally covered by concurrency unit tests
on the pure services. Limitations recorded honestly in results doc.

Risk register (gameplay): portal-gate false positives (cancel legitimate
travel — mitigated: gate only destination==expedition); respawn redirect
fighting Corpse/comforts mods (staging verify); countdown drift across restarts
(persist deadline in lifecycle record); localization regressions (key-presence
tests for both langs).

---

## 6. Immediate next steps

1. Branch `feat/goal-04-expedition-gameplay`; commit this assessment.
2. WS1+WS2 implementation rounds (modular commits per Git policy).
3. Proceed through plan; do not pause pending approval — no external blocker
   identified (staging env operational, pack sources readable).
