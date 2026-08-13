#!/usr/bin/env bash
set -euo pipefail

GAME="${GAME:-/Applications/Starsector.app}"
CACHE="${CACHE:-$HOME/.starsector-preflight/cache}"
ORDER="${ORDER:-}"
JAR="$PWD/preflight-cli/target/preflight.jar"
DETECTOR="$PWD/scripts/starsector_log_ready_detector.py"
PROFILE_GUARD="$PWD/scripts/starsector_profile_guard.py"
CORE_RESOURCE_GUARD="$PWD/scripts/starsector_core_resource_guard.py"
DIAGNOSTIC_PROPERTY="-Dpreflight.preparedPixels.coherentDirect=true"
EXPECTED_ARCHIVE_SHA="10d89e113f6d1627cc7bc90b692e8a7f450fdd820c5a4ac5edaecd6710afe708"
EXPECTED_CLASS_SHA="d8fcb4cb90d457fc3075e711b6293940774dcf990ea66a7584c231bd96898b50"
ACCEPTED_GAMEPLAY_ARCHIVE_SHA="cbc9f5884d89f69e93f6b0ca882c911fdb0cb43397932b77b191920ded0a11bf"
AXIS_CONTRACT='return new DimensionSetters(setters.get(1), setters.get(0));'
MIN_RUNTIME_SECONDS=30
LAUNCHER_TIMEOUT_SECONDS=60
LAUNCHER_QUIET_SECONDS=6
MAIN_MENU_TIMEOUT_SECONDS=300
MAIN_MENU_QUIET_SECONDS=6
MUTABLE_RESTORE_AUTHORIZED=false
FINAL_MUTABLE_RESTORE_DONE=false

for command in git java mvn jq shasum unzip tar grep python3; do
    if ! command -v "$command" >/dev/null 2>&1; then
        echo "Missing required command: $command" >&2
        exit 1
    fi
done

if [[ ! -f pom.xml ]]; then
    echo "Run this script from the Preflight repository root." >&2
    exit 1
fi
for helper in "$DETECTOR" "$PROFILE_GUARD" "$CORE_RESOURCE_GUARD"; do
    if [[ ! -f "$helper" ]]; then
        echo "Comparison helper not found: $helper" >&2
        exit 1
    fi
done
if [[ ! -d "$GAME" ]]; then
    echo "Starsector installation not found: $GAME" >&2
    exit 1
fi

CORE_RESOURCE_JSON="$(python3 "$CORE_RESOURCE_GUARD" --game "$GAME")"
CORE_RESOURCE_ROOT="$(jq -er '.resourceRoot' <<<"$CORE_RESOURCE_JSON")"
CORE_MISSION_LIST="$(jq -er '.missionList' <<<"$CORE_RESOURCE_JSON")"
CORE_MISSION_DESCRIPTOR="$(jq -er '.missionDescriptor' <<<"$CORE_RESOURCE_JSON")"
echo "Reviewed core mission resource root: $CORE_RESOURCE_ROOT"
for core_resource in "$CORE_MISSION_LIST" "$CORE_MISSION_DESCRIPTOR"; do
    if [[ ! -f "$core_resource" ]]; then
        echo "Reviewed core mission resource is missing: $core_resource" >&2
        exit 1
    fi
done

PLAN_SOURCE="preflight-agent/src/main/java/dev/starsector/preflight/agent/TexturePreparedPixelPlan.java"
if [[ ! -f "$PLAN_SOURCE" ]] || ! grep -Fq "$AXIS_CONTRACT" "$PLAN_SOURCE"; then
    echo "This checkout does not contain the reviewed height-first/width-second axis mapping." >&2
    echo "Run: git switch main && git pull --ff-only" >&2
    exit 1
fi

if [[ -z "$ORDER" ]]; then
    ORDER="$(python3 - <<'PYORDER'
import secrets
print(secrets.choice(("compatibility,prepared", "prepared,compatibility")))
PYORDER
)"
fi

IFS=',' read -r first_mode second_mode extra_mode <<< "$ORDER"
if [[ -n "${extra_mode:-}" \
        || "$first_mode" == "$second_mode" \
        || ( "$first_mode" != compatibility && "$first_mode" != prepared ) \
        || ( "$second_mode" != compatibility && "$second_mode" != prepared ) ]]; then
    echo "ORDER must be compatibility,prepared or prepared,compatibility." >&2
    exit 1
fi

STAMP="$(date -u +%Y%m%d-%H%M%S)"
REPORT_DIR="$CACHE/reports"
CONTRACT_REPORT="$REPORT_DIR/prepared-pixel-main-menu-comparison-contract-$STAMP.json"
PREP_REPORT="$REPORT_DIR/prepared-pixel-main-menu-comparison-preparation-$STAMP.json"
ROOT_DIR="$HOME/.starsector-preflight/runs/prepared-pixel-main-menu-comparison-pilot-$STAMP"
ARCHIVE_OUT="$HOME/Desktop/$(basename "$ROOT_DIR").tar.gz"
mkdir -p "$REPORT_DIR" "$ROOT_DIR"

