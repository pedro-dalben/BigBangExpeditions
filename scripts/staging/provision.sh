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
# Evidence (staging boots 2026-08-24): these fail or crash dedicated servers:
# drippy/colorwheel (missing client deps), shouldersurfing (client mixin
# breaks Create 6.0.6 load on DEDICATED_SERVER), sodium family +
# entity model/texture features + ItemPhysicLite + EMF (client-only).
find mods -type f \( \
    -iname "*controllable*" -o -iname "*fancymenu*" -o -iname "*oculus*" \
    -o -iname "*embeddium*" -o -iname "*drippyloadingscreen*" \
    -o -iname "*colorwheel*" -o -iname "*shouldersurfing*" \
    -o -iname "*sodium*" -o -iname "*itemphysic*" \
    -o -iname "*entity_model_features*" -o -iname "*entity_texture_features*" \
    -o -iname "*tp_shooting*" -o -iname "*tacz*" \
    -o -iname "*gundb*" -o -iname "*shotsfired*" \) -delete
# tp_shooting hard-depends on removed shouldersurfing; tacz is the gun API
# family (tp_shooting/gundb/shotsfired depend on it and break server load).

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

info "configuring OPAC: expedition dimension unclaimable"
# world/serverconfig may not exist until first boot; patch defaultconfigs too
for cfg in "$SERVER_DIR/world/serverconfig/openpartiesandclaims-server.toml" \
           "$SERVER_DIR/defaultconfigs/openpartiesandclaims-server.toml"; do
    [ -f "$cfg" ] || continue
    python3 - "$cfg" <<'PYEOF'
import sys
p = sys.argv[1]
s = open(p).read()
changed = False
if 'bigbangexpeditions:expedition' not in s and 'claimableDimensionsList = []' in s:
    s = s.replace('claimableDimensionsList = []',
                  'claimableDimensionsList = ["bigbangexpeditions:expedition"]')
    changed = True
if 'allowExistingClaimsInUnclaimableDimensions = true' in s:
    s = s.replace('allowExistingClaimsInUnclaimableDimensions = true',
                  'allowExistingClaimsInUnclaimableDimensions = false')
    changed = True
if changed:
    open(p, 'w').write(s)
    print('patched:', p)
else:
    print('no change needed:', p)
PYEOF
done

info "installing latest BigBangExpeditions jar"
bash "$SCRIPT_DIR/install-mod.sh"

touch "$SENTINEL"
info "provisioning complete. sentinel: $SENTINEL"
