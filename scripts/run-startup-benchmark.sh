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
CONDITIONS="vanilla,agent,enabled,fast"
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
  --conditions LIST   Comma-separated subset of vanilla,agent,enabled,fast,profile,prepared
                      (default vanilla,agent,enabled,fast; the last two are opt-in).
  --resume DIR        Continue an interrupted session, keeping its completed runs.
  --skip-warmup       Skip the discarded settling launch (only if you just ran one).
  --game PATH         Starsector installation (default /Applications/Starsector.app).
  -h, --help          Show this message.

Conditions:
  vanilla   the game's own launcher, no preflight at all -- the true baseline
  agent     preflight run --no-adapter -- isolates what the JFR recorder itself costs
  enabled   preflight run --adapter --texture-auto -- the prepared texture path, recorded
  fast      the same, plus --no-record -- the caches without paying for the profile
  prepared  --texture-mode prepared-pixels --prepared-npot --no-record -- hands the game
            upload-ready bytes instead of a BufferedImage it has to unpack a pixel at a
            time. --prepared-npot is not optional here: without it the bridge declines
            every texture needing power-of-two padding, which was 6,165 of 6,706 on the
            reviewed profile. Compare against `fast`, the same launch in compatibility mode.
  profile   the same, plus --profile -- sampling only, for asking where the time goes.
            Not a timing condition: it records, so it is slower than fast. Analyse its
            recordings with `preflight analyze`; do not read its wall clock as a result.

Each launch costs about 90 seconds plus your two clicks, so the default 5 rounds across
four conditions is roughly 45 minutes. Ctrl-C is safe at any point: completed runs are
kept, and --resume picks the session back up where it stopped.

To answer only "is Preflight worth it", drop the two diagnostic conditions:
  --conditions vanilla,fast
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
        vanilla|agent|enabled|fast|profile|prepared) ;;
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
  "hardware": $(jq -Rs 'rtrimstr("\n")' <<< "$(sysctl -n machdep.cpu.brand_string 2>/dev/null || uname -m)"),
  "os": $(jq -Rs 'rtrimstr("\n")' <<< "$(sw_vers -productVersion 2>/dev/null || uname -sr)"),
  "java": $(jq -Rs 'rtrimstr("\n")' <<< "$(java -version 2>&1 | head -1)"),
  "preparationMillis": ${PREPARE_MS:-0}
}
IDENTITY

RECORDING=true

# An enabled run only measures the prepared texture path if that path actually ran. The
# adapter fails open on a stale artifact or a circuit-breaker trip, and a failed-open run
# is indistinguishable from a baseline run by timing alone -- which is how the July pilot
# nearly reported an untouched prepared path as a valid prepared measurement.
served_prepared_textures() {
    local run_dir="$1"
    local report="$run_dir/adapter.json"
    if [[ ! -f "$report" ]]; then
        bad "The enabled run produced no adapter report; the texture path did not run."
        return 1
    fi
    if jq -e '.textureCompatibility
            | .ready == true
            and .hits > 0
            and .internalErrors == 0
            and (.disableReasons | length) == 0' "$report" >/dev/null 2>&1; then
        note "prepared textures served: $(jq -r '.textureCompatibility
            | "\(.hits) hits, \(.bytesServed) bytes, \(.fallbacks) fallbacks"' "$report")"
        return 0
    fi
    bad "The adapter failed open, so this run did not exercise the prepared texture path:"
    jq -c '.textureCompatibility
        | {ready, hits, misses, fallbacks, internalErrors, disableReasons}' "$report" >&2 || true
    return 1
}

# Prepared-pixels needs a second, stricter check. Its bridge falls back to the ordinary
# BufferedImage conversion per texture, so a run can serve every cache entry -- passing the
# check above in full -- while bypassing no conversions at all. That run is compatibility
# mode wearing the prepared label, and timing it would report "prepared-pixels changes
# nothing" when what actually happened is that prepared-pixels never ran.
bypassed_pixel_conversions() {
    local run_dir="$1"
    local report="$run_dir/adapter.json"
    if [[ ! -f "$report" ]]; then
        bad "The prepared run produced no adapter report; the texture path did not run."
        return 1
    fi
    if jq -e '.textureCompatibility.preparedPixels
            | .hits > 0
            and .internalErrors == 0
            and (.hits > .fallbacks)' "$report" >/dev/null 2>&1; then
        note "pixel conversions bypassed: $(jq -r '.textureCompatibility.preparedPixels
            | "\(.conversionCallsBypassed) conversions, \(.uploadBytesSupplied) bytes, \(.fallbacks) fallbacks"' \
            "$report")"
        return 0
    fi
    bad "The prepared-pixel bridge did not carry this run; it is a compatibility run in disguise:"
    jq -c '.textureCompatibility.preparedPixels
        | {hits, fallbacks, dimensionFallbacks, npotProbeFallbacks, internalErrors}' "$report" >&2 || true
    return 1
}

# Deepest descendant first, so a parent is never signalled before its children.
descendants() {
    local pid="$1" child
    for child in $(pgrep -P "$pid" 2>/dev/null); do
        descendants "$child"
        printf '%s\n' "$child"
    done
}

