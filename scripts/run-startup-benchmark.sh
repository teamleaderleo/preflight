#!/usr/bin/env bash
# Repeated real startup benchmark: vanilla versus agent-only versus preflight-enabled.
#
# You do exactly two things per launch: click Play Starsector when told, and quit from
# the main menu when told. Everything else is automatic, and the session survives a bad
# run -- one launch that drifts or crashes is excluded and the campaign continues.
set -euo pipefail

GAME_WAS_SET=false
[[ -n "${GAME+x}" ]] && GAME_WAS_SET=true
CACHE_WAS_SET=false
[[ -n "${CACHE+x}" ]] && CACHE_WAS_SET=true
SEED_WAS_SET=false
[[ -n "${SEED+x}" ]] && SEED_WAS_SET=true
GAME="${GAME:-/Applications/Starsector.app}"
CACHE="${CACHE:-$HOME/.starsector-preflight/cache}"
ROUNDS=5
CONDITIONS="vanilla,agent,enabled,fast"
UNATTENDED=false
REPREPARE=false
COOLDOWN_SECONDS=0
SESSION=""
SKIP_WARMUP=false
SEED="${SEED:-$RANDOM}"
CONDITIONS_WERE_SET=false
UNATTENDED_WAS_SET=false
COOLDOWN_WAS_SET=false
GAME_OPTION_WAS_SET=false
ROOT=""
ENGINE=""
ENGINE_WAS_SET=false
ENGINE_SHA256=""
ENGINE_SOURCE=checkout
ENGINE_VERSION=""
MANIFEST_JAR_SHA=""

CHECKOUT_JAR="$PWD/preflight-cli/target/preflight.jar"
JAR="$CHECKOUT_JAR"
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
  --conditions LIST   Comma-separated subset of vanilla,agent,enabled,compatibility,fast,
                      fast-eager,full,profile,fast-profile,prepared
                      (default vanilla,agent,enabled,fast; every other condition is opt-in).
  --unattended        Start the game without its launcher and stop it once the main menu is
                      up, so the campaign needs no clicks at all. Uses Starsector's own
                      launchDirect path with the resolution, fullscreen and sound settings
                      the launcher itself would have used, and applies to every condition
                      including vanilla. Refuses up front, with a reason, if the install
                      cannot support it.
  --cooldown-seconds N
                      Idle for N seconds before every launch, so each one starts from the same
                      thermal state. A fanless machine loading this game repeatedly slows as it
                      heats: the 2026-08-01 campaign drifted +19.6s across fifteen launches,
                      which is ten times the effect it was trying to measure. 240 is a
                      reasonable starting point.
  --reprepare         Rebuild the caches even when they already match this profile.
  --resume DIR        Continue an interrupted session, keeping its completed runs and restoring
                      its conditions, protocol, display/sound settings, cooldown, cache, and seed.
                      Conflicting explicit arguments or code/environment drift are rejected.
  --skip-warmup       Skip the discarded settling launch (only if you just ran one).
  --game PATH         Starsector installation (default /Applications/Starsector.app).
  --engine PATH       Measure the engine a release candidate actually carries instead of
                      building this checkout. PATH is a runnable preflight.jar, an installed
                      or extracted Preflight (a .app, or any directory holding
                      engine/preflight.jar), and the checkout build is not consulted at all:
                      there is no fallback to preflight-cli/target/preflight.jar. Without it
                      a campaign measures checkout bytes and says so, which makes it
                      development evidence rather than a release-candidate result.
  --engine-sha256 HEX Additionally require the resolved engine to hash to this SHA-256.
                      Packaged engines are already pinned by the bundle.json beside them;
                      this accepts an independently recorded expected digest as well.
  -h, --help          Show this message.

Conditions:
  vanilla   the game's own launcher, no preflight at all -- the true baseline
  agent     preflight run --no-adapter -- isolates what the JFR recorder itself costs
  enabled   preflight run --adapter --texture-auto -- the prepared texture path, recorded
  compatibility
            the historical `fast` condition: compatibility textures and no recorder. It is
            retained for component comparisons, but does not represent a normal user launch.
  fast      the CLI's actual --fast preset: every startup and gameplay optimization that has
            passed its live gate. Installed Preflight launchers use this mode.
  fast-eager
            the same preset with --eager-heap-commit, an exact control for the Recommended
            preset's on-demand heap commitment. This is a diagnostic condition, not a player
            configuration.
  full      the frozen 2026-08-03 explicit stack (prepared pixels plus the two rule caches).
            It remains available to reproduce the accepted historical campaign, but newer
            live-gated optimizations are present only in `fast`.
  prepared  --texture-mode prepared-pixels --prepared-npot --no-record -- hands the game
            upload-ready bytes instead of a BufferedImage it has to unpack a pixel at a
            time. The flag is not optional: without it the bridge declines every texture
            needing power-of-two padding, which was 6,165 of 6,706 on the reviewed profile.
            Compare against `fast`, the same launch in compatibility mode.
  prepared-unpadded
            additionally serves textures at their true size. This needs the fold bypass to
            be installed in the loader, and nothing installs it yet -- the two plans target
            the same class and have not been composed. Without it the runtime fails closed
            and this condition just behaves like `prepared`. See
            docs/evidence/2026-07-31-half-an-invariant-kills-the-launcher.md.
  profile   the same, plus --profile sampling, for asking where the time goes. The harness
            asks the live agent to finish its recording at the main-menu boundary before it
            stops the JVM, so the active tail cannot be lost to shutdown-hook ordering. Not a
            timing condition: it records, so it is slower than fast.
  fast-profile
            the current --fast preset plus sampling. Use this to find the next hotspot in the
            actual user configuration; like `profile`, its wall clock is diagnostic only.

