# Upgrade rehearsal — Goal 02 era staging -> Goal 03 jar

Procedure (release-process.md):
1. `.staging/server` contained the Goal 02-era state: sectors.json with sector
   b04 (14 resets), goal-02 baselines, goal-02 reset plans, live modpack.
2. Installed BigBangExpeditions 1.0.0 (Goal 03) jar via install-mod.sh.
3. Booted: no errors attributable to BBE; existing registry loaded; new
   lifecycle.json created automatically at OPEN generation=0.
4. `/expedition lifecycle health|status|dryrun` operational immediately.
5. All subsequent rehearsals (01-18) ran on this upgraded installation.

Result: PASS — upgrade non-destructive, additive, backward compatible.
