#!/usr/bin/env bash
# rollback-reset.sh — PRODUCTION verified rollback to the backup of one authorization.
#
# Guard chain: production signals -> server stopped -> flock ->
#   backup manifest hash verification (pre-restore) -> confined restore ->
#   re-verification of restored files -> journal ROLLBACK_DONE.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common-prod.sh"

AUTH_ID="${1:?usage: rollback-reset.sh <authId>}"

require_production_signals
require_server_stopped

BACKUP_DIR="$BBE_ROOT/backups/$AUTH_ID"
[ -d "$BACKUP_DIR" ] || { echo "ROLLBACK REFUSED — no backup for $AUTH_ID"; exit 53; }
[ -f "$BACKUP_DIR/backup-manifest.json" ] || { echo "ROLLBACK REFUSED — manifest missing"; exit 54; }

(
    flock -n 200 || { echo "ROLLBACK REFUSED — another operation holds the reset lock"; exit 55; }

    DIM_REAL="$(dimension_dir)"
    [ -n "$DIM_REAL" ] && [ "$DIM_REAL" != "REFUSED" ] || {
        echo "ROLLBACK REFUSED — dimension dir derivation failed"; exit 56;
    }

    mkdir -p "$DIM_REAL"

    # verify every listed file BEFORE touching the world
    python3 - "$BACKUP_DIR" <<'PYEOF'
import hashlib, json, os, sys
backup = sys.argv[1]
m = json.load(open(os.path.join(backup, "backup-manifest.json")))
body = json.dumps(m, sort_keys=True)
# manifest checksum covers everything except its own field; rebuild without it
m2 = dict(m); saved = m2.pop("manifestChecksum", None)
if hashlib.sha256(json.dumps(m2, sort_keys=True).encode()).hexdigest() != saved:
    print("ROLLBACK REFUSED — manifest checksum invalid"); sys.exit(1)
bad = []
for f in m["files"]:
    p = os.path.join(backup, f["path"])
    if not os.path.isfile(p):
        bad.append("missing:" + f["path"]); continue
    data = open(p, "rb").read()
    if len(data) != f["bytes"] or hashlib.sha256(data).hexdigest() != f["sha256"]:
        bad.append("hash:" + f["path"])
if bad:
    print("ROLLBACK REFUSED — corrupted backup: " + "; ".join(bad[:5])); sys.exit(1)
print("[prod] pre-restore verification OK:", len(m["files"]), "files")
PYEOF

    # restore + re-verify in place
    while IFS= read -r rel; do
        case "$rel" in ..*|/*) die "confinement violation: $rel" ;; esac
        mkdir -p "$DIM_REAL/$(dirname "$rel")"
        cp "$BACKUP_DIR/$rel" "$DIM_REAL/$rel"
    done < <(python3 -c "
import json,sys
m=json.load(open('$BACKUP_DIR/backup-manifest.json'))
[print(f['path']) for f in m['files']]" )

    FAILS=$(python3 - "$BACKUP_DIR" "$DIM_REAL" <<'PYEOF'
import hashlib, json, os, sys
backup, dim = sys.argv[1], sys.argv[2]
m = json.load(open(os.path.join(backup, "backup-manifest.json")))
fails = 0
for f in m["files"]:
    p = os.path.join(dim, f["path"])
    okfile = os.path.isfile(p)
    if not okfile:
        fails += 1; continue
    data = open(p, "rb").read()
    if len(data) != f["bytes"] or hashlib.sha256(data).hexdigest() != f["sha256"]:
        fails += 1
print(fails)
PYEOF
)
    [ "$FAILS" = "0" ] || { echo "ROLLBACK FAILED — $FAILS file(s) failed post-restore verification"; exit 57; }

    java -cp "$CLI_CLASSPATH" com.bigbangcraft.expeditions.reset.OperationJournalCli \
        "$JOURNAL_DIR" "$AUTH_ID" "ROLLBACK_DONE" >/dev/null 2>&1 || true

    info "rollback complete and re-verified from $BACKUP_DIR"
    info "next: start server, run validation before any reopen"
) 200>"$LOCK_FILE"