REPOSITORY_HEAD="$(git rev-parse HEAD)"
echo "Repository HEAD: $REPOSITORY_HEAD"

echo
echo "== Building and verifying the checkout =="
mvn --batch-mode --no-transfer-progress verify

if [[ ! -f "$JAR" ]]; then
    echo "Runnable JAR was not produced: $JAR" >&2
    exit 1
fi
JAR_SHA="$(shasum -a 256 "$JAR" | awk '{print $1}')"
echo "Preflight JAR SHA-256: $JAR_SHA"

echo
echo "== Checking launcher discovery =="
java -jar "$JAR" doctor --game "$GAME"

ARCHIVE="$GAME/Contents/Resources/Java/fs.common_obf.jar"
if [[ ! -f "$ARCHIVE" ]]; then
    echo "Installed Starsector archive not found: $ARCHIVE" >&2
    exit 1
fi
ARCHIVE_SHA="$(shasum -a 256 "$ARCHIVE" | awk '{print $1}')"
CLASS_SHA="$(unzip -p "$ARCHIVE" com/fs/graphics/TextureLoader.class | shasum -a 256 | awk '{print $1}')"

if [[ "$ARCHIVE_SHA" != "$EXPECTED_ARCHIVE_SHA" ]]; then
    echo "Installed archive identity does not match the reviewed target." >&2
    exit 1
fi
if [[ "$CLASS_SHA" != "$EXPECTED_CLASS_SHA" ]]; then
    echo "Installed TextureLoader identity does not match the reviewed target." >&2
    exit 1
fi

echo
echo "== Running the offline prepared-pixel contract check =="
set +e
java -cp "$JAR" dev.starsector.preflight.agent.PreparedPixelContractCheck \
    "$ARCHIVE" > "$CONTRACT_REPORT"
checker_status=$?
set -e
cat "$CONTRACT_REPORT"
if [[ "$checker_status" -ne 0 ]]; then
    echo "Prepared-pixel contract check declined with exit $checker_status." >&2
    exit "$checker_status"
fi

echo
echo "== Preparing the exact current profile =="
java -jar "$JAR" prepare \
    --game "$GAME" \
    --cache-dir "$CACHE" \
    --report "$PREP_REPORT" \
    --deep \
    --verify-lookups

jq -e '
    .successful == true
    and .readiness.preparedPixelsAdapter == "pot-bypass-enabled-npot-coherent-direct-gameplay-accepted-opt-in"
    and .readiness.preparedPixelsBehavioralAcceptance == "accepted-2026-07-23-exact-profile"
    and .readiness.preparedPixelsDefaultEnablement == "blocked-pending-main-menu-comparison-and-repeat-timing"
    and .readiness.preparedPixelsComparisonPilotRequired == true
    and .readiness.preparedPixelsNextOperatorAction == "single-main-menu-comparison-pilot"
    and .readiness.repeatTimingCampaignRequired == true
    and .readiness.launchAccelerationClaimed == false
' "$PREP_REPORT" >/dev/null

INSTALL_ROOT="$(jq -er '.installRoot' "$PREP_REPORT")"
LAUNCHER="$(jq -er '.launcher' "$PREP_REPORT")"
CACHE_DIRECTORY="$(jq -er '.cacheDirectory' "$PREP_REPORT")"
RESOURCE_INDEX="$(jq -er '.resourceIndex' "$PREP_REPORT")"
TEXTURE_MANIFEST="$(jq -er '.stages.textures.details.manifest' "$PREP_REPORT")"
BASE_PROFILE="$ROOT_DIR/operator-preparation-profile.json"
jq '.stages.census.details.profile' "$PREP_REPORT" > "$BASE_PROFILE"
EXPECTED_PROFILE_FINGERPRINT="$(jq -er '.profileFingerprint' "$BASE_PROFILE")"
BASE_PROFILE_STATE="$ROOT_DIR/operator-profile-state.json"
MUTABLE_STATE_SNAPSHOT="$CACHE/comparison-state-snapshots/$STAMP"
python3 "$PROFILE_GUARD" capture \
    --profile-report "$PREP_REPORT" \
    --output "$BASE_PROFILE_STATE" \
    --snapshot-dir "$MUTABLE_STATE_SNAPSHOT"
EXPECTED_IMMUTABLE_FINGERPRINT="$(jq -er '.immutableProfile.fingerprint' "$BASE_PROFILE_STATE")"
GRAPHICSLIB_CACHE_DIRECTORY="$(jq -er '.mutableCaches[0].cacheDirectory' "$BASE_PROFILE_STATE")"
GRAPHICSLIB_HASH_CONTROL="$(jq -er '.mutableCaches[0].hashControlFile' "$BASE_PROFILE_STATE")"
CORE_MISSION_LIST_SHA="$(shasum -a 256 "$CORE_MISSION_LIST" | awk '{print $1}')"
CORE_MISSION_DESCRIPTOR_SHA="$(shasum -a 256 "$CORE_MISSION_DESCRIPTOR" | awk '{print $1}')"
LOG_DIR="$INSTALL_ROOT/logs"
if [[ ! -d "$LOG_DIR" ]]; then
    echo "Starsector log directory not found: $LOG_DIR" >&2
    exit 1
