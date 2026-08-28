#!/usr/bin/env bash
#
# Run one unattended, exact issue #1153 GraphicsLib tessellation-array A/B leg.
#
# Usage:
#   scripts/run-1153-tess-array-pilot.sh --variant baseline|candidate --route ordinary|symmetric-1040 [--workload-id ID] [--label LABEL] [--game DIR]
set -euo pipefail

GAME="${STARSECTOR_HOME:-/Applications/Starsector.app}"
VARIANT=""
ROUTE=""
WORKLOAD_ID=""
LABEL=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --variant) VARIANT="$2"; shift 2 ;;
        --route) ROUTE="$2"; shift 2 ;;
        --workload-id) WORKLOAD_ID="$2"; shift 2 ;;
        --label) LABEL="$2"; shift 2 ;;
        --game) GAME="$2"; shift 2 ;;
        -h|--help)
            sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *) echo "Unknown option: $1" >&2; exit 2 ;;
    esac
done

case "$VARIANT" in
    baseline|candidate) ;;
    *) echo "--variant must be baseline or candidate" >&2; exit 2 ;;
esac
case "$ROUTE" in
    ordinary|symmetric-1040) ;;
    *) echo "--route must be ordinary or symmetric-1040" >&2; exit 2 ;;
esac
[[ -f pom.xml ]] || { echo "Run this from the Preflight repository root." >&2; exit 1; }
[[ -d "$GAME" ]] || { echo "Starsector installation not found: $GAME" >&2; exit 1; }

if [[ -z "$WORKLOAD_ID" ]]; then
    WORKLOAD_ID="$ROUTE"
fi
if [[ -z "$LABEL" ]]; then
    LABEL="issue-1153-tess-array-${VARIANT}-${ROUTE}"
fi
case "$LABEL" in
    *[!A-Za-z0-9._-]*) echo "--label may contain only letters, digits, '.', '_' and '-'." >&2; exit 2 ;;
esac

STATE_ROOT="${STARSECTOR_PREFLIGHT_HOME:-$HOME/.starsector-preflight}"
SESSION="$STATE_ROOT/issue-1153/$LABEL-$(date +%Y%m%d-%H%M%S)"
REPORT="$SESSION/tess-array.json"
SUMMARY="$SESSION/summary.json"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/preflight-1153-tess.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$SESSION"

hash_file() {
    shasum -a 256 "$1" | awk '{print $1}'
}

matches="$(find "$GAME/mods" -type f -iname Graphics.jar -print 2>/dev/null | head -2)"
[[ "$(printf '%s\n' "$matches" | sed '/^$/d' | wc -l | tr -d '[:space:]')" == 1 ]] || {
    echo "Could not resolve exactly one Graphics.jar under $GAME/mods." >&2
    exit 1
}
GRAPHICS_JAR="$matches"
EXPECTED_ARCHIVE_SHA="832064013fe853731941e547842884ba121fb8b20eff08d24137f7a2c916903a"
EXPECTED_CLASS_SHA="0e25f52eb84a184bd426afaa69372a49d57befe672bda88a71691e09facfeacf"
ARCHIVE_SHA="$(hash_file "$GRAPHICS_JAR")"
[[ "$ARCHIVE_SHA" == "$EXPECTED_ARCHIVE_SHA" ]] || {
    echo "Graphics.jar differs from reviewed GraphicsLib 1.12.1." >&2
    echo "Expected: $EXPECTED_ARCHIVE_SHA" >&2
    echo "Actual:   $ARCHIVE_SHA" >&2
    exit 1
}
unzip -p "$GRAPHICS_JAR" org/dark/graphics/util/Tessellate.class >"$TMP/Tessellate.class"
CLASS_SHA="$(hash_file "$TMP/Tessellate.class")"
[[ "$CLASS_SHA" == "$EXPECTED_CLASS_SHA" ]] || {
    echo "Tessellate.class differs from the reviewed exact target." >&2
    echo "Expected: $EXPECTED_CLASS_SHA" >&2
    echo "Actual:   $CLASS_SHA" >&2
    exit 1
}

