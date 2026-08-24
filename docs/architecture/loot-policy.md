# Renewable Loot Policy (Goal 02 Phases 11–12)

**Status:** DECIDED — Strategy A with acquisition-state verification
**Machine-readable form:** `src/main/resources/config/bigbangexpeditions/loot-policy.json`
**Date:** 2026-08-24

## Audit evidence

Full sweep of all 300 pack jars + `kubejs/` + FTB Quests SNBT for every
progression-relevant item id, plus Lost Cities chest condition tables.

| Item | Class | Renewable source? | Evidence |
|------|-------|-------------------|----------|
| research_paper_1 | PROGRESSION_ITEM | yes — LC building loot (`multi_courtyardofficetower_6/floor7/dead_body.json`) | DCTweaks jar |
| research_paper_2 | PROGRESSION_ITEM | yes — 2 dead_body tables | DCTweaks jar |
| research_paper_3 | PROGRESSION_ITEM | yes — `multi_terraceplaza_8/floor4/dead_body.json` | DCTweaks jar |
| research_paper_4 | PROGRESSION_ITEM | yes — `multi_casino_6/base9/safe.json` | DCTweaks jar |
| research_paper_5 | **UNIQUE_ITEM** | **NO source anywhere in pack** | exhaustive sweep: no loot table/recipe/structure reference |
| research_book | PROGRESSION_ITEM | crafted from papers 1–5 | `kubejs/server_scripts/recipes/deceasedcraft/cures.js` |
| x_factor | **UNIQUE_ITEM** | **NO** — lang text says "END OF CONTENT… locked behind a deactivated portal" | DCTweaks en_us.json |
| formula_x | PROGRESSION_ITEM | crafted, but consumes x_factor → stock-capped | cures.js sequenced_assembly |
| experimental_serum | PROGRESSION_ITEM | worldgen military cargo table | Zombie Extreme jar |
| golden_apple | PROGRESSION_ITEM | vanilla recipe removed by pack; gated behind formula_x | cures.js |
| lostcitychest / raildungeonchest tables | REGULAR/RARE_LOOT | vanilla valuables only | lostcities jar loot_tables |

Lootr is disabled pack-wide (`lootr-common.toml: disable = true`); no Lootr
SavedData exists, so Goal 00's per-player looting analysis collapses to
vanilla loot-table behavior.

## Strategies considered

| Strategy | Idea | Verdict |
|----------|------|---------|
| A | Progression items still generate; safety enforced at reset time via baseline comparison | **CHOSEN** |
| B | Per-player persistent acquisition state | Rejected — requires hooking every container interaction across mods; high complexity, invasive |
| C | Server-level entitlement/quest gate | Rejected — duplicates FTB Quests; cross-mod coupling |
| D | Move progression to a separate non-renewable system | Rejected — would require redesigning pack content, out of scope |

## Why A works here

The dangerous event is not "progression item generates" — it is
"reset creates *additional* copies beyond what players consumed." Strategy A
closes that gap structurally:

1. **Pre-reset inventory snapshot**: preflight captures progression-item
   containers in the sector and compares against the sector baseline.
   Any delta (player took a paper) → `RESET REFUSED` until operator
   re-baselines with explicit acknowledgment.
2. **Post-reset proof**: validation compares the regenerated sector against a
   fresh-generation baseline. Identical hash ⇒ regeneration reproduced the
   same items ⇒ no duplication occurred.
3. **UNIQUE_ITEM containment**: paper_5 and x_factor have no worldgen source,
   so they can only exist where players carried them. Preflight refuses any
   sector containing them unless they were part of the original baseline.

## Reset rules (enforced by Phase 13 engine)

```text
PROGRESSION_ITEM delta vs baseline            -> RESET REFUSED
UNKNOWN item encountered during classification -> RESET REFUSED
UNIQUE_ITEM inside renewable sector            -> sector LOCKED, excluded
```

## Residual risks

- Vanilla `golden_apple` appears in some vanilla loot tables (e.g.
  simple_dungeon); those tables are reachable through LC conditions
  (`chestloot.json` factor 30 for simple_dungeon). A golden apple found
  outside its crafting chain is therefore possible; classified
  PROGRESSION_ITEM conservatively and covered by rule 1.
- If the pack later adds renewable sources for paper_5/x_factor, this policy
  file must be updated — the loader fails closed on unknowns, but it cannot
  detect reclassification errors.