fi

for file in "$RESOURCE_INDEX" "$TEXTURE_MANIFEST"; do
    if [[ ! -f "$file" ]]; then
        echo "Preparation artifact does not exist: $file" >&2
        exit 1
    fi
done

cat <<CACHE_RESET_NOTICE

The comparison must reset one proven mutable GraphicsLib runtime state so both
randomized halves start from identical pre-warmed bytes and timestamps.

Exact installation paths that may be mutated:
  cache directory: $GRAPHICSLIB_CACHE_DIRECTORY
  hash control:    $GRAPHICSLIB_HASH_CONTROL

Read-only recovery snapshot:
  $MUTABLE_STATE_SNAPSHOT

Before each half and once at the end, the guard may:
  - delete only newly generated files in the exact GraphicsLib cache directory
    whose names match GraphicsLib's reviewed *_normal.png grammar;
  - atomically replace changed or missing reviewed cache files from the snapshot;
  - restore their recorded modes and nanosecond mtimes; and
  - atomically restore shaderlib_cache_hash.data from the snapshot.

It refuses to mutate if the cache contains a symlink, subdirectory, or
unrecognized file. It never mutates any other mod, core resource, save, or game
file. Exact before/after names, sizes, hashes, and timestamps remain in evidence.
CACHE_RESET_NOTICE
read -r -p "Type RESTORE GRAPHICSLIB CACHE to permit those exact reversible mutations: " cache_confirmation
if [[ "$cache_confirmation" != "RESTORE GRAPHICSLIB CACHE" ]]; then
    echo "Mutable-cache restore permission was not granted; no installation files were changed." >&2
    exit 1
fi
MUTABLE_RESTORE_AUTHORIZED=true

cp "$CONTRACT_REPORT" "$ROOT_DIR/operator-contract.json"
cp "$PREP_REPORT" "$ROOT_DIR/operator-preparation.json"
cat > "$ROOT_DIR/operator-comparison-identity.txt" <<IDENTITY
repositoryHead=$REPOSITORY_HEAD
jarSha256=$JAR_SHA
archiveSha256=$ARCHIVE_SHA
classSha256=$CLASS_SHA
acceptedGameplayArchiveSha256=$ACCEPTED_GAMEPLAY_ARCHIVE_SHA
dimensionReplay=reviewed-converter-height-first-width-second
scope=main-menu-comparison-pilot
order=$ORDER
samplesPerMode=1
timingMethod=automatic-starsector-log-phase-detection
expectedProfileFingerprint=$EXPECTED_PROFILE_FINGERPRINT
expectedImmutableProfileFingerprint=$EXPECTED_IMMUTABLE_FINGERPRINT
graphicsLibCacheDirectory=$GRAPHICSLIB_CACHE_DIRECTORY
graphicsLibHashControl=$GRAPHICSLIB_HASH_CONTROL
mutableStateSnapshot=$MUTABLE_STATE_SNAPSHOT
mutableStateRestoreAuthorized=true
coreMissionList=$CORE_MISSION_LIST
coreMissionListSha256=$CORE_MISSION_LIST_SHA
coreMissionDescriptor=$CORE_MISSION_DESCRIPTOR
coreMissionDescriptorSha256=$CORE_MISSION_DESCRIPTOR_SHA
launcherQuietConfirmationSeconds=$LAUNCHER_QUIET_SECONDS
benchmarkAccepted=false
IDENTITY

now_ns() {
    python3 - <<'PY'
import time
print(time.monotonic_ns())
PY
}

snapshot_logs() {
    python3 "$DETECTOR" snapshot --log-dir "$LOG_DIR" --output "$1"
}

extract_log_delta() {
    python3 "$DETECTOR" extract --log-dir "$LOG_DIR" --snapshot "$1" --output "$2"
}

classify_log_delta() {
    python3 "$DETECTOR" classify --input "$1" --output "$2"
}

restore_mutable_state() {
    local label="$1"
    local output="$2"
    if [[ "$MUTABLE_RESTORE_AUTHORIZED" != true ]]; then
        echo "Mutable-cache restore was not explicitly authorized ($label)." >&2
        return 1
    fi
    if ! python3 "$PROFILE_GUARD" restore \
            --baseline "$BASE_PROFILE_STATE" \
            --snapshot-dir "$MUTABLE_STATE_SNAPSHOT" \
            --output "$output"; then
        echo "The bounded GraphicsLib state could not be restored exactly ($label)." >&2
        [[ -f "$output" ]] && jq . "$output" >&2
        return 1
    fi
}

