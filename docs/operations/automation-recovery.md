# Automation Recovery Runbook — Goal 05

Fail-safe philosophy: automation failures narrow authority; they never widen
it. Every path below ends in a state an operator can inspect with commands,
not log archaeology.

## Symptom → meaning → action

| Symptom (status/explain/audit) | Meaning | Action |
|---|---|---|
| `[PAUSED: repeated evaluation failures]` | 3 consecutive evaluation exceptions | read server log for the stack; fix cause; `automation resume` |
| `[PAUSED: automation state unreadable…]` + `AUTOMATION_STATE_FAILSAFE` audit | corrupt/truncated automation-state.json | inspect quarantined `.corrupt-*` file next to it; `automation resume` after understanding (state was reset: streak/pending voided — safe) |
| `CLOCK ANOMALY recorded` in status | wall clock stepped back >5 min or forward >24 h vs persisted observation | verify system time/NTP; `automation clock-clear` (audited) |
| pending decision vanished, audit `AUTOMATION_PENDING_INVALIDATED` / `_VOIDED` | policy fingerprint changed or generation rollover | expected protection; wait for re-maturation |
| `AUTOMATION_PENDING_EXPIRED` | approval TTL lapsed | expected; re-maturation creates a fresh one |
| telemetry unavailable in explain (`CORRUPT`/`UNSUPPORTED_SCHEMA`) | gen-*.json damaged or from newer schema | inspect quarantined file; restore/remove deliberately; engine is blocked-safe meanwhile |
| `TELEMETRY_STALE_OPEN_KEPT` at boot | interrupted cycle left an OPEN telemetry file | keep for forensics; it cannot influence the current generation |

## Crash matrix (automation-relevant)

| Crash during | Result on restart |
|---|---|
| health evaluation | nothing persisted mid-eval; streak resumes from last flush |
| closure scheduling | Goal 04 deadline lives in lifecycle.json; boot probe re-arms warnings/extraction |
| countdown/extraction | Goal 03/04 guarantees unchanged |
| post-close archival | boot catch-up archives closed-but-unarchived generation (`unrecorded-restart`) |
| authorization creation | untouched by automation (operator act); ledger/artifact checks apply |
| automated handoff window | automation holds no destructive artifact; RESET_READY states fail-closed per Goal 03 startup gate |
| summary generation | catch-up path above |

## Manual override recipes

Postpone one weekend event:
```text
/expedition automation postpone 3
/expedition automation status        # shows "postponed until …"
```

Cancel a wrong pending decision:
```text
/expedition automation cancel        # drops pending AND resets streak
```

Approve under SCHEDULED_WITH_APPROVAL:
```text
/expedition automation approve       # begins timed closing NOW (warnings still run)
```

Emergency full stop of automation (lifecycle unaffected):
```text
/expedition automation pause operator-emergency
# and set automation.mode=MANUAL + reload for persistence across restarts
```

## What recovery must NEVER do

* No command clears pause/clock/postpone state without perm 3 + audit.
* No automatic path re-arms itself after a fail-safe pause.
* Corrupted telemetry/state can never be interpreted as depletion evidence
  (engine returns UNKNOWN/BLOCKED; store refuses unusable files).
