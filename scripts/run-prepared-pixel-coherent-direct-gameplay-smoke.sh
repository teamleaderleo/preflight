#!/usr/bin/env bash
set -euo pipefail

GAME="${GAME:-/Applications/Starsector.app}"
CACHE="${CACHE:-$HOME/.starsector-preflight/cache}"
JAR="$PWD/preflight-cli/target/preflight.jar"
DIAGNOSTIC_PROPERTY="-Dpreflight.preparedPixels.coherentDirect=true"
EXPECTED_ARCHIVE_SHA="10d89e113f6d1627cc7bc90b692e8a7f450fdd820c5a4ac5edaecd6710afe708"
EXPECTED_CLASS_SHA="d8fcb4cb90d457fc3075e711b6293940774dcf990ea66a7584c231bd96898b50"
ACCEPTED_LAUNCHER_ARCHIVE_SHA="898f99beb8940900a34634d53affc9a97705366fd42faf57a7d2b033bb8bb555"
AXIS_CONTRACT='return new DimensionSetters(setters.get(1), setters.get(0));'
MIN_RUNTIME_SECONDS=120

for command in git java mvn jq shasum unzip tar grep; do
    if ! command -v "$command" >/dev/null 2>&1; then
        echo "Missing required command: $command" >&2
        exit 1
    fi
done

if [[ ! -f pom.xml ]]; then
    echo "Run this script from the Preflight repository root." >&2
    exit 1
fi

if [[ ! -d "$GAME" ]]; then
    echo "Starsector installation not found: $GAME" >&2
    exit 1
fi

PLAN_SOURCE="preflight-agent/src/main/java/dev/starsector/preflight/agent/TexturePreparedPixelPlan.java"
if [[ ! -f "$PLAN_SOURCE" ]] || ! grep -Fq "$AXIS_CONTRACT" "$PLAN_SOURCE"; then
    echo "This checkout does not contain the reviewed height-first/width-second axis mapping." >&2
    echo "Run: git switch main && git pull --ff-only" >&2
    exit 1
fi

STAMP="$(date -u +%Y%m%d-%H%M%S)"
REPORT_DIR="$CACHE/reports"
CONTRACT_REPORT="$REPORT_DIR/prepared-pixel-coherent-direct-gameplay-contract-$STAMP.json"
PREP_REPORT="$REPORT_DIR/prepared-pixel-coherent-direct-gameplay-preparation-$STAMP.json"
RUN_DIR="$HOME/.starsector-preflight/runs/prepared-pixel-coherent-direct-gameplay-smoke-$STAMP"
mkdir -p "$REPORT_DIR"

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

echo
echo "== Verifying exact installed identities =="
echo "Archive SHA-256: $ARCHIVE_SHA"
echo "Class SHA-256:   $CLASS_SHA"

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
java -cp "$JAR" \
    dev.starsector.preflight.agent.PreparedPixelContractCheck \
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
    and .readiness.preparedPixelsAdapter == "pot-bypass-enabled-npot-coherent-direct-gameplay-smoke-diagnostic"
    and .readiness.preparedPixelsBehavioralAcceptance == "launcher-accepted-2026-07-23-gameplay-smoke-required"
    and .readiness.preparedPixelsNextOperatorAction == "single-coherent-direct-gameplay-smoke"
    and .readiness.launchAccelerationClaimed == false
' "$PREP_REPORT" >/dev/null

INSTALL_ROOT="$(jq -er '.installRoot' "$PREP_REPORT")"
LAUNCHER="$(jq -er '.launcher' "$PREP_REPORT")"
CACHE_DIRECTORY="$(jq -er '.cacheDirectory' "$PREP_REPORT")"
RESOURCE_INDEX="$(jq -er '.resourceIndex' "$PREP_REPORT")"
TEXTURE_MANIFEST="$(jq -er '.stages.textures.details.manifest' "$PREP_REPORT")"