emergency_restore_mutable_state() {
    if [[ "$MUTABLE_RESTORE_AUTHORIZED" == true \
            && "$FINAL_MUTABLE_RESTORE_DONE" != true \
            && -f "$BASE_PROFILE_STATE" \
            && -d "$MUTABLE_STATE_SNAPSHOT" ]]; then
        echo "Restoring the authorized GraphicsLib state before exit..." >&2
        restore_mutable_state "exit-trap" "$ROOT_DIR/final-mutable-cache-restore.json" || true
    fi
}
trap emergency_restore_mutable_state EXIT

check_profile_stability() {
    local label="$1"
    local report="$2"
    local drift="$3"

    java -jar "$JAR" prepare \
        --game "$GAME" \
        --cache-dir "$CACHE" \
        --report "$report" \
        --deep \
        --verify-lookups >/dev/null

    local mission_list_sha mission_descriptor_sha
    mission_list_sha="$(shasum -a 256 "$CORE_MISSION_LIST" | awk '{print $1}')"
    mission_descriptor_sha="$(shasum -a 256 "$CORE_MISSION_DESCRIPTOR" | awk '{print $1}')"
    if [[ "$mission_list_sha" != "$CORE_MISSION_LIST_SHA" \
            || "$mission_descriptor_sha" != "$CORE_MISSION_DESCRIPTOR_SHA" ]]; then
        jq -n \
            --arg label "$label" \
            --arg expectedMissionListSha256 "$CORE_MISSION_LIST_SHA" \
            --arg actualMissionListSha256 "$mission_list_sha" \
            --arg expectedMissionDescriptorSha256 "$CORE_MISSION_DESCRIPTOR_SHA" \
            --arg actualMissionDescriptorSha256 "$mission_descriptor_sha" \
            '{
                stable: false,
                immutableProfileStable: false,
                error: "reviewed-core-mission-resources-changed",
                label: $label,
                coreMissionList: {
                    expectedSha256: $expectedMissionListSha256,
                    actualSha256: $actualMissionListSha256
                },
                coreMissionDescriptor: {
                    expectedSha256: $expectedMissionDescriptorSha256,
                    actualSha256: $actualMissionDescriptorSha256
                }
            }' > "$drift"
        echo "Reviewed core mission resources changed during the comparison ($label)." >&2
        return 1
    fi
    set +e
    python3 "$PROFILE_GUARD" check \
        --baseline "$BASE_PROFILE_STATE" \
        --current-report "$report" \
        --output "$drift"
    local guard_status=$?
    set -e
    if [[ "$guard_status" -ne 0 ]]; then
        echo "The immutable profile changed or the mutable-cache shape was unsafe ($label)." >&2
        [[ -f "$drift" ]] && jq . "$drift" >&2
        return 1
    fi
    if [[ "$(jq -r '.mutableCache.changedDuringRun' "$drift")" == true ]]; then
        echo "Recorded bounded GraphicsLib runtime-cache changes ($label):"
        jq '{
            sourceCensusFingerprintChanged,
            immutableProfileStable,
            mutableCache: {
                exactEquivalentToBaseline,
                contentEquivalentToBaseline,
                drift
            }
        }' "$drift"
    fi
}

terminate_wrapper() {
    local pid="$1"
    kill "$pid" >/dev/null 2>&1 || true
    wait "$pid" >/dev/null 2>&1 || true
}

ask_yes_no() {
    local prompt="$1"
    local reply
    while true; do
        read -r -p "$prompt [y/n]: " reply
        case "$reply" in
            y|Y|yes|YES|Yes) printf 'true\n'; return 0 ;;
            n|N|no|NO|No) printf 'false\n'; return 0 ;;
            *) echo "Please answer y or n." >&2 ;;
        esac
    done
}

