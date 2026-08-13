#!/usr/bin/env bash
set -euo pipefail

GAME="${GAME:-/Applications/Starsector.app}"
CACHE="${CACHE:-$HOME/.starsector-preflight/cache}"
JAR="$PWD/preflight-cli/target/preflight.jar"
REQUIRED_MAIN_COMMIT="1fd63567e5834546ab5d617234f84371df9909ea"
EXPECTED_ARCHIVE_SHA="10d89e113f6d1627cc7bc90b692e8a7f450fdd820c5a4ac5edaecd6710afe708"
EXPECTED_CLASS_SHA="d8fcb4cb90d457fc3075e711b6293940774dcf990ea66a7584c231bd96898b50"

for command in git java mvn jq shasum unzip tar; do
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

if ! git merge-base --is-ancestor "$REQUIRED_MAIN_COMMIT" HEAD; then
    echo "This checkout does not contain the merged NPOT fail-open repair." >&2
    echo "Run: git switch main && git pull --ff-only" >&2
    echo "Current HEAD: $(git rev-parse HEAD)" >&2
    exit 1
fi

STAMP="$(date -u +%Y%m%d-%H%M%S)"
REPORT_DIR="$CACHE/reports"
CONTRACT_REPORT="$REPORT_DIR/prepared-pixel-layout-probe-contract-$STAMP.json"
PREP_REPORT="$REPORT_DIR/prepared-pixel-layout-probe-preparation-$STAMP.json"
RUN_DIR="$HOME/.starsector-preflight/runs/prepared-pixel-layout-probe-$STAMP"
mkdir -p "$REPORT_DIR"

echo "Repository HEAD: $(git rev-parse HEAD)"

echo
echo "== Building and verifying the merged checkout =="
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
    and .readiness.preparedPixelsNextOperatorAction == "launcher-only-original-layout-probe"
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

echo
echo "== Exact launcher-only probe plan =="
java -jar "$JAR" "${RUN_ARGS[@]}" --dry-run

echo
echo "This is one launcher-only evidence run."
echo "When the launcher appears:"
echo "  1. Confirm the background, buttons, borders, toggles, and text look normal."
echo "  2. Take a screenshot."
echo "  3. DO NOT click Play or start Starsector."
echo "  4. Close the launcher with its X button."
echo
read -r -p "Press Enter to start the single launcher probe, or Ctrl-C to stop: " _

mkdir -p "$RUN_DIR"
{
    printf 'repositoryHead=%s\n' "$(git rev-parse HEAD)"
    printf 'jarSha256=%s\n' "$JAR_SHA"
    printf 'archiveSha256=%s\n' "$ARCHIVE_SHA"
    printf 'classSha256=%s\n' "$CLASS_SHA"
    printf 'command='
    printf '%q ' java -jar "$JAR" "${RUN_ARGS[@]}"
    printf '\n'
} > "$RUN_DIR/operator-probe-identity.txt"
cp "$CONTRACT_REPORT" "$RUN_DIR/operator-contract.json"
cp "$PREP_REPORT" "$RUN_DIR/operator-preparation.json"

echo
echo "== Starting launcher-only original-layout probe =="
set +e
java -jar "$JAR" "${RUN_ARGS[@]}"
probe_exit=$?
set -e

echo
echo "Preflight exit: $probe_exit"
echo "Run directory: $RUN_DIR"

if [[ ! -f "$RUN_DIR/run.json" ]]; then
    echo "The probe did not produce run.json." >&2
    exit 1
fi
if [[ ! -f "$RUN_DIR/adapter.json" ]]; then
    echo "The probe did not produce adapter.json." >&2
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
echo "== Prepared-pixel probe telemetry =="
jq '.textureCompatibility.preparedPixels' "$RUN_DIR/adapter.json"

echo
echo "== Original layout observations =="
jq '.textureCompatibility.preparedPixels.originalLayoutObservations' "$RUN_DIR/adapter.json"

jq -e '
    .outcome == "COMPLETED"
    and .exitCode == 0
    and .launcherExitCode == 0
    and .lifecycleEvidence.fatalDetected == false
' "$RUN_DIR/run.json" >/dev/null

jq -e '
    .transformationsApplied == 1
    and .containedFailures == 0
    and .textureCompatibility.preparedPixels.npotProbeFallbacks > 0
    and .textureCompatibility.preparedPixels.paddedUploads == 0
    and .textureCompatibility.preparedPixels.paddingBytes == 0
    and .textureCompatibility.preparedPixels.layoutObservationErrors == 0
    and (.textureCompatibility.preparedPixels.originalLayoutObservations | length) > 0
    and .textureCompatibility.preparedPixels.internalErrors == 0
    and .textureCompatibility.preparedPixels.activeDirectBytes == 0
    and .textureCompatibility.preparedPixels.activeBuffers == 0
    and .textureCompatibility.preparedPixels.pendingBuffers == 0
' "$RUN_DIR/adapter.json" >/dev/null

ARCHIVE_OUT="$HOME/Desktop/$(basename "$RUN_DIR").tar.gz"
tar -czf "$ARCHIVE_OUT" -C "$(dirname "$RUN_DIR")" "$(basename "$RUN_DIR")"

if [[ "$probe_exit" -ne 0 ]]; then
    echo "The probe returned a nonzero exit after evidence was retained." >&2
    echo "Upload: $ARCHIVE_OUT" >&2
    exit "$probe_exit"
fi

echo
echo "Automated lifecycle, fallback, observation, and cleanup checks passed."
echo "Manual visual acceptance is still required."
echo
echo "Upload both:"
echo "  $ARCHIVE_OUT"
echo "  the launcher screenshot"
echo
echo "Do not run another probe, start gameplay, or benchmark yet."
