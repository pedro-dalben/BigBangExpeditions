# Investigation — Custom dimension_type Refused by Registry Loader

**Date:** 2026-08-24
**Status:** OPEN — worked around; revisit in a later goal phase
**Severity:** Low (workaround fully functional for Goal 02 scope)

## Symptom

A custom `bigbangexpeditions:expedition_type` dimension type JSON caused the
dedicated staging server to abort during datapack registry loading:

```text
> Errors in registry minecraft:dimension:
>> Errors in element bigbangexpeditions:expedition:
java.lang.IllegalStateException: Failed to parse bigbangexpeditions:dimension/expedition.json
Caused by: java.lang.RuntimeException: Failed to get element ResourceKey[minecraft:dimension_type / bigbangexpeditions:expedition_type]
```

No error was ever reported for `minecraft:dimension_type` itself — the custom
type silently never registered.

## What was tried (all failed identically)

| Attempt | Delivery | Result |
|---------|----------|--------|
| 1 | Mod jar (`BigBangExpeditions-1.0.0.jar`) with both JSONs | FAILED |
| 2 | World datapack `.staging/server/world/datapacks/expedition-dim/` (pack_format 15) | FAILED |
| 3 | World datapack with byte-identical copy of known-good `deceasedcraft:abyss_type` content, only key renamed | FAILED |
| 4 | Dimension referencing `minecraft:overworld` instead | **SERVER BOOTS, dimension generates** |

Meanwhile `deceasedcraft:abyss_type` (structurally identical file from
DCTweaks mod jar) loads fine on the same server — only one
`Errors in registry` block appears and it names only our element.

## Environment facts

* Forge 47.4.0 dedicated server, 290 mods.
* Same failure with and without Sinytra Connector present.
* Not a JSON syntax problem: keys are an exact superset match of the working
  abyss_type layout; attempt 3 used identical bytes.

## Suspicion (unproven)

A pack mod interfering with worldgen registry data loading order or resource
resolution (candidates seen in boot logs: ModernFix dynamic resources,
Lazyyyyy loader patches). Root cause requires a minimal-mod repro which is not
worth blocking Goal 02's core validation work.

## Workaround applied

`data/bigbangexpeditions/dimension/expedition.json` now uses
`"type": "minecraft:overworld"` (vanilla overworld type).

Consequences:

* Beds work inside the expedition dimension → respawn anchoring inside a
  disposable world is possible until mitigated.
* Mitigation plan: `/expedition enter|leave` (Phase 5) tracks return position;
  building policy (Phase 7) may forbid bed placement in expedition territory;
  post-reset validation can flag beds as prohibited persistence.

## Follow-up

Minimal repro outside the pack (vanilla Forge server + only BBE jar), then
bisect pack mods if it reproduces there. Do NOT assume the workaround is
safe to carry into production reset design without revisiting bed policy.
