#!/usr/bin/env bash
#
# Launch one manually played campaign, save, and combat pilot with every relevant beta probe enabled.
#
# Usage:
#   scripts/run-gameplay-pilot.sh --disposable-save DIRECTORY [--game DIR] [--label NAME] [--safer-jvm] [--without-audio-repair] [--without-profile] [--without-adapter] [--disable-plans IDS]
#
# Load a disposable copy of a representative campaign, exercise campaign and combat play, save and
# reload that copy, then exit Starsector normally. Preflight keeps a coherent JFR and reports whether
# each exact adapter applied, how often it ran, and what its measured paths cost.
set -euo pipefail

GAME="${STARSECTOR_HOME:-/Applications/Starsector.app}"
LABEL="gameplay-pilot"
STARTUP_CACHES=true
GAMEPLAY_CACHES=true
SAFER_JVM=false
AUDIO_REPAIR=true
PROFILE=true
ADAPTER=true
DISABLED_PLANS=""
DISPOSABLE_SAVE=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --game) GAME="$2"; shift 2 ;;
        --label) LABEL="$2"; shift 2 ;;
        --disposable-save) DISPOSABLE_SAVE="$2"; shift 2 ;;
        --without-startup-caches) STARTUP_CACHES=false; shift ;;
        --without-gameplay-caches) GAMEPLAY_CACHES=false; shift ;;
        --safer-jvm) SAFER_JVM=true; shift ;;
        --without-audio-repair) AUDIO_REPAIR=false; shift ;;
        --without-profile) PROFILE=false; shift ;;
        --without-adapter) ADAPTER=false; shift ;;
        --disable-plans) DISABLED_PLANS="$2"; shift 2 ;;
        -h|--help) sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "Unknown option: $1" >&2; exit 2 ;;
    esac
done

if [[ "$ADAPTER" != true ]]; then
    STARTUP_CACHES=false
    GAMEPLAY_CACHES=false
    AUDIO_REPAIR=false
    PROFILE=false
fi

for required_command in git java mvn jq python3 pgrep lsof ps awk sed tr sort grep head mv xargs seq sleep date; do
    command -v "$required_command" >/dev/null 2>&1 || {
        echo "Required command is unavailable: $required_command" >&2
        exit 1
    }
done

[[ -f pom.xml ]] || { echo "Run this from the Preflight repository root." >&2; exit 1; }
[[ -d "$GAME" ]] || { echo "Starsector installation not found: $GAME" >&2; exit 1; }
[[ -n "$DISPOSABLE_SAVE" ]] || {
    echo "Name the disposable campaign directory with --disposable-save save_Name_123." >&2
    echo "The pilot refuses to start without an exact save boundary." >&2
    exit 2
}

JAR="$PWD/preflight-cli/target/preflight.jar"
PREFLIGHT_STATE_ROOT="${STARSECTOR_PREFLIGHT_HOME:-$HOME/.starsector-preflight}"
PILOT_RUNS_ROOT="$PREFLIGHT_STATE_ROOT/runs"
OUT="$PILOT_RUNS_ROOT/$LABEL-$(date +%Y%m%d-%H%M%S)"
SAVES_DIRECTORY="$GAME/saves"
SAVE_GUARD="$PWD/scripts/save_state_guard.py"
SAVE_STATE_BEFORE="$OUT/save-state-before.json"
SAVE_STATE_AFTER="$OUT/save-state-after.json"
WRAPPER_PID=""
DUPLICATE_WATCHER_PID=""
OWNED_PID_FILE="$OUT/owned-game-pids"

game_pids() {
    local resolved pid cwd
    resolved="$(cd "$GAME" && pwd -P)"
    for pid in $(pgrep -x java 2>/dev/null; pgrep -f '[j]ava$|/java ' 2>/dev/null); do
        [[ "$pid" == "$$" ]] && continue
        cwd="$(lsof -a -p "$pid" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p' | head -1)"
        [[ -n "$cwd" && ( "$cwd" == "$resolved" || "$cwd" == "$resolved/"* ) ]] && echo "$pid"
    done | sort -u
}