run_mode() {
    local mode="$1"
    local run_dir="$ROOT_DIR/$mode"
    local before_logs="$run_dir/log-snapshot-before.json"
    local game_snapshot="$run_dir/log-snapshot-before-play.json"
    local launcher_detection="$run_dir/launcher-ready-detection.json"
    local main_menu_detection="$run_dir/main-menu-ready-detection.json"
    local log_delta="$run_dir/starsector-log-delta.txt"
    local log_classification="$run_dir/log-classification.json"
    local wrapper_output="$run_dir/operator-wrapper-output.txt"
    local result="$run_dir/operator-main-menu-result.json"
    local mutable_restore_before="$run_dir/mutable-cache-restore-before.json"
    local profile_before_report="$run_dir/profile-check-before.json"
    local profile_before_drift="$run_dir/profile-drift-before.json"
    local profile_after_report="$run_dir/profile-check-after.json"
    local profile_after_drift="$run_dir/profile-drift-after.json"
    local -a run_args
    local java_tool_options="${JAVA_TOOL_OPTIONS:-}"

    mkdir -p "$run_dir"
    if ! restore_mutable_state "before-$mode" "$mutable_restore_before"; then
        return 1
    fi
    if ! check_profile_stability "before-$mode" "$profile_before_report" "$profile_before_drift"; then
        return 1
    fi
    if [[ "$(jq -r '.mutableCache.exactEquivalentToBaseline' "$profile_before_drift")" != true ]]; then
        echo "The $mode half did not start from the exact restored GraphicsLib state." >&2
        return 1
    fi

    # Launch with the resource index and texture manifest that check_profile_stability just
    # validated for THIS half, not the artifacts captured from the initial preparation. The
    # deep prepare above regenerates an internally matched index/manifest pair; the initial
    # global pair can be stale by now (an earlier half's startup can mutate an indexed file).
    # The index and manifest must remain a matched pair, so both come from the same report.
    local half_resource_index half_texture_manifest artifact
    half_resource_index="$(jq -er '.resourceIndex' "$profile_before_report")"
    half_texture_manifest="$(jq -er '.stages.textures.details.manifest' "$profile_before_report")"
    for artifact in "$half_resource_index" "$half_texture_manifest"; do
        if [[ ! -f "$artifact" ]]; then
            echo "Validated per-half preparation artifact does not exist ($mode): $artifact" >&2
            return 1
        fi
    done
    snapshot_logs "$before_logs"

    run_args=(
        run
        --game "$INSTALL_ROOT"
        --launcher "$LAUNCHER"
        --trace-dir "$run_dir"
        --adapter
        --texture-mode "$([[ "$mode" == compatibility ]] && printf compatibility || printf prepared-pixels)"
        --texture-cache-dir "$CACHE_DIRECTORY"
        --texture-manifest "$half_texture_manifest"
        --texture-index "$half_resource_index"
    )
    if [[ "$mode" == prepared ]]; then
        java_tool_options="${java_tool_options:+$java_tool_options }$DIAGNOSTIC_PROPERTY"
    fi

    echo
    echo "== $mode dry-run plan =="
    JAVA_TOOL_OPTIONS="$java_tool_options" java -jar "$JAR" "${run_args[@]}" --dry-run
    echo
    echo "This is the $mode half of a two-run comparison pilot."
    echo "The script will detect launcher readiness and main-menu readiness from starsector.log."
    echo "Do not load a campaign. Exit from the main menu when instructed."
    read -r -p "Press Enter to launch the $mode run."

    local process_start_ns
    process_start_ns="$(now_ns)"
    set +e
    JAVA_TOOL_OPTIONS="$java_tool_options" java -jar "$JAR" "${run_args[@]}" >"$wrapper_output" 2>&1 &
    local wrapper_pid=$!
    set -e

    echo "Waiting for the launcher readiness marker..."
    if ! python3 "$DETECTOR" watch-launcher \
            --log-dir "$LOG_DIR" \
            --snapshot "$before_logs" \
            --output "$launcher_detection" \
            --pid "$wrapper_pid" \
            --process-start-ns "$process_start_ns" \
            --timeout-seconds "$LAUNCHER_TIMEOUT_SECONDS" \
            --quiet-seconds "$LAUNCHER_QUIET_SECONDS"; then
        echo "Automatic launcher readiness detection failed." >&2
        terminate_wrapper "$wrapper_pid"
        extract_log_delta "$before_logs" "$log_delta"
        classify_log_delta "$log_delta" "$log_classification"
        check_profile_stability "after-$mode-launcher-failure" \
            "$profile_after_report" "$profile_after_drift" || true
        return 1
    fi

    echo
    printf '\a'
    echo "Launcher readiness detected automatically after the fixed safety confirmation."
    jq '{launcherReadyMs, launcherReadyLogMillis, launcherMarker, quietConfirmationMillis}' "$launcher_detection"
    snapshot_logs "$game_snapshot"
    echo
    echo "Click Play Starsector now. Do not press Enter; the log watcher starts timing on the first new game log line."

    if ! python3 "$DETECTOR" watch-main-menu \
            --log-dir "$LOG_DIR" \
            --snapshot "$game_snapshot" \
            --output "$main_menu_detection" \
            --pid "$wrapper_pid" \
            --timeout-seconds "$MAIN_MENU_TIMEOUT_SECONDS" \
            --quiet-seconds "$MAIN_MENU_QUIET_SECONDS"; then
        echo "Automatic main-menu readiness detection failed." >&2
        terminate_wrapper "$wrapper_pid"
        extract_log_delta "$before_logs" "$log_delta"
        classify_log_delta "$log_delta" "$log_classification"
        check_profile_stability "after-$mode-main-menu-failure" \
            "$profile_after_report" "$profile_after_drift" || true
        return 1
    fi

    echo
    printf '\a'
    echo "Main-menu readiness detected automatically."
    jq '{gameLogStartToMainMenuMs, gameLogMillisDelta, saveDescriptorSeen, graphicsPreloadSeen, quietConfirmationMillis}' \
        "$main_menu_detection"
    echo "Confirm the main menu is fully visible and responsive, then exit Starsector from the main menu."
    echo "Close the launcher if it reappears."

    set +e
    wait "$wrapper_pid"
    local preflight_exit=$?
    set -e

    extract_log_delta "$before_logs" "$log_delta"
    classify_log_delta "$log_delta" "$log_classification"

    local profile_stable=true
    if ! check_profile_stability "after-$mode" "$profile_after_report" "$profile_after_drift"; then
        profile_stable=false
    fi

    # Three consolidated operator questions cover the six independent acceptance signals.
    # The visual question folds together launcher-normal, main-menu-normal, and texture
    # corruption (which were near-duplicates); the lifecycle question folds together
    # attached-until-exit and clean-exit. Each derived flag below still feeds the result
    # JSON and acceptance logic unchanged.
    local launcher_ok main_menu_ok detector_ok attached_ok clean_exit_ok corruption_seen
    local visuals_ok lifecycle_ok
    visuals_ok="$(ask_yes_no "Did the $mode launcher and main menu both look normal, with no black, sliced, repeated, stretched, missing, flipped, or worsening textures?")"
    detector_ok="$(ask_yes_no "Did the automatic main-menu notification fire only after the menu was fully visible and responsive?")"
    lifecycle_ok="$(ask_yes_no "Did the terminal command stay attached until Starsector exited cleanly, with no crash dialog?")"
    launcher_ok="$visuals_ok"
    main_menu_ok="$visuals_ok"
    corruption_seen="$([[ "$visuals_ok" == true ]] && printf false || printf true)"
    attached_ok="$lifecycle_ok"
    clean_exit_ok="$lifecycle_ok"

    local lifecycle_check=1 telemetry_check=0 log_check=1 launcher_detector_check=1 menu_detector_check=1
    local expected_texture_mode
    if [[ "$mode" == compatibility ]]; then
        expected_texture_mode="COMPATIBILITY"
    else
        expected_texture_mode="PREPARED_PIXELS"
    fi
    if [[ -f "$run_dir/run.json" ]]; then
        set +e
        jq -e '
            .outcome == "COMPLETED"
            and .exitCode == 0
            and .launcherExitCode == 0
            and .lifecycleEvidence.fatalDetected == false
            and .textureAdapterMode == $expectedTextureMode
            and ((.ended | sub("\\.[0-9]+Z$"; "Z") | fromdateiso8601)
                - (.started | sub("\\.[0-9]+Z$"; "Z") | fromdateiso8601)) >= $minRuntime
        ' --arg expectedTextureMode "$expected_texture_mode" --argjson minRuntime "$MIN_RUNTIME_SECONDS" "$run_dir/run.json" >/dev/null
        lifecycle_check=$?
        set -e
    fi

    set +e
    jq -e '.bytes > 0' "$log_classification" >/dev/null
    log_check=$?
    jq -e '.detected == true and .launcherReadyMs > 0 and .observedLines > 0' "$launcher_detection" >/dev/null
    launcher_detector_check=$?
    jq -e '
        .detected == true
        and .gameLogStartToMainMenuMs > 0
        and .saveDescriptorSeen == true
        and .graphicsPreloadSeen == true
        and .observedLines > 0
    ' "$main_menu_detection" >/dev/null
    menu_detector_check=$?
    set -e

    if [[ "$mode" == prepared ]]; then
        telemetry_check=1
        if [[ -f "$run_dir/adapter.json" ]]; then
            set +e
            jq -e '
                .transformationsApplied == 1
                and .containedFailures == 0
                and .textureCompatibility.preparedPixels.coherentDirectEnabled == true
                and .textureCompatibility.preparedPixels.hits > 0
                and .textureCompatibility.preparedPixels.coherentDirectHits > 0
                and .textureCompatibility.preparedPixels.fallbacks == 0
                and .textureCompatibility.preparedPixels.internalErrors == 0
                and .textureCompatibility.preparedPixels.activeDirectBytes == 0
                and .textureCompatibility.preparedPixels.activeBuffers == 0
                and .textureCompatibility.preparedPixels.pendingBuffers == 0
            ' "$run_dir/adapter.json" >/dev/null
            telemetry_check=$?
            set -e
        fi
    fi

    local operator_accepted=false automated_accepted=false
    if [[ "$launcher_ok" == true && "$main_menu_ok" == true && "$detector_ok" == true \
            && "$attached_ok" == true && "$clean_exit_ok" == true && "$corruption_seen" == false ]]; then
        operator_accepted=true
    fi
    if [[ "$preflight_exit" -eq 0 && "$lifecycle_check" -eq 0 && "$telemetry_check" -eq 0 \
            && "$log_check" -eq 0 && "$launcher_detector_check" -eq 0 && "$menu_detector_check" -eq 0 \
            && "$profile_stable" == true ]]; then
        automated_accepted=true
    fi

    jq -n \
        --arg mode "$mode" \
        --argjson preflightExit "$preflight_exit" \
        --argjson launcherVisualsNormal "$launcher_ok" \
        --argjson mainMenuNormal "$main_menu_ok" \
        --argjson automaticDetectionVisuallyAccurate "$detector_ok" \
        --argjson commandAttachedUntilExit "$attached_ok" \
        --argjson cleanExitObserved "$clean_exit_ok" \
        --argjson visualCorruptionObserved "$corruption_seen" \
        --argjson operatorAccepted "$operator_accepted" \
        --argjson automatedAccepted "$automated_accepted" \
        --argjson profileStable "$profile_stable" \
        --arg expectedProfileFingerprint "$EXPECTED_PROFILE_FINGERPRINT" \
        --slurpfile launcherDetection "$launcher_detection" \
        --slurpfile mainMenuDetection "$main_menu_detection" \
        --slurpfile logClassification "$log_classification" \
        --slurpfile profileStateCheck "$profile_after_drift" \
        '{
            mode: $mode,
            launcherReadyMs: $launcherDetection[0].launcherReadyMs,
            gameLogStartToMainMenuMs: $mainMenuDetection[0].gameLogStartToMainMenuMs,
            gameLogMillisDelta: $mainMenuDetection[0].gameLogMillisDelta,
            timingMethod: "automatic-starsector-log-phase-detection",
            preflightExit: $preflightExit,
            launcherVisualsNormal: $launcherVisualsNormal,
            mainMenuNormal: $mainMenuNormal,
            automaticDetectionVisuallyAccurate: $automaticDetectionVisuallyAccurate,
            commandAttachedUntilExit: $commandAttachedUntilExit,
            cleanExitObserved: $cleanExitObserved,
            visualCorruptionObserved: $visualCorruptionObserved,
            operatorAccepted: $operatorAccepted,
            automatedAccepted: $automatedAccepted,
            profileStable: $profileStable,
            expectedProfileFingerprint: $expectedProfileFingerprint,
            immutableProfileStable: $profileStateCheck[0].immutableProfileStable,
            mutableCacheChangedDuringRun: $profileStateCheck[0].mutableCache.changedDuringRun,
            mutableCacheContentEquivalentToBaseline: $profileStateCheck[0].mutableCache.contentEquivalentToBaseline,
            profileStateCheck: $profileStateCheck[0],
            launcherDetection: $launcherDetection[0],
            mainMenuDetection: $mainMenuDetection[0],
            logClassification: $logClassification[0]
        }' > "$result"

    if [[ "$operator_accepted" != true || "$automated_accepted" != true ]]; then
        # Distinguish the failure classes so "executed" is never conflated with "passed":
        #   1. the half never executed (handled earlier: detection failures return before here);
        #   2. operator rejection of a visual/lifecycle question;
        #   3. lifecycle/detector/log/profile rejection of an executed run;
        #   4. prepared telemetry unavailable or rejected (prepared path was not exercised).
        echo "The $mode comparison half executed but did not pass all acceptance checks." >&2
        local -a failure_reasons=()
        if [[ "$operator_accepted" != true ]]; then
            failure_reasons+=("operator rejected one or more visual/lifecycle questions")
        fi
        if [[ "$preflight_exit" -ne 0 ]]; then
            failure_reasons+=("preflight wrapper exited with status $preflight_exit")
        fi
        if [[ "$lifecycle_check" -ne 0 ]]; then
            failure_reasons+=("lifecycle/exit-code/texture-mode acceptance failed")
        fi
        if [[ "$launcher_detector_check" -ne 0 ]]; then
            failure_reasons+=("launcher-readiness detector rejected the run")
        fi
        if [[ "$menu_detector_check" -ne 0 ]]; then
            failure_reasons+=("main-menu readiness detector rejected the run")
        fi
        if [[ "$log_check" -ne 0 ]]; then
            failure_reasons+=("starsector.log classification was unavailable")
        fi
        if [[ "$profile_stable" != true ]]; then
            failure_reasons+=("immutable-profile / mutable-cache stability check failed")
        fi
        if [[ "$mode" == prepared && "$telemetry_check" -ne 0 ]]; then
            local applied="unknown" disable_reasons=""
            if [[ -f "$run_dir/adapter.json" ]]; then
                applied="$(jq -r '.transformationsApplied // "unknown"' "$run_dir/adapter.json" 2>/dev/null || printf unknown)"
                disable_reasons="$(jq -r '(.textureCompatibility.disableReasons // []) | join(",")' "$run_dir/adapter.json" 2>/dev/null || printf '')"
            fi
            if [[ -n "$disable_reasons" ]]; then
                failure_reasons+=("prepared telemetry rejected: adapter failed open (disableReasons=$disable_reasons, transformationsApplied=$applied); the prepared-pixel path was NOT exercised, so this half's timing is not a valid prepared measurement")
            else
                failure_reasons+=("prepared telemetry rejected: transformationsApplied=$applied with no prepared-pixel hits; the prepared-pixel path was NOT exercised, so this half's timing is not a valid prepared measurement")
            fi
        fi
        for artifact in "${failure_reasons[@]}"; do
            echo "  - $artifact" >&2
        done
        return 1
    fi
}

