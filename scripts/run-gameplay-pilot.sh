#!/usr/bin/env bash
#
# Launch one manually played combat pilot with every relevant beta probe enabled.
#
# Usage:
#   scripts/run-gameplay-pilot.sh [--game DIR] [--label NAME]
#
# Load a representative campaign, open a simulation, raise the DP cap, deploy many capitals,
# fight for three to five minutes, then exit Starsector normally. Preflight keeps a coherent JFR
# and reports whether each exact adapter applied, how often it ran, and what its measured paths cost.
set -euo pipefail

GAME="${STARSECTOR_HOME:-/Applications/Starsector.app}"
LABEL="gameplay-pilot"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --game) GAME="$2"; shift 2 ;;
        --label) LABEL="$2"; shift 2 ;;
        -h|--help) sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "Unknown option: $1" >&2; exit 2 ;;
    esac
done

[[ -f pom.xml ]] || { echo "Run this from the starsector-preflight repository root." >&2; exit 1; }
[[ -d "$GAME" ]] || { echo "Starsector installation not found: $GAME" >&2; exit 1; }

JAR="$PWD/preflight-cli/target/preflight.jar"
PREFLIGHT_STATE_ROOT="${STARSECTOR_PREFLIGHT_HOME:-$HOME/.starsector-preflight}"
OUT="$PREFLIGHT_STATE_ROOT/runs/$LABEL-$(date +%Y%m%d-%H%M%S)"
WRAPPER_PID=""

game_pids() {
    local resolved pid cwd
    resolved="$(cd "$GAME" && pwd -P)"
    for pid in $(pgrep -x java 2>/dev/null; pgrep -f '[j]ava$|/java ' 2>/dev/null); do
        [[ "$pid" == "$$" ]] && continue
        cwd="$(lsof -a -p "$pid" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p' | head -1)"
        [[ -n "$cwd" && "$cwd" == "$resolved"* ]] && echo "$pid"
    done | sort -u
}

cleanup() {
    local status=$? pids
    pids="$(game_pids)"
    if [[ -n "$pids" ]]; then
        echo "Stopping Starsector so the recording and reports flush..."
        echo "$pids" | xargs -r kill -TERM 2>/dev/null || true
        for _ in $(seq 1 15); do
            [[ -z "$(game_pids)" ]] && break
            sleep 1
        done
        pids="$(game_pids)"
        if [[ -n "$pids" ]]; then
            echo "Starsector did not stop after 15 seconds; forcing shutdown." >&2
            echo "$pids" | xargs -r kill -9 2>/dev/null || true
        fi
    fi
    [[ -n "$WRAPPER_PID" ]] && kill -TERM "$WRAPPER_PID" 2>/dev/null || true
    return "$status"
}
trap cleanup EXIT INT TERM

if [[ -n "$(game_pids)" ]]; then
    echo "A Starsector process is already running (pids: $(game_pids | tr '\n' ' '))." >&2
    echo "Exit it before starting the pilot." >&2
    exit 1
fi

echo "Building the combined pilot..."
mvn -q -DskipTests package
[[ -f "$JAR" ]] || { echo "Runnable JAR was not produced: $JAR" >&2; exit 1; }

mkdir -p "$OUT"
LAUNCHER="$(java -jar "$JAR" doctor --game "$GAME" 2>/dev/null \
    | awk '/^Selected: /{print substr($0, 11); exit}')"
[[ -n "$LAUNCHER" && -f "$LAUNCHER" ]] \
    || { echo "Could not resolve the launcher under $GAME" >&2; exit 1; }

echo
echo "Pilot directory: $OUT"
echo "Pilot commit:    $(git rev-parse --short HEAD)"
echo
echo "In Starsector:"
echo "  1. Load a representative campaign."
echo "  2. Open a battle simulation and raise the DP limit."
echo "  3. Deploy lots of capitals and fight for 3-5 minutes."
echo "  4. Exit Starsector normally when done."
echo
echo "Launching now; wrapper output is being saved to $OUT/wrapper.log"

java -jar "$JAR" run \
    --game "$GAME" \
    --launcher "$LAUNCHER" \
    --trace-dir "$OUT" \
    --direct \
    --adapter \
    --fast \
    --graphicslib-compact-replay \
    --janino-bytecode-cache \
    --graphicslib-insignia-cache \
    --campaign-entity-index \
    --profile \
    --single-chunk-recording \
    >"$OUT/wrapper.log" 2>&1 &
WRAPPER_PID=$!

set +e
wait "$WRAPPER_PID"
PILOT_STATUS=$?
set -e
WRAPPER_PID=""

# A normal game exit leaves no process. If a launcher/wrapper failed while its child survived,
# cleanup still owns that child rather than leaking several gigabytes and a GPU context.
cleanup
trap - EXIT INT TERM

echo
echo "Pilot process exit: $PILOT_STATUS"
if [[ -f "$OUT/adapter-health.json" ]]; then
    echo "Adapter health:"
    jq '{status, summary, transformationsApplied, transformationsDeclined, containedFailures}' \
        "$OUT/adapter-health.json"
fi
if [[ -f "$OUT/adapter.json" ]]; then
    echo "Probe telemetry:"
    jq '{graphicsLibCompactReplay, janinoBytecodeCache, graphicsLibInsigniaManagerCache, campaignEntityIndex, deploymentIconCache}' \
        "$OUT/adapter.json"
else
    echo "No adapter report was produced; inspect $OUT/wrapper.log" >&2
fi
echo "Full pilot data: $OUT"

exit "$PILOT_STATUS"