is_descendant_of() {
    local pid="$1" ancestor="$2" parent
    while [[ "$pid" =~ ^[0-9]+$ && "$pid" -gt 1 ]]; do
        [[ "$pid" == "$ancestor" ]] && return 0
        parent="$(ps -o ppid= -p "$pid" 2>/dev/null | tr -d '[:space:]')"
        [[ -n "$parent" && "$parent" != "$pid" ]] || break
        pid="$parent"
    done
    return 1
}

owned_game_pids() {
    local pid
    [[ -n "$WRAPPER_PID" ]] || return 0
    while read -r pid; do
        [[ -n "$pid" ]] || continue
        is_descendant_of "$pid" "$WRAPPER_PID" && echo "$pid"
    done < <(game_pids)
}

foreign_game_pids() {
    local pid
    while read -r pid; do
        [[ -n "$pid" ]] || continue
        if [[ -z "$WRAPPER_PID" ]] || ! is_descendant_of "$pid" "$WRAPPER_PID"; then
            echo "$pid"
        fi
    done < <(game_pids)
}

remember_owned_game_pids() {
    local temporary="$OWNED_PID_FILE.tmp" pid started
    : >"$temporary"
    while read -r pid; do
        [[ -n "$pid" ]] || continue
        started="$(ps -o lstart= -p "$pid" 2>/dev/null | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')"
        [[ -n "$started" ]] && printf '%s\t%s\n' "$pid" "$started" >>"$temporary"
    done < <(owned_game_pids)
    mv "$temporary" "$OWNED_PID_FILE"
}

remembered_owned_game_pids() {
    local live pid recorded_start current_start
    [[ -f "$OWNED_PID_FILE" ]] || return 0
    live="$(game_pids)"
    while IFS=$'\t' read -r pid recorded_start; do
        [[ -n "$pid" ]] || continue
        current_start="$(ps -o lstart= -p "$pid" 2>/dev/null | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')"
        if [[ -n "$recorded_start" && "$current_start" == "$recorded_start" ]] \
            && grep -qx "$pid" <<<"$live"; then
            echo "$pid"
        fi
    done <"$OWNED_PID_FILE"
}

watch_for_second_instance() {
    local pids
    while kill -0 "$WRAPPER_PID" 2>/dev/null; do
        remember_owned_game_pids
        pids="$(foreign_game_pids)"
        if [[ -n "$pids" ]]; then
            echo "A second Starsector instance appeared (foreign pids: $(echo "$pids" | tr '\n' ' '))." >&2
            echo "Stopping only this pilot's owned process tree." >&2
            kill -TERM "$WRAPPER_PID" 2>/dev/null || true
            return
        fi
        sleep 1
    done
}