Each launch costs about 90 seconds plus your two clicks, so the default 5 rounds across
four conditions is roughly 45 minutes. Ctrl-C is safe at any point: completed runs are
kept, and --resume picks the session back up where it stopped.

To answer only "is Preflight worth it", drop the two diagnostic conditions:
  --conditions vanilla,fast
USAGE
}

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

while [[ $# -gt 0 ]]; do
    case "$1" in
        --rounds) ROUNDS="$2"; shift 2 ;;
        --conditions) CONDITIONS="$2"; CONDITIONS_WERE_SET=true; shift 2 ;;
        --unattended) UNATTENDED=true; UNATTENDED_WAS_SET=true; shift ;;
        --cooldown-seconds) COOLDOWN_SECONDS="$2"; COOLDOWN_WAS_SET=true; shift 2 ;;
        --reprepare) REPREPARE=true; shift ;;
        # Removed rather than aliased. --auto-play searched the launcher for a Swing button to
        # press, and current Starsector draws its launcher in OpenGL, so there was never a button
        # to find. The replacement measures a different interval -- no launcher phase at all --
        # and silently accepting the old name would mix two protocols in one results file.
        --auto-play|--auto-play-timeout-ms)
            bad "--auto-play is gone: it looked for a Swing button that this launcher does not have."
            bad "Use --unattended, which starts the game without the launcher instead of driving it."
            exit 2 ;;
        --resume) SESSION="$2"; shift 2 ;;
        --skip-warmup) SKIP_WARMUP=true; shift ;;
        --game) GAME="$2"; GAME_OPTION_WAS_SET=true; shift 2 ;;
        --engine) ENGINE="$2"; ENGINE_WAS_SET=true; shift 2 ;;
        --engine-sha256) ENGINE_SHA256="$2"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
    esac
done

if [[ ! "$ROUNDS" =~ ^[0-9]+$ ]] || (( ROUNDS < 1 )); then
    echo "--rounds must be a positive integer." >&2
    exit 2
fi
if [[ ! "$COOLDOWN_SECONDS" =~ ^[0-9]+$ ]]; then
    echo "--cooldown-seconds must be a non-negative integer." >&2
    exit 2
fi
if [[ ! "$SEED" =~ ^[0-9]+$ ]]; then
    echo "SEED must be a non-negative integer." >&2
    exit 2
fi
if [[ -n "$ENGINE_SHA256" && "$ENGINE_WAS_SET" != true ]]; then
    echo "--engine-sha256 describes a candidate engine, so it requires --engine." >&2
    exit 2
fi
if [[ -n "$ENGINE_SHA256" && ! "$ENGINE_SHA256" =~ ^[0-9a-fA-F]{64}$ ]]; then
    echo "--engine-sha256 must be a 64-character hex SHA-256." >&2
    exit 2
fi
if [[ "$ENGINE_WAS_SET" == true ]]; then
    [[ -n "$ENGINE" ]] || { echo "--engine needs a path." >&2; exit 2; }
    ENGINE_SOURCE=candidate
fi

for command in git java mvn jq python3 shasum; do
    command -v "$command" >/dev/null 2>&1 || { bad "Missing required command: $command"; exit 1; }
done

