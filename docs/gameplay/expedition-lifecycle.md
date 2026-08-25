# Expedition Lifecycle — Player-Facing Documentation

## The fantasy, end to end

```text
☢ NEW EXPEDITION ZONE AVAILABLE  (opening broadcast, zone #N)
        ↓
/expedition enter   → quarantine log, warnings stated up front
        ↓
explore districts (/expedition where), loot, fight, die hard
        ↓
supplies thin — zone phase reads "Supplies thinning" as explorers
strip the district and new discoveries collapse
        ↓
"Final days — closure soon"  (phase line from real depletion state)
        ↓
☢ LOCKDOWN IN 15 / 5 / 1 MINUTES  (escalating warnings + bell)
        ↓
automatic extraction to the safe world at deadline
        ↓
military reset of the territory (offline; nobody inside, ever)
        ↓
validated reopening → next zone number, fresh city, same rules
```

## What players should understand

* Nothing built or stored in the zone survives closure. Inventory-borne items
  do. Corpses do not.
* The zone is temporary by design: this is what keeps exploration available
  for players who arrive months from now.
* Closure is announced with escalating warnings; leaving before the deadline
  is always the player's choice (`/expedition leave`).
* Zone phase lines (`/expedition status`) reflect REAL observed state — how
  picked-clean the district is — not a cosmetic timer.

## Where automation fits (and doesn't)

Automation decides WHEN a zone has genuinely been consumed and schedules the
same lockdown every player already knows — same warnings, same extraction,
same reopening ceremony. No cron monsters, no silent wipes: if automation
initiates a closing, it says so through the normal lockdown sequence.

Administrators can postpone or cancel a scheduled lockdown at any time;
players experience that simply as the zone staying open.
