#!/usr/bin/env bash
# Repeated real startup benchmark: vanilla versus agent-only versus preflight-enabled.
#
# You do exactly two things per launch: click Play Starsector when told, and quit from
# the main menu when told. Everything else is automatic, and the session survives a bad
# run -- one launch that drifts or crashes is excluded and the campaign continues.
set -euo pipefail

GAME="${GAME:-/Applications/Starsector.app}"
CACHE="${CACHE:-$HOME/.starsector-preflight/cache}"
ROUNDS=5
CONDITIONS="vanilla,agent,enabled"
SESSION=""
SKIP_WARMUP=false
SEED="${SEED:-$RANDOM}"

JAR="$PWD/preflight-cli/target/preflight.jar"
DETECTOR="$PWD/scripts/starsector_log_ready_detector.py"
REPORTER="$PWD/scripts/starsector_benchmark_report.py"

LAUNCHER_TIMEOUT_SECONDS=120
LAUNCHER_QUIET_SECONDS=6
MAIN_MENU_TIMEOUT_SECONDS=600
MAIN_MENU_QUIET_SECONDS=6
SCENARIO_ID="main-menu-v1"

usage() {
    cat <<'USAGE'
Usage: scripts/run-startup-benchmark.sh [options]

  --rounds N          Rounds of every condition (default 5, the campaign threshold for
                      a reportable claim). Fewer rounds cannot reach significance: with
                      three per condition the smallest possible p-value is 0.1.
  --conditions LIST   Comma-separated subset of vanilla,agent,enabled (default all).
  --resume DIR        Continue an interrupted session, keeping its completed runs.
  --skip-warmup       Skip the discarded settling launch (only if you just ran one).
  --game PATH         Starsector installation (default /Applications/Starsector.app).
  -h, --help          Show this message.

Conditions:
  vanilla   the game's own launcher, no preflight at all -- the true baseline
  agent     preflight run --no-adapter -- isolates what the JFR recorder itself costs
  enabled   preflight run --adapter --texture-auto -- the prepared texture path

Each launch costs about 90 seconds plus your two clicks, so the default 5 rounds across
three conditions is roughly 35 minutes. Ctrl-C is safe at any point: completed runs are
kept, and --resume picks the session back up where it stopped.
USAGE
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --rounds) ROUNDS="$2"; shift 2 ;;
        --conditions) CONDITIONS="$2"; shift 2 ;;
        --resume) SESSION="$2"; shift 2 ;;
        --skip-warmup) SKIP_WARMUP=true; shift ;;
        --game) GAME="$2"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
    esac
done

if [[ ! "$ROUNDS" =~ ^[0-9]+$ ]] || (( ROUNDS < 1 )); then
    echo "--rounds must be a positive integer." >&2
    exit 2
fi

BOLD=$'\033[1m'; DIM=$'\033[2m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'
RED=$'\033[31m'; CYAN=$'\033[36m'; RESET=$'\033[0m'
if [[ ! -t 1 ]]; then
    BOLD=""; DIM=""; GREEN=""; YELLOW=""; RED=""; CYAN=""; RESET=""
fi

banner() { printf '\n%s%s%s\n' "$BOLD$CYAN" "$*" "$RESET"; }
act()    { printf '\a\n%s  >>> %s  <<<%s\n\n' "$BOLD$YELLOW" "$*" "$RESET"; }
good()   { printf '%s%s%s\n' "$GREEN" "$*" "$RESET"; }
bad()    { printf '%s%s%s\n' "$RED" "$*" "$RESET" >&2; }
note()   { printf '%s%s%s\n' "$DIM" "$*" "$RESET"; }

for command in git java mvn jq python3 shasum; do
    command -v "$command" >/dev/null 2>&1 || { bad "Missing required command: $command"; exit 1; }
done
[[ -f pom.xml ]] || { bad "Run this from the starsector-preflight repository root."; exit 1; }
[[ -d "$GAME" ]] || { bad "Starsector installation not found: $GAME"; exit 1; }
for helper in "$DETECTOR" "$REPORTER"; do
    [[ -f "$helper" ]] || { bad "Helper not found: $helper"; exit 1; }
done

IFS=',' read -r -a CONDITION_LIST <<< "$CONDITIONS"
for condition in "${CONDITION_LIST[@]}"; do
    case "$condition" in
        vanilla|agent|enabled) ;;
        *) bad "Unknown condition: $condition"; exit 2 ;;
    esac
done

LOG_DIR="$GAME/logs"
[[ -d "$LOG_DIR" ]] || { bad "Starsector log directory not found: $LOG_DIR"; exit 1; }

if [[ -n "$SESSION" ]]; then
    ROOT="$SESSION"
    [[ -d "$ROOT" ]] || { bad "Session directory not found: $ROOT"; exit 1; }
    banner "Resuming session $ROOT"