# A benchmark session is a measurement contract, not merely a directory of partial results.
# Resuming must restore every input that can change what is launched or how samples are ordered.
# Explicit conflicting arguments are rejected rather than silently mixing unlike runs.
if [[ -n "$SESSION" ]]; then
    ROOT="$SESSION"
    [[ -d "$ROOT" ]] || { bad "Session directory not found: $ROOT"; exit 1; }
    SESSION_CONFIG="$ROOT/session-config.json"
    if [[ ! -f "$SESSION_CONFIG" ]]; then
        bad "This session predates the resumable session contract: $SESSION_CONFIG"
        bad "Refusing to guess its protocol, conditions, display settings, or shuffle seed."
        note "Start a new session; the existing results remain available for reporting."
        exit 2
    fi

    RECORDED_CONDITIONS="$(jq -er '.conditions' "$SESSION_CONFIG")"
    RECORDED_UNATTENDED="$(jq -er '.unattended' "$SESSION_CONFIG")"
    RECORDED_COOLDOWN="$(jq -er '.cooldownSeconds' "$SESSION_CONFIG")"
    RECORDED_GAME="$(jq -er '.game' "$SESSION_CONFIG")"
    RECORDED_CACHE="$(jq -er '.cache' "$SESSION_CONFIG")"
    RECORDED_SEED="$(jq -er '.seed' "$SESSION_CONFIG")"
    RECORDED_PROTOCOL="$(jq -er '.protocol' "$SESSION_CONFIG")"
    # Version 1 sessions predate --engine, so their engine was necessarily the checkout build.
    # That is a fact about the format rather than an assumption about the session.
    RECORDED_ENGINE_SOURCE="$(jq -er '.engineSource // "checkout"' "$SESSION_CONFIG")"
    RECORDED_ENGINE="$(jq -er '.engine // ""' "$SESSION_CONFIG")"

    if [[ "$CONDITIONS_WERE_SET" == true && "$CONDITIONS" != "$RECORDED_CONDITIONS" ]]; then
        bad "--conditions conflicts with this session: $CONDITIONS != $RECORDED_CONDITIONS"
        exit 2
    fi
    if [[ "$UNATTENDED_WAS_SET" == true && "$RECORDED_UNATTENDED" != true ]]; then
        bad "--unattended conflicts with this clicked-protocol session."
        exit 2
    fi
    if [[ "$COOLDOWN_WAS_SET" == true && "$COOLDOWN_SECONDS" != "$RECORDED_COOLDOWN" ]]; then
        bad "--cooldown-seconds conflicts with this session: $COOLDOWN_SECONDS != $RECORDED_COOLDOWN"
        exit 2
    fi
    if [[ ( "$GAME_OPTION_WAS_SET" == true || "$GAME_WAS_SET" == true )
            && "$GAME" != "$RECORDED_GAME" ]]; then
        bad "The requested game conflicts with this session: $GAME != $RECORDED_GAME"
        exit 2
    fi
    if [[ "$CACHE_WAS_SET" == true && "$CACHE" != "$RECORDED_CACHE" ]]; then
        bad "CACHE conflicts with this session: $CACHE != $RECORDED_CACHE"
        exit 2
    fi
    if [[ "$SEED_WAS_SET" == true && "$SEED" != "$RECORDED_SEED" ]]; then
        bad "SEED conflicts with this session: $SEED != $RECORDED_SEED"
        exit 2
    fi
    # A candidate session must be resumed against the same candidate. Resuming it without
    # --engine would build the checkout, and the identity check would then reject the session
    # for a reason that reads like unrelated code drift.
    if [[ "$ENGINE_WAS_SET" == true && "$ENGINE" != "$RECORDED_ENGINE" ]]; then
        bad "--engine conflicts with this session: $ENGINE != ${RECORDED_ENGINE:-the checkout build}"
        exit 2
    fi
    if [[ "$ENGINE_WAS_SET" != true && "$RECORDED_ENGINE_SOURCE" == candidate ]]; then
        bad "This session measured a release candidate engine, not the checkout."
        bad "Resume it with --engine '$RECORDED_ENGINE'."
        exit 2
    fi

    CONDITIONS="$RECORDED_CONDITIONS"
    UNATTENDED="$RECORDED_UNATTENDED"
    COOLDOWN_SECONDS="$RECORDED_COOLDOWN"
    GAME="$RECORDED_GAME"
    CACHE="$RECORDED_CACHE"
    SEED="$RECORDED_SEED"
    ENGINE="$RECORDED_ENGINE"
    ENGINE_SOURCE="$RECORDED_ENGINE_SOURCE"
fi

[[ -f pom.xml ]] || { bad "Run this from the Preflight repository root."; exit 1; }
[[ -d "$GAME" ]] || { bad "Starsector installation not found: $GAME"; exit 1; }
for helper in "$DETECTOR" "$REPORTER"; do
    [[ -f "$helper" ]] || { bad "Helper not found: $helper"; exit 1; }
done

IFS=',' read -r -a CONDITION_LIST <<< "$CONDITIONS"
for condition in "${CONDITION_LIST[@]}"; do
    case "$condition" in
        vanilla|agent|enabled|compatibility|fast|fast-eager|full|profile|fast-profile|prepared|prepared-unpadded) ;;
        *) bad "Unknown condition: $condition"; exit 2 ;;
    esac
done

LOG_DIR="$GAME/logs"
[[ -d "$LOG_DIR" ]] || { bad "Starsector log directory not found: $LOG_DIR"; exit 1; }

if [[ -n "$ROOT" ]]; then
    banner "Resuming session $ROOT"
else
    ROOT="$HOME/.starsector-preflight/benchmarks/$(date +%Y%m%d-%H%M%S)"
    mkdir -p "$ROOT/runs"
fi
RESULTS="$ROOT/results.jsonl"
touch "$RESULTS"

# A release-candidate campaign has to execute the bytes the candidate ships. Resolution is a
# short list of reviewed layouts rather than a search, and it never falls back to the checkout
# build: a candidate campaign that quietly measured preflight-cli/target/preflight.jar would be
# indistinguishable in its own evidence from one that did not.
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

