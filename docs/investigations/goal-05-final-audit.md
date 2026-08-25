# Goal 05 Final Audit — adversarial review

Auditor stance: attempt to DISPROVE safety assumptions; checklist-reading is
not auditing. Findings ordered by severity; every CRITICAL/HIGH was fixed with
a regression before PASS.

## Method

* re-read automation/telemetry/depletion sources against Goal 03/04 invariants;
* replayed live staging evidence (13 automation-driven cycles + failure
  injections) looking for unexplained state;
* probed exploit paths against the engine and the live scheduler;
* attempted to construct "automation destroys without operator" and
  "corrupt data causes destruction" chains end-to-end.

## Findings

| # | Sev | Finding | Disposition |
|---|---|---|---|
| F1 | HIGH | `probe`/`baseline`/`compare` were misnested under `attach-baseline` since the Goal 04 command refactor — the content-regression gate silently no-op'd during early campaign cycles (REF never established) | FIXED paren nesting; regression = live baseline+compare transcript in `evidence/goal-05/automation-cycle-013-regression/compare.txt`; matrix row added |
| F2 | HIGH | Mid-renewal boots bound telemetry to the DYING generation, creating a ghost `gen-N.json` (bounded-tidy violation, confusion risk) | FIXED defer-bind during RESETTING/BOOTING/VALIDATING; ghost self-heals via boot catch-up; verified live (telemetry dir clean at gen≥14 boots) |
| F3 | MEDIUM | Cycle summaries stayed `reset/validation=PENDING` forever | FIXED: record-validation stamps archived summary (`reflectValidationIntoHistory`) |
| F4 | MEDIUM | Telemetry service kept a private config copy — `automation reload` did not retune sampling/flush intervals until restart | FIXED: single config source incl. reload |
| F5 | MEDIUM | Stale-jar verification gap: fix compiled but `build/libs` jar not rebuilt before install → false "still broken" reading during campaign | process fix: build→install→md5-compare discipline; noted in runbook |
| F6 | LOW | `approve()` intentionally bypasses maintenance window (operator timing IS explicit consent) while still refusing stale fingerprints; documented rather than changed | documented in automation-lifecycle.md |
| F7 | LOW | Shadow ring serializes on every persist (~30 KB worst case) | accepted: persists are evaluation/override-frequency, bounded |
| F8 | LOW | Staging summaries show `explorers 0` because synthetic seeding injects loot/chunk facts without player sessions | expected: client-session multiplayer validation remains Goal 04 F1 precondition for production activation |

## Safety-invariant re-verification (Goal 05 list, all 15)

1. production reset default-off ✓ (absent-config ⇒ MANUAL tested)
2. automation cannot bypass authorization/preflight ✓ (no auth code path from
   automation; live audits separate actors)
3. backup mandatory ✓ executor guard chain; campaign resets all verified first
4/5. validation mandatory & gates reopen ✓ live VALIDATING→PASS→open
6. crash uncertainty fail-closed ✓ StartupRecovery unchanged; automation adds
   no new destructive states
7. wrong-dimension refusal ✓ confinement unchanged (VerifyAuthCli DIMENSION +
   realpath guard exercised 13×)
8. no concurrent resets ✓ flock + single pending consumption
9. unknown persistence blocks execution ✓ state-corruption fail-safe pause
10. player closure uses Goal 04 lifecycle ✓ single beginTimedClosing path
11. entry blocked in unsafe states ✓ untouched EntryDecision gates
12. pause overrides automation ✓ armed-flag recomputation + canAct
13. corrupted telemetry cannot authorize reset ✓ CORRUPT⇒UNKNOWN⇒blocked;
    live injection transcript
14. missing telemetry ≠ depletion proof ✓ UNKNOWN contract tests
15. production activation deliberate ✓ environment signals untouched

## Attempted attacks that failed

* eval-spam to mature hysteresis instantly — defeated by minSustainedSpanMs;
* config-edit to make stale pending destructive — fingerprint refusal
  (+ reload invalidation);
* seed-sim outside staging — environment gate refuses production/missing;
* AFK farm / road-sprint / alt-swarm / death-spam score manipulation —
  property tests;
* restart storms to duplicate pendings/recommendations — consumed-once
  semantics + maturation key memory; 26 boots across campaign showed zero
  duplicates.

## Verdict

No open CRITICAL/HIGH findings. Remaining MEDIUM/LOW are documented above
with dispositions; none weaken Goal 03/04 guarantees.
