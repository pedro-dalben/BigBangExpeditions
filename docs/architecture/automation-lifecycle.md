# Automation Lifecycle — Goal 05 Architecture

Status: implemented (WS5/WS6).

## Authority ladder (requirement 15)

`automation.mode` in `config/bigbangexpeditions/automation.properties`;
applied ONLY by explicit, audited `/expedition automation reload`
(validated-before-apply; failure keeps previous policy). Default after
install/upgrade: **MANUAL** — production activation remains deliberate.

| Mode | Scheduled evaluation | Recommendation surfaced | Pending decision | Executes closure |
|---|---|---|---|---|
| MANUAL | no (on-demand only) | via explain/dryrun | never | never |
| ADVISORY | yes | yes (event + status) | never | never |
| SCHEDULED_WITH_APPROVAL | yes | yes | yes (TTL-bound) | only after operator `approve` |
| AUTOMATIC_CLOSURE | yes | yes | yes | at maintenance window, itself |

## Hard boundaries

* Automation NEVER destroys anything and NEVER touches authorization artifacts.
  Its single lifecycle action is `ClosureService.beginTimedClosing(...)` — the
  Goal 04 player-facing pipeline (warnings → deadline extraction → LOCKED).
  Everything downstream (authorization issue is a separate operator/ops step;
  destructive execution) stays offline-by-design (Goal 03).
* Operator pause overrides everything (`paused=true`: no scheduled evaluations,
  no execution; invariant 12). Pause survives restarts.
* Clock anomalies (backward >5 min, forward >24 h vs persisted observation)
  suspend automatic execution until an operator runs `clock-clear` (audited).
  Advisory evaluation continues.
* Policy fingerprints: pending decisions bind to the sha256-of-canonical-policy
  at creation. A config edit that changes the fingerprint invalidates pending
  decisions on reload AND refuses execution of stale ones — a stale
  recommendation can never silently become destructive (requirement 63).
* Generation binding: streaks/pending belong to one generation; rollover voids
  them. Metrics from generation N can never close generation N+1.

## Decision flow per evaluation

```
evaluateNow(trigger)
  ├─ clockGuard(now)                     anomaly → suspend automatic actions
  ├─ expireStalePending(now)
  ├─ bindGeneration(lifecycle.generation) rollover isolation
  ├─ snapshot = TelemetryService.snapshotCurrentOr(gen)
  ├─ totalChunks = census pin OR Σ sector chunk counts (-1 unknown)
  ├─ result = DepletionEngine.evaluate(...)
  ├─ shadow ring append (always — WOULD-HAVE evidence)
  ├─ health-change event on transition
  └─ handleRecommendation(result):
       MANUAL     → (shadow only)
       ADVISORY   → RenewalRecommended event once per maturation
       SCHEDULED… → + PendingClosure{gen,score,reasons,TTL=48h,fingerprint}
       AUTOMATIC  → + PendingClosure armed for window execution
```

Execution guard chain (`canAct`) before any automated closing:
not paused ∧ no clock anomaly ∧ pending exists ∧ not postponed ∧
pending generation == bound generation ∧ fingerprint matches ∧ inside
maintenance window. Then `ClosureService.beginTimedClosing(actor=
automation:<MODE>)` — audited distinctly from human actors (requirement 24's
distinguishability applies to the live-side action; destructive authorization
remains an explicit operator/ops act offline).

## Failure escalation (requirement 30)

Evaluation exceptions count consecutively; ≥3 ⇒ self-pause with reason +
`ExpeditionAutomationPaused` event. Persistence failures log-and-retry next
interval (advisory-grade). State corruption at boot fails SAFE: paused=true,
pending dropped, streak reset (never operational-with-unknown-state).

## Overrides (all audited)

pause/resume · postpone <1-365d> · cancel (drops pending + resets streak) ·
approve (executes pending immediately; stale-fingerprint pending is dropped
with explanation instead of executed) · reload (validate-then-apply; drift
invalidates foreign-fingerprint pendings) · clock-clear.

## Shadow mode & dry-run (requirements 49/50)

Every evaluation appends to the bounded shadow ring regardless of mode:
`{at, gen, score, health, wouldRecommend, blockers}`. In MANUAL this IS the
shadow mode — operators run `automation evaluate` periodically or enable
ADVISORY for cadence, then read `automation shadow`. `automation dryrun`
answers "would automation act right now?" including mode/window/anomaly/
postponement reasoning without touching anything.
