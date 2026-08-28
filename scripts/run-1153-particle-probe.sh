#!/usr/bin/env bash
#
# Profile vanilla DynamicParticleGroup.render(FF)V for issue #1153 with an exact installed-core target.
#
# Usage:
#   scripts/run-1153-particle-probe.sh --route ordinary|symmetric-1040 [--workload-id NAME] [--game DIR] [--label NAME] [--common-jar FILE]
set -euo pipefail

GAME="${STARSECTOR_HOME:-/Applications/Starsector.app}"
ROUTE=""
WORKLOAD_ID=""
LABEL=""
COMMON_JAR="${STARSECTOR_COMMON_JAR:-}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --route) ROUTE="$2"; shift 2 ;;
        --workload-id) WORKLOAD_ID="$2"; shift 2 ;;
        --game) GAME="$2"; shift 2 ;;
        --label) LABEL="$2"; shift 2 ;;
        --common-jar) COMMON_JAR="$2"; shift 2 ;;
        -h|--help)
            sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *) echo "Unknown option: $1" >&2; exit 2 ;;
    esac
done

case "$ROUTE" in
    ordinary|symmetric-1040) ;;
    *) echo "--route must be ordinary or symmetric-1040" >&2; exit 2 ;;
esac

[[ -f pom.xml ]] || { echo "Run this from the Preflight repository root." >&2; exit 1; }
[[ -d "$GAME" ]] || { echo "Starsector installation not found: $GAME" >&2; exit 1; }
[[ -f preflight-cli/target/preflight.jar ]] \
    || { echo "Build preflight-cli/target/preflight.jar before running the probe." >&2; exit 1; }

[[ -n "$WORKLOAD_ID" ]] || WORKLOAD_ID="$ROUTE"
[[ -n "$LABEL" ]] || LABEL="issue-1153-particle-probe-${ROUTE}"
case "$LABEL" in
    *[!A-Za-z0-9._-]*) echo "--label may contain only letters, digits, '.', '_' and '-'" >&2; exit 2 ;;
esac

STATE_ROOT="${STARSECTOR_PREFLIGHT_HOME:-$HOME/.starsector-preflight}"
SESSION="$STATE_ROOT/issue-1153/$LABEL-$(date +%Y%m%d-%H%M%S)"
REPORT="$SESSION/particle-probe.json"
SUMMARY="$SESSION/summary.json"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/preflight-1153-particles.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$SESSION"

hash_file() {
    local file="$1"
    if command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$file" | awk '{print $1}'
    elif command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$file" | awk '{print $1}'
    elif command -v openssl >/dev/null 2>&1; then
        openssl dgst -sha256 "$file" | awk '{print $NF}'
    else
        echo "Need shasum, sha256sum, or openssl for exact target hashing." >&2
        return 1
    fi
}

absolute_file() {
    local file="$1"
    [[ -f "$file" ]] || return 1
    (cd "$(dirname "$file")" && printf '%s/%s\n' "$(pwd -P)" "$(basename "$file")")
}

extract_class() {
    local archive="$1" entry="$2" destination="$3" scratch
    if command -v unzip >/dev/null 2>&1; then
        unzip -p "$archive" "$entry" >"$destination"
    elif command -v jar >/dev/null 2>&1; then
        scratch="$TMP/extract"
        rm -rf "$scratch"
        mkdir -p "$scratch"
        (cd "$scratch" && jar xf "$archive" "$entry")
        cp "$scratch/$entry" "$destination"
    else
        echo "Need unzip or jar to derive the target class hash." >&2
        return 1
    fi
    [[ -s "$destination" ]] || { echo "Could not extract $entry from $archive" >&2; return 1; }
}