if [[ "$VARIANT" == candidate ]]; then
    ENABLED=true
else
    ENABLED=false
fi
export _JAVA_OPTIONS="${_JAVA_OPTIONS:+$_JAVA_OPTIONS }-Dpreflight.frameSync=false -Dpreflight.dynamicParticleGroupProbe=false -Dpreflight.graphicsLibTessellateArray=$ENABLED -Dpreflight.graphicsLibTessellatePackedReplay=false -Dpreflight.graphicsLibTessellateWorldReplay=false -Dpreflight.graphicsLibTessellateArray.report='$REPORT'"

if [[ "$ROUTE" == ordinary ]]; then
    SCENARIO="scripts/scenarios/campaign-simulation-combat-particle-sanity.json"
else
    SCENARIO="scripts/scenarios/campaign-simulation-combat-1000dp-thin.json"
fi
RUN_DIR="$STATE_ROOT/runs/$LABEL-$(date +%Y%m%d-%H%M%S)"

echo "Issue #1153 GraphicsLib tessellation-array pilot"
echo "  variant:     $VARIANT"
echo "  route:       $ROUTE"
echo "  workload id: $WORKLOAD_ID"
echo "  archive sha: $ARCHIVE_SHA"
echo "  class sha:   $CLASS_SHA"
echo "  session:     $SESSION"
echo

set +e
java -jar preflight-cli/target/preflight.jar desktop smoke launch \
    "$SCENARIO" "$RUN_DIR" --game "$GAME"
PILOT_STATUS=$?
set -e

if [[ -d "$RUN_DIR" ]]; then
    [[ -f "$REPORT" ]] && cp "$REPORT" "$RUN_DIR/issue-1153-tess-array.json"
    if [[ -f "$RUN_DIR/adapter.json" && -f "$REPORT" ]]; then
        jq \
            --arg variant "$VARIANT" \
            --arg route "$ROUTE" \
            --arg workloadId "$WORKLOAD_ID" \
            --arg commit "$(git rev-parse HEAD)" \
            --arg archiveSha "$ARCHIVE_SHA" \
            --arg classSha "$CLASS_SHA" \
            --slurpfile candidate "$REPORT" \
            '{issue:1153,
              experiment:"graphicslib-tessellate-array",
              variant:$variant,
              route:$route,
              workloadId:$workloadId,
              commit:$commit,
              archiveSha256:$archiveSha,
              classSha256:$classSha,
              measurementOverhead:.frameTimes.measurementOverhead,
              selectedFrameScope:(if $route == "symmetric-1040"
                then .frameTimes.measurementWindow else .frameTimes.combatActive end),
              workloadFingerprint:.frameTimes.combatWorkloadFingerprint,
              presentationPolicy:.frameTimes.presentationPolicy,
              candidate:$candidate[0],
              transformationsApplied,
              transformationsDeclined,
              containedFailures}' \
            "$RUN_DIR/adapter.json" >"$SUMMARY"
        cp "$SUMMARY" "$RUN_DIR/issue-1153-tess-array-summary.json"
        jq '{issue,
             experiment,
             variant,
             route,
             workloadId,
             commit,
             candidate,
             frame:(.selectedFrameScope | {
               frames,
               totalActiveNanos,
               p50Micros,
               p95Micros,
               p99Micros,
               onePercentLowFps,
               maximumMicros,
               over50Millis,
               over100Millis
             }),
             workload:(.workloadFingerprint | {
               recipeId,
               requestedBattleDp,
               combatSecondsElapsed,
               sideZeroNonFighterLosses,
               sideOneNonFighterLosses
             }),
             transformationsApplied,
             transformationsDeclined,
             containedFailures}' "$SUMMARY"
    fi
fi

echo "Issue #1153 tessellation-array session: $SESSION"
echo "Installed-game run: $RUN_DIR"
exit "$PILOT_STATUS"
