# Gameplay Administration — Operations Guide (Goal 04)

Audience: server staff (permission level 2/3). Production reset execution
remains governed by `production-reset-runbook.md`; nothing here replaces it.

## Daily situational awareness

```
/expedition ops players      # who is inside, districts, distribution
/expedition ops countdown    # time until scheduled extraction
/expedition ops config       # active gameplay settings + notices
/expedition status           # player-facing state line
/expedition lifecycle status # full operational record (recent transitions)
```

## Running a zone cycle

```text
1. /expedition lifecycle close            # timed closing starts (default 15 min)
   ├─ warnings fire automatically at configured offsets
   └─ extraction runs itself at the deadline → LOCKED
   alternatives:
     /expedition lifecycle close immediate  # skip warnings (legacy flow)
     /expedition lifecycle abort-close      # cancel a running closing

2. (offline) scripts/production/execute-reset.sh <authId>   # Goal 03 runbook

3. after reboot: begin-validation → baseline compare → record-validation PASS
4. /expedition lifecycle open              # validation gate + opening broadcast
```

The opening broadcast fires automatically on validated reopen; generation
number is included. Do not reopen without a recorded PASS — the gate refuses.

## Authorization window tools

```
/expedition lifecycle issue-authorization              # first attempt
/expedition lifecycle issue-authorization <hash12>     # confirm purge manifest
/expedition lifecycle cancel-reset                     # abort window + revoke artifact
```

If players built inside the zone since the baseline, the first attempt refuses
with `PURGE_ACK_REQUIRED` and a 12-hex manifest hash quantifying the delta.
Re-run with that hash to bind the acknowledgment to those exact counts; if the
world changed meanwhile the hash mismatches and re-scan happens automatically
on the next attempt. `cancel-reset` returns to LOCKED and revokes a still-
ISSUED artifact — use it whenever plans change after issuing.

## Evacuation

* automatic at closing deadline (`closing-schedule` actor in audit);
* join-time recovery completes evictions for anyone who disconnected inside;
* manual single-player extraction: `/expedition ops evacuate <player>` (perm 3).

Every evacuation posts `PLAYER_EVACUATED`/`PLAYER_RECOVERED` to the audit log
and a `PlayerEvacuated` internal event.

## Configuration

`config/bigbangexpeditions/gameplay.properties` (restart to apply):

```properties
closingDurationMinutes=15
closingWarningOffsetsMinutes=15,5,1
announcementsEnabled=true
soundEnabled=true
openingAnnouncementEnabled=true
```

Invalid values fall back to defaults with NOTICE lines visible via
`/expedition ops config`. Safety rules (lifecycle gating, evacuation,
purge-acknowledgment discipline, validation gate) are NOT configurable.

## Sector districts

```
/expedition sector rename b04 "Setor Médico"
/expedition sector list | status <id>
```

Display names feed `/expedition where` and ops telemetry. Districts carry no
lifecycle authority.

## Audit quick reference (goal 04 events)

| Event | Meaning |
|---|---|
| PLAYER_ENTERED / PLAYER_LEFT | voluntary journey legs |
| PLAYER_EVACUATED | extraction, admin evacuate, join-time eviction |
| PLAYER_RECOVERED | login-matrix recovery actions |
| CLOSING_STARTED / CLOSING_WARNING | timed sequence progress |
| LIFECYCLE_CLOSE (actor=closing-schedule) | deadline extraction completed |
| LIFECYCLE_ABORT_CLOSE / LIFECYCLE_CANCEL_RESET | operator aborts |
| OPENING_ANNOUNCED | ceremony broadcast for new zone cycle |
| TRAVEL_GATE | alternate-route arrival refusal |
| AUTH_ISSUED / AUTH revoked | purge-bound authorization lifecycle |