else
    ROOT="$HOME/.starsector-preflight/benchmarks/$(date +%Y%m%d-%H%M%S)"
    mkdir -p "$ROOT/runs"
fi
RESULTS="$ROOT/results.jsonl"
touch "$RESULTS"

banner "== Building the checkout =="
mvn -q -DskipTests package
[[ -f "$JAR" ]] || { bad "Runnable JAR was not produced: $JAR"; exit 1; }
JAR_SHA="$(shasum -a 256 "$JAR" | awk '{print $1}')"
REPOSITORY_HEAD="$(git rev-parse HEAD)"
note "repository HEAD: $REPOSITORY_HEAD"
note "preflight.jar:   $JAR_SHA"

LAUNCHER="$(java -jar "$JAR" doctor --game "$GAME" 2>/dev/null | awk '/^Selected: /{print substr($0, 11); exit}')"
[[ -n "$LAUNCHER" && -f "$LAUNCHER" ]] || { bad "Could not resolve the game launcher under $GAME"; exit 1; }
note "launcher:        $LAUNCHER"

# Preparation is an offline step: it builds the caches and exits well before any launch.
# Its cost belongs in the report as setup, never inside a measured startup.
PREPARE_REPORT="$ROOT/prepare.json"
if [[ ! -f "$PREPARE_REPORT" ]]; then
    banner "== Preparing the caches (offline, one time) =="
    prepare_start="$(python3 -c 'import time; print(time.time_ns())')"
    java -jar "$JAR" prepare --game "$GAME" --cache-dir "$CACHE" --deep --verify-lookups \
        --report "$PREPARE_REPORT" >/dev/null
    prepare_end="$(python3 -c 'import time; print(time.time_ns())')"
    PREPARE_MS=$(( (prepare_end - prepare_start) / 1000000 ))
    echo "$PREPARE_MS" > "$ROOT/prepare-millis.txt"
    good "Caches prepared in $(( PREPARE_MS / 1000 ))s."
else
    PREPARE_MS="$(cat "$ROOT/prepare-millis.txt" 2>/dev/null || echo 0)"
    note "Reusing the preparation from this session ($(( PREPARE_MS / 1000 ))s)."
fi

scan_fingerprint() {
    local output="$1"
    java -jar "$JAR" scan --game "$GAME" --json "$output" >/dev/null 2>&1 || return 1
    jq -er '.profileFingerprint' "$output"
}

BASELINE_PROFILE="$ROOT/profile-baseline.json"
if [[ ! -f "$BASELINE_PROFILE" ]]; then
    EXPECTED_FINGERPRINT="$(scan_fingerprint "$BASELINE_PROFILE")"
    echo "$EXPECTED_FINGERPRINT" > "$ROOT/expected-fingerprint.txt"
else
    EXPECTED_FINGERPRINT="$(cat "$ROOT/expected-fingerprint.txt")"
fi
note "profile:         $EXPECTED_FINGERPRINT"

cat > "$ROOT/identity.json" <<IDENTITY
{
  "repositoryHead": "$REPOSITORY_HEAD",
  "preflightJarSha256": "$JAR_SHA",
  "game": "$GAME",
  "launcher": "$LAUNCHER",
  "profileFingerprint": "$EXPECTED_FINGERPRINT",
  "scenarioId": "$SCENARIO_ID",
  "seed": $SEED,
  "hardware": $(jq -Rs . <<< "$(sysctl -n machdep.cpu.brand_string 2>/dev/null || uname -m)"),
  "os": $(jq -Rs . <<< "$(sw_vers -productVersion 2>/dev/null || uname -sr)"),
  "java": $(jq -Rs . <<< "$(java -version 2>&1 | head -1)"),
  "preparationMillis": ${PREPARE_MS:-0}
}
IDENTITY

terminate() {
    local pid="$1"
    kill "$pid" >/dev/null 2>&1 || true
    for _ in $(seq 1 40); do
        kill -0 "$pid" >/dev/null 2>&1 || return 0
        sleep 0.25
    done
    kill -9 "$pid" >/dev/null 2>&1 || true
}

