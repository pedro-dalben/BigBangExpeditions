# Release Process (Goal 03)

## Build & artifact

```bash
./gradlew clean build
# artifacts: build/libs/bigbangexpeditions-<version>.jar
sha256sum build/libs/bigbangexpeditions-*.jar > build/libs/SHA256SUMS.txt
```

Release checklist:

1. `gradle.properties` → bump `mod_version` (semver; MINOR for pipeline changes,
   MAJOR when reset semantics or manifest schema change).
2. `./gradlew clean test build` — suite green, jar produced.
3. sha-256 both jar and this changelog entry.
4. Update `docs/operations/release-process.md` (this file) with the version
   block below.
5. Tag: `git tag -a v<version> -m "..."`.

## Version blocks

### 1.0.0 (Goal 03)

* date: 2026-08-24
* baseline: Goal 02 final (`7f533bb`), branch `feat/goal-03-production-readiness`
* adds: environment profiles + install fingerprint + drift policy; dimension
  lifecycle machine with validation gate; startup recovery gate; audit trail;
  authorization artifacts v2 + single-use ledger; persistent reset lock +
  flock; phase journal + offline VerifyAuthCli/OperationJournalCli;
  verifiable backup manifests + retention; production executor scripts v2;
  dry-run engine + `/expedition lifecycle dryrun|health|issue-authorization`;
  permission split 2/3.
* config additions: `config/bigbangexpeditions/environment.properties`,
  optional `production.enabled`, optional
  `qualification-fingerprint.json`.
* migration: none destructive — Goal 02 staging files remain valid; lifecycle
  starts at OPEN generation 0. Destructive behavior stays disabled until the
  three production signals exist (see production-readiness.md).
* rollback of the mod itself: replace jar with previous release; world data is
  untouched by upgrades.

## Upgrade rehearsal (performed for Goal 03)

1. Check out Goal 02 final commit into a scratch dir; provision staging.
2. Boot once, create sector+baseline (Goal 02 flow) to produce era-typical data.
3. Copy the new mod jar over `mods/`, boot: startup gate logs consistent state,
   `/expedition lifecycle health` answers, existing sector registry loads.
4. Run dry-run end-to-end. Evidence: `evidence/goal-03/upgrade-rehearsal/`.

## Prohibited

* Releasing with failing/adversarial-audit findings unresolved.
* Shipping any default configuration that enables destruction.
* Skipping the upgrade rehearsal when persistence formats changed.
