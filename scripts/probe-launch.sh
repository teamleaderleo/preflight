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
#   scripts/probe-launch.sh [--mode NAME] [--label NAME] [--game DIR]
#                           [--timeout-seconds N] [-- EXTRA_FLAGS...]
#
# --mode names the same conditions the benchmark harness uses, with the same flags, so a probe and
# a campaign mean the same thing by the same word:
#
#   fast       the shipped preset -- what an installed Preflight launcher runs. Use this to ask
#              where time goes for a real user. (default)
#   enabled    --adapter --texture-auto: the prepared texture path.
#   adapter    --adapter alone. The least-optimized launch a probe can measure, which is NOT a
#              baseline: adapters are on, because the phase probe is implemented by the adapter.
#   prepared   enabled plus prepared pixels with power-of-two padding retained.
#
#   vanilla    refused, with a pointer. The game's own launcher cannot carry the phase probe, so
#              there is no such thing as a probed baseline. Use the harness:
#                  scripts/run-startup-benchmark.sh --unattended --conditions vanilla,fast
#              which is also how to compare two conditions: it shuffles them inside each round
#              rather than running them back to back, because a launch on a hot machine is slower.
#              The 2026-08-01 campaign drifted +19.6s across fifteen launches from heat alone.
#
# Any flags after `--` are appended to the mode, so conditions still compose:
#   scripts/probe-launch.sh --mode enabled --label npot -- --texture-mode prepared-pixels --prepared-npot
set -euo pipefail

GAME="${STARSECTOR_HOME:-/Applications/Starsector.app}"
LABEL=""
MODE="fast"
TIMEOUT_SECONDS=400
QUIET_SECONDS=25
EXTRA=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        --mode) MODE="$2"; shift 2 ;;
        --label) LABEL="$2"; shift 2 ;;
        --game) GAME="$2"; shift 2 ;;
        --timeout-seconds) TIMEOUT_SECONDS="$2"; shift 2 ;;
        --) shift; EXTRA=("$@"); break ;;
        -h|--help) sed -n '2,41p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "Unknown option: $1" >&2; exit 2 ;;
    esac
done

# The flags are copied from run-startup-benchmark.sh's own condition table rather than invented
# here. Two tools that disagree about what "fast" means produce two numbers nobody can compare.
MODE_FLAGS=()
case "$MODE" in
    fast)     MODE_FLAGS=(--fast) ;;
    enabled)  MODE_FLAGS=(--adapter --texture-auto) ;;
    adapter)  MODE_FLAGS=(--adapter) ;;
    prepared) MODE_FLAGS=(--adapter --texture-auto --texture-mode prepared-pixels --prepared-npot) ;;
    vanilla)
        cat >&2 <<'REFUSED'
There is no probed vanilla launch, and a number from one would be a lie.

`--startup-phase-probe` is implemented by the adapter -- preflight run refuses the two together
(CommandLine.java) -- so the least-optimized launch this script can measure still has adapters on.
It is not a baseline and must not be reported as one.

For a real baseline, and for comparing it against an optimized launch:

    scripts/run-startup-benchmark.sh --unattended --conditions vanilla,fast

That runs the game's own launcher for `vanilla`, shuffles the conditions inside every round so
neither gets a hotter machine than the other, and refuses a result below five runs per condition.
REFUSED
        exit 2 ;;
    *)
        echo "Unknown mode: $MODE (expected fast, enabled, adapter, prepared, or vanilla)" >&2
        exit 2 ;;
esac
[[ -n "$LABEL" ]] || LABEL="$MODE"

[[ -f pom.xml ]] || { echo "Run this from the Preflight repository root." >&2; exit 1; }
[[ -d "$GAME" ]] || { echo "Starsector installation not found: $GAME" >&2; exit 1; }

JAR=preflight-cli/target/preflight.jar
DETECTOR=scripts/starsector_log_ready_detector.py
LOG_DIR="$GAME/logs"

# Finding the game process is the part that has to be right, and matching its command line is not
# the way to do it. The launcher script execs java from inside the game directory using a *relative*
# path -- the real command line is `../../Home/bin/java -Xdock:name=Starsector ...` -- so a pattern
# built from the absolute install path matches nothing, silently, and the game is left running.
#
# Its working directory is not relative. Ask each JVM where it is, and keep the ones that answer
# from inside the install.
game_pids() {
    local resolved
    resolved="$(cd "$GAME" && pwd -P)"
    local pid cwd
    for pid in $(pgrep -x java 2>/dev/null; pgrep -f '[j]ava$|/java ' 2>/dev/null); do
        [[ "$pid" == "$$" ]] && continue
        cwd="$(lsof -a -p "$pid" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p' | head -1)"
        [[ -n "$cwd" && "$cwd" == "$resolved"* ]] && echo "$pid"
    done | sort -u
}

