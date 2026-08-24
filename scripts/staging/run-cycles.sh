#!/usr/bin/env bash
# run-cycles.sh — batch determinism campaign driver (staging only).
# Usage: run-cycles.sh <startCycle> <endCycle> <refBaselineFile>
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common.sh"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
CONSOLE="$SCRIPT_DIR/console.sh"

START=${1:?}; END=${2:?}; REF_BASELINE=${3:-}

force_full_region() {
    for args in "2048 2048 2303 2303" "2304 2048 2559 2303" "2048 2304 2303 2559" "2304 2304 2559 2559"; do
        "$CONSOLE" "execute in bigbangexpeditions:expedition run forceload add $args" >/dev/null 2>&1 || true
        sleep 15
    done
}

for CYCLE in $(seq "$START" "$END"); do
    echo "=== CYCLE $CYCLE ==="
    EV="$REPO_ROOT/evidence/goal-02/cycle-$(printf '%03d' "$CYCLE")"

    # prepare plan while running
    "$CONSOLE" "expedition sector lock b04" >/dev/null || true
    PLAN_OUT=$("$CONSOLE" "expedition sector reset-plan b04" | grep -o '[0-9a-f-]\{36\}' || true)
    if [ -z "$PLAN_OUT" ]; then echo "plan failed"; exit 1; fi
    echo "plan: $PLAN_OUT"

    bash "$SCRIPT_DIR/stop.sh" >/dev/null
    T0=$(date +%s)
    bash "$SCRIPT_DIR/execute-reset.sh" "$PLAN_OUT" | tail -2
    T_RESET=$(($(date +%s)-T0))
    bash "$SCRIPT_DIR/start.sh" >/dev/null
    sleep 90

    T1=$(date +%s)
    force_full_region
    sleep 90
    T_GEN=$(($(date +%s)-T1))
    "$CONSOLE" "save-all flush" >/dev/null

    "$CONSOLE" "expedition sector begin-validation b04" >/dev/null || true

    BASE="c${CYCLE}base"
    "$CONSOLE" "expedition sector baseline $BASE bigbangexpeditions:expedition 128 128 159 159" >/dev/null
    BASE_FILE=$(ls -t "$SERVER_DIR"/bigbangexpeditions/baselines/${BASE}_*.json | head -1)

    RESULT="PASS"
    DETAIL=""
    if [ -n "$REF_BASELINE" ]; then
        CMP=$("$CONSOLE" "expedition sector compare $(basename "$REF_BASELINE") c${CYCLE}cmp bigbangexpeditions:expedition 128 128 159 159")
        echo "$CMP" > "$EV-compare.txt"
        BAD=$(echo "$CMP" | grep -E "^  [a-z]" | grep -vE "lootr_chest|campfire|blast_furnace|command_block|cannon|vending|beehive" | head -5 || true)
        SPAWNER_OK=$(echo "$CMP" | grep "^spawnerCount" | grep -q "unchanged" && echo yes || echo no)
        if [ "$SPAWNER_OK" = "no" ]; then RESULT="FAIL"; DETAIL="spawner mismatch"; fi
    else
        REF_BASELINE="$BASE_FILE"
        DETAIL="reference baseline established"
    fi

    "$CONSOLE" "expedition sector open b04" >/dev/null || true

    mkdir -p "$EV"
    cat > "$EV/summary.json" <<EOF
{
  "cycle": $CYCLE,
  "plan": "$PLAN_OUT",
  "result": "$RESULT",
  "resetDurationSec": $T_RESET,
  "regenSettleSec": $T_GEN,
  "detail": "$DETAIL",
  "referenceBaseline": "$(basename "${REF_BASELINE:-none}")",
  "cycleBaseline": "$(basename "$BASE_FILE")"
}
EOF
    cp "$BASE_FILE" "$EV/" 2>/dev/null || true
    echo "cycle $CYCLE: $RESULT (${T_RESET}s reset, ${T_GEN}s gen)"
done
echo "campaign done; reference: ${REF_BASELINE}"
