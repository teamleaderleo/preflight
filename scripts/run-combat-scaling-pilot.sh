#!/usr/bin/env bash
# Run one exact, unattended, Preflight-only installed-game combat-scaling cell.
#
# Example:
#   scripts/run-combat-scaling-pilot.sh --run-id block1 --cell-id symmetric-1040 \
#     --battle-dp 1040 --game /Applications/Starsector.app
set -euo pipefail

RUN_ID=""
CELL_ID=""
BATTLE_DP=""
GAME=""
EVERY=60
DENSITY_SAMPLES=8
DENSITY_BOX=2000
SCENARIO="scripts/scenarios/campaign-simulation-combat-scaling.json"
JAR="preflight-cli/target/preflight.jar"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --run-id) RUN_ID="$2"; shift 2 ;;
        --cell-id) CELL_ID="$2"; shift 2 ;;
        --battle-dp) BATTLE_DP="$2"; shift 2 ;;
        --game) GAME="$2"; shift 2 ;;
        --every) EVERY="$2"; shift 2 ;;
        --density-samples) DENSITY_SAMPLES="$2"; shift 2 ;;
        --density-box) DENSITY_BOX="$2"; shift 2 ;;
        --scenario) SCENARIO="$2"; shift 2 ;;
        --jar) JAR="$2"; shift 2 ;;
        -h|--help)
            sed -n '2,7p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *) echo "Unknown option: $1" >&2; exit 2 ;;
    esac
done

[[ -n "$RUN_ID" ]] || { echo "--run-id is required" >&2; exit 2; }
[[ -n "$CELL_ID" ]] || { echo "--cell-id is required" >&2; exit 2; }
[[ -n "$BATTLE_DP" ]] || { echo "--battle-dp is required" >&2; exit 2; }
[[ -n "$GAME" ]] || { echo "--game is required" >&2; exit 2; }
[[ "$RUN_ID" =~ ^[A-Za-z0-9._-]+$ ]] \
    || { echo "--run-id may contain only letters, digits, dot, underscore, and dash" >&2; exit 2; }
[[ "$CELL_ID" =~ ^[A-Za-z0-9._-]+$ ]] \
    || { echo "--cell-id may contain only letters, digits, dot, underscore, and dash" >&2; exit 2; }
case "$BATTLE_DP" in
    260|520|780|1040) ;;
    *) echo "--battle-dp must be one of the exact fixture sizes: 260, 520, 780, 1040" >&2; exit 2 ;;
esac
[[ "$EVERY" =~ ^[0-9]+$ && "$EVERY" -ge 1 ]] \
    || { echo "--every must be a positive integer" >&2; exit 2; }
[[ "$DENSITY_SAMPLES" =~ ^[0-9]+$ && "$DENSITY_SAMPLES" -ge 1 ]] \
    || { echo "--density-samples must be a positive integer" >&2; exit 2; }
[[ "$DENSITY_BOX" =~ ^[0-9]+([.][0-9]+)?$ ]] \
    || { echo "--density-box must be a positive number" >&2; exit 2; }
[[ -f "$SCENARIO" ]] || { echo "Scaling scenario not found: $SCENARIO" >&2; exit 1; }
[[ -f "$JAR" ]] || { echo "Runnable Preflight JAR not found: $JAR" >&2; exit 1; }
[[ -d "$GAME" ]] || { echo "Game directory not found: $GAME" >&2; exit 1; }

STATE_ROOT="${STARSECTOR_PREFLIGHT_HOME:-$HOME/.starsector-preflight}"
RUN_ROOT="$STATE_ROOT/runs"
REPORT_ROOT="$STATE_ROOT/combat-scaling"
mkdir -p "$RUN_ROOT" "$REPORT_ROOT"
TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
LABEL="combat-scaling-$RUN_ID-$CELL_ID"
RUN_DIR="$RUN_ROOT/$LABEL-$TIMESTAMP"
DESTINATION="$REPORT_ROOT/$LABEL-$TIMESTAMP.json"
[[ ! -e "$RUN_DIR" ]] || { echo "Run directory already exists: $RUN_DIR" >&2; exit 1; }

SCALING_OPTIONS="-Dpreflight.combatScaling=true"
SCALING_OPTIONS+=" -Dpreflight.combatScaling.runId=$RUN_ID"
SCALING_OPTIONS+=" -Dpreflight.combatScaling.cellId=$CELL_ID"
SCALING_OPTIONS+=" -Dpreflight.combatScaling.battleDp=$BATTLE_DP"
SCALING_OPTIONS+=" -Dpreflight.combatScaling.every=$EVERY"
SCALING_OPTIONS+=" -Dpreflight.combatScaling.densitySamples=$DENSITY_SAMPLES"
SCALING_OPTIONS+=" -Dpreflight.combatScaling.densityBox=$DENSITY_BOX"
SCALING_OPTIONS+=" -Dpreflight.combatScaling.output=$RUN_DIR/combat-workload.json"
export _JAVA_OPTIONS="${_JAVA_OPTIONS:+$_JAVA_OPTIONS }$SCALING_OPTIONS"

set +e
java -jar "$JAR" desktop smoke launch "$SCENARIO" "$RUN_DIR" --game "$GAME"
STATUS=$?
set -e

if [[ -f "$RUN_DIR/combat-workload.json" ]]; then
    cp "$RUN_DIR/combat-workload.json" "$DESTINATION"
    echo "Run-local combat workload: $RUN_DIR/combat-workload.json"
    echo "Combat scaling corpus: $DESTINATION"
    echo "Fit with: python3 scripts/fit_combat_scaling.py $REPORT_ROOT/*.json"
else
    echo "Combat scaling workload report was not produced." >&2
fi
echo "Installed-game run: $RUN_DIR"
exit "$STATUS"