cat <<'NOTICE'

This pilot runs the same exact profile twice:
  compatibility decoded-image path
  accepted coherent-direct prepared path

The runner automatically detects launcher readiness and main-menu readiness from starsector.log.
It uses a fixed six-second launcher safety confirmation without adding that wait to the measured endpoint.
It strictly rejects immutable profile drift, records bounded GraphicsLib cache changes,
and restores the exact pre-warmed cache state before each randomized half.
You only need to click Play when instructed, visually confirm the result, and exit from the main menu.
A single pair is preliminary evidence only. It cannot support an acceleration claim.
Do not enter a campaign or combat.
NOTICE
read -r -p "Type COMPARE to authorize this two-run main-menu pilot: " confirmation
if [[ "$confirmation" != COMPARE ]]; then
    echo "Comparison pilot was not authorized." >&2
    exit 1
fi

comparison_failed=0
for mode in "$first_mode" "$second_mode"; do
    if ! run_mode "$mode"; then
        comparison_failed=1
        break
    fi
done

if restore_mutable_state "after-comparison" "$ROOT_DIR/final-mutable-cache-restore.json"; then
    FINAL_MUTABLE_RESTORE_DONE=true
else
    comparison_failed=1
fi

if [[ -f "$ROOT_DIR/compatibility/operator-main-menu-result.json" \
        && -f "$ROOT_DIR/prepared/operator-main-menu-result.json" ]]; then
    python3 - "$ROOT_DIR/compatibility/operator-main-menu-result.json" \
        "$ROOT_DIR/prepared/operator-main-menu-result.json" \
        "$ROOT_DIR/comparison-result.json" "$ORDER" \
        "$ROOT_DIR/final-mutable-cache-restore.json" <<'PY'
