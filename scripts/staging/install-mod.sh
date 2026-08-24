#!/usr/bin/env bash
# install-mod.sh — copy latest local BigBangExpeditions jar into staging mods.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common.sh"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

[ -d "$SERVER_DIR/mods" ] || { echo "staging not provisioned"; exit 1; }

JAR=$(ls -t "$REPO_ROOT"/build/libs/*.jar 2>/dev/null | grep -vi sources | head -1 || true)
if [ -z "$JAR" ]; then
    echo "no built jar found — running ./gradlew build"
    (cd "$REPO_ROOT" && ./gradlew build --console=plain -q)
    JAR=$(ls -t "$REPO_ROOT"/build/libs/*.jar | grep -vi sources | head -1)
fi

rm -f "$SERVER_DIR"/mods/[Bb]ig[Bb]ang[Ee]xpeditions-*.jar
cp "$JAR" "$SERVER_DIR/mods/"
info "installed $(basename "$JAR") -> staging mods/"
