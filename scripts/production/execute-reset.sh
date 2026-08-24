#!/usr/bin/env bash
# execute-reset.sh — PRODUCTION whole-dimension destructive reset (Goal 03).
#
# Guard chain (every step fails closed):
#   production signals -> server stopped -> OS flock + persistent lock ->
#   VerifyAuthCli (ledger/checksum/expiry/fingerprint) -> journal AUTH_VERIFIED ->
#   disk space -> BACKUP_START -> backup+SHA manifest -> BACKUP_DONE ->
#   DELETION_INTENT -> confined delete -> DELETION_DONE -> lifecycle RESETTING.
#
# Usage: execute-reset.sh <authId>
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common-prod.sh"

AUTH_ID="${1:?usage: execute-reset.sh <authId>}"

require_production_signals
require_server_stopped

mkdir -p "$BBE_ROOT/locks"
(
    # OS-level lock: two executors can never run concurrently on this host
    flock -n 200 || { echo "RESET REFUSED — another executor holds the reset lock"; exit 48; }

    # ---- authorization gate (single canonical Java implementation) ----------
    set +e
    VERIFY_OUT="$(verify_auth "$AUTH_ID")"
    VERIFY_RC=$?
    set -e
    [ $VERIFY_RC -eq 0 ] || { echo "RESET REFUSED — $VERIFY_OUT"; exit 49; }
    info "$VERIFY_OUT"

    journal() {  # recordCompleted <phase>
        java -cp "$CLI_CLASSPATH" com.bigbangcraft.expeditions.reset.OperationJournalCli \
            "$JOURNAL_DIR" "$AUTH_ID" "$1" >/dev/null
    }
    require_mod_jar
    journal "AUTH_VERIFIED"

    DIM_REAL="$(dimension_dir)"
    [ -n "$DIM_REAL" ] && [ "$DIM_REAL" != "REFUSED" ] || {
        echo "RESET REFUSED — dimension dir derivation/confinement failed"; exit 50;
    }
    info "target confined: $DIM_REAL"

    # ---- disk space ----------------------------------------------------------
    AVAIL_KB=$(df -kP "$SERVER_DIR" | awk 'NR==2{print $4}')
    DIM_SIZE_KB=$(du -sk "$DIM_REAL" 2>/dev/null | cut -f1 || echo 0)
    NEEDED=$(( DIM_SIZE_KB * 2 + 10240 ))
    if [ "$AVAIL_KB" -lt "$NEEDED" ]; then
        echo "RESET REFUSED — insufficient disk for backup (need ~${NEEDED}KB, avail ${AVAIL_KB}KB)"; exit 51
    fi

    # ---- backup ---------------------------------------------------------------
    mkdir -p "$BBE_ROOT/backups"
    BACKUP_DIR="$BBE_ROOT/backups/$AUTH_ID"
    if mkdir "$BACKUP_DIR" 2>/dev/null; then   # atomic creation = no double backup
        :
    else
        echo "RESET REFUSED — backup already exists for this plan (rollback first or remove deliberately)"; exit 52
    fi
    java -cp "$CLI_CLASSPATH" com.bigbangcraft.expeditions.reset.OperationJournalCli \
        "$JOURNAL_DIR" "$AUTH_ID" "BACKUP_START" >/dev/null

    cp "$(dirname "$BBE_ROOT")/bigbangexpeditions/authorizations/$AUTH_ID.json" "$BACKUP_DIR/authorization.json"
    ( cd "$DIM_REAL" && find . -type f ! -name SHA256SUMS ! -name backup-manifest.json | sed 's|^\./||' ) > "$BACKUP_DIR/filelist.txt"
    while IFS= read -r rel; do
        [ -f "$DIM_REAL/$rel" ] || continue
        mkdir -p "$BACKUP_DIR/$(dirname "$rel")"
        cp "$DIM_REAL/$rel" "$BACKUP_DIR/$rel"
    done < "$BACKUP_DIR/filelist.txt"

    python3 - "$BACKUP_DIR" "$AUTH_ID" <<'PYEOF'
import hashlib, json, os, sys
backup, auth_id = sys.argv[1], sys.argv[2]
files = []
for root, _, names in os.walk(backup):
    for n in names:
        if n in ("SHA256SUMS", "backup-manifest.json"): continue
        p = os.path.join(root, n)
        rel = os.path.relpath(p, backup).replace(os.sep, "/")
        data = open(p, "rb").read()
        files.append({"path": rel,
                      "sha256": hashlib.sha256(data).hexdigest(),
                      "bytes": len(data)})
m = {"formatVersion": 1, "backupId": auth_id, "lifecycleGeneration": -1,
     "bbeVersion": "?", "minecraftVersion": "?", "forgeVersion": "?",
     "createdAtEpochMs": __import__("time").time_ns() // 1_000_000,
     "files": files, "totalBytes": sum(f["bytes"] for f in files)}
body = json.dumps(m, sort_keys=True)
m["manifestChecksum"] = hashlib.sha256(body.encode()).hexdigest()
open(os.path.join(backup, "backup-manifest.json"), "w").write(json.dumps(m))
print("[prod] backup manifest:", len(files), "files,", m["totalBytes"], "bytes")
PYEOF
    java -cp "$CLI_CLASSPATH" com.bigbangcraft.expeditions.reset.OperationJournalCli \
        "$JOURNAL_DIR" "$AUTH_ID" "BACKUP_DONE" >/dev/null
    info "backup verified at $BACKUP_DIR"

    # ---- deletion (confined to the expedition dimension only) -----------------
    java -cp "$CLI_CLASSPATH" com.bigbangcraft.expeditions.reset.OperationJournalCli \
        "$JOURNAL_DIR" "$AUTH_ID" "DELETION_INTENT" >/dev/null

    DELETED=0
    while IFS= read -r rel; do
        case "$rel" in
            ..*|*../*|/*) die "confinement violation: $rel" ;;
        esac
        rm -f "$DIM_REAL/$rel" && DELETED=$((DELETED+1))
    done < "$BACKUP_DIR/filelist.txt"
    find "$DIM_REAL" -type d -empty -delete 2>/dev/null || true
    java -cp "$CLI_CLASSPATH" com.bigbangcraft.expeditions.reset.OperationJournalCli \
        "$JOURNAL_DIR" "$AUTH_ID" "DELETION_DONE" >/dev/null
    info "deleted $DELETED files under $DIM_REAL"

    # ---- lifecycle bookkeeping ----------------------------------------------
    LIFECYCLE="$BBE_ROOT/lifecycle.json"
    python3 - "$LIFECYCLE" <<'PYEOF'
import json, os, sys
p = sys.argv[1]
if not os.path.isfile(p):
    print("[prod] no lifecycle file — skipping"); sys.exit(0)
reg = json.load(open(p))
if reg.get("status") == "RESET_READY":
    reg["status"] = "RESETTING"
    reg["lastChangeReason"] = "offline executor completed deletion"
    reg.setdefault("recent", []).append({"from": "RESET_READY", "to": "RESETTING", "by": "execute-reset", "reason": "deletion done"})
    tmp = p + ".tmp"
    open(tmp, "w").write(json.dumps(reg, indent=2))
    os.replace(tmp, p)
    print("[prod] lifecycle -> RESETTING")
else:
    print("[prod] lifecycle status", reg.get("status"), "- left unchanged")
PYEOF
    java -cp "$CLI_CLASSPATH" com.bigbangcraft.expeditions.reset.OperationJournalCli \
        "$JOURNAL_DIR" "$AUTH_ID" "FINALIZED" >/dev/null

    info "RESET COMPLETE for authorization $AUTH_ID"
    info "next: start the server — startup gate resumes BOOTING->VALIDATING; run baseline compare then /expedition lifecycle open"
    exit 0
) 200>"$LOCK_FILE"