if [[ "$ENGINE_SOURCE" == candidate ]]; then
    banner "== Using the release candidate engine =="
    JAR="$(resolve_engine "$ENGINE")" || {
        bad "No Preflight engine under: $ENGINE"
        bad "Expected a preflight.jar, an installed Preflight.app, or a directory holding"
        bad "engine/preflight.jar. Refusing to fall back to the checkout build."
        exit 1
    }
    JAR="$(cd "$(dirname "$JAR")" && pwd)/$(basename "$JAR")"
    # A packaged engine ships a manifest beside it. The package boundary verifies this same
    # digest before publication; checking it here binds the campaign to those packaged bytes.
    ENGINE_MANIFEST="$(dirname "$JAR")/bundle.json"
    if [[ -f "$ENGINE_MANIFEST" ]]; then
        ENGINE_VERSION="$(jq -er '.sourceVersion // ""' "$ENGINE_MANIFEST" 2>/dev/null || echo "")"
        MANIFEST_JAR_BYTES="$(jq -er '.jarBytes // empty' "$ENGINE_MANIFEST" 2>/dev/null || echo "")"
        MANIFEST_JAR_SHA="$(jq -er '.jarSha256 // empty' "$ENGINE_MANIFEST" 2>/dev/null || echo "")"
        RESOLVED_JAR_BYTES="$(wc -c < "$JAR" | tr -d ' ')"
        if [[ -n "$MANIFEST_JAR_BYTES" && "$MANIFEST_JAR_BYTES" != "$RESOLVED_JAR_BYTES" ]]; then
            bad "This engine does not match the bundle manifest packaged beside it."
            bad "  $ENGINE_MANIFEST records jarBytes=$MANIFEST_JAR_BYTES"
            bad "  $JAR is $RESOLVED_JAR_BYTES bytes"
            exit 1
        fi
        if [[ ! "$MANIFEST_JAR_SHA" =~ ^[0-9a-f]{64}$ ]]; then
            bad "The bundle manifest beside this engine has no valid JAR digest."
            exit 1
        fi
    fi
else
    banner "== Building the checkout =="
    mvn -q -DskipTests package
    JAR="$CHECKOUT_JAR"
    [[ -f "$JAR" ]] || { bad "Runnable JAR was not produced: $JAR"; exit 1; }
fi
JAR_SHA="$(shasum -a 256 "$JAR" | awk '{print $1}')"
if [[ -n "$MANIFEST_JAR_SHA" && "$JAR_SHA" != "$MANIFEST_JAR_SHA" ]]; then
    bad "This engine does not match the JAR digest in its bundle manifest."
    bad "  manifest: $MANIFEST_JAR_SHA"
    bad "  resolved: $JAR_SHA  ($JAR)"
    exit 1
fi
if [[ -n "$ENGINE_SHA256" ]]; then
    EXPECTED_ENGINE_SHA="$(tr '[:upper:]' '[:lower:]' <<< "$ENGINE_SHA256")"
    if [[ "$(tr '[:upper:]' '[:lower:]' <<< "$JAR_SHA")" != "$EXPECTED_ENGINE_SHA" ]]; then
        bad "This engine is not the one --engine-sha256 named."
        bad "  expected: $EXPECTED_ENGINE_SHA"
        bad "  resolved: $JAR_SHA  ($JAR)"
        exit 1
    fi
fi
REPOSITORY_HEAD="$(git rev-parse HEAD)"
if [[ "$ENGINE_SOURCE" == candidate ]]; then
    note "harness HEAD:    $REPOSITORY_HEAD (the checkout driving this campaign, not the engine's source)"
    note "engine:          $JAR"
    if [[ -n "$ENGINE_VERSION" ]]; then
        note "engine version:  $ENGINE_VERSION"
    fi
else
    note "repository HEAD: $REPOSITORY_HEAD"
    note "engine:          the checkout build -- development evidence, not a release candidate"
fi
note "preflight.jar:   $JAR_SHA"

LAUNCHER="$(java -jar "$JAR" doctor --game "$GAME" 2>/dev/null | awk '/^Selected: /{print substr($0, 11); exit}')"
[[ -n "$LAUNCHER" && -f "$LAUNCHER" ]] || { bad "Could not resolve the game launcher under $GAME"; exit 1; }
note "launcher:        $LAUNCHER"

# Two protocols, and a results file may only ever hold one. `clicked` waits for the launcher and
# an operator; `direct` uses Starsector's own launchDirect path, where no launcher is built at
# all. They do not measure the same thing -- the launcher's OpenGL context, font loading and
# window creation exist in one and not the other -- so mixing them in a single comparison would
# be reading two quantities as one.
LAUNCH_SETTINGS="$ROOT/launch-settings.json"
if [[ -n "$SESSION" ]]; then
    [[ -f "$LAUNCH_SETTINGS" ]] || {
        bad "The session has no immutable launch-settings snapshot: $LAUNCH_SETTINGS"
        exit 2
    }
else
    java -jar "$JAR" launch-settings > "$LAUNCH_SETTINGS"
fi

PROTOCOL=clicked
if [[ "$UNATTENDED" == true ]]; then
    if [[ "$(jq -r '.directLaunchAvailable' "$LAUNCH_SETTINGS")" != true ]]; then
        # Failing here rather than falling back: someone who asked for an unattended campaign
        # should learn it cannot be one now, not forty minutes in at the first unanswered prompt.
        bad "This install cannot start without its launcher, so --unattended is not available:"
        bad "  $(jq -r '.reason' "$LAUNCH_SETTINGS")"
        note "Run without --unattended to use the clicked protocol."
        exit 2
    fi
    PROTOCOL=direct
    DIRECT_OPTIONS="$(jq -r '.settings.javaOptions | join(" ")' "$LAUNCH_SETTINGS")"
    # Appended, not assigned: EXTRAARGS is the game's own hook and may already carry something
    # the operator put there.
    export EXTRAARGS="${EXTRAARGS:+$EXTRAARGS }$DIRECT_OPTIONS"
    good "Unattended: the game will start itself at $(jq -r '.settings.resolution' "$LAUNCH_SETTINGS")," \
         "fullscreen=$(jq -r '.settings.fullscreen' "$LAUNCH_SETTINGS")," \
         "sound=$(jq -r '.settings.sound' "$LAUNCH_SETTINGS")."
    if [[ -n "$SESSION" ]]; then
        note "Those are this session's immutable saved settings, not today's launcher defaults."
    else
        note "Those are the launcher's own saved settings, so this is the launch you would have clicked."
    fi