# One launch. Never exits the script: a failure records an excluded run and returns 1 so
# the campaign keeps its remaining runs.
launch_once() {
    local condition="$1" iteration="$2" label="$3"
    local run_dir="$ROOT/runs/${condition}-${iteration}"
    local before_logs="$run_dir/log-snapshot-before.json"
    local play_logs="$run_dir/log-snapshot-before-play.json"
    local launcher_detection="$run_dir/launcher-ready.json"
    local menu_detection="$run_dir/main-menu-ready.json"
    local profile_after="$run_dir/profile-after.json"
    local wrapper_output="$run_dir/wrapper-output.txt"
    rm -rf "$run_dir"
    mkdir -p "$run_dir"

    local -a command
    case "$condition" in
        vanilla)
            command=(env -u JAVA_TOOL_OPTIONS "$LAUNCHER") ;;
        agent)
            command=(java -jar "$JAR" run --game "$GAME" --launcher "$LAUNCHER"
                     --trace-dir "$run_dir" --no-adapter) ;;
        enabled)
            command=(java -jar "$JAR" run --game "$GAME" --launcher "$LAUNCHER"
                     --trace-dir "$run_dir" --adapter --texture-auto --texture-cache-dir "$CACHE") ;;
    esac

    banner "$label"
    note "${command[*]}"
    python3 "$DETECTOR" snapshot --log-dir "$LOG_DIR" --output "$before_logs"

    local start_ns
    start_ns="$(python3 -c 'import time; print(time.monotonic_ns())')"
    "${command[@]}" >"$wrapper_output" 2>&1 &
    local pid=$!

    if ! python3 "$DETECTOR" watch-launcher --log-dir "$LOG_DIR" --snapshot "$before_logs" \
            --output "$launcher_detection" --pid "$pid" --process-start-ns "$start_ns" \
            --timeout-seconds "$LAUNCHER_TIMEOUT_SECONDS" --quiet-seconds "$LAUNCHER_QUIET_SECONDS"; then
        bad "The launcher never became ready. Excluding this run."
        # Usually the wrapper refused to start at all -- a stale prepared texture index is
        # the common cause -- so show what it actually said instead of just a timeout.
        if [[ -s "$wrapper_output" ]]; then
            bad "Last output from the launch:"
            tail -5 "$wrapper_output" >&2
        fi
        terminate "$pid"
        record_run "$condition" "$iteration" "excluded" "launcher-not-ready" "null" "null" "null"
        return 1
    fi

    python3 "$DETECTOR" snapshot --log-dir "$LOG_DIR" --output "$play_logs"
    act "CLICK  Play Starsector  NOW"
    note "Timing starts on the first game log line, not on this prompt. Do not press Enter."

    if ! python3 "$DETECTOR" watch-main-menu --log-dir "$LOG_DIR" --snapshot "$play_logs" \
            --output "$menu_detection" --pid "$pid" \
            --timeout-seconds "$MAIN_MENU_TIMEOUT_SECONDS" --quiet-seconds "$MAIN_MENU_QUIET_SECONDS"; then
        bad "The main menu was never detected. Excluding this run."
        terminate "$pid"
        record_run "$condition" "$iteration" "excluded" "main-menu-not-detected" "null" "null" "null"
        return 1
    fi

    local menu_ms
    menu_ms="$(jq -er '.gameLogStartToMainMenuMs' "$menu_detection")"
    good "$(awk -v ms="$menu_ms" 'BEGIN { printf "Main menu ready in %.1fs", ms / 1000 }')"
    act "QUIT from the main menu now  (close the launcher if it reappears)"

    set +e
    wait "$pid"
    local exit_code=$?
    set -e

    local fingerprint status reason
    fingerprint="$(scan_fingerprint "$profile_after" || echo unknown)"
    status=accepted
    reason=""
    if (( exit_code != 0 )); then
        status=excluded; reason="nonzero-exit-$exit_code"
    elif [[ "$fingerprint" != "$EXPECTED_FINGERPRINT" ]]; then
        status=excluded; reason="profile-drift"
    fi

    if [[ "$status" == excluded ]]; then
        bad "Run excluded: $reason"
        if [[ "$reason" == profile-drift ]]; then
            note "The enabled mod profile changed during this launch, so it is not comparable."
            EXPECTED_FINGERPRINT="$fingerprint"
            echo "$EXPECTED_FINGERPRINT" > "$ROOT/expected-fingerprint.txt"
            # The prepared texture index is keyed by profile fingerprint, so --texture-auto
            # refuses to launch at all against a profile it was not built for. Adopting the
            # new fingerprint without rebuilding would fail every later enabled run.
            note "Rebuilding the caches for the settled profile so enabled runs still launch..."
            if java -jar "$JAR" prepare --game "$GAME" --cache-dir "$CACHE" --deep --verify-lookups \
                    --report "$ROOT/prepare-reprepared.json" >/dev/null 2>&1; then
                note "Rebuilt. The campaign continues from the settled profile."
            else
                bad "Re-preparation failed. Enabled runs will not launch until 'preflight prepare' succeeds."
            fi
        fi
    fi

    record_run "$condition" "$iteration" "$status" "$reason" "$menu_ms" \
        "$(jq -er '.launcherReadyMs' "$launcher_detection")" "$exit_code"
    [[ "$status" == accepted ]]
}

