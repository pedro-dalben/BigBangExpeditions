#!/usr/bin/env bash
# common-prod.sh — shared helpers for BigBangExpeditions PRODUCTION scripts.
# Source this file; do not execute directly.
set -euo pipefail

STAGING_ROOT="${BIGBANG_STAGING_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/.staging}"
SERVER_DIR="${BIGBANG_SERVER_DIR:-$STAGING_ROOT/server}"
BBE_ROOT="$SERVER_DIR/bigbangexpeditions"
JOURNAL_DIR="$BBE_ROOT/journal"
LOCK_FILE="$BBE_ROOT/locks/reset.lock"
LEDGER_FILE="$BBE_ROOT/authorization-ledger.json"
CONFIG_DIR="$SERVER_DIR/config/bigbangexpeditions"
CURRENT_FP_FILE="$CONFIG_DIR/current-fingerprint.json"
MOD_JAR="$(ls "$SERVER_DIR"/mods/[Bb]ig[Bb]ang[Ee]xpeditions-*.jar 2>/dev/null | head -1 || true)"
GSON_JAR="$(ls "$SERVER_DIR"/libraries/com/google/code/gson/gson/*/gson-*.jar 2>/dev/null | sort -V | tail -1 || true)"
CLI_CLASSPATH="${MOD_JAR}${GSON_JAR:+:$GSON_JAR}"

die() { echo "ERROR: $*" >&2; exit 1; }
info() { echo "[prod] $*"; }

# The mod jar carries VerifyAuthCli — single canonical implementation of the
# authorization checks (no duplicated checksum logic in bash/python).
require_mod_jar() {
    [ -n "$MOD_JAR" ] && [ -f "$MOD_JAR" ] || die "bigbangexpeditions jar not found in $SERVER_DIR/mods"
}

verify_auth() {
    require_mod_jar
    local AUTH_ID="$1"
    [[ "$AUTH_ID" =~ ^[0-9a-fA-F-]{36}$ ]] || { echo "AUTH_REFUSED:malformed id"; return 40; }
    local FP_ARG=""
    [ -f "$CURRENT_FP_FILE" ] && FP_ARG="$CURRENT_FP_FILE"
    java -cp "$CLI_CLASSPATH" com.bigbangcraft.expeditions.reset.VerifyAuthCli \
        "$BBE_ROOT" "$AUTH_ID" ${FP_ARG:+$FP_ARG} "$LEDGER_FILE" DIMENSION
}

# Production destructive operations require BOTH signals:
#   1. config/bigbangexpeditions/environment.properties -> environment=production
#   2. config/bigbangexpeditions/production.enabled whose trimmed content equals
#      the short hash of the CURRENT install fingerprint (install-bound ack)
require_production_signals() {
    local ENV_LINE ACK
    if [ ! -f "$CONFIG_DIR/environment.properties" ]; then
        echo "RESET REFUSED — no environment configuration (default STAGING is non-destructive)"; exit 42
    fi
    ENV_LINE="$(grep -E '^environment[[:space:]]*=' "$CONFIG_DIR/environment.properties" | tail -1 | cut -d= -f2- | xargs || true)"
    [ "$ENV_LINE" = "production" ] || {
        echo "RESET REFUSED — environment=$ENV_LINE (destructive execution requires production)"; exit 43;
    }
    if [ ! -f "$CONFIG_DIR/production.enabled" ]; then
        echo "RESET REFUSED — production acknowledgment file missing (config/bigbangexpeditions/production.enabled)"; exit 44
    fi
    ACK="$(tr -d '[:space:]' < "$CONFIG_DIR/production.enabled")"
    local SHORT
    SHORT="$(python3 -c "import json,sys;m=json.load(open('$CURRENT_FP_FILE'));print(m['sha256'][:12])" 2>/dev/null || true)"
    if [ -z "$SHORT" ]; then
        echo "RESET REFUSED — current fingerprint unavailable (server must run once after config change to export it)"; exit 45
    fi
    [ "$ACK" = "$SHORT" ] || {
        echo "RESET REFUSED — production.enabled '$ACK' does not match this installation ($SHORT)"; exit 46;
    }
    # dry-run escape hatch stays impossible: signals above are mandatory
    :
}

require_server_stopped() {
    if pgrep -f "fabricloader|fmlserver|net.minecraftforge" >/dev/null 2>&1; then
        echo "RESET REFUSED — a Minecraft server process appears to be running"; exit 47
    fi
}

# Whole-dimension target derivation, strictly confined.
dimension_dir() {
    python3 - "$SERVER_DIR" <<'PYEOF'
import os, sys, re
server = sys.argv[1]
level = "world"
props = os.path.join(server, "server.properties")
if os.path.isfile(props):
    for line in open(props, errors="replace"):
        m = re.match(r"^level-name\s*=\s*(\S+)", line.strip())
        if m:
            level = m.group(1)
            break
dim = os.path.realpath(os.path.join(server, level, "dimensions", "bigbangexpeditions", "expedition"))
world_root = os.path.realpath(os.path.join(server, level))
if not dim.startswith(world_root + os.sep):
    print("REFUSED"); sys.exit(0)
print(dim)
PYEOF
}
