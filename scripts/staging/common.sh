#!/usr/bin/env bash
# common.sh — shared helpers for BigBangExpeditions staging scripts.
# Source this file; do not execute directly.

STAGING_ROOT="${BIGBANG_STAGING_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/.staging}"
SERVER_DIR="$STAGING_ROOT/server"
SENTINEL="$SERVER_DIR/.bigbangexpeditions-staging"
PID_FILE="$SERVER_DIR/.server.pid"
LOG_FILE="$SERVER_DIR/logs/latest.log"

# Sentinel requirement for ANY destructive operation.
require_staging_sentinel() {
    if [ ! -f "$SENTINEL" ]; then
        echo "RESET REFUSED"
        echo "Not a BigBangExpeditions staging environment."
        echo "(missing sentinel: $SENTINEL)"
        exit 42
    fi
}

require_server_stopped() {
    if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
        echo "RESET REFUSED"
        echo "Staging server appears to be running (pid $(cat "$PID_FILE"))."
        exit 43
    fi
    # Extra guard: any live java process with our server dir as cwd/main class hint.
    if pgrep -f "fabric|minecraftforge" >/dev/null 2>&1 && pgrep -af "java" | grep -q "bigbangexpeditions\|forge.*47.4.0"; then
        : # heuristic only; authoritative check is PID file above
    fi
}

die() { echo "ERROR: $*" >&2; exit 1; }
info() { echo "[staging] $*"; }