record_run() {
    local condition="$1" iteration="$2" status="$3" reason="$4"
    local menu_ms="$5" launcher_ms="$6" exit_code="$7"
    local menu_detection="$ROOT/runs/${condition}-${iteration}/main-menu-ready.json"
    local start_instant="null" ready_instant="null" log_delta="null"
    if [[ -f "$menu_detection" ]]; then
        start_instant="$(jq -c '.gameStartInstant // null' "$menu_detection")"
        ready_instant="$(jq -c '.mainMenuReadyInstant // null' "$menu_detection")"
        log_delta="$(jq -c '.gameLogMillisDelta // null' "$menu_detection")"
    fi
    jq -nc \
        --arg condition "$condition" \
        --arg status "$status" \
        --arg reason "$reason" \
        --argjson iteration "$iteration" \
        --argjson menuMs "${menu_ms:-null}" \
        --argjson launcherMs "${launcher_ms:-null}" \
        --argjson exitCode "${exit_code:-null}" \
        --argjson gameStartInstant "$start_instant" \
        --argjson mainMenuReadyInstant "$ready_instant" \
        --argjson gameLogMillisDelta "$log_delta" \
        '{condition: $condition, iteration: $iteration, status: $status,
          reason: (if $reason == "" then null else $reason end),
          gameLogStartToMainMenuMs: $menuMs, launcherReadyMs: $launcherMs,
          gameLogMillisDelta: $gameLogMillisDelta,
          gameStartInstant: $gameStartInstant, mainMenuReadyInstant: $mainMenuReadyInstant,
          exitCode: $exitCode}' >> "$RESULTS"
}

completed() {
    local condition="$1" iteration="$2"
    jq -e --arg c "$condition" --argjson i "$iteration" \
        'select(.condition == $c and .iteration == $i and .status == "accepted")' \
        "$RESULTS" >/dev/null 2>&1
}

shuffle_conditions() {
    printf '%s\n' "${CONDITION_LIST[@]}" | python3 -c '
import random, sys
seed, offset = int(sys.argv[1]), int(sys.argv[2])
items = [line.strip() for line in sys.stdin if line.strip()]
random.Random(seed + offset).shuffle(items)
print("\n".join(items))
' "$SEED" "$1"
}

if [[ "$SKIP_WARMUP" != true && ! -f "$ROOT/warmup-done" ]]; then
    banner "== Discarded settling launch =="
    cat <<'WARMUP'
GraphicsLib generates normal maps into its own cache on a first run and reuses them
afterwards. That one-time write is what invalidated the July comparison. This launch is
thrown away; it exists so the installation stops changing before anything is counted.
WARMUP
    read -r -p "Press Enter to run the settling launch (or type skip): " reply
    if [[ "$reply" != skip ]]; then
        launch_once enabled 0 "SETTLING LAUNCH  (discarded)" || true
        EXPECTED_FINGERPRINT="$(scan_fingerprint "$ROOT/profile-settled.json")"
        echo "$EXPECTED_FINGERPRINT" > "$ROOT/expected-fingerprint.txt"
        note "settled profile: $EXPECTED_FINGERPRINT"
    fi
    touch "$ROOT/warmup-done"
fi

TOTAL=$(( ROUNDS * ${#CONDITION_LIST[@]} ))
banner "== $TOTAL measured launches: ${#CONDITION_LIST[@]} conditions x $ROUNDS rounds =="
cat <<'PROTOCOL'
Per launch you do two things, each announced with a bell:
  1. click Play Starsector
  2. quit from the main menu once it is up

Do not load a save. The order of conditions is shuffled inside every round so that
thermal drift and background load cannot line up with any one condition.
PROTOCOL
read -r -p "Press Enter to begin, or Ctrl-C to stop: "

INDEX=0
for round in $(seq 1 "$ROUNDS"); do
    while read -r condition; do
        INDEX=$(( INDEX + 1 ))
        if completed "$condition" "$round"; then
            note "[$INDEX/$TOTAL] $condition round $round already completed; skipping."
            continue
        fi
        launch_once "$condition" "$round" \
            "[$INDEX/$TOTAL]  ROUND $round/$ROUNDS  --  $(tr '[:lower:]' '[:upper:]' <<< "$condition")" || true
        python3 "$REPORTER" progress --results "$RESULTS"
    done < <(shuffle_conditions "$round")
done

banner "== Campaign result =="
python3 "$REPORTER" summary --results "$RESULTS" --identity "$ROOT/identity.json" \
    --output "$ROOT/benchmark-summary.json"

echo
good "Session directory: $ROOT"
note "Resume or add rounds with: scripts/run-startup-benchmark.sh --resume '$ROOT' --rounds N"
