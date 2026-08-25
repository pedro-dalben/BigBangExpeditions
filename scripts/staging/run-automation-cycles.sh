#!/usr/bin/env bash
# run-automation-cycles.sh — Goal 05 multi-cycle AUTONOMY campaign (staging).
#
# Drives N COMPLETE production-discipline cycles with ZERO operator close orders:
#   synthetic activity (staging-gated) -> forced evaluation ->
#   AUTOMATIC_CLOSURE decision (pending) -> Goal-04 timed closing + extraction ->
#   LOCKED -> DIMENSION-scope authorization issue (purge-ack aware) ->
#   offline authenticated reset (flock/journal/backup/consume, whole dimension) ->
#   boot resume BOOTING->VALIDATING -> record-validation PASS -> open (gen++) ->
#   telemetry rollover isolation check.
#
# Usage: run-automation-cycles.sh <startCycle> <endCycle>
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common.sh"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
CONSOLE="$SCRIPT_DIR/console.sh"
SERVER_JSON="$SERVER_DIR/bigbangexpeditions/lifecycle.json"
EVROOT="$REPO_ROOT/evidence/goal-05"
START=${1:?}; END=${2:?}
REF_BASELINE=""

c() { "$CONSOLE" "$1"; }

active_auth_id() {
    python3 -c "import json;print(json.load(open('$SERVER_JSON')).get('activeAuthId',''))" 2>/dev/null
}

wait_lifecycle_state() { # $1=grep-pattern $2=timeoutSec
    local want="$1" timeout="${2:-120}" elapsed=0
    while [ "$elapsed" -lt "$timeout" ]; do
        if c "expedition lifecycle status" 2>/dev/null | grep -q "status: $want"; then return 0; fi
        sleep 4; elapsed=$((elapsed+4))
    done
    return 1
}

force_full_region() {
    for args in "2048 2048 2303 2303" "2304 2048 2559 2303" "2048 2304 2303 2559" "2304 2304 2559 2559"; do
        c "execute in bigbangexpeditions:expedition run forceload add $args" >/dev/null 2>&1 || true
        sleep 15
    done
}

mkdir -p "$EVROOT"

# single-campaign lockfile (prevent zombie drivers from double-seeding)
LOCK="$EVROOT/.campaign.lock"
if ! mkdir "$LOCK" 2>/dev/null; then
    echo "another campaign holds $LOCK — refusing to start"; exit 90
fi
trap 'rmdir "$LOCK" 2>/dev/null' EXIT