import json
import sys
from pathlib import Path

compatibility = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
prepared = json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))
final_restore = json.loads(Path(sys.argv[5]).read_text(encoding="utf-8"))
counts_a = compatibility["logClassification"]["counts"]
counts_b = prepared["logClassification"]["counts"]
keys = sorted(set(counts_a) | set(counts_b))
result = {
    "scope": "main-menu-comparison-pilot",
    "order": sys.argv[4].split(","),
    "samplesPerMode": 1,
    "preliminaryOnly": True,
    "benchmarkAccepted": False,
    "timingMethod": "automatic-starsector-log-phase-detection",
    "compatibility": compatibility,
    "prepared": prepared,
    "preparedMinusCompatibilityMs": {
        "launcherReady": round(prepared["launcherReadyMs"] - compatibility["launcherReadyMs"], 3),
        "gameLogStartToMainMenu": round(
            prepared["gameLogStartToMainMenuMs"] - compatibility["gameLogStartToMainMenuMs"], 3
        ),
    },
    "preparedMinusCompatibilityLogCounts": {
        key: counts_b.get(key, 0) - counts_a.get(key, 0) for key in keys
    },
    "logPatternCountsEqual": all(counts_a.get(key, 0) == counts_b.get(key, 0) for key in keys),
    "automaticDetectionVisuallyAccepted": (
        compatibility["automaticDetectionVisuallyAccurate"]
        and prepared["automaticDetectionVisuallyAccurate"]
    ),
    "profileStableAcrossBothHalves": (
        compatibility.get("profileStable") is True
        and prepared.get("profileStable") is True
        and compatibility.get("expectedProfileFingerprint")
            == prepared.get("expectedProfileFingerprint")
    ),
    "mutableCacheBehavior": {
        "compatibility": compatibility["profileStateCheck"]["mutableCache"],
        "prepared": prepared["profileStateCheck"]["mutableCache"],
    },
    "boundedMutableStateRestoredAfterComparison": final_restore.get("restored") is True,
    "nextDecision": "review-log-deltas-before-repeat-timing-campaign",
}
Path(sys.argv[3]).write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PY
fi

