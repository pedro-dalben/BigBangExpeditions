#!/usr/bin/env bash
# status.sh — staging server status snapshot.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common.sh"

echo "staging dir: $SERVER_DIR"
if [ -f "$SENTINEL" ]; then echo "sentinel: present"; else echo "sentinel: MISSING"; fi

if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
    echo "server: RUNNING (pid $(cat "$PID_FILE"))"
    if [ -f "$LOG_FILE" ]; then
        if grep -q 'Done ([0-9.]*s)!' "$LOG_FILE" 2>/dev/null; then echo "state: READY"; else echo "state: BOOTING"; fi
        grep -E 'Done \(|ERROR|Exception in thread' "$LOG_FILE" | tail -5 || true
    fi
else
    echo "server: STOPPED"
fi

if [ -d "$SERVER_DIR/world/dimensions/bigbangexpeditions/expedition/region" ]; then
    n=$(find "$SERVER_DIR/world/dimensions/bigbangexpeditions/expedition/region" -name "*.mca" | wc -l)
    echo "expedition region files: $n"
else
    echo "expedition region: not generated yet"
fi
