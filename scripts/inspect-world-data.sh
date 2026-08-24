#!/usr/bin/env bash
# inspect-world-data.sh — READ-ONLY enumerator for world/data/*.dat
# Usage: ./scripts/inspect-world-data.sh [world_dir]
# Identifies potential mod ownership without mutating NBT.
set -euo pipefail
WORLD_DIR="${1:-world}"
DATA_DIR="$WORLD_DIR/data"
echo "=== Inspect world/data ==="
echo "world=$WORLD_DIR data=$DATA_DIR"
if [ ! -d "$DATA_DIR" ]; then
  echo "No $DATA_DIR — create a staging world first" >&2
  exit 0
fi
echo "Files:"
ls -lh "$DATA_DIR" | head -100
echo ""
echo "Ownership hints (by filename prefix):"
for f in "$DATA_DIR"/*.dat; do
  [ -e "$f" ] || continue
  base=$(basename "$f")
  hint="unknown"
  case "$base" in
    lootr*|Lootr*) hint="Lootr (noobanidus/mods/lootr/data/DataStorage)" ;;
    openpartiesandclaims*|opac*) hint="OPAC ServerClaimsManager" ;;
    ftbteams*|ftb*team*) hint="FTB Teams" ;;
    refinedstorage*|refined_storage*) hint="Refined Storage NetworkManager" ;;
    securitycraft*|sc_*) hint="SecurityCraft" ;;
    hordes*|horde*) hint="Hordes timers/infection" ;;
    create*|contraption*) hint="Create" ;;
    immersiveengineering*|ie*) hint="IE" ;;
    level_sponge*) hint="Sponge/Forge" ;;
  esac
  size=$(stat -c%s "$f" 2>/dev/null || stat -f%z "$f" 2>/dev/null || echo "?")
  sha=$(sha256sum "$f" 2>/dev/null | cut -c1-16 || echo "?")
  printf "%-40s %-10s %-8s %s\n" "$base" "$size" "$sha" "$hint"
done
echo ""
echo "NBT quick check (requires nbt tools, optional):"
echo "  python3 -c \"import nbtlib; print(nbtlib.load('$DATA_DIR/<file>.dat'))\" "
echo "Done. This script never modifies world/data."
