# Production Lifecycle Architecture

Status: Goal 03. Owner: BigBangExpeditions.

## States

Dimension-level lifecycle (`lifecycle/LifecycleState`), persisted at
`<server>/bigbangexpeditions/lifecycle.json` — OUTSIDE the world directory so
regeneration can never destroy operational bookkeeping:

```text
OPEN ──close──> CLOSING ──evacuated──> EVACUATING ──empty──> LOCKED
   ^                │ abort              │ abort
   └────────────────┴────────────────────┘

LOCKED ──issue auth──> PREFLIGHT ──authorized──> RESET_READY
                          │ refusal                 │ offline executor
                          v                         v
                       LOCKED / FAILED           RESETTING ──boot──> BOOTING
                                                                        │ startup gate
                                                                        v
                                                                    VALIDATING ──PASS──> OPEN
                                                                        │ FAIL
                                                                        v
                                                                     FAILED

Any state ──automatic──> RECOVERY_REQUIRED ──operator recover──> LOCKED
```

Rules enforced by `LifecycleService.transition`:

* Unknown transitions are rejected (fail-closed), same discipline as the
  Goal 02 `SectorState` machine which remains in charge of staging sectors.
* Entering `RECOVERY_REQUIRED` is legal from EVERY state — it is the automatic
  fail-closed sink used by the startup gate.
* VALIDATING → OPEN requires a recorded `lastValidationResult=PASS`
  (`recordValidationResult`). No PASS ⇒ no reopen, ever.
* A successful validated reset cycle increments `generation` exactly once.
* Every accepted transition is appended to an on-record history (capped) and to
  the durable audit log.

## Offline window

The destructive phase runs with the server STOPPED. The executor scripts own
these transitions and write `lifecycle.json` directly (trusted local tooling),
guarded by: production signals, OS `flock`, the persistent reset lock,
`VerifyAuthCli`, disk-space checks and the phase journal
(`reset/OperationJournal`). Phase markers are written atomically ONLY AFTER a
phase completes, so a crash always leaves an unambiguous last-known phase that
the startup gate translates into explicit recovery states.

## Startup gate

At every `ServerStartedEvent`, `core/StartupGate` cross-checks lifecycle state
against the latest operation journal via `StartupRecovery.evaluate`:

| Persisted state | Journal | Result |
|---|---|---|
| OPEN | none | OK |
| RESETTING / BOOTING | none | RECOVERY_REQUIRED (unknown destructive state) |
| RESETTING | last < DELETION_DONE | RECOVERY_REQUIRED (interrupted deletion) |
| RESET_READY..LOCKED | active op | RECOVERY_REQUIRED (stale operation) |
| OPEN | active op | RECOVERY_REQUIRED (finalize missing) |
| RESETTING→BOOTING | DELETION_DONE | auto-resume BOOTING→VALIDATING |

Unreadable/corrupt lifecycle or journal also forces RECOVERY_REQUIRED; if even
that cannot be persisted the mod logs a CRITICAL error and the expedition must
be treated as unsafe until manual repair.

## Player safety integration

* `/expedition enter` consults `EntryDecision.check(state)` — only `OPEN`
  admits players; every other state refuses with the blocking state named.
* `/expedition lifecycle close` evacuates everyone (overworld spawn), refuses
  to lock while anyone remains inside, and records per-player audit events.
* Players who disconnect inside get a persistent marker and are evicted at
  next join unless the dimension reopened meanwhile (`EvacuationService.onJoin`).
