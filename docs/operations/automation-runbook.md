# Automation Runbook — Goal 05

Audience: server operators. Prerequisites: read
`docs/architecture/production-safety-model.md` (Goal 03) — nothing below
weakens it.

## Daily operation

```text
/expedition automation status     # mode, pause, pending, streak, window, clock
/expedition automation explain    # WHY the current health verdict holds
/expedition automation history    # bounded completed-cycle summaries
/expedition automation shadow     # WOULD-HAVE log from evaluations
/expedition automation dryrun     # would automation act right now?
```

## Configuration

`config/bigbangexpeditions/automation.properties` (create on first use; every
key optional, invalid values fall back with notices):

```properties
# authority — default MANUAL; loosening is deliberate and audited via reload
automation.mode=MANUAL            # MANUAL | ADVISORY | SCHEDULED_WITH_APPROVAL | AUTOMATIC_CLOSURE

# scheduler
scheduler.evaluateMinutes=60      # 10..1440
scheduler.windowStart=03:00       # HH:MM server-local; == end => any time
scheduler.windowEnd=05:00
scheduler.approvalTtlHours=48     # 1..720

# telemetry collection
telemetry.flushSeconds=30         # 5..600
telemetry.sampleSeconds=5         # 1..60
telemetry.structureSignalGraceChunks=2000

# observed-fact census pins (0 = derive/unknown)
census.totalChunks=0
census.totalStructurePlacements=0

# depletion policy (see docs/operations/policy-tuning.md)
depletion.closeScoreThreshold=80
depletion.minAgeDays=3
depletion.maxAgeDays=21
depletion.sustainedEvaluationsRequired=3
depletion.minSustainedSpanHours=6
depletion.recoveryBand=5
depletion.lootMinAbsoluteOpens=50
depletion.inactivityAbandonDays=14
depletion.unknownSpatialHandling=BLOCK   # BLOCK | FALLBACK
depletion.coverageClosePercent=70
```

Apply changes without restart:

```text
/expedition automation reload
```

Validation-before-apply: a broken file keeps the previous policy; notices are
printed. Changing the policy fingerprint invalidates pending decisions bound
to the old one (they can never silently become destructive).

## Mode transitions

```text
MANUAL → ADVISORY                # observe recommendations in production safely
ADVISORY → SCHEDULED_WITH_APPROVAL   # decisions require explicit approve
SCHEDULED_WITH_APPROVAL → AUTOMATIC_CLOSURE  # window-gated self-execution
<any> → MANUAL                   # emergency stop switch
```

Every transition = edit file + `automation reload` + audit line
`AUTOMATION_RELOADED`. `pause <reason>` is the immediate stop switch; it beats
every mode and survives restarts.

## Recommended rollout

1. Ship with MANUAL. Run `automation evaluate` daily for a week; read
   `explain`.
2. Set ADVISORY for ≥ one full expedition cycle. Compare `shadow`
   would-recommend entries against operator judgment.
3. Tune thresholds (`policy-tuning.md`). Reload.
4. SCHEDULED_WITH_APPROVAL for another cycle — exercise approve/postpone/
   cancel deliberately once each.
5. Only then AUTOMATIC_CLOSURE, keeping a conservative window.

## What automation will and will not do

Will: evaluate on cadence; surface explained health; create TTL-bound pending
decisions; begin the Goal 04 timed closing (warnings → extraction) at an
approved/window-gated decision.

Will NOT: issue reset authorizations by itself, delete anything, restart the
server, bypass purge-ack/drift/preflight, act while paused/anomalous/
postponed, or skip validation gates after a reset. After LOCKED, completing
the renewal remains the operator/offline pipeline exactly as documented in
`production-reset-runbook.md`.

## Reading the audit trail

Automation writes distinct events: AUTOMATION_BOOT, AUTOMATION_HEALTH_CHANGED,
AUTOMATION_RENEWAL_RECOMMENDED, AUTOMATION_PENDING_CREATED / _EXPIRED /
_VOIDED / _INVALIDATED, AUTOMATION_CLOSURE_ARMED, AUTOMATION_CLOSED_STARTED,
AUTOMATION_CLOSE refusals, AUTOMATION_PAUSED/_RESUMED, AUTOMATION_POSTPONED,
AUTOMATION_CANCELLED, AUTOMATION_CLOCK_ANOMALY(+_CLEARED), AUTOMATION_RELOADED.
Actors are `automation`, `automation:<MODE>`, or `operator:<source>` — always
distinguishable from human-issued equivalents.