if [[ -z "$COMMON_JAR" ]]; then
    matches="$(find "$GAME" -type f -name fs.common_obf.jar -print 2>/dev/null | head -2)"
    [[ "$(printf '%s\n' "$matches" | sed '/^$/d' | wc -l | tr -d '[:space:]')" == 1 ]] || {
        echo "Could not resolve exactly one fs.common_obf.jar under $GAME; pass --common-jar." >&2
        exit 1
    }
    COMMON_JAR="$matches"
fi
COMMON_JAR="$(absolute_file "$COMMON_JAR")" || { echo "Common archive not found." >&2; exit 1; }

EXPECTED_COMMON_SHA="10d89e113f6d1627cc7bc90b692e8a7f450fdd820c5a4ac5edaecd6710afe708"
ACTUAL_COMMON_SHA="$(hash_file "$COMMON_JAR")"
[[ "$ACTUAL_COMMON_SHA" == "$EXPECTED_COMMON_SHA" ]] || {
    echo "Common archive differs from reviewed Starsector 0.98a-RC8." >&2
    echo "Expected: $EXPECTED_COMMON_SHA" >&2
    echo "Actual:   $ACTUAL_COMMON_SHA" >&2
    exit 1
}

CLASS_FILE="$TMP/DynamicParticleGroup.class"
extract_class "$COMMON_JAR" "com/fs/graphics/particle/DynamicParticleGroup.class" "$CLASS_FILE"
CLASS_SHA="$(hash_file "$CLASS_FILE")"
export _JAVA_OPTIONS="${_JAVA_OPTIONS:+$_JAVA_OPTIONS }-Dpreflight.frameSync=false -Dpreflight.graphicsLibTessellateArray=false -Dpreflight.graphicsLibTessellatePackedReplay=false -Dpreflight.dynamicParticleGroupProbe=true -Dpreflight.dynamicParticleGroupProbe.report='$REPORT'"

echo "Issue #1153 vanilla particle render probe"
echo "  route:       $ROUTE"
echo "  workload id: $WORKLOAD_ID"
echo "  class sha:   $CLASS_SHA"
echo "  common sha:  $ACTUAL_COMMON_SHA"
echo "  session:     $SESSION"
echo

if [[ "$ROUTE" == ordinary ]]; then
    SCENARIO="scripts/scenarios/campaign-simulation-combat-particle-sanity.json"
else
    SCENARIO="scripts/scenarios/campaign-simulation-combat-1000dp-thin.json"
fi
RUN_DIR="$STATE_ROOT/runs/$LABEL-$(date +%Y%m%d-%H%M%S)"
set +e
java -jar preflight-cli/target/preflight.jar desktop smoke launch \
    "$SCENARIO" "$RUN_DIR" --game "$GAME"
PILOT_STATUS=$?
set -e

if [[ -d "$RUN_DIR" ]]; then
    [[ -f "$REPORT" ]] && cp "$REPORT" "$RUN_DIR/issue-1153-particle-probe.json"
    if [[ -f "$RUN_DIR/adapter.json" && -f "$REPORT" ]]; then
        jq \
            --arg route "$ROUTE" \
            --arg workloadId "$WORKLOAD_ID" \
            --arg commit "$(git rev-parse HEAD)" \
            --slurpfile probe "$REPORT" \
            '{issue:1153,
              experiment:"particle-probe",
              route:$route,
              workloadId:$workloadId,
              commit:$commit,
              measurementOverhead:.frameTimes.measurementOverhead,
              combatActive:.frameTimes.combatActive,
              combatAfterCampaignActive:.frameTimes.combatAfterCampaignActive,
              particleProbe:$probe[0],
              transformationsApplied,
              transformationsDeclined,
              containedFailures}' \
            "$RUN_DIR/adapter.json" >"$SUMMARY"
        cp "$SUMMARY" "$RUN_DIR/issue-1153-particle-probe-summary.json"
        jq . "$SUMMARY"
    fi
fi

echo "Issue #1153 particle probe session: $SESSION"
echo "Installed-game run: $RUN_DIR"
exit "$PILOT_STATUS"