for file in "$RESOURCE_INDEX" "$TEXTURE_MANIFEST"; do
    if [[ ! -f "$file" ]]; then
        echo "Preparation artifact does not exist: $file" >&2
        exit 1
    fi
done

RUN_ARGS=(
    run
    --game "$INSTALL_ROOT"
    --launcher "$LAUNCHER"
    --trace-dir "$RUN_DIR"
    --adapter
    --texture-mode prepared-pixels
    --texture-cache-dir "$CACHE_DIRECTORY"
    --texture-manifest "$TEXTURE_MANIFEST"
    --texture-index "$RESOURCE_INDEX"
)

DIAGNOSTIC_JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }$DIAGNOSTIC_PROPERTY"

echo
echo "== Exact coherent-direct gameplay smoke plan =="
JAVA_TOOL_OPTIONS="$DIAGNOSTIC_JAVA_TOOL_OPTIONS" \
    java -jar "$JAR" "${RUN_ARGS[@]}" --dry-run

echo
echo "This is one controlled gameplay smoke test, not a benchmark."
echo "Use a new campaign, a copied save, or another disposable save."
echo "Do not overwrite a save you cannot afford to lose."
echo
echo "Required route:"
echo "  1. Confirm the launcher still looks normal, then click Play Starsector."
echo "  2. Reach the main menu and inspect its background, text, and controls."
echo "  3. Start a new campaign or load a disposable save."
echo "  4. Inspect campaign UI, portraits, ships, backgrounds, and effects."
echo "  5. Enter one combat and inspect ships, weapons, projectiles, effects, and UI."
echo "  6. Finish or exit combat normally, then save the campaign."
echo "  7. Return to the main menu and exit Starsector cleanly."
echo "  8. If the launcher reappears, close it with its X."
echo
echo "The wrapper must remain attached for at least $MIN_RUNTIME_SECONDS seconds."
echo "Stop and exit cleanly if you see black, sliced, repeated, stretched,"
echo "missing, flipped, or progressively corrupt textures."
echo
read -r -p "Type SMOKE to authorize this single disposable-save gameplay run: " confirmation
if [[ "$confirmation" != "SMOKE" ]]; then
    echo "Gameplay smoke was not authorized." >&2
    exit 1
fi

mkdir -p "$RUN_DIR"
{
    printf 'repositoryHead=%s\n' "$REPOSITORY_HEAD"
    printf 'jarSha256=%s\n' "$JAR_SHA"
    printf 'archiveSha256=%s\n' "$ARCHIVE_SHA"
    printf 'classSha256=%s\n' "$CLASS_SHA"
    printf 'diagnosticProperty=%s\n' "$DIAGNOSTIC_PROPERTY"
    printf 'dimensionReplay=%s\n' 'reviewed-converter-height-first-width-second'
    printf 'validationScope=%s\n' 'gameplay-smoke'
    printf 'prerequisiteLauncherArchiveSha256=%s\n' "$ACCEPTED_LAUNCHER_ARCHIVE_SHA"
    printf 'requiredRoute=%s\n' 'launcher-main-menu-campaign-combat-save-clean-exit'
    printf 'minimumObservedRuntimeSeconds=%s\n' "$MIN_RUNTIME_SECONDS"
    printf 'command=JAVA_TOOL_OPTIONS=%q ' "$DIAGNOSTIC_JAVA_TOOL_OPTIONS"
    printf '%q ' java -jar "$JAR" "${RUN_ARGS[@]}"
    printf '\n'
} > "$RUN_DIR/operator-smoke-identity.txt"
cp "$CONTRACT_REPORT" "$RUN_DIR/operator-contract.json"
cp "$PREP_REPORT" "$RUN_DIR/operator-preparation.json"

echo
echo "== Starting single coherent-direct gameplay smoke =="
set +e
JAVA_TOOL_OPTIONS="$DIAGNOSTIC_JAVA_TOOL_OPTIONS" \
    java -jar "$JAR" "${RUN_ARGS[@]}"
smoke_exit=$?
set -e

