#!/usr/bin/env bash
#
# Internal diagnostic engine. Use scripts/benchmark-startup.sh --details.
set -euo pipefail

GAME="${STARSECTOR_HOME:-/Applications/Starsector.app}"
ENGINE=""
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
        --engine) ENGINE="$2"; shift 2 ;;
        --timeout-seconds) TIMEOUT_SECONDS="$2"; shift 2 ;;
        --) shift; EXTRA=("$@"); break ;;
        -h|--help)
            cat <<'USAGE'
Usage: scripts/benchmark-startup.sh --details [OPTIONS]

  --mode NAME         fast, enabled, adapter, or prepared (default: fast)
  --label NAME        Name the saved diagnostic run
  --game PATH         Starsector installation
  --engine PATH       Existing preflight.jar or installed Preflight app
  --timeout-seconds N Stop waiting after N seconds
  -- EXTRA_FLAGS      Append diagnostic engine flags
USAGE
            exit 0
            ;;
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
The phase probe requires Preflight, so it cannot measure vanilla.
Use: scripts/benchmark-startup.sh --campaign --unattended --conditions vanilla,fast
REFUSED
        exit 2 ;;
    *)
        echo "Unknown mode: $MODE (expected fast, enabled, adapter, prepared, or vanilla)" >&2
        exit 2 ;;
esac
[[ -n "$LABEL" ]] || LABEL="$MODE"

[[ -f pom.xml ]] || { echo "Run this from the Preflight repository root." >&2; exit 1; }
[[ -d "$GAME" ]] || { echo "Starsector installation not found: $GAME" >&2; exit 1; }

CHECKOUT_JAR=preflight-cli/target/preflight.jar
DETECTOR=scripts/starsector_log_ready_detector.py
LOG_DIR="$GAME/logs"

resolve_engine() {
    local requested="$1" candidate
    if [[ -f "$requested" ]]; then
        [[ "$requested" == *.jar ]] || return 1
        printf '%s\n' "$requested"
        return 0
    fi
    [[ -d "$requested" ]] || return 1
    for candidate in \
            "$requested/engine/preflight.jar" \
            "$requested/Contents/Resources/engine/preflight.jar" \
            "$requested"/*.app/Contents/Resources/engine/preflight.jar \
            "$requested"/usr/lib/*/engine/preflight.jar \
            "$requested"/lib/*/engine/preflight.jar; do
        if [[ -f "$candidate" ]]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done
    return 1
}

if [[ -n "$ENGINE" ]]; then
    JAR="$(resolve_engine "$ENGINE")" || {
        echo "No Preflight engine under: $ENGINE" >&2
        exit 1
    }
else
    checkout_is_current=false
    if [[ -f "$CHECKOUT_JAR" ]] \
            && ! find pom.xml preflight-*/pom.xml preflight-*/src \
                -type f -newer "$CHECKOUT_JAR" -print -quit | grep -q .; then
        checkout_is_current=true
    fi
    if [[ "$checkout_is_current" != true ]]; then
        echo "Building diagnostic engine..."
        mvn -q -DskipTests package
    fi
    JAR="$CHECKOUT_JAR"
fi
JAR="$(cd "$(dirname "$JAR")" && pwd -P)/$(basename "$JAR")"
[[ -f "$JAR" ]] || { echo "Runnable JAR was not produced: $JAR" >&2; exit 1; }

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
        [[ -n "$cwd" && ("$cwd" == "$resolved" || "$cwd" == "$resolved/"*) ]] && echo "$pid"
    done | sort -u
}

if [[ -n "$(game_pids)" ]]; then
    echo "A Starsector process is already running (pids: $(game_pids | tr '\n' ' '))." >&2
    echo "Stop it before probing." >&2
    exit 1
fi

WRAPPER_PID=""
CLEANED=false

descendants() {
    local pid="$1" child
    for child in $(pgrep -P "$pid" 2>/dev/null); do
        descendants "$child"
        printf '%s\n' "$child"
    done
}

stop_owned_game() {
    [[ -n "$WRAPPER_PID" ]] || return 0
    local tree runtime_pid="" target
    tree="$(descendants "$WRAPPER_PID")"
    if [[ -f "$OUT/runtime-process.json" ]]; then
        runtime_pid="$(jq -r '.pid // empty' "$OUT/runtime-process.json" 2>/dev/null || true)"
    fi
    if [[ "$runtime_pid" =~ ^[0-9]+$ ]] && grep -qx "$runtime_pid" <<< "$tree"; then
        kill "$runtime_pid" 2>/dev/null || true
        for _ in $(seq 1 40); do
            kill -0 "$runtime_pid" 2>/dev/null || return 0
            sleep 0.25
        done
        kill -9 "$runtime_pid" 2>/dev/null || true
        return 0
    fi
    for target in $tree; do
        kill "$target" 2>/dev/null || true
    done
}

cleanup() {
    local status=$?
    [[ "$CLEANED" == true ]] && return "$status"
    CLEANED=true
    stop_owned_game
    if [[ -n "$WRAPPER_PID" ]]; then
        for _ in $(seq 1 40); do
            kill -0 "$WRAPPER_PID" 2>/dev/null || break
            sleep 0.25
        done
        kill -0 "$WRAPPER_PID" 2>/dev/null && kill -TERM "$WRAPPER_PID" 2>/dev/null || true
        wait "$WRAPPER_PID" 2>/dev/null || true
    fi
    local survivors
    survivors="$(game_pids)"
    if [[ -n "$survivors" ]]; then
        echo "A game process survived cleanup: $(tr '\n' ' ' <<< "$survivors")" >&2
        echo "$survivors" | xargs -r kill -9 2>/dev/null || true
    fi
    return "$status"
}
trap cleanup EXIT INT TERM

OUT="$HOME/.starsector-preflight/runs/$LABEL-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT"
LAUNCHER="$(java -jar "$JAR" doctor --game "$GAME" --no-scan 2>/dev/null | awk '/^Selected: /{print substr($0, 11); exit}')"
[[ -n "$LAUNCHER" && -f "$LAUNCHER" ]] || { echo "Could not resolve the launcher under $GAME" >&2; exit 1; }

echo "Diagnostic: $OUT"

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
