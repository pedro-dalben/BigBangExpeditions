# Goal 04 Final Audit — Expedition Gameplay & Player Experience

**Date:** 2026-08-24 · **Branch:** `feat/goal-04-expedition-gameplay`
**Scope audited:** `master..HEAD` (12 commits), staging evidence
`evidence/goal-04/`, live 290-mod DeceasedCraft server runs, full test suite.

## Method

Fresh review of every changed file against four perspectives (new player,
veteran abuser, administrator, operator), adversarial probing on the live
staging server (RCON console + real block-entity deltas), and a re-run of the
complete automated suite including all Goal 03 safety tests.

## Findings

### F1 — Live multi-client coverage gap · MEDIUM (accepted with conditions)

True connected-client flows (enter/leave teleports, respawn redirect firing,
portal traversal through the travel gate) were not exercisable in this
environment: no Minecraft client can join the headless staging server.
Everything beneath those thin adapters IS covered: decision matrices, fallback
policies, rate limits and state machines are pure-core unit-tested; the
adapter code paths are short, single-threaded, and fail toward "player lands
in the persistent world".

Condition attached to production activation: one supervised client session
executing the journey checklist in `docs/operations/gameplay-administration.md`
(entry, leave, death-in-zone, portal attempt while LOCKED). Until then the
production environment remains disabled by default exactly as Goal 03 left it,
so this gap cannot bite silently.

### F2 — Broadcast visibility with zero clients · LOW

Closing/opening broadcasts were verified via audit events (`CLOSING_WARNING`,
`OPENING_ANNOUNCED`) rather than visually; no player was online. Message
construction is unit-covered localization output. Cosmetic risk only.

### F3 — `ops config` reads fresh vs service cache · LOW

`ClosureService` caches GameplayConfig per process (restart to apply);
`ops config` re-reads the file so it can show unsaved intent. Documented in
the operations guide; harmless divergence.

### F4 — Travel-gate refusal map retains stale entries · LOW

`DimensionTravelGate.LAST_REFUSAL` keeps one timestamp per player name ever
refused (bounded by unique-player count, ~40 bytes each). No cleanup needed at
current scale; revisit if player churn ever warrants it.

### F5 — Legacy English admin text in perm-2/3 tooling · LOW

Operator diagnostics (`lifecycle status`, probe/baseline outputs) remain
technical English by design; player-facing surfaces are fully localized
(pt_br default, en_us parity enforced by test).

## Adversarial probes executed (live)

| Probe | Result |
|---|---|
| Place chest+furnace BEs, request authorization | REFUSED `PURGE_ACK_REQUIRED` w/ quantified manifest + hash ✔ |
| Confirm with correct manifest hash | issued, warning recorded ✔ |
| Confirm with wrong hash | REFUSED mismatch (binding holds) ✔ |
| cancel-reset after issuance | LOCKED + artifact revoked ✔ |
| close order from LOCKED | refused (illegal transition) ✔ |
| Restart mid-CLOSING | boot auto-extracted stale schedule → LOCKED, no stranded state ✔ |
| Command spam | rate limiter unit-enforced (6/10 s) ✔ |

## Exploit review (veteran perspective)

* **Permanent storage**: impossible by design; reset destroys territory;
  purge acknowledgment makes destruction deliberate and quantified.
* **Claims/forceload backdoor**: OPAC excludes dimension + forbids existing
  claims/forceloads there; preflight CLAIMS_INTERSECT hard-refuses resets.
* **Logout protection**: generation-aware recovery evicts or recovers on
  next join; unknown generations fail closed.
* **Respawn anchoring**: set-spawn cancelled inside zone; post-respawn
  redirect; closure between death and respawn still lands safely.
* **Teleport bypass**: all dimension arrivals funnel through the travel gate
  keyed on the same EntryDecision as the command.
* **Duplication**: Goal 02 loot-policy progression gates untouched and green.

## Operator / Goal 03 regression check

* Production destructive path: still disabled by default (env signals +
  fingerprint-bound ack unchanged; `EnvironmentConfigTest`, `DriftPolicyTest`
  green).
* Offline executor scripts: untouched this goal.
* StartupGate/journal/ledger/lock semantics: untouched; full suites green
  (292 passed incl. all Goal 03 tests).
* New lifecycle surface added this goal is strictly additive:
  `startClosing/abortClosing/cancelReset/markClosingWarned` reuse the existing
  validated transition table; no new unsafe edges (`LifecycleStateTest` green).

## Verdict

No unresolved CRITICAL findings. No unresolved HIGH findings.
MEDIUM item F1 carries an explicit production-activation condition consistent
with Goal 03's "first real reset is an operator decision" stance.

```text
FINAL AUDIT: PASS
```
