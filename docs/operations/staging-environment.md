# Staging Environment (Goal 02)

## Layout

```text
.staging/                     gitignored, disposable
├── server/                   full Forge 47.4.0 dedicated server
│   ├── .bigbangexpeditions-staging   SENTINEL — destructive scripts refuse without it
│   ├── .server.pid           written by start.sh
│   ├── mods/                 pack copy (~290 jars) + BigBangExpeditions jar
│   ├── config/, defaultconfigs/, kubejs/, datapacks/
│   └── world/                PINNED seed bigbangexpeditions-goal02
└── backups/<planId>/         immutable pre-reset backups + SHA256SUMS
```

Source instance (read-only, never mutated):
`/home/pedro/Documents/curseforge/minecraft/Instances/DeceasedCraft - Urban Zombie Apocalypse`

## Scripts (`scripts/staging/`)

| Script | Purpose |
|--------|---------|
| `provision.sh` | Copy pack content, install Forge headless, pin seed, register expedition dim (LC config), make expedition unclaimable (OPAC), install mod jar, create sentinel |
| `install-mod.sh` | Build (if needed) and install latest BBE jar |
| `start.sh` / `stop.sh` | PID-tracked background server; SIGTERM = graceful save |
| `status.sh` | sentinel/server/expedition region snapshot |
| `console.sh "<cmd>"` | RCON command execution (rcon.port 25575) |
| `archive-evidence.sh <label>` | filtered logs + baselines + plans into `evidence/goal-02/<label>/` |
| `execute-reset.sh` | DESTRUCTIVE — staging-only offline reset (see reset-runbook) |
| `rollback-reset.sh` | DESTRUCTIVE — restore from verified backup |
| `run-cycles.sh` | determinism campaign driver |

## Provisioning notes

- Client-only mods are removed at provision time; each removal has a recorded
  failure reason (drippy/colorwheel deps, shouldersurfing breaking Create's
  server mixin load, sodium family, EMF/ETF, ItemPhysicLite, tp_shooting/
  tacz/gundb/shotsfired dependency chain).
- OPAC serverconfig must be patched while the server is STOPPED — runtime
  edits are overwritten on shutdown (verified).
- RCON credentials are staging-only (`server.properties`).

## Recreating from scratch

```bash
rm -rf .staging && bash scripts/staging/provision.sh && bash scripts/staging/start.sh
# first boot generates overworld + expedition + abyss; ~21s Done after warm cache
```