# The game is a GRANDCHILD: this script starts the Preflight wrapper, the wrapper starts
# starsector_mac.sh, and that shell starts the JVM. Signalling only the wrapper leaves a
# hung or crashed game running, holding the screen and the next run's page cache -- which
# is exactly what happened on 2026-07-31, where a crashed JVM outlived its own timeout.
terminate() {
    local pid="$1" target
    local tree
    tree="$(descendants "$pid"; printf '%s\n' "$pid")"
    for target in $tree; do
        kill "$target" >/dev/null 2>&1 || true
    done
    for _ in $(seq 1 40); do
        kill -0 "$pid" >/dev/null 2>&1 || break
        sleep 0.25
    done
    for target in $tree; do
        kill -0 "$target" >/dev/null 2>&1 && kill -9 "$target" >/dev/null 2>&1 || true
    done
}

# A fatal JVM error does not end the process. Starsector's launcher passes
# -XX:+ShowMessageBoxOnError, so HotSpot prints its report and then waits on stdin for a
# RETURN that never comes. The result looks exactly like a slow load: process alive, low
# CPU, no new log lines. On 2026-07-31 that cost a full 600-second timeout to discover
# something the wrapper output had said within seconds.
#
# The patterns must be specific. The game's own log is interleaved into this file and
# contains arbitrary mod text -- a mod literally named "Guarantee Rare Items" matches a
# naive search for the word in HotSpot's guarantee() failures.
FATAL_JVM_PATTERNS='A fatal error has been detected by the Java Runtime Environment|Internal Error at [a-zA-Z_]+\.cpp:[0-9]+|Do you want to debug the problem\?'

report_fatal_jvm_error() {
    local flag="$1"
    bad "The game's JVM crashed. Excluding this run."
    while IFS= read -r line; do
        bad "  $line"
    done < "$flag"
    note "This is a HotSpot failure, not a mod or a Preflight logic error. It hangs rather"
    note "than exits because Starsector's launcher passes -XX:+ShowMessageBoxOnError."
    note "If it recurs, try --conditions vanilla,fast: --no-record removes the execution"
    note "sampling that is the most likely contributor. See docs/startup-benchmark.md."
}

