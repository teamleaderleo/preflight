#!/usr/bin/env bash
# Run one labeled combat-scaling discovery pilot on top of the existing gameplay pilot.
#
# Example:
#   scripts/run-combat-scaling-pilot.sh --run-id r1 --cell-id symmetric-1040 \
#     --battle-dp 1040 --game /Applications/Starsector.app
set -euo pipefail

RUN_ID=""
CELL_ID=""
BATTLE_DP=""
EVERY=60
DENSITY_SAMPLES=8
DENSITY_BOX=2000
FORWARD=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        --run-id) RUN_ID="$2"; shift 2 ;;
        --cell-id) CELL_ID="$2"; shift 2 ;;
        --battle-dp) BATTLE_DP="$2"; shift 2 ;;
        --every) EVERY="$2"; shift 2 ;;
        --density-samples) DENSITY_SAMPLES="$2"; shift 2 ;;
        --density-box) DENSITY_BOX="$2"; shift 2 ;;
        -h|--help)
            sed -n '2,7p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        --) shift; FORWARD+=("$@"); break ;;
        *) FORWARD+=("$1"); shift ;;
    esac
done

[[ -n "$RUN_ID" ]] || { echo "--run-id is required" >&2; exit 2; }
[[ -n "$CELL_ID" ]] || { echo "--cell-id is required" >&2; exit 2; }
[[ "$RUN_ID" =~ ^[A-Za-z0-9._-]+$ ]] \
    || { echo "--run-id may contain only letters, digits, dot, underscore, and dash" >&2; exit 2; }
[[ "$CELL_ID" =~ ^[A-Za-z0-9._-]+$ ]] \
    || { echo "--cell-id may contain only letters, digits, dot, underscore, and dash" >&2; exit 2; }
if [[ -n "$BATTLE_DP" ]]; then
    [[ "$BATTLE_DP" =~ ^[0-9]+([.][0-9]+)?$ ]] \
        || { echo "--battle-dp must be a non-negative number" >&2; exit 2; }
fi
[[ "$EVERY" =~ ^[0-9]+$ && "$EVERY" -ge 1 ]] \
    || { echo "--every must be a positive integer" >&2; exit 2; }
[[ "$DENSITY_SAMPLES" =~ ^[0-9]+$ && "$DENSITY_SAMPLES" -ge 1 ]] \
    || { echo "--density-samples must be a positive integer" >&2; exit 2; }
[[ "$DENSITY_BOX" =~ ^[0-9]+([.][0-9]+)?$ ]] \
    || { echo "--density-box must be a positive number" >&2; exit 2; }
[[ -f scripts/run-gameplay-pilot.sh ]] \
    || { echo "Run this from the Preflight repository root." >&2; exit 1; }

STATE_ROOT="${STARSECTOR_PREFLIGHT_HOME:-$HOME/.starsector-preflight}"
REPORT_ROOT="$STATE_ROOT/combat-scaling"
mkdir -p "$REPORT_ROOT"
TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
DESTINATION="$REPORT_ROOT/$RUN_ID-$CELL_ID-$TIMESTAMP.json"
TEMP_DIR="$(mktemp -d /tmp/preflight-combat-scaling.XXXXXX)"
TEMP_REPORT="$TEMP_DIR/workload.json"

cleanup() {
    rm -rf "$TEMP_DIR"
}
trap cleanup EXIT

SCALING_OPTIONS="-Dpreflight.combatScaling=true"
SCALING_OPTIONS+=" -Dpreflight.combatScaling.runId=$RUN_ID"
SCALING_OPTIONS+=" -Dpreflight.combatScaling.cellId=$CELL_ID"
if [[ -n "$BATTLE_DP" ]]; then
    SCALING_OPTIONS+=" -Dpreflight.combatScaling.battleDp=$BATTLE_DP"
fi
SCALING_OPTIONS+=" -Dpreflight.combatScaling.every=$EVERY"
SCALING_OPTIONS+=" -Dpreflight.combatScaling.densitySamples=$DENSITY_SAMPLES"
SCALING_OPTIONS+=" -Dpreflight.combatScaling.densityBox=$DENSITY_BOX"
SCALING_OPTIONS+=" -Dpreflight.combatScaling.output=$TEMP_REPORT"
export _JAVA_OPTIONS="${_JAVA_OPTIONS:+$_JAVA_OPTIONS }$SCALING_OPTIONS"

LABEL="combat-scaling-$RUN_ID-$CELL_ID"
set +e
scripts/run-gameplay-pilot.sh --label "$LABEL" "${FORWARD[@]}"
STATUS=$?
set -e

if [[ -f "$TEMP_REPORT" ]]; then
    mv "$TEMP_REPORT" "$DESTINATION"
    echo "Combat scaling workload: $DESTINATION"
    echo "Fit with: python3 scripts/fit_combat_scaling.py $REPORT_ROOT/*.json"
else
    echo "Combat scaling workload report was not produced." >&2
fi

exit "$STATUS"