cleanup() {
    local status=$? pids
    [[ -n "$DUPLICATE_WATCHER_PID" ]] && kill -TERM "$DUPLICATE_WATCHER_PID" 2>/dev/null || true
    pids="$({ owned_game_pids; remembered_owned_game_pids; } | sort -u)"
    if [[ -n "$pids" ]]; then
        echo "Stopping the pilot-owned Starsector process so the recording and reports flush..."
        echo "$pids" | xargs -r kill -TERM 2>/dev/null || true
        for _ in $(seq 1 15); do
            [[ -z "$({ owned_game_pids; remembered_owned_game_pids; } | sort -u)" ]] && break
            sleep 1
        done
        pids="$({ owned_game_pids; remembered_owned_game_pids; } | sort -u)"
        if [[ -n "$pids" ]]; then
            echo "The pilot-owned Starsector process did not stop after 15 seconds; forcing shutdown." >&2
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

echo "Disposable campaign: $DISPOSABLE_SAVE"
echo "Only this named save may change during the pilot; saves/common is global mod state and is excluded."
save_confirmation=""
read -r -p "Type $DISPOSABLE_SAVE to confirm it is a disposable copy: " save_confirmation || true
if [[ "$save_confirmation" != "$DISPOSABLE_SAVE" ]]; then
    echo "Disposable-save confirmation did not match; nothing was launched." >&2
    exit 2
fi

mkdir -p "$PILOT_RUNS_ROOT"
mkdir "$OUT" || {
    echo "Pilot directory already exists; choose another --label or wait for a new timestamp: $OUT" >&2
    exit 1
}
python3 "$SAVE_GUARD" snapshot \
    --saves-dir "$SAVES_DIRECTORY" \
    --selected "$DISPOSABLE_SAVE" \
    --output "$SAVE_STATE_BEFORE"

echo "Building the combined pilot..."
mvn -q -DskipTests package
[[ -f "$JAR" ]] || { echo "Runnable JAR was not produced: $JAR" >&2; exit 1; }
PILOT_SOURCE_REVISION="$(git rev-parse HEAD)"
PILOT_SOURCE_DIRTY=false
[[ -n "$(git status --porcelain --untracked-files=normal)" ]] && PILOT_SOURCE_DIRTY=true

LAUNCHER="$(java -jar "$JAR" doctor --game "$GAME" 2>/dev/null \
    | awk '/^Selected: /{print substr($0, 11); exit}')"
[[ -n "$LAUNCHER" && -f "$LAUNCHER" ]] \
    || { echo "Could not resolve the launcher under $GAME" >&2; exit 1; }

echo
echo "Pilot directory: $OUT"
echo "Pilot commit:    $(git rev-parse --short HEAD)"
echo "Disposable save: $DISPOSABLE_SAVE"
echo "Startup caches:  $STARTUP_CACHES"
echo "Gameplay caches: $GAMEPLAY_CACHES"
echo "Safer JVM:        $SAFER_JVM"
echo "Audio repair:     $AUDIO_REPAIR"
echo "Profile:          $PROFILE"
echo "Adapter:          $ADAPTER"
echo "Disabled plans:   ${DISABLED_PLANS:-none}"
echo
echo "In Starsector:"
echo "  1. Load a disposable copy of a representative campaign. Do not use the only copy of a save."
echo "  2. Roam the campaign map long enough to include its first 30 seconds and steady state."
echo "  3. Open a battle simulation, raise the DP limit, deploy lots of capitals, and fight for 3-5 minutes."
echo "  4. Save the disposable campaign, return to the title screen, and reload it."
echo "  5. Confirm campaign play resumes normally, then exit Starsector normally."
echo
echo "Launching now; wrapper output is being saved to $OUT/wrapper.log"

# Some third-party launch scripts enable HotSpot's interactive native-crash debugger prompt. On
# macOS that leaves the already-crashed JVM behind an unresponsive 0% game window until someone
# force-quits it, and killing that prompt also prevents HotSpot from writing its hs_err evidence.
# _JAVA_OPTIONS is intentionally used here: HotSpot applies it after command-line flags, so this
# overrides a launcher's earlier +ShowMessageBoxOnError without editing the user's installation.
# Normal Preflight launches independently auto-gate Ship's cast-site exclusions against the exact
# known-risk launcher/runtime/class fingerprint; --safer-jvm remains as a manual diagnostic override.
PILOT_CRASH_REPORT="$OUT/hs_err_pid%p.log"
# Frame telemetry is independent of JFR and intentionally remains available in --without-profile
# pilots. That gives Rosetta launches a safe FPS/1%-low path when HotSpot's sampling profiler itself
# triggers the known sharedRuntime safepoint assertion.
PILOT_CRASH_OPTIONS="-XX:-ShowMessageBoxOnError -XX:ErrorFile='$PILOT_CRASH_REPORT' -Dpreflight.frameTimes=true -Dpreflight.campaignTimes=true"
if [[ "$SAFER_JVM" == true ]]; then
    # Diagnostic only: interpret the exact vanilla method that produced an otherwise impossible
    # ClassCastException. Verification must remain disabled: the shipped obfuscated core contains
    # identifiers such as "for.Object" that Java 17 rejects before the title screen. Nothing here
    # edits the installation.
    PILOT_CRASH_OPTIONS+=" -XX:CompileCommand=exclude,com/fs/starfarer/combat/entities/Ship.advance"
    PILOT_CRASH_OPTIONS+=" -XX:CompileCommand=exclude,com/fs/starfarer/combat/entities/Ship.render"
    PILOT_CRASH_OPTIONS+=" -Dpreflight.combatIntegrity.jvmMode=ship-cast-sites-interpreted"
fi
if [[ "$AUDIO_REPAIR" != true ]]; then
    PILOT_CRASH_OPTIONS+=" -Dpreflight.audioStreamSourceError.disabled=true"
fi
if [[ -n "$DISABLED_PLANS" ]]; then
    PILOT_CRASH_OPTIONS+=" -Dpreflight.adapter.disabledPlans=$DISABLED_PLANS"
fi
export _JAVA_OPTIONS="${_JAVA_OPTIONS:+$_JAVA_OPTIONS }$PILOT_CRASH_OPTIONS"

RUN_ARGS=(run \
    --game "$GAME" \
    --launcher "$LAUNCHER" \
    --trace-dir "$OUT" \
    --direct)
if [[ "$ADAPTER" == true ]]; then
    RUN_ARGS+=(--adapter)
else
    RUN_ARGS+=(--no-adapter)
fi
if [[ "$STARTUP_CACHES" == true ]]; then
    RUN_ARGS+=(--fast)
fi
if [[ "$PROFILE" == true ]]; then
    RUN_ARGS+=(--profile --single-chunk-recording --startup-phase-probe)
else
    RUN_ARGS+=(--no-record)
fi
if [[ "$GAMEPLAY_CACHES" == true && "$STARTUP_CACHES" != true ]]; then
    # --fast already includes this; the explicit flag keeps gameplay-only isolation available.
    RUN_ARGS+=(--campaign-entity-index)
elif [[ "$GAMEPLAY_CACHES" != true && "$STARTUP_CACHES" == true ]]; then
    RUN_ARGS+=(--no-campaign-entity-index)
fi

java -jar "$JAR" "${RUN_ARGS[@]}" \
    >"$OUT/wrapper.log" 2>&1 &
WRAPPER_PID=$!
watch_for_second_instance &
DUPLICATE_WATCHER_PID=$!

set +e
wait "$WRAPPER_PID"
PILOT_STATUS=$?
set -e
kill -TERM "$DUPLICATE_WATCHER_PID" 2>/dev/null || true
DUPLICATE_WATCHER_PID=""

# A normal game exit leaves no process. If a launcher/wrapper failed while its child survived,
# cleanup still owns that child rather than leaking several gigabytes and a GPU context.
cleanup
WRAPPER_PID=""
trap - EXIT INT TERM

set +e
python3 "$SAVE_GUARD" compare \
    --before "$SAVE_STATE_BEFORE" \
    --saves-dir "$SAVES_DIRECTORY" \
    --output "$SAVE_STATE_AFTER"
SAVE_GUARD_STATUS=$?
set -e

echo
echo "Pilot process exit: $PILOT_STATUS"
if [[ -f "$SAVE_STATE_AFTER" ]]; then
    echo "Campaign save boundary:"
    jq '{accepted, selectedSave, selectedSaveChanged, otherCampaignSavesUnchanged, changedCampaignSaves, unexpectedChangedCampaignSaves, reasons}' \
        "$SAVE_STATE_AFTER"
else
    echo "Campaign save boundary was not produced." >&2
fi
for crash_report in "$OUT"/hs_err_pid*.log; do
    if [[ -f "$crash_report" ]]; then
        echo "Native JVM crash report: $crash_report" >&2
    fi
done
if [[ -f "$OUT/adapter-health.json" ]]; then
    echo "Adapter health:"
    jq '{status, summary, transformationsApplied, transformationsDeclined, containedFailures}' \
        "$OUT/adapter-health.json"
fi
if [[ -f "$OUT/adapter.json" ]]; then
    echo "Probe telemetry:"
    jq '{preparedAudio, audioStreamSourceError, audioResourceFallback, audioMusicTransitions, aiTweaksEngagementRange, graphicsLibCompactReplay, janinoBytecodeCache, graphicsLibInsigniaManagerCache, graphicsLibHotSettings, magicLibPaintjob, magicLibPaintjobNotification, stelnetMarketUpdater, macMemoryWarning, combatRuntimeIntegrity, frameTimes: (.frameTimes | .allActive |= del(.worstFrames) | .postStartupActive |= del(.worstFrames) | .campaignActive |= del(.worstFrames) | .combatActive |= del(.worstFrames)), campaignCallTimes, campaignEngineTimes, campaignLocationEconomyTimes, campaignMarketFleetTimes, campaignEntityMaintenance, fleetAiProfiler, campaignEntityIndex, campaignRadarRender, deploymentIconCache, commodityEventModMemo, simOpponentSafety}' \
        "$OUT/adapter.json"
else
    echo "No adapter report was produced; inspect $OUT/wrapper.log" >&2
fi

RELOAD_ATTESTED=false
if [[ "$PILOT_STATUS" -eq 0 && "$SAVE_GUARD_STATUS" -eq 0 ]]; then
    reload_confirmation=""
    echo
    read -r -p "If this save returned to the title screen, reloaded, resumed play, and exited normally, type SAVE RELOAD VERIFIED: " reload_confirmation || true
    [[ "$reload_confirmation" == "SAVE RELOAD VERIFIED" ]] && RELOAD_ATTESTED=true
fi
set +e
python3 "$SAVE_GUARD" attest \
    --before "$SAVE_STATE_BEFORE" \
    --after "$SAVE_STATE_AFTER" \
    --engine "$JAR" \
    --run "$OUT/run.json" \
    --profile-report "$OUT/profile.json" \
    --adapter-report "$OUT/adapter.json" \
    --adapter-health "$OUT/adapter-health.json" \
    --selected "$DISPOSABLE_SAVE" \
    --source-revision "$PILOT_SOURCE_REVISION" \
    --source-dirty "$PILOT_SOURCE_DIRTY" \
    --process-exit-status "$PILOT_STATUS" \
    --reload-attested "$RELOAD_ATTESTED" \
    --recorded-at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    --startup-caches "$STARTUP_CACHES" \
    --gameplay-caches "$GAMEPLAY_CACHES" \
    --safer-jvm "$SAFER_JVM" \
    --audio-repair "$AUDIO_REPAIR" \
    --profile "$PROFILE" \
    --adapter "$ADAPTER" \
    --disabled-plans "$DISABLED_PLANS" \
    --output "$OUT/operator-attestation.json"
ATTESTATION_STATUS=$?
set -e
if [[ "$ATTESTATION_STATUS" -eq 2 ]]; then
    echo "The save/reload attestation could not be bound to this pilot's evidence." >&2
elif [[ "$ATTESTATION_STATUS" -eq 1 ]]; then
    echo "Bound an incomplete pilot attestation; inspect its reasons before using this run as evidence." >&2
else
    echo "Bound complete save/reload and route attestation: $OUT/operator-attestation.json"
fi
if [[ "$RELOAD_ATTESTED" != true ]]; then
    echo "Save/reload/resume was not attested; this pilot is not complete lifecycle evidence." >&2
fi
echo "Full pilot data: $OUT"

FINAL_STATUS="$PILOT_STATUS"
if [[ "$FINAL_STATUS" -eq 0 && "$SAVE_GUARD_STATUS" -ne 0 ]]; then
    FINAL_STATUS="$SAVE_GUARD_STATUS"
fi
if [[ "$FINAL_STATUS" -eq 0 && "$RELOAD_ATTESTED" != true ]]; then
    FINAL_STATUS=1
fi
if [[ "$FINAL_STATUS" -eq 0 && "$ATTESTATION_STATUS" -ne 0 ]]; then
    FINAL_STATUS="$ATTESTATION_STATUS"
fi
exit "$FINAL_STATUS"