watch_for_fatal_jvm_error() {
    local output="$1" pid="$2" flag="$3"
    while kill -0 "$pid" >/dev/null 2>&1; do
        if [[ -s "$output" ]] && grep -qaE "$FATAL_JVM_PATTERNS" "$output" 2>/dev/null; then
            grep -aE "$FATAL_JVM_PATTERNS" "$output" 2>/dev/null | head -3 > "$flag"
            # Killing the tree makes the readiness watcher's own liveness check fire, so
            # the run fails in seconds through the ordinary path instead of timing out.
            terminate "$pid"
            return 0
        fi
        sleep 1
    done
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
    local fatal_flag="$run_dir/fatal-jvm-error.txt"
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
        fast)
            command=(java -jar "$JAR" run --game "$GAME" --launcher "$LAUNCHER"
                     --trace-dir "$run_dir" --adapter --texture-auto --texture-cache-dir "$CACHE"
                     --no-record) ;;
        profile)
            command=(java -jar "$JAR" run --game "$GAME" --launcher "$LAUNCHER"
                     --trace-dir "$run_dir" --adapter --texture-auto --texture-cache-dir "$CACHE"
                     --profile) ;;
        prepared)
            command=(java -jar "$JAR" run --game "$GAME" --launcher "$LAUNCHER"
                     --trace-dir "$run_dir" --adapter --texture-auto --texture-cache-dir "$CACHE"
                     --texture-mode prepared-pixels --prepared-npot --no-record) ;;
    esac

    banner "$label"
    note "${command[*]}"
    python3 "$DETECTOR" snapshot --log-dir "$LOG_DIR" --output "$before_logs"

    local start_ns
    start_ns="$(python3 -c 'import time; print(time.monotonic_ns())')"
    "${command[@]}" >"$wrapper_output" 2>&1 &
    local pid=$!
    watch_for_fatal_jvm_error "$wrapper_output" "$pid" "$fatal_flag" &
    local watchdog=$!

    if ! python3 "$DETECTOR" watch-launcher --log-dir "$LOG_DIR" --snapshot "$before_logs" \
            --output "$launcher_detection" --pid "$pid" --process-start-ns "$start_ns" \
            --timeout-seconds "$LAUNCHER_TIMEOUT_SECONDS" --quiet-seconds "$LAUNCHER_QUIET_SECONDS"; then
        kill "$watchdog" >/dev/null 2>&1 || true
        if [[ -s "$fatal_flag" ]]; then
            report_fatal_jvm_error "$fatal_flag"
            record_run "$condition" "$iteration" "excluded" "jvm-crash" "null" "null" "null"
            return 1
        fi
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
        kill "$watchdog" >/dev/null 2>&1 || true
        terminate "$pid"
        if [[ -s "$fatal_flag" ]]; then
            report_fatal_jvm_error "$fatal_flag"
            record_run "$condition" "$iteration" "excluded" "jvm-crash" "null" "null" "null"
            return 1
        fi
        bad "The main menu was never detected. Excluding this run."
        record_run "$condition" "$iteration" "excluded" "main-menu-not-detected" "null" "null" "null"
        return 1
    fi

    local menu_ms
    # The preload marker, not the last line before the quiet window: the trailing chatter
    # after preload ranged 0.0-9.3s across otherwise identical runs on 2026-07-31.
    menu_ms="$(jq -er '.gameLogStartToGraphicsPreloadMs' "$menu_detection")"
    good "$(awk -v ms="$menu_ms" 'BEGIN { printf "Main menu ready in %.1fs", ms / 1000 }')"
    act "QUIT from the main menu now  (close the launcher if it reappears)"

    set +e
    wait "$pid"
    local exit_code=$?
    set -e
    kill "$watchdog" >/dev/null 2>&1 || true
    # The game outlives the wrapper, so a crash can still be sitting on the message-box
    # prompt after the wrapper returns. Clear the tree before the next run starts.
    terminate "$pid"

    local fingerprint status reason
    fingerprint="$(scan_fingerprint "$profile_after" || echo unknown)"
    status=accepted
    reason=""
    if [[ -s "$fatal_flag" ]]; then
        report_fatal_jvm_error "$fatal_flag"
        status=excluded; reason="jvm-crash"
    elif (( exit_code != 0 )); then
        status=excluded; reason="nonzero-exit-$exit_code"
    elif [[ "$fingerprint" != "$EXPECTED_FINGERPRINT" ]]; then
        status=excluded; reason="profile-drift"
    elif [[ "$condition" == prepared ]] \
            && { ! served_prepared_textures "$run_dir" || ! bypassed_pixel_conversions "$run_dir"; }; then
        status=excluded; reason="prepared-pixels-served-nothing"
    elif [[ "$condition" == enabled || "$condition" == fast || "$condition" == profile ]] \
            && ! served_prepared_textures "$run_dir"; then
        # The adapter is fail-open by design, so a launch where it served nothing looks
        # exactly like a normal launch. Timing it would measure the baseline twice and
        # report the honest conclusion that the cache does nothing.
        status=excluded; reason="adapter-served-nothing"
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
    # The settling launch is deliberately thrown away. It is also the slowest launch of
    # the session, because it is the one that regenerates the GraphicsLib cache, so
    # letting it reach the results file would drag its condition's median up.
    [[ "$RECORDING" == true ]] || return 0
    local menu_detection="$ROOT/runs/${condition}-${iteration}/main-menu-ready.json"
    local start_instant="null" ready_instant="null" log_delta="null" trailing="null"
    if [[ -f "$menu_detection" ]]; then
        start_instant="$(jq -c '.gameStartInstant // null' "$menu_detection")"
        ready_instant="$(jq -c '.mainMenuReadyInstant // null' "$menu_detection")"
        log_delta="$(jq -c '.gameLogMillisDelta // null' "$menu_detection")"
        trailing="$(jq -c '.trailingLogActivityMs // null' "$menu_detection")"
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
        --argjson trailing "$trailing" \
        '{condition: $condition, iteration: $iteration, status: $status,
          reason: (if $reason == "" then null else $reason end),
          gameLogStartToMainMenuMs: $menuMs, launcherReadyMs: $launcherMs,
          trailingLogActivityMs: $trailing,
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
    settled=skipped
    if [[ "$reply" != skip ]]; then
        RECORDING=false
        settled=false
        while :; do
            # `fast`, not `enabled`. This launch is thrown away, so it has no reason to carry
            # the recorder -- and the recorder is the most likely contributor to the HotSpot
            # safepoint crash this configuration keeps hitting: the two documented triggers for
            # that assertion are binary translation and an attached profiler, and Rosetta 2 is
            # the one of those we cannot remove. `fast` exercises the same cache path without it.
            if launch_once fast 0 "SETTLING LAUNCH  (discarded)"; then
                settled=true
                break
            fi
            bad "The settling launch did not reach the main menu."
            note "It exists so GraphicsLib finishes writing its normal-map cache before"
            note "anything is counted. A launch that stopped early may not have finished,"
            note "and whatever is left would be paid by the first measured run instead --"
            note "which is the exact contamination that invalidated the July comparison."
            read -r -p "Press Enter to retry it, or type skip to go on anyway: " retry
            [[ "$retry" == skip ]] && break
        done
        RECORDING=true
        EXPECTED_FINGERPRINT="$(scan_fingerprint "$ROOT/profile-settled.json")"
        echo "$EXPECTED_FINGERPRINT" > "$ROOT/expected-fingerprint.txt"
        note "settled profile: $EXPECTED_FINGERPRINT"
    fi
    # Only a settled installation is recorded as settled. Leaving the marker off means a
    # --resume tries again rather than inheriting an assumption that was never established.
    if [[ "$settled" == true ]]; then
        touch "$ROOT/warmup-done"
    else
        bad "Continuing without a confirmed settling launch; treat the first run of each"
        bad "condition with suspicion, and prefer --resume once one has succeeded."
    fi
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