echo
echo "Preflight exit: $smoke_exit"
echo "Run directory: $RUN_DIR"

ARCHIVE_OUT="$HOME/Desktop/$(basename "$RUN_DIR").tar.gz"

if [[ ! -f "$RUN_DIR/run.json" ]]; then
    tar -czf "$ARCHIVE_OUT" -C "$(dirname "$RUN_DIR")" "$(basename "$RUN_DIR")"
    echo "The smoke run did not produce run.json." >&2
    echo "Upload: $ARCHIVE_OUT" >&2
    exit 1
fi
if [[ ! -f "$RUN_DIR/adapter.json" ]]; then
    tar -czf "$ARCHIVE_OUT" -C "$(dirname "$RUN_DIR")" "$(basename "$RUN_DIR")"
    echo "The smoke run did not produce adapter.json." >&2
    echo "Upload: $ARCHIVE_OUT" >&2
    exit 1
fi

echo
echo "== Lifecycle result =="
jq '{
    outcome,
    exitCode,
    launcherExitCode,
    lifecycleEvidence,
    launcherConsole,
    launcherConsoleCapture
}' "$RUN_DIR/run.json"

echo
echo "== Prepared-pixel telemetry =="
jq '.textureCompatibility.preparedPixels' "$RUN_DIR/adapter.json"

set +e
jq -e '
    .outcome == "COMPLETED"
    and .exitCode == 0
    and .launcherExitCode == 0
    and .lifecycleEvidence.fatalDetected == false
    and ((.ended | sub("\\.[0-9]+Z$"; "Z") | fromdateiso8601)
        - (.started | sub("\\.[0-9]+Z$"; "Z") | fromdateiso8601)) >= $minRuntime
' --argjson minRuntime "$MIN_RUNTIME_SECONDS" "$RUN_DIR/run.json" >/dev/null
lifecycle_check=$?

jq -e '
    .transformationsApplied == 1
    and .containedFailures == 0
    and .textureCompatibility.preparedPixels.coherentDirectEnabled == true
    and .textureCompatibility.preparedPixels.coherentDirectCarriers > 0
    and .textureCompatibility.preparedPixels.coherentDirectHits > 0
    and .textureCompatibility.preparedPixels.coherentCarriers >= .textureCompatibility.preparedPixels.coherentDirectCarriers
    and .textureCompatibility.preparedPixels.coherentCarrierBytes > 0
    and .textureCompatibility.preparedPixels.coherentOriginalConvertFallbacks == 0
    and .textureCompatibility.preparedPixels.coherentOriginalDecodeBypasses == 0
    and .textureCompatibility.preparedPixels.npotProbeFallbacks == 0
    and .textureCompatibility.preparedPixels.paddedUploads == .textureCompatibility.preparedPixels.coherentDirectHits
    and .textureCompatibility.preparedPixels.paddingBytes > 0
    and .textureCompatibility.preparedPixels.uploadBytesSupplied > .textureCompatibility.preparedPixels.bytesBypassed
    and .textureCompatibility.preparedPixels.internalErrors == 0
    and .textureCompatibility.preparedPixels.activeDirectBytes == 0
    and .textureCompatibility.preparedPixels.activeBuffers == 0
    and .textureCompatibility.preparedPixels.pendingBuffers == 0
' "$RUN_DIR/adapter.json" >/dev/null
telemetry_check=$?

grep -Fq 'dimensionReplay=reviewed-converter-height-first-width-second' \
    "$RUN_DIR/operator-smoke-identity.txt"
identity_check=$?
set -e

ask_yes_no() {
    local prompt="$1"
    local reply
    while true; do
        read -r -p "$prompt [y/n]: " reply
        case "$reply" in
            y|Y|yes|YES|Yes)
                printf 'true\n'
                return 0
                ;;
            n|N|no|NO|No)
                printf 'false\n'
                return 0
                ;;
            *)
                echo "Please answer y or n." >&2
                ;;
        esac
    done
}

