#!/usr/bin/env bash
#
# One probed direct launch that stops itself, then prints where the time went.
#
# The benchmark harness already does cooled, shuffled, repeated campaigns. This is the other thing
# you want: a single launch with `--startup-phase-probe`, run to the main-menu marker, stopped, and
# summarised as a phase table and a per-plugin callback table. Use it to find out *where* time goes.
# Use the harness to prove a change moved it.
#
# The game must always stop. It is a grandchild of the wrapper process, so killing the wrapper's
# direct children never reaches it, and a launch left running holds ~4 GB and a GPU context and
# poisons every measurement that follows. Cleanup therefore runs from an EXIT trap -- it happens on
# success, on failure, on a detector timeout, and on Ctrl-C alike.
#
# Usage:
#   scripts/probe-launch.sh [--label NAME] [--game DIR] [--timeout-seconds N] [-- EXTRA_FLAGS...]
#
# Any flags after `--` are passed through to `preflight run`, so conditions compose:
#   scripts/probe-launch.sh --label stock -- --texture-auto
#   scripts/probe-launch.sh --label full -- --texture-auto --texture-mode prepared-pixels \
#       --prepared-npot --rule-token-cache --rule-command-cache
set -euo pipefail

GAME="${STARSECTOR_HOME:-/Applications/Starsector.app}"
LABEL="probe"
TIMEOUT_SECONDS=400
QUIET_SECONDS=25
EXTRA=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        --label) LABEL="$2"; shift 2 ;;
        --game) GAME="$2"; shift 2 ;;
        --timeout-seconds) TIMEOUT_SECONDS="$2"; shift 2 ;;
        --) shift; EXTRA=("$@"); break ;;
        -h|--help) sed -n '2,25p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "Unknown option: $1" >&2; exit 2 ;;
    esac
done

[[ -f pom.xml ]] || { echo "Run this from the starsector-preflight repository root." >&2; exit 1; }
[[ -d "$GAME" ]] || { echo "Starsector installation not found: $GAME" >&2; exit 1; }

JAR=preflight-cli/target/preflight.jar
DETECTOR=scripts/starsector_log_ready_detector.py
LOG_DIR="$GAME/logs"
# Matching on the game's own bundled JVM path is what makes cleanup reliable: it identifies the
# game whatever its ancestry, and it cannot match this script, the wrapper, or an unrelated JVM.
GAME_JVM_PATTERN="$GAME/Contents/Home/bin/java"

if pgrep -f "$GAME_JVM_PATTERN" >/dev/null 2>&1; then
    echo "A Starsector process is already running. Stop it before probing." >&2
    exit 1
fi

WRAPPER_PID=""
cleanup() {
    local status=$?
    if pgrep -f "$GAME_JVM_PATTERN" >/dev/null 2>&1; then
        echo "Stopping the game..."
        # SIGTERM first so the JVM runs its shutdown hooks and flushes any recording.
        pkill -TERM -f "$GAME_JVM_PATTERN" 2>/dev/null || true
        for _ in $(seq 1 15); do
            pgrep -f "$GAME_JVM_PATTERN" >/dev/null 2>&1 || break
            sleep 1
        done
        if pgrep -f "$GAME_JVM_PATTERN" >/dev/null 2>&1; then
            echo "The game ignored SIGTERM; forcing."
            pkill -KILL -f "$GAME_JVM_PATTERN" 2>/dev/null || true
            sleep 1
        fi
    fi
    [[ -n "$WRAPPER_PID" ]] && kill -TERM "$WRAPPER_PID" 2>/dev/null || true
    if pgrep -f "$GAME_JVM_PATTERN" >/dev/null 2>&1; then
        echo "WARNING: a game process survived cleanup." >&2
    fi
    return $status
}
trap cleanup EXIT INT TERM

echo "== Building =="
mvn -q -DskipTests package
[[ -f "$JAR" ]] || { echo "Runnable JAR was not produced: $JAR" >&2; exit 1; }

OUT="$HOME/.starsector-preflight/runs/$LABEL-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT"
LAUNCHER="$(java -jar "$JAR" doctor --game "$GAME" 2>/dev/null | awk '/^Selected: /{print substr($0, 11); exit}')"
[[ -n "$LAUNCHER" && -f "$LAUNCHER" ]] || { echo "Could not resolve the launcher under $GAME" >&2; exit 1; }

echo "run:      $OUT"
echo "head:     $(git rev-parse --short HEAD)"
echo "flags:    --direct --adapter --startup-phase-probe --no-record ${EXTRA[*]:-}"

python3 "$DETECTOR" snapshot --log-dir "$LOG_DIR" --output "$OUT/before.json"

java -jar "$JAR" run --game "$GAME" --launcher "$LAUNCHER" \
    --trace-dir "$OUT" --direct --adapter --startup-phase-probe --no-record \
    ${EXTRA[@]+"${EXTRA[@]}"} >"$OUT/wrapper.log" 2>&1 &
WRAPPER_PID=$!

if python3 "$DETECTOR" watch-main-menu --log-dir "$LOG_DIR" --snapshot "$OUT/before.json" \
        --output "$OUT/menu.json" --pid "$WRAPPER_PID" \
        --timeout-seconds "$TIMEOUT_SECONDS" --quiet-seconds "$QUIET_SECONDS"; then
    echo "main menu reached"
else
    echo "MAIN MENU NOT DETECTED -- see $OUT/wrapper.log" >&2
    tail -5 "$OUT/wrapper.log" >&2 || true
fi

# Stop the game before reporting: the report is pure file reading and there is no reason to hold
# the machine while it happens.
cleanup
trap - EXIT INT TERM

python3 scripts/summarize_startup_probe.py "$OUT"
