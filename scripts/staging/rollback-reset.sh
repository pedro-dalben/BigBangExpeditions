#!/usr/bin/env bash
# rollback-reset.sh — restore a sector from a verified backup.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common.sh"
require_staging_sentinel
require_server_stopped

PLAN_ID="${1:?usage: rollback-reset.sh <planId>}"
[[ "$PLAN_ID" =~ ^[0-9a-fA-F-]{36}$ ]] || { echo "ROLLBACK REFUSED — malformed plan id"; exit 45; }

BACKUP_DIR="$STAGING_ROOT/backups/$PLAN_ID"
[ -d "$BACKUP_DIR" ] || { echo "ROLLBACK REFUSED — no backup for plan $PLAN_ID"; exit 52; }

PLAN_FILE="$BACKUP_DIR/reset-plan.json"
[ -f "$PLAN_FILE" ] || { echo "ROLLBACK REFUSED — backup missing reset-plan.json"; exit 53; }

# verify every hash BEFORE touching the world
( cd "$BACKUP_DIR" && sha256sum -c SHA256SUMS --quiet ) || {
    echo "ROLLBACK REFUSED — backup hashes do not match (corrupted backup)"; exit 54;
}

DIM_REAL=$(python3 - "$SERVER_DIR/world" <<'PYEOF'
import os, sys
print(os.path.realpath(os.path.join(sys.argv[1], "dimensions", "bigbangexpeditions", "expedition")))
PYEOF
)

cd "$BACKUP_DIR"
RESTORED=0
for sub in region entities poi; do
    [ -d "$sub" ] || continue
    while IFS= read -r f; do
        rel="${f#./}"
        [ -f "$BACKUP_DIR/$rel" ] || continue
        mkdir -p "$DIM_REAL/$(dirname "$rel")"
        cp "$BACKUP_DIR/$rel" "$DIM_REAL/$rel"
        RESTORED=$((RESTORED+1))
    done < <(find "$sub" -type f)
done
echo "[staging] rollback complete: $RESTORED files restored from $BACKUP_DIR"