echo
echo "== Operator gameplay observations =="
launcher_ok="$(ask_yes_no "Did the launcher remain visually normal?")"
main_menu_ok="$(ask_yes_no "Did you reach a visually normal main menu?")"
campaign_ok="$(ask_yes_no "Did campaign UI, portraits, ships, backgrounds, and effects look normal?")"
combat_ok="$(ask_yes_no "Did one combat and its ships, weapons, projectiles, effects, and UI look normal?")"
save_ok="$(ask_yes_no "Did the campaign save complete successfully?")"
attached_ok="$(ask_yes_no "Did this terminal command remain running until you exited Starsector?")"
clean_exit_ok="$(ask_yes_no "Did Starsector exit cleanly without a crash dialog?")"
corruption_seen="$(ask_yes_no "Did you see any black, sliced, repeated, stretched, missing, flipped, or worsening textures?")"

operator_accepted=false
if [[ "$launcher_ok" == true \
        && "$main_menu_ok" == true \
        && "$campaign_ok" == true \
        && "$combat_ok" == true \
        && "$save_ok" == true \
        && "$attached_ok" == true \
        && "$clean_exit_ok" == true \
        && "$corruption_seen" == false ]]; then
    operator_accepted=true
fi

automated_accepted=false
if [[ "$smoke_exit" -eq 0 \
        && "$lifecycle_check" -eq 0 \
        && "$telemetry_check" -eq 0 \
        && "$identity_check" -eq 0 ]]; then
    automated_accepted=true
fi

jq -n \
    --arg scope "gameplay-smoke" \
    --arg route "launcher-main-menu-campaign-combat-save-clean-exit" \
    --arg prerequisiteLauncherArchiveSha256 "$ACCEPTED_LAUNCHER_ARCHIVE_SHA" \
    --argjson launcherVisualsNormal "$launcher_ok" \
    --argjson mainMenuNormal "$main_menu_ok" \
    --argjson campaignVisualsNormal "$campaign_ok" \
    --argjson combatVisualsNormal "$combat_ok" \
    --argjson saveCompleted "$save_ok" \
    --argjson commandAttachedUntilGameExit "$attached_ok" \
    --argjson cleanExitObserved "$clean_exit_ok" \
    --argjson visualCorruptionObserved "$corruption_seen" \
    --argjson operatorAccepted "$operator_accepted" \
    --argjson automatedAccepted "$automated_accepted" \
    --argjson preflightExit "$smoke_exit" \
    '{
        scope: $scope,
        route: $route,
        prerequisiteLauncherArchiveSha256: $prerequisiteLauncherArchiveSha256,
        launcherVisualsNormal: $launcherVisualsNormal,
        mainMenuNormal: $mainMenuNormal,
        campaignVisualsNormal: $campaignVisualsNormal,
        combatVisualsNormal: $combatVisualsNormal,
        saveCompleted: $saveCompleted,
        commandAttachedUntilGameExit: $commandAttachedUntilGameExit,
        cleanExitObserved: $cleanExitObserved,
        visualCorruptionObserved: $visualCorruptionObserved,
        operatorAccepted: $operatorAccepted,
        automatedAccepted: $automatedAccepted,
        preflightExit: $preflightExit
    }' > "$RUN_DIR/operator-gameplay-result.json"

tar -czf "$ARCHIVE_OUT" -C "$(dirname "$RUN_DIR")" "$(basename "$RUN_DIR")"

if [[ "$automated_accepted" != true ]]; then
    echo "Automated lifecycle, telemetry, or identity checks failed." >&2
    echo "Upload: $ARCHIVE_OUT" >&2
    exit 1
fi

if [[ "$operator_accepted" != true ]]; then
    echo "The operator gameplay checklist did not pass." >&2
    echo "Upload: $ARCHIVE_OUT" >&2
    exit 1
fi

echo
echo "Automated checks and the operator gameplay checklist passed."
echo "Upload this retained evidence archive:"
echo "  $ARCHIVE_OUT"
echo
echo "Do not repeat the smoke test or begin benchmarks yet."