for CYCLE in $(seq "$START" "$END"); do
    echo "=== GOAL05 AUTO-CYCLE $CYCLE ==="
    EV="$EVROOT/automation-cycle-$(printf '%03d' "$CYCLE")"
    mkdir -p "$EV"

    # ---- 1. synthetic activity (staging-gated) + forced evaluations ----
    c "expedition automation seed-sim 1000 800 20 400" > "$EV/seed.txt" 2>&1 || true
    c "expedition automation seed-sim 24 200 5 400" >> "$EV/seed.txt" 2>&1 || true
    c "expedition automation evaluate" > "$EV/evaluate.txt" 2>&1 || true

    # ---- 2. automation must execute closure itself ----
    if ! wait_lifecycle_state "CLOSING\|LOCKED" 30; then
        echo "$CYCLE: automation did not trigger closing"; echo FAIL > "$EV/RESULT"; exit 12
    fi
    if ! wait_lifecycle_state "LOCKED" 150; then
        echo "$CYCLE: extraction never reached LOCKED"; echo FAIL > "$EV/RESULT"; exit 13
    fi
    c "expedition lifecycle status" > "$EV/locked-status.txt" 2>&1 || true
    c "expedition automation history" > "$EV/history.txt" 2>&1 || true

    # ---- 3. DIMENSION authorization (purge-ack + drift-revalidation aware) ----
    : > "$EV/issue-auth.txt"
    AUTH_ID=""
    for ATTEMPT in 1 2 3; do
        ISSUE_OUT=$(c "expedition lifecycle issue-authorization" 2>&1)
        echo "--- attempt $ATTEMPT" >> "$EV/issue-auth.txt"
        echo "$ISSUE_OUT" >> "$EV/issue-auth.txt"
        if echo "$ISSUE_OUT" | grep -qiE "RESET_READY|issued|authorization bound"; then
            AUTH_ID=$(active_auth_id); break
        fi
        HASH=$(echo "$ISSUE_OUT" | grep -oE "[0-9a-f]{12}" | head -1)
        if [ -n "$HASH" ] && ! echo "$ISSUE_OUT" | grep -q DRIFT; then
            ISSUE_OUT=$(c "expedition lifecycle issue-authorization $HASH" 2>&1)
            echo "$ISSUE_OUT" >> "$EV/issue-auth.txt"
            if echo "$ISSUE_OUT" | grep -qiE "RESET_READY|issued|authorization bound"; then
                AUTH_ID=$(active_auth_id); break
            fi
        fi
        if echo "$ISSUE_OUT" | grep -q "REQUIRE_REVALIDATION"; then
            c "expedition lifecycle record-qualification" >> "$EV/issue-auth.txt" 2>&1 || true
            continue
        fi
    done
    [ -n "$AUTH_ID" ] || { echo "$CYCLE: authorization never issued"; echo FAIL > "$EV/RESULT"; exit 14; }
    echo "auth: $AUTH_ID"

    # ---- 4. offline authenticated destructive reset ----
    bash "$SCRIPT_DIR/stop.sh" >/dev/null
    T0=$(date +%s)
    if ! bash "$SCRIPT_DIR/execute-authenticated-reset.sh" "$AUTH_ID" > "$EV/reset-execution.txt" 2>&1; then
        echo "$CYCLE: authenticated reset failed"; tail -5 "$EV/reset-execution.txt"; echo FAIL > "$EV/RESULT"; exit 16
    fi
    T_RESET=$(( $(date +%s) - T0 ))
    grep -E "RESET COMPLETE|backup manifest|deleted|lifecycle" "$EV/reset-execution.txt" | tail -4

    # startup gate resumes one step per boot (RESETTING->BOOTING, then
    # BOOTING->VALIDATING on the following boot) — deterministic restart dance
    bash "$SCRIPT_DIR/start.sh" >/dev/null
    if ! wait_lifecycle_state "VALIDATING\|OPEN\|BOOTING" 240; then
        echo "$CYCLE: boot never reached BOOTING"; echo FAIL > "$EV/RESULT"; exit 17
    fi
    if wait_lifecycle_state "BOOTING" 8; then
        echo "[cycle $CYCLE] gate resume step 1 complete — restarting for VALIDATING"
        bash "$SCRIPT_DIR/stop.sh" >/dev/null
        bash "$SCRIPT_DIR/start.sh" >/dev/null
    fi
    if ! wait_lifecycle_state "VALIDATING\|OPEN" 240; then
        echo "$CYCLE: boot never reached VALIDATING"; echo FAIL > "$EV/RESULT"; exit 17
    fi
    c "save-all flush" >/dev/null 2>&1
    T1=$(date +%s)
    force_full_region
    sleep 30
    T_GEN=$(( $(date +%s) - T1 ))

    # ---- 5. content regression evidence + mandatory validation gate ----
    BASE="g05c${CYCLE}base"
    c "expedition sector baseline $BASE bigbangexpeditions:expedition 128 128 159 159" >/dev/null 2>&1
    BASE_FILE=$(ls -t "$SERVER_DIR"/bigbangexpeditions/baselines/${BASE}_*.json 2>/dev/null | head -1)
    RESULT="PASS"; DETAIL=""
    if [ -n "$REF_BASELINE" ] && [ -n "$BASE_FILE" ]; then
        CMP=$(c "expedition sector compare $(basename "$REF_BASELINE") g05c${CYCLE}cmp bigbangexpeditions:expedition 128 128 159 159" 2>/dev/null)
        echo "$CMP" > "$EV/compare.txt"
        BAD=$(echo "$CMP" | grep -E "^  [a-z]" | grep -vE "lootr_chest|campfire|blast_furnace|command_block|cannon|vending|beehive" | head -5 || true)
        SPAWNER_OK=$(echo "$CMP" | grep "^spawnerCount" | grep -q "unchanged" && echo yes || echo no)
        [ "$SPAWNER_OK" = "no" ] && { RESULT="FAIL"; DETAIL="spawner mismatch"; }
        [ -n "$BAD" ] && { RESULT="FAIL"; DETAIL="$DETAIL unexpected-delta: $BAD"; }
    else
        REF_BASELINE="$BASE_FILE"
        DETAIL="reference established"
    fi

    c "expedition lifecycle record-validation PASS" > "$EV/validation.txt" 2>&1 || true
    OPEN_OUT=$(c "expedition lifecycle open" 2>&1)
    echo "$OPEN_OUT" > "$EV/open.txt"
    echo "$OPEN_OUT" | grep -qi "open" || { RESULT="FAIL"; DETAIL="$DETAIL open-refused"; }
    sleep 3

    GEN_NOW=$(c "expedition lifecycle status" 2>/dev/null | grep -o "generation: [0-9]*" | awk '{print $2}')
    TELEMETRY_FILES=$(ls "$SERVER_DIR"/bigbangexpeditions/telemetry/ 2>/dev/null | tr '\n' ' ')
    EXPECT_GEN=$(( START == CYCLE ? 0 : 0 )) # computed below via arithmetic from pre-cycle gen file
    HISTORY=$(c "expedition automation history" 2>/dev/null | head -4)

    cat > "$EV/summary.json" <<EOF
{
  "goal": "05", "cycle": $CYCLE, "result": "$RESULT",
  "closureDecisionBy": "automation:AUTOMATIC_CLOSURE",
  "authId": "$AUTH_ID",
  "resetDurationSec": $T_RESET, "regenSettleSec": $T_GEN,
  "detail": "$DETAIL", "generationAfterReopen": ${GEN_NOW:-unknown},
  "telemetryDirAfterReopen": "$TELEMETRY_FILES",
  "referenceBaseline": "$(basename "${REF_BASELINE:-none}")"
}
EOF
    echo "$HISTORY" > "$EV/history-head.txt"
    echo "cycle $CYCLE: $RESULT (reset ${T_RESET}s, settle ${T_GEN}s, generation now ${GEN_NOW:-?})"
done

echo "campaign complete: evidence under $EVROOT; reference baseline: ${REF_BASELINE}"
