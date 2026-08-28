#!/usr/bin/env bash
#
# Run issue #1153's guarded GL11 glIsEnabled cache A/B on the reviewed LWJGL archive.
#
# Usage:
#   scripts/run-1153-gl-state-cache-pilot.sh --variant baseline|candidate --route ordinary|symmetric-1040 [--workload-id NAME] [--game DIR] [--label NAME] [--lwjgl-jar FILE] [gameplay-pilot options]
set -euo pipefail

GAME="${STARSECTOR_HOME:-/Applications/Starsector.app}"
VARIANT=""
ROUTE=""
WORKLOAD_ID=""
LABEL=""
LWJGL_JAR="${STARSECTOR_LWJGL_JAR:-}"
PILOT_EXTRA_ARGS=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        --variant) VARIANT="$2"; shift 2 ;;
        --route) ROUTE="$2"; shift 2 ;;
        --workload-id) WORKLOAD_ID="$2"; shift 2 ;;
        --game) GAME="$2"; shift 2 ;;
        --label) LABEL="$2"; shift 2 ;;
        --lwjgl-jar) LWJGL_JAR="$2"; shift 2 ;;
        --safer-jvm|--without-audio-repair|--without-profile|--without-startup-caches|--without-gameplay-caches)
            PILOT_EXTRA_ARGS+=("$1"); shift ;;
        --disable-plans)
            PILOT_EXTRA_ARGS+=("$1" "$2"); shift 2 ;;
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
[[ -x scripts/run-gameplay-pilot.sh ]] || { echo "Gameplay pilot is missing or not executable." >&2; exit 1; }

[[ -n "$WORKLOAD_ID" ]] || WORKLOAD_ID="$ROUTE"
[[ -n "$LABEL" ]] || LABEL="issue-1153-gl-state-cache-${VARIANT}-${ROUTE}"
case "$LABEL" in
    *[!A-Za-z0-9._-]*) echo "--label may contain only letters, digits, '.', '_' and '-'" >&2; exit 2 ;;
esac

STATE_ROOT="${STARSECTOR_PREFLIGHT_HOME:-$HOME/.starsector-preflight}"
SESSION="$STATE_ROOT/issue-1153/$LABEL-$(date +%Y%m%d-%H%M%S)"
TARGETS="$SESSION/adapter-targets.txt"
REPORT="$SESSION/gl-state-cache.json"
CONSOLE="$SESSION/pilot-console.log"
SUMMARY="$SESSION/summary.json"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/preflight-1153-gl-state.XXXXXX")"
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

if [[ -z "$LWJGL_JAR" ]]; then
    matches="$(find "$GAME" -type f -path '*/contents/resources/java/lwjgl.jar' -print 2>/dev/null | head -2)"
    if [[ "$(printf '%s\n' "$matches" | sed '/^$/d' | wc -l | tr -d '[:space:]')" != 1 ]]; then
        matches="$(find "$GAME" -type f -name lwjgl.jar -print 2>/dev/null | head -2)"
    fi
    [[ "$(printf '%s\n' "$matches" | sed '/^$/d' | wc -l | tr -d '[:space:]')" == 1 ]] || {
        echo "Could not resolve exactly one lwjgl.jar under $GAME; pass --lwjgl-jar." >&2
        exit 1
    }
    LWJGL_JAR="$matches"
fi
LWJGL_JAR="$(absolute_file "$LWJGL_JAR")" || { echo "LWJGL archive not found." >&2; exit 1; }

EXPECTED_LWJGL_SHA="527d509f60132e5b2653c7fc0f8cf299d6f698f4a8013342bef47705dc57ed3f"
ACTUAL_LWJGL_SHA="$(hash_file "$LWJGL_JAR")"
[[ "$ACTUAL_LWJGL_SHA" == "$EXPECTED_LWJGL_SHA" ]] || {
    echo "lwjgl.jar differs from the reviewed Starsector archive." >&2
    echo "Expected: $EXPECTED_LWJGL_SHA" >&2
    echo "Actual:   $ACTUAL_LWJGL_SHA" >&2
    exit 1
}

