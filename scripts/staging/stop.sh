#!/usr/bin/env bash
# stop.sh — gracefully stop staging server (SIGTERM -> JVM shutdown hook saves worlds).
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common.sh"

if [ ! -f "$PID_FILE" ]; then
    info "no pid file — server not started by start.sh"
    exit 0
fi
PID=$(cat "$PID_FILE")
if ! kill -0 "$PID" 2>/dev/null; then
    rm -f "$PID_FILE"
    info "stale pid file removed"
    exit 0
fi
info "sending SIGTERM to $PID (graceful save)"
kill "$PID"
for i in $(seq 1 120); do
    kill -0 "$PID" 2>/dev/null || { rm -f "$PID_FILE"; info "stopped cleanly after ${i}s"; exit 0; }
    sleep 1
done
echo "ERROR: server did not stop within 120s" >&2
exit 44
