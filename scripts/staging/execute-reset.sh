#!/usr/bin/env bash
# execute-reset.sh — STAGING-ONLY offline destructive sector reset.
# Refuses unless every safety condition holds. See docs/operations/reset-runbook.md.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common.sh"
require_staging_sentinel
require_server_stopped

PLAN_ID="${1:?usage: execute-reset.sh <planId>}"
[[ "$PLAN_ID" =~ ^[0-9a-fA-F-]{36}$ ]] || { echo "RESET REFUSED — malformed plan id"; exit 45; }

PLAN_FILE="$SERVER_DIR/bigbangexpeditions/reset-plans/$PLAN_ID.json"
[ -f "$PLAN_FILE" ] || { echo "RESET REFUSED — plan not found: $PLAN_ID"; exit 46; }

# ---- validate manifest (structure + checksum + dimension + region names) ----
MANIFEST_OK=$(python3 - "$PLAN_FILE" <<'PYEOF'
import json, sys, hashlib

path = sys.argv[1]
m = json.load(open(path))

required = ["planId","sectorId","dimension","minChunkX","minChunkZ","maxChunkX","maxChunkZ",
            "expectedRegionFiles","baselineId","sectorResetCountAtPlanTime",
            "profileFingerprint","worldSeedHash","createdAtEpochMs","createdBy","manifestChecksum"]
for k in required:
    if k not in m:
        print("REFUSED"); sys.exit()

if m["dimension"] != "bigbangexpeditions:expedition":
    print("REFUSED"); sys.exit()

saved = m.pop("manifestChecksum")
# canonical serialization MUST mirror ResetPlanManifest.toDeterministicJson()
def esc(s):
    return s.replace("\\", "\\\\").replace('"', '\\"')
sb = "{"
sb += '"planId":"%s"' % esc(m["planId"])
sb += ',"sectorId":"%s"' % esc(m["sectorId"])
sb += ',"dimension":"%s"' % esc(m["dimension"])
sb += ',"minChunkX":%d' % m["minChunkX"]
sb += ',"minChunkZ":%d' % m["minChunkZ"]
sb += ',"maxChunkX":%d' % m["maxChunkX"]
sb += ',"maxChunkZ":%d' % m["maxChunkZ"]
files = {i: f for i, f in enumerate(sorted(m["expectedRegionFiles"]))}
import json as j
sb += ',"expectedRegionFiles":' + j.dumps(files).replace(" ", "")
sb += ',"baselineId":"%s"' % esc(m["baselineId"])
sb += ',"sectorResetCountAtPlanTime":%d' % m["sectorResetCountAtPlanTime"]
sb += ',"profileFingerprint":"%s"' % esc(m["profileFingerprint"])
sb += ',"worldSeedHash":"%s"' % esc(m["worldSeedHash"])
sb += ',"createdAtEpochMs":%d' % m["createdAtEpochMs"]
sb += ',"createdBy":"%s"' % esc(m.get("createdBy") or "")
sb += "}"
digest = hashlib.sha256(sb.encode("utf8")).hexdigest()
if digest != saved:
    print("REFUSED"); sys.exit()
print("OK")
PYEOF
)
[ "$MANIFEST_OK" = "OK" ] || { echo "RESET REFUSED — manifest checksum/validation failed"; exit 47; }

# ---- derive target region files strictly from validated bounds ----
TARGETS=$(python3 - "$PLAN_FILE" "$SERVER_DIR/world" <<'PYEOF'
import json, sys, math, os, re

m = json.load(open(sys.argv[1]))
world = sys.argv[2]
dim = os.path.join(world, "dimensions", "bigbangexpeditions", "expedition")

rx0 = math.floor(m["minChunkX"] / 32); rx1 = math.floor(m["maxChunkX"] / 32)
rz0 = math.floor(m["minChunkZ"] / 32); rz1 = math.floor(m["maxChunkZ"] / 32)
derived = ["r.%d.%d.mca" % (rx, rz) for rx in range(rx0, rx1+1) for rz in range(rz0, rz1+1)]
if sorted(derived) != sorted(m["expectedRegionFiles"]):
    print("REFUSED"); sys.exit()

real_dim = os.path.realpath(dim)
out = []
for sub in ("region", "entities", "poi"):
    for f in derived:
        p = os.path.realpath(os.path.join(dim, sub, f))
        if not p.startswith(real_dim + os.sep):
            print("REFUSED"); sys.exit()
        out.append(os.path.join(sub, f))
print("\n".join(out) + "\n---\n" + real_dim)
PYEOF
)
[ -n "$TARGETS" ] && [ "$TARGETS" != "REFUSED" ] || { echo "RESET REFUSED — target derivation/confinement failed"; exit 48; }
DIM_REAL=$(echo "$TARGETS" | sed -n '/^---$/,$p' | tail -1)
FILES=$(echo "$TARGETS" | sed '/^---$/,$d')

echo "[staging] targets confined under $DIM_REAL:"
echo "$FILES"

# ---- disk space guard for backup ----
AVAIL_KB=$(df -kP "$SERVER_DIR" | awk 'NR==2{print $4}')
DIM_SIZE_KB=$(du -sk "$DIM_REAL" 2>/dev/null | cut -f1 || echo 0)
NEEDED=$(( DIM_SIZE_KB * 2 + 10240 ))
if [ "$AVAIL_KB" -lt "$NEEDED" ]; then
    echo "RESET REFUSED — insufficient disk space for backup (need ~${NEEDED}KB, avail ${AVAIL_KB}KB)"
    exit 49
fi

# ---- immutable backup BEFORE any deletion ----
BACKUP_DIR="$STAGING_ROOT/backups/$PLAN_ID"
if [ -d "$BACKUP_DIR" ]; then
    echo "RESET REFUSED — backup dir already exists for this plan (rollback-first or remove deliberately)"
    exit 50
fi
mkdir -p "$BACKUP_DIR/data"
cp "$PLAN_FILE" "$BACKUP_DIR/reset-plan.json"

cd "$DIM_REAL"
for sub in region entities poi; do
    mkdir -p "$BACKUP_DIR/$sub"
done
while IFS= read -r rel; do
    sub="${rel%%/*}"
    [ -f "$rel" ] || continue   # absent poi/entity files are fine pre-generation
    cp "$rel" "$BACKUP_DIR/$rel"
done <<< "$FILES"

# hash EVERYTHING backed up + record
( cd "$BACKUP_DIR" && find region entities poi data -type f ! -name SHA256SUMS -exec sha256sum {} \; > SHA256SUMS )
COUNT=$(wc -l < "$BACKUP_DIR/SHA256SUMS")
( cd "$BACKUP_DIR" && sha256sum -c SHA256SUMS --quiet ) || {
    echo "RESET REFUSED — backup self-verification failed"; exit 51;
}
echo "[staging] backup verified ($COUNT files): $BACKUP_DIR"

# ---- delete ONLY the listed files ----
cd "$DIM_REAL"
while IFS= read -r rel; do
    if [ -f "$rel" ]; then
        rm -f "$rel"
        echo "[staging] deleted $rel"
    fi
done <<< "$FILES"

echo "[staging] RESET COMPLETE for plan $PLAN_ID"
echo "[staging] next: start server, run post-reset validation (VALIDATING state), keep backup until PASS"