CLASS_FILE="$TMP/GL11.class"
extract_class "$LWJGL_JAR" "org/lwjgl/opengl/GL11.class" "$CLASS_FILE"
CLASS_SHA="$(hash_file "$CLASS_FILE")"
cat >"$TARGETS" <<EOF
target issue-1153-lwjgl-gl11-is-enabled-cache
class org/lwjgl/opengl/GL11
sha256 $CLASS_SHA
plan lwjgl-display-frame-time-probe-v1
source-kind STARSECTOR_CORE
source-suffix contents/resources/java/lwjgl.jar
source-sha256 $EXPECTED_LWJGL_SHA
loader-class jdk/internal/loader/ClassLoaders\$AppClassLoader
loader-name app
method glIsEnabled (I)Z
method glEnable (I)V
method glDisable (I)V
method glPushAttrib (I)V
method glPopAttrib ()V
method glNewList (II)V
method glEndList ()V
method glCallList (I)V
end
EOF

CACHE=false
[[ "$VARIANT" == candidate ]] && CACHE=true
export _JAVA_OPTIONS="${_JAVA_OPTIONS:+$_JAVA_OPTIONS }-Dpreflight.frameSync=false -Dpreflight.graphicsLibTessellateArray=false -Dpreflight.graphicsLibTessellatePackedReplay=false -Dpreflight.dynamicParticleGroupProbe=false -Dpreflight.glIsEnabledCache=$CACHE -Dpreflight.glIsEnabledCache.report='$REPORT'"

echo "Issue #1153 guarded glIsEnabled cache pilot"
echo "  variant:     $VARIANT"
echo "  route:       $ROUTE"
echo "  workload id: $WORKLOAD_ID"
echo "  GL11 sha:    $CLASS_SHA"
echo "  lwjgl sha:   $ACTUAL_LWJGL_SHA"
echo "  session:     $SESSION"
echo

PILOT_ARGS=(--game "$GAME" --label "$LABEL" --adapter-targets "$TARGETS")
PILOT_ARGS+=("${PILOT_EXTRA_ARGS[@]}")
set +e
scripts/run-gameplay-pilot.sh "${PILOT_ARGS[@]}" 2>&1 | tee "$CONSOLE"
PILOT_STATUS=${PIPESTATUS[0]}
set -e

PILOT_DIR="$(sed -n 's/^Full pilot data: //p' "$CONSOLE" | tail -1)"
if [[ -n "$PILOT_DIR" && -d "$PILOT_DIR" ]]; then
    cp "$TARGETS" "$PILOT_DIR/issue-1153-gl-state-target.txt"
    [[ -f "$REPORT" ]] && cp "$REPORT" "$PILOT_DIR/issue-1153-gl-state-cache.json"
    if [[ -f "$PILOT_DIR/adapter.json" && -f "$REPORT" ]]; then
        jq \
            --arg variant "$VARIANT" \
            --arg route "$ROUTE" \
            --arg workloadId "$WORKLOAD_ID" \
            --arg commit "$(git rev-parse HEAD)" \
            --slurpfile candidate "$REPORT" \
            'def combat_metrics:
                {frames, meanMicros, p50Micros, p95Micros, p99Micros, averageFps,
                 onePercentLowFps, over50Millis, over100Millis,
                 over50Per1000: (if .frames > 0 then (.over50Millis * 1000 / .frames) else null end),
                 over100Per1000: (if .frames > 0 then (.over100Millis * 1000 / .frames) else null end)};
             {issue:1153,
              experiment:"gl-state-cache",
              variant:$variant,
              route:$route,
              workloadId:$workloadId,
              commit:$commit,
              measurementOverhead:.frameTimes.measurementOverhead,
              combatActive:(.frameTimes.combatActive | combat_metrics),
              combatAfterCampaignActive:(.frameTimes.combatAfterCampaignActive | combat_metrics),
              candidate:$candidate[0],
              transformationsApplied,
              transformationsDeclined,
              containedFailures}' \
            "$PILOT_DIR/adapter.json" >"$SUMMARY"
        cp "$SUMMARY" "$PILOT_DIR/issue-1153-gl-state-cache-summary.json"
        jq . "$SUMMARY"
    fi
fi

echo "Issue #1153 gl-state cache session: $SESSION"
exit "$PILOT_STATUS"