tar -czf "$ARCHIVE_OUT" -C "$(dirname "$ROOT_DIR")" "$(basename "$ROOT_DIR")"

if [[ "$comparison_failed" -ne 0 || ! -f "$ROOT_DIR/comparison-result.json" ]]; then
    echo "The comparison pilot did not complete both accepted halves." >&2
    echo "Upload: $ARCHIVE_OUT" >&2
    exit 1
fi

echo
echo "Comparison pilot completed. Preliminary result:"
jq '{
    samplesPerMode,
    preliminaryOnly,
    benchmarkAccepted,
    timingMethod,
    preparedMinusCompatibilityMs,
    preparedMinusCompatibilityLogCounts,
    logPatternCountsEqual,
    automaticDetectionVisuallyAccepted,
    profileStableAcrossBothHalves,
    boundedMutableStateRestoredAfterComparison,
    mutableCacheBehavior: {
        compatibility: {
            changedDuringRun: .mutableCacheBehavior.compatibility.changedDuringRun,
            contentEquivalentToBaseline: .mutableCacheBehavior.compatibility.contentEquivalentToBaseline
        },
        prepared: {
            changedDuringRun: .mutableCacheBehavior.prepared.changedDuringRun,
            contentEquivalentToBaseline: .mutableCacheBehavior.prepared.contentEquivalentToBaseline
        }
    },
    nextDecision
}' "$ROOT_DIR/comparison-result.json"
echo
echo "Upload this retained evidence archive:"
echo "  $ARCHIVE_OUT"
echo
echo "Do not repeat the pilot or claim a benchmark result without reviewing the retained profile-stability evidence."