elif [[ -n "$SESSION" ]]; then
    # A clicked launch cannot be forced to use old options. Refuse if its external launcher
    # settings changed, because continuing would silently mix resolutions or display modes.
    CURRENT_LAUNCH_SETTINGS="$(mktemp)"
    java -jar "$JAR" launch-settings > "$CURRENT_LAUNCH_SETTINGS"
    if [[ "$(jq -S -c '.settings' "$CURRENT_LAUNCH_SETTINGS")" \
            != "$(jq -S -c '.settings' "$LAUNCH_SETTINGS")" ]]; then
        rm -f "$CURRENT_LAUNCH_SETTINGS"
        bad "The launcher's display/sound settings changed since this clicked session began."
        bad "Refusing to mix them into one benchmark. Start a new session or restore the settings."
        exit 2
    fi
    rm -f "$CURRENT_LAUNCH_SETTINGS"
fi
note "protocol:        $PROTOCOL"

if [[ -n "$SESSION" ]]; then
    if [[ "$PROTOCOL" != "$RECORDED_PROTOCOL" ]]; then
        bad "Protocol drift: this invocation resolved $PROTOCOL, session requires $RECORDED_PROTOCOL."
        exit 2
    fi
else
    jq -n \
        --arg game "$GAME" \
        --arg cache "$CACHE" \
        --arg conditions "$CONDITIONS" \
        --arg protocol "$PROTOCOL" \
        --argjson unattended "$UNATTENDED" \
        --argjson cooldownSeconds "$COOLDOWN_SECONDS" \
        --argjson seed "$SEED" \
        --arg scenarioId "$SCENARIO_ID" \
        --arg engineSource "$ENGINE_SOURCE" \
        --arg engine "$ENGINE" \
        '{version: 2, game: $game, cache: $cache, conditions: $conditions,
          protocol: $protocol, unattended: $unattended, cooldownSeconds: $cooldownSeconds,
          seed: $seed, scenarioId: $scenarioId,
          engineSource: $engineSource, engine: $engine}' > "$ROOT/session-config.json"
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
    if [[ -n "$SESSION" ]]; then
        RESUME_FINGERPRINT="$(scan_fingerprint "$ROOT/profile-resume-check.json")"
        if [[ "$RESUME_FINGERPRINT" != "$EXPECTED_FINGERPRINT" ]]; then
            bad "The enabled mod profile changed since this session began."
            bad "Refusing to combine profile $RESUME_FINGERPRINT with $EXPECTED_FINGERPRINT."
            exit 2
        fi
    fi
fi
note "profile:         $EXPECTED_FINGERPRINT"

# Preparation is an offline step: it builds the caches and exits well before any launch.
# Its cost belongs in the report as setup, never inside a measured startup.
#
# Skipped when the cache already matches this profile. The guard used to be "did this session
# already prepare", and a session is a fresh timestamped directory, so it could only ever hit on
# --resume: every new campaign paid 16s to rebuild nothing. The stamp lives beside the cache
# because that is what it describes -- the caches are keyed by profile fingerprint, so a matching
# stamp means the artifacts on disk are the ones this profile needs.
PREPARE_REPORT="$ROOT/prepare.json"
PREPARE_STAMP="$CACHE/prepared-profile.txt"
prepare_caches() {
    local reason="$1"
    banner "== Preparing the caches (offline, one time) =="
    note "$reason"
    local prepare_start prepare_end
    prepare_start="$(python3 -c 'import time; print(time.time_ns())')"
    java -jar "$JAR" prepare --game "$GAME" --cache-dir "$CACHE" --deep --verify-lookups \
        --report "$PREPARE_REPORT" >/dev/null
    prepare_end="$(python3 -c 'import time; print(time.time_ns())')"
    PREPARE_MS=$(( (prepare_end - prepare_start) / 1000000 ))
    echo "$PREPARE_MS" > "$ROOT/prepare-millis.txt"
    # Written only after prepare returns zero, so a failed or interrupted preparation leaves the
    # stamp stale and the next campaign rebuilds rather than trusting a half-built cache.
    printf '%s\n' "$EXPECTED_FINGERPRINT" > "$PREPARE_STAMP"
    good "Caches prepared in $(( PREPARE_MS / 1000 ))s."
}

PREPARE_MS=0
if [[ -f "$PREPARE_REPORT" ]]; then
    PREPARE_MS="$(cat "$ROOT/prepare-millis.txt" 2>/dev/null || echo 0)"
    note "Reusing the preparation from this session ($(( PREPARE_MS / 1000 ))s)."
elif [[ "$REPREPARE" == true ]]; then
    prepare_caches "Rebuilding because --reprepare was given."
elif [[ -f "$PREPARE_STAMP" && "$(cat "$PREPARE_STAMP")" == "$EXPECTED_FINGERPRINT" ]]; then
    note "Caches already prepared for this profile; skipping. Force with --reprepare."
