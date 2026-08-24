#!/usr/bin/env bash
# provision.sh — create isolated DeceasedCraft staging server.
# Copies pack content from the CurseForge instance (READ-ONLY source; never mutated).
# Usage: scripts/staging/provision.sh [--force]
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common.sh"

INSTANCE="${BIGBANG_INSTANCE:-/home/pedro/Documents/curseforge/minecraft/Instances/DeceasedCraft - Urban Zombie Apocalypse}"
FORGE_VERSION="47.4.0"
MC_VERSION="1.20.1"
FORGE_INSTALLER_URL="https://maven.minecraftforge.net/net/minecraftforge/forge/${MC_VERSION}-${FORGE_VERSION}/forge-${MC_VERSION}-${FORGE_VERSION}-installer.jar"
PINNED_SEED="bigbangexpeditions-goal02"

[ -d "$INSTANCE" ] || die "instance not found: $INSTANCE"
if [ -f "$SENTINEL" ]; then
    [ "${1:-}" = "--force" ] || die "already provisioned (sentinel exists). Use --force to re-provision."
fi

mkdir -p "$SERVER_DIR"
cd "$SERVER_DIR"

info "copying pack content (read-only source)"
for item in mods config defaultconfigs kubejs datapacks; do
    if [ -d "$INSTANCE/$item" ]; then
        rm -rf "$item"
        cp -r "$INSTANCE/$item" "$item"
        info "  $item: $(find "$item" -maxdepth 1 -type f | wc -l) files"
    fi
done

info "removing client-only jars from staging mods"
find mods -iname "*controllable*" -o -iname "*fancymenu*" -o -iname "*oculus*" -o -iname "*embeddium*" | while read -r f; do rm -f "$f"; done

info "fetching forge installer"
mkdir -p .forge-cache
INSTALLER=".forge-cache/forge-${MC_VERSION}-${FORGE_VERSION}-installer.jar"
if [ ! -f "$INSTALLER" ]; then
    curl -fsSL -o "$INSTALLER" "$FORGE_INSTALLER_URL"
fi

info "installing forge server (headless)"
java -jar "$INSTALLER" --installServer .

echo "eula=true" > eula.txt

info "writing pinned server.properties"
cat > server.properties <<EOF
# BigBangExpeditions staging — PINNED SEED, do not change
level-seed=$PINNED_SEED
allow-nether=false
generate-structures=true
online-mode=false
difficulty=hard
gamemode=survival
max-tick-time=-1
motd=BigBangExpeditions staging (DISPOSABLE)
enable-rcon=false
view-distance=8
simulation-distance=6
spawn-protection=0
level-type=minecraft\:normal
EOF

info "adding expedition dimension to lostcities dimensionsWithProfiles"
python3 - "$SERVER_DIR/config/lostcities/common.toml" <<'PYEOF'
import sys, re
p = sys.argv[1]
s = open(p).read()
entry = "bigbangexpeditions:expedition=deceasedcraft_onlycities"
if entry not in s:
    s = re.sub(r'(dimensionsWithProfiles\s*=\s*\[)', r'\1"' + entry + '", ', s, count=1)
    open(p, "w").write(s)
    print("added:", entry)
else:
    print("already present")
PYEOF

info "installing latest BigBangExpeditions jar"
bash "$SCRIPT_DIR/install-mod.sh"

touch "$SENTINEL"
info "provisioning complete. sentinel: $SENTINEL"
