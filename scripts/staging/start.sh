#!/usr/bin/env bash
# start.sh — start staging server (background, PID-tracked).
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common.sh"
require_staging_sentinel

if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
    info "already running (pid $(cat "$PID_FILE"))"
    exit 0
fi

cd "$SERVER_DIR"
echo "-Xmx6G" > user_jvm_args.txt
nohup java @user_jvm_args.txt @libraries/net/minecraftforge/forge/1.20.1-47.4.0/linux_args.txt nogui \
    > "$SERVER_DIR/console.out" 2>&1 &
echo $! > "$PID_FILE"
info "started pid $(cat "$PID_FILE"); console: $SERVER_DIR/console.out; log: $LOG_FILE"