else
    prepare_caches "No cache on disk matches this profile."
fi

HARDWARE="$(sysctl -n machdep.cpu.brand_string 2>/dev/null || uname -m)"
OS_VERSION="$(sw_vers -productVersion 2>/dev/null || uname -sr)"
JAVA_VERSION="$(java -version 2>&1 | head -1)"
if [[ -n "$SESSION" ]]; then
    [[ -f "$ROOT/identity.json" ]] || {
        bad "The session has no identity record: $ROOT/identity.json"
        exit 2
    }
    if ! jq -e \
            --arg head "$REPOSITORY_HEAD" \
            --arg jar "$JAR_SHA" \
            --arg game "$GAME" \
            --arg launcher "$LAUNCHER" \
            --arg profile "$EXPECTED_FINGERPRINT" \
            --arg scenario "$SCENARIO_ID" \
            --arg hardware "$HARDWARE" \
            --arg os "$OS_VERSION" \
            --arg java "$JAVA_VERSION" \
            --arg engineSource "$ENGINE_SOURCE" \
            '.repositoryHead == $head and .preflightJarSha256 == $jar
             and .game == $game and .launcher == $launcher
             and .profileFingerprint == $profile and .scenarioId == $scenario
             and .hardware == $hardware and .os == $os and .java == $java
             and (.engineSource // "checkout") == $engineSource' \
            "$ROOT/identity.json" >/dev/null; then
        bad "Code, game, profile, machine, OS, or Java identity drifted since this session began."
        bad "Refusing to combine unlike launches. Start a new benchmark session."
        exit 2
    fi
else
cat > "$ROOT/identity.json" <<IDENTITY
{
  "repositoryHead": "$REPOSITORY_HEAD",
  "preflightJarSha256": "$JAR_SHA",
  "engineSource": "$ENGINE_SOURCE",
  "enginePath": $(jq -Rs 'rtrimstr("\n")' <<< "$JAR"),
  "engineVersion": $(jq -Rs 'rtrimstr("\n") | if . == "" then null else . end' <<< "$ENGINE_VERSION"),
  "game": "$GAME",
  "launcher": "$LAUNCHER",
  "profileFingerprint": "$EXPECTED_FINGERPRINT",
  "scenarioId": "$SCENARIO_ID",
  "seed": $SEED,
  "hardware": $(jq -Rs 'rtrimstr("\n")' <<< "$HARDWARE"),
  "os": $(jq -Rs 'rtrimstr("\n")' <<< "$OS_VERSION"),
  "java": $(jq -Rs 'rtrimstr("\n")' <<< "$JAVA_VERSION"),
  "preparationMillis": ${PREPARE_MS:-0}
}
IDENTITY
fi

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
# An operator prompt that an unattended campaign does not have an operator for.
#
# --unattended exists so a campaign can run with nobody at the keyboard, and these prompts
# defeated that twice on 2026-08-01: piping a single newline satisfied the first prompt, the
# next one read EOF, and `set -e` killed the script one line after it announced it was
# starting. A ninety-minute campaign looked like it was running and was not.
#
# So: skip entirely when unattended, and never let an exhausted stdin end the run silently.
confirm() {
    local prompt="$1" target="${2:-}" answer=""
    if [[ "$UNATTENDED" == true ]]; then
        [[ -n "$target" ]] && printf -v "$target" '%s' ""
        return 0
    fi
    if ! read -r -p "$prompt" answer; then
        # Closed stdin is not an answer. Carrying on is the same choice a bare Enter makes,
        # and it is the one that does not discard a campaign already in progress.
        echo
        note "stdin closed; continuing as though you pressed Enter."
        answer=""
    fi
    [[ -n "$target" ]] && printf -v "$target" '%s' "$answer"
    return 0
}

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

# Stop only the launched game tree and leave the Preflight wrapper alive long enough to observe
# the child exit, inspect the finished JFR, and finalize run.json. Killing the wrapper alongside
# the game made unattended profile runs look accepted to the harness while their own receipts
# remained permanently RUNNING and their single-chunk postcondition was never checked.
terminate_descendants() {
    local pid="$1" target
    local tree
    tree="$(descendants "$pid")"
    for target in $tree; do
        kill "$target" >/dev/null 2>&1 || true
    done
    for _ in $(seq 1 40); do
        local live=false
        for target in $tree; do
            if kill -0 "$target" >/dev/null 2>&1; then
                live=true
                break
            fi
        done
        [[ "$live" == false ]] && return 0
        sleep 0.25
    done
    for target in $tree; do
        kill -0 "$target" >/dev/null 2>&1 && kill -9 "$target" >/dev/null 2>&1 || true
    done
}

# Do not leave recording completion to the relative ordering of our SIGTERM and multiple JVM
# shutdown hooks. Ask the live agent to stop and close the recording before signalling the JVM.
# The request/ack protocol is file-only, so it has the same semantics on macOS, Windows, and Linux
# and needs no Attach API.
finish_live_recording() {
    local run_dir="$1"
    local request="$run_dir/startup.stop-request"
    local complete="$run_dir/startup.stop-complete"
    touch "$request"
    for _ in $(seq 1 200); do
        if [[ -f "$complete" ]]; then
            if [[ "$(head -1 "$complete" 2>/dev/null)" == ok ]]; then
                note "The live agent closed the startup recording before JVM shutdown."
                return 0
            fi
            bad "The live agent could not finish the startup recording: $(head -1 "$complete")"
            return 1
        fi
        sleep 0.05
    done
    bad "The live agent did not acknowledge the startup recording stop request."
    return 1
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

# The second way a launch hangs forever, and the one behind the 2026-07-31 "Fatal: Number of
# remaining buffer elements is 668043, must be at least 1572864" dialog. StarfarerLauncher wraps
# both the launcher construction and the game start in one try, and its handler logs the
# throwable and then calls org.lwjgl.Sys.alert -- a modal native dialog with nothing to dismiss
# it. The process stays alive at idle with no new log lines, which is indistinguishable from a
# slow load. It is not a HotSpot failure and must not be reported as one.
#
# Anchored on the logger category at ERROR rather than on the message, because the message is
# whatever was thrown. That category is only ever this handler.
FATAL_GAME_PATTERN='ERROR +com\.fs\.starfarer\.StarfarerLauncher'

report_fatal_jvm_error() {
    local flag="$1"
    if grep -qaE "$FATAL_GAME_PATTERN" "$flag" 2>/dev/null; then
        bad "The game threw out of its own launcher. Excluding this run."
        while IFS= read -r line; do
            bad "  $line"
        done < "$flag"
        note "Starsector's top-level handler answers this with org.lwjgl.Sys.alert, a modal"
        note "dialog that nothing dismisses, so the process would have sat here until the"
        note "600-second timeout. The message above is the actual cause."
        return
    fi
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
    local patterns="$FATAL_JVM_PATTERNS|$FATAL_GAME_PATTERN"
    while kill -0 "$pid" >/dev/null 2>&1; do
        if [[ -s "$output" ]] && grep -qaE "$patterns" "$output" 2>/dev/null; then
            grep -aE "$patterns" "$output" 2>/dev/null | head -3 > "$flag"
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
    # The direct protocol needs nothing in the process, so it applies to vanilla too: the
    # properties travel through the game's own EXTRAARGS hook, not through the agent. That is
    # what makes the baseline unattended as well, which --auto-play could never manage.
    local auto=false
    if [[ "$PROTOCOL" == direct ]]; then
        auto=true
    fi
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
        compatibility)
            command=(java -jar "$JAR" run --game "$GAME" --launcher "$LAUNCHER"
                     --trace-dir "$run_dir" --adapter --texture-auto --texture-cache-dir "$CACHE"
                     --no-record) ;;
        fast)
            command=(java -jar "$JAR" run --game "$GAME" --launcher "$LAUNCHER"
                     --trace-dir "$run_dir" --fast --texture-cache-dir "$CACHE") ;;
        fast-eager)
            command=(java -jar "$JAR" run --game "$GAME" --launcher "$LAUNCHER"
                     --trace-dir "$run_dir" --fast --eager-heap-commit
                     --texture-cache-dir "$CACHE") ;;
        profile)
            command=(java -jar "$JAR" run --game "$GAME" --launcher "$LAUNCHER"
                     --trace-dir "$run_dir" --adapter --texture-auto --texture-cache-dir "$CACHE"
                     --profile) ;;
        fast-profile)
            command=(java -jar "$JAR" run --game "$GAME" --launcher "$LAUNCHER"
                     --trace-dir "$run_dir" --fast --texture-cache-dir "$CACHE"
                     --profile) ;;
        full)
            command=(java -jar "$JAR" run --game "$GAME" --launcher "$LAUNCHER"
                     --trace-dir "$run_dir" --adapter --texture-auto --texture-cache-dir "$CACHE"
                     --texture-mode prepared-pixels --prepared-npot
                     --rule-token-cache --rule-command-cache
                     --resource-probe-cache --loadjson-memo --no-record) ;;
        prepared)
            command=(java -jar "$JAR" run --game "$GAME" --launcher "$LAUNCHER"
                     --trace-dir "$run_dir" --adapter --texture-auto --texture-cache-dir "$CACHE"
                     --texture-mode prepared-pixels --prepared-npot --no-record) ;;
        prepared-unpadded)
            command=(java -jar "$JAR" run --game "$GAME" --launcher "$LAUNCHER"
                     --trace-dir "$run_dir" --adapter --texture-auto --texture-cache-dir "$CACHE"
                     --texture-mode prepared-pixels --prepared-unpadded --no-record) ;;
    esac

    banner "$label"
    # Before the snapshot and before the launch: the point is that every launch begins from the
    # same thermal state, so the comparison between conditions is not a comparison between how
    # hot the machine happened to be. It does not need to cool fully -- ten minutes only
    # recovered 87% of the drift -- it needs to be the same every time.
    if (( COOLDOWN_SECONDS > 0 )); then
        note "Cooling down for ${COOLDOWN_SECONDS}s so this launch starts where the others did..."
        sleep "$COOLDOWN_SECONDS"
    fi
    note "${command[*]}"
    python3 "$DETECTOR" snapshot --log-dir "$LOG_DIR" --output "$before_logs"

    local start_ns
    start_ns="$(python3 -c 'import time; print(time.monotonic_ns())')"
    "${command[@]}" >"$wrapper_output" 2>&1 &
    local pid=$!
    watch_for_fatal_jvm_error "$wrapper_output" "$pid" "$fatal_flag" &
    local watchdog=$!

    # Under the direct protocol there is no launcher to become ready and nothing to click, so
    # the pre-launch snapshot is also the measurement's snapshot. Waiting for a launcher marker
    # that will never be logged would spend the whole launcher timeout before every run.
    local menu_snapshot="$before_logs"
    if [[ "$auto" != true ]]; then
        menu_snapshot="$play_logs"
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
        note "Timing starts where the game's own log says it started. Do not press Enter."
    else
        note "The game is starting itself; no launcher, nothing to click."
    fi

    if ! python3 "$DETECTOR" watch-main-menu --log-dir "$LOG_DIR" --snapshot "$menu_snapshot" \
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
    local deliberate_stop=false recording_ready=true
    if [[ "$auto" == true ]]; then
        # The measurement ended at the marker above, so there is nothing left to observe.
        # SIGTERM the game tree first, which runs the JVM shutdown hooks that dump any recording,
        # but keep the Preflight wrapper alive so it can validate that dump and close its receipt.
        note "Main menu reached; stopping the game."
        deliberate_stop=true
        if [[ "$condition" == profile || "$condition" == fast-profile ]]; then
            finish_live_recording "$run_dir" || recording_ready=false
        fi
        terminate_descendants "$pid"
    else
        act "QUIT from the main menu now  (close the launcher if it reappears)"
    fi

    set +e
    wait "$pid"
    local exit_code=$?
    set -e
    # A signalled exit is the expected outcome when we did the signalling, and must not be read
    # as a failed run. A crash before that still reports, because the fatal flag is checked first.
    if [[ "$deliberate_stop" == true ]]; then
        exit_code=0
    fi
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
    elif [[ "$recording_ready" != true ]]; then
        status=excluded; reason="recording-not-finalized"
    elif [[ "$fingerprint" != "$EXPECTED_FINGERPRINT" ]]; then
        status=excluded; reason="profile-drift"
    elif [[ "$condition" == prepared || "$condition" == prepared-unpadded \
            || "$condition" == fast || "$condition" == fast-eager \
            || "$condition" == fast-profile || "$condition" == full ]] \
            && { ! served_prepared_textures "$run_dir" || ! bypassed_pixel_conversions "$run_dir"; }; then
        status=excluded; reason="prepared-pixels-served-nothing"
    elif [[ "$condition" == enabled || "$condition" == compatibility \
            || "$condition" == profile ]] \
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
                printf '%s\n' "$EXPECTED_FINGERPRINT" > "$PREPARE_STAMP"
                note "Rebuilt. The campaign continues from the settled profile."
            else
                # The stamp still names the old profile, and it must stay that way: the next
                # campaign has to rebuild rather than trust a cache that failed to build.
                bad "Re-preparation failed. Enabled runs will not launch until 'preflight prepare' succeeds."
            fi
        fi
    fi

    # The direct protocol has no launcher phase, so there is no launcher-ready duration to
    # record. Null is the honest value; a zero would read as "the launcher was instantly ready".
    local launcher_ms=null
    if [[ -f "$launcher_detection" ]]; then
        launcher_ms="$(jq -c '.launcherReadyMs // null' "$launcher_detection")"
    fi
    record_run "$condition" "$iteration" "$status" "$reason" "$menu_ms" \
        "$launcher_ms" "$exit_code"
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
    local first_line_ms="null"
    if [[ -f "$menu_detection" ]]; then
        start_instant="$(jq -c '.gameStartInstant // null' "$menu_detection")"
        ready_instant="$(jq -c '.mainMenuReadyInstant // null' "$menu_detection")"
        log_delta="$(jq -c '.gameLogMillisDelta // null' "$menu_detection")"
        trailing="$(jq -c '.trailingLogActivityMs // null' "$menu_detection")"
        first_line_ms="$(jq -c '.firstObservedLogLineToGraphicsPreloadMs // null' "$menu_detection")"
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
        --argjson firstLineMs "$first_line_ms" \
        --arg protocol "$PROTOCOL" \
        '{condition: $condition, iteration: $iteration, status: $status,
          protocol: $protocol,
          reason: (if $reason == "" then null else $reason end),
          gameLogStartToMainMenuMs: $menuMs, launcherReadyMs: $launcherMs,
          trailingLogActivityMs: $trailing,
          firstObservedLogLineToGraphicsPreloadMs: $firstLineMs,
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
    reply=""
    confirm "Press Enter to run the settling launch (or type skip): " reply
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
            retry=""
            confirm "Press Enter to retry it, or type skip to go on anyway: " retry
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
if [[ "$PROTOCOL" == direct ]]; then
cat <<'PROTOCOL'
Nothing to do per launch. The game starts itself and is stopped once its own log says the
load finished, so you can leave this running.

The order of conditions is shuffled inside every round so that thermal drift and background
load cannot line up with any one condition.
PROTOCOL
else
cat <<'PROTOCOL'
Per launch you do two things, each announced with a bell:
  1. click Play Starsector
  2. quit from the main menu once it is up

Do not load a save. The order of conditions is shuffled inside every round so that
thermal drift and background load cannot line up with any one condition.
PROTOCOL
fi
confirm "Press Enter to begin, or Ctrl-C to stop: "

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
