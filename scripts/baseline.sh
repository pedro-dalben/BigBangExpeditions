#!/usr/bin/env bash
# baseline.sh — READ-ONLY hash collector for Goal 00 T02/T06/T23
# Usage: ./scripts/baseline.sh [world_dir] [sector_label]
#   world_dir: path to server world (default: world)
#   sector_label: label for output prefix (default: sector)
# Output: hashes in bigbangexpeditions/baselines/ and stdout
# NEVER deletes or mutates world data.
set -euo pipefail
WORLD_DIR="${1:-world}"
LABEL="${2:-sector}"
OUT_DIR="bigbangexpeditions/baselines"
mkdir -p "$OUT_DIR"
TS=$(date -u +"%Y%m%dT%H%M%SZ")
echo "=== Baseline $TS ==="
echo "world_dir=$WORLD_DIR"
if [ ! -d "$WORLD_DIR" ]; then
  echo "WARN: world dir $WORLD_DIR not found — static check only" >&2
fi
for sub in "region" "entities" "poi" "data"; do
  dir="$WORLD_DIR/$sub"
  if [ -d "$dir" ]; then
    echo "--- $sub ---"
    # sha256 of each .mca / .dat
    find "$dir" -type f \( -name "*.mca" -o -name "*.dat" \) -exec sha256sum {} \; | sort | tee "$OUT_DIR/${LABEL}_${sub}_${TS}.sha256"
    echo "count: $(find "$dir" -type f | wc -l) files"
  else
    echo "--- $sub: not present ($dir) ---"
  fi
done
# level.dat
if [ -f "$WORLD_DIR/level.dat" ]; then
  sha256sum "$WORLD_DIR/level.dat" | tee "$OUT_DIR/${LABEL}_level_${TS}.sha256"
fi
# LostCities profile hash
for p in config/lostcities/common.toml config/lostcities/profiles/deceasedcraft_onlycities.json defaultconfigs/lostcities-server.toml; do
  if [ -f "$p" ]; then
    sha256sum "$p" | tee -a "$OUT_DIR/${LABEL}_profiles_${TS}.sha256"
  fi
done
echo "Done. Files in $OUT_DIR"
ls -lh "$OUT_DIR" | tail -20
