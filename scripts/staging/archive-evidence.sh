#!/usr/bin/env bash
# archive-evidence.sh — copy lightweight evidence into evidence/goal-02/<label>/.
# Never copies world/region data or backups. Logs + JSON + hashes only.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common.sh"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

LABEL="${1:?usage: archive-evidence.sh <label>}"
EV_DIR="$REPO_ROOT/evidence/goal-02/$LABEL"
mkdir -p "$EV_DIR"

# Lightweight evidence only — raw logs are too large to track.
[ -f "$LOG_FILE" ] && grep -E 'BigBangExpeditions|expedition|Done \([0-9.]+s\)|ERROR|FATAL' "$LOG_FILE" > "$EV_DIR/latest-relevant.log"
[ -f "$SERVER_DIR/console.out" ] && grep -E 'BigBangExpeditions|expedition|Done \([0-9.]+s\)|ERROR|FATAL' "$SERVER_DIR/console.out" > "$EV_DIR/console-relevant.log" || true
[ -f "$SERVER_DIR/logs/debug.log" ] && grep -E 'BigBangExpeditions|lostcities' "$SERVER_DIR/logs/debug.log" > "$EV_DIR/relevant-debug.log" || true

if [ -d "$SERVER_DIR/bigbangexpeditions/baselines" ]; then
    mkdir -p "$EV_DIR/baselines"
    cp "$SERVER_DIR"/bigbangexpeditions/baselines/* "$EV_DIR/baselines/" 2>/dev/null || true
fi
if [ -d "$SERVER_DIR/bigbangexpeditions/reset-plans" ]; then
    mkdir -p "$EV_DIR/reset-plans"
    cp "$SERVER_DIR"/bigbangexpeditions/reset-plans/*.json "$EV_DIR/reset-plans/" 2>/dev/null || true
fi

info "evidence archived: $EV_DIR"
ls -la "$EV_DIR"