if [[ -n "$(game_pids)" ]]; then
    echo "A Starsector process is already running (pids: $(game_pids | tr '\n' ' '))." >&2
    echo "Stop it before probing." >&2
    exit 1
fi

WRAPPER_PID=""
cleanup() {
    local status=$?
    local pids
    pids="$(game_pids)"
    if [[ -n "$pids" ]]; then
        echo "Stopping the game (pids: $(echo "$pids" | tr '\n' ' '))..."
        # SIGTERM first so the JVM runs its shutdown hooks and flushes any recording.
        echo "$pids" | xargs -r kill -TERM 2>/dev/null || true
        for _ in $(seq 1 15); do
            [[ -z "$(game_pids)" ]] && break
            sleep 1
        done
        pids="$(game_pids)"
        if [[ -n "$pids" ]]; then
            echo "The game ignored SIGTERM; forcing."
            echo "$pids" | xargs -r kill -9 2>/dev/null || true
            sleep 2
        fi
    fi
    [[ -n "$WRAPPER_PID" ]] && kill -TERM "$WRAPPER_PID" 2>/dev/null || true
    if [[ -n "$(game_pids)" ]]; then
        echo "WARNING: a game process survived cleanup: $(game_pids | tr '\n' ' ')" >&2
    else
        echo "game stopped"
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
echo "mode:     $MODE"
echo "flags:    --direct ${MODE_FLAGS[*]} --startup-phase-probe --no-record ${EXTRA[*]:-}"

python3 "$DETECTOR" snapshot --log-dir "$LOG_DIR" --output "$OUT/before.json"

java -jar "$JAR" run --game "$GAME" --launcher "$LAUNCHER" \
    --trace-dir "$OUT" --direct "${MODE_FLAGS[@]}" --startup-phase-probe --no-record \
    ${EXTRA[@]+"${EXTRA[@]}"} >"$OUT/wrapper.log" 2>&1 &
WRAPPER_PID=$!

QUIET_LOGS=false
if (( ${#EXTRA[@]} )); then
    for flag in "${EXTRA[@]}"; do
        if [[ "$flag" == "--quiet-logs" ]]; then
            QUIET_LOGS=true
        fi
    done
fi

if [[ "$QUIET_LOGS" == true ]]; then
    # The main-menu log marker can legitimately remain in log4j's final 64 KiB buffer until the
    # JVM shuts down. Waiting for it would deadlock the probe: this script is what shuts the game
    # down. The startup probe writes each phase transactionally, and resource-init-complete lands
    # about 0.1s before the ordinary GraphicsLib menu marker on the measured warm pair, so give the
    # UI five seconds after that exact phase before stopping it. The flushed log is checked below;
    # this phase alone is deliberately not called the main menu.
    detected=false
    deadline=$((SECONDS + TIMEOUT_SECONDS))
    phase_report="$OUT/adapter-startup-phases.json"
    while (( SECONDS < deadline )); do
        if [[ -f "$phase_report" ]] \
                && jq -e '.phases | any(.name == "resource-init-complete")' "$phase_report" \
                    >/dev/null 2>&1; then
            sleep 5
            detected=true
            break
        fi
        kill -0 "$WRAPPER_PID" 2>/dev/null || break
        sleep 0.2
    done
    if [[ "$detected" == true ]]; then
        echo "resource initialization complete (quiet-log startup phase)"
    else
        echo "MAIN MENU NOT DETECTED -- see $OUT/wrapper.log" >&2
        tail -5 "$OUT/wrapper.log" >&2 || true
    fi
elif python3 "$DETECTOR" watch-main-menu --log-dir "$LOG_DIR" --snapshot "$OUT/before.json" \
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

if [[ "$QUIET_LOGS" == true && "$detected" == true ]]; then
    # SIGTERM ran Preflight's log4j shutdown hook, so the final buffer is visible now. Observe the
    # ordinary marker as a compatibility check, but keep it out of menu.json: reading the whole
    # flushed delta at once gives every line the same observation timestamp and is not a launch
    # measurement. The log's own millisecond delta remains in menu-flushed.json as evidence.
    if python3 "$DETECTOR" watch-main-menu --log-dir "$LOG_DIR" --snapshot "$OUT/before.json" \
            --output "$OUT/menu-flushed.json" --pid "$WRAPPER_PID" \
            --timeout-seconds 1 --quiet-seconds 0; then
        echo "main menu marker present after quiet-log shutdown flush"
    else
        echo "MAIN MENU MARKER ABSENT AFTER SHUTDOWN -- see $OUT/wrapper.log" >&2
    fi
fi

python3 scripts/summarize_startup_probe.py "$OUT"
