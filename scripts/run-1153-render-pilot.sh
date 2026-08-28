#!/usr/bin/env bash
#
# Run one exact issue #1153 renderer A/B leg using the existing gameplay pilot.
#
# Usage:
#   scripts/run-1153-render-pilot.sh --experiment frame-sync|tess-array --variant baseline|candidate --route ordinary|symmetric-1040 [--workload-id NAME] [--game DIR] [--label NAME] [--core-jar FILE] [--graphics-jar FILE] [gameplay-pilot options]
#
# Run the same experiment/route/workload-id twice: once as baseline and once as candidate.
# The baseline keeps the same exact external target and adapter overhead while leaving the selected
# experiment disabled. The candidate flips only that experiment's runtime switch.
set -euo pipefail

GAME="${STARSECTOR_HOME:-/Applications/Starsector.app}"
EXPERIMENT=""
VARIANT=""
ROUTE=""
WORKLOAD_ID=""
LABEL=""
CORE_JAR="${STARSECTOR_CORE_JAR:-}"
GRAPHICS_JAR="${GRAPHICSLIB_JAR:-}"
PILOT_EXTRA_ARGS=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        --experiment) EXPERIMENT="$2"; shift 2 ;;
        --variant) VARIANT="$2"; shift 2 ;;
        --route) ROUTE="$2"; shift 2 ;;
        --workload-id) WORKLOAD_ID="$2"; shift 2 ;;
        --game) GAME="$2"; shift 2 ;;
        --label) LABEL="$2"; shift 2 ;;
        --core-jar) CORE_JAR="$2"; shift 2 ;;
        --graphics-jar) GRAPHICS_JAR="$2"; shift 2 ;;
        --safer-jvm|--without-audio-repair|--without-profile|--without-startup-caches|--without-gameplay-caches)
            PILOT_EXTRA_ARGS+=("$1"); shift ;;
        --disable-plans)
            PILOT_EXTRA_ARGS+=("$1" "$2"); shift 2 ;;
        -h|--help)
            sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *) echo "Unknown option: $1" >&2; exit 2 ;;
    esac
done

case "$EXPERIMENT" in
    frame-sync|tess-array) ;;
    *) echo "--experiment must be frame-sync or tess-array" >&2; exit 2 ;;
esac
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
[[ -x scripts/run-gameplay-pilot.sh ]] \
    || { echo "Gameplay pilot is missing or not executable." >&2; exit 1; }

if [[ -z "$WORKLOAD_ID" ]]; then
    WORKLOAD_ID="$ROUTE"
fi
if [[ -z "$LABEL" ]]; then
    LABEL="issue-1153-${EXPERIMENT}-${VARIANT}-${ROUTE}"
fi
case "$LABEL" in
    *[!A-Za-z0-9._-]*) echo "--label may contain only letters, digits, '.', '_' and '-'" >&2; exit 2 ;;
esac

PREFLIGHT_STATE_ROOT="${STARSECTOR_PREFLIGHT_HOME:-$HOME/.starsector-preflight}"
SESSION="$PREFLIGHT_STATE_ROOT/issue-1153/$LABEL-$(date +%Y%m%d-%H%M%S)"
TARGETS="$SESSION/adapter-targets.txt"
REPORT="$SESSION/${EXPERIMENT}.json"
CONSOLE="$SESSION/pilot-console.log"
SUMMARY="$SESSION/summary.json"
METADATA="$SESSION/run.txt"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/preflight-1153.XXXXXX")"
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

first_match() {
    local pattern="$1" matches
    matches="$(find "$GAME" -type f -path "$pattern" -print 2>/dev/null | head -2)"
    if [[ -z "$matches" ]]; then
        return 1
    fi
    if [[ "$(printf '%s\n' "$matches" | sed '/^$/d' | wc -l | tr -d '[:space:]')" != 1 ]]; then
        echo "Multiple archive matches for $pattern; pass an explicit archive path." >&2
        printf '%s\n' "$matches" >&2
        return 1
    fi
    printf '%s\n' "$matches"
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
        echo "Need unzip or jar to derive the exact class hash from $archive." >&2
        return 1
    fi
    [[ -s "$destination" ]] \
        || { echo "Could not extract $entry from $archive" >&2; return 1; }
}

PLAN_ID="lwjgl-display-frame-time-probe-v1"
ARCHIVE=""
ARCHIVE_EXPECTED=""
ARCHIVE_ACTUAL=""
CLASS_FILE="$TMP/target.class"
CLASS_SHA=""
TARGET_CLASS=""

if [[ "$EXPERIMENT" == frame-sync ]]; then
    ARCHIVE_EXPECTED="a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149"
    if [[ -z "$CORE_JAR" ]]; then
        CORE_JAR="$(first_match '*/starfarer_obf.jar')" \
            || { echo "Could not find starfarer_obf.jar under $GAME; pass --core-jar." >&2; exit 1; }
    fi
    ARCHIVE="$(absolute_file "$CORE_JAR")" \
        || { echo "Core archive not found: $CORE_JAR" >&2; exit 1; }
    ARCHIVE_ACTUAL="$(hash_file "$ARCHIVE")"
    [[ "$ARCHIVE_ACTUAL" == "$ARCHIVE_EXPECTED" ]] || {
        echo "Core archive differs from reviewed Starsector 0.98a-RC8." >&2
        echo "Expected: $ARCHIVE_EXPECTED" >&2
        echo "Actual:   $ARCHIVE_ACTUAL" >&2
        exit 1
    }
    extract_class "$ARCHIVE" "com/fs/starfarer/BaseGameState.class" "$CLASS_FILE"
    CLASS_SHA="$(hash_file "$CLASS_FILE")"
    TARGET_CLASS="com/fs/starfarer/BaseGameState"
    cat >"$TARGETS" <<EOF
target issue-1153-frame-sync-live
class com/fs/starfarer/BaseGameState
sha256 $CLASS_SHA
plan $PLAN_ID
source-kind STARSECTOR_CORE
source-suffix contents/resources/java/starfarer_obf.jar
source-sha256 $ARCHIVE_EXPECTED
loader-class jdk/internal/loader/ClassLoaders\$AppClassLoader
loader-name app
method traverse ()Ljava/lang/String;
end
EOF
else
    ARCHIVE_EXPECTED="832064013fe853731941e547842884ba121fb8b20eff08d24137f7a2c916903a"
    if [[ -z "$GRAPHICS_JAR" ]]; then
        GRAPHICS_JAR="$(first_match '*/mods/GraphicsLib/jars/Graphics.jar')" \
            || { echo "Could not find GraphicsLib/jars/Graphics.jar under $GAME; pass --graphics-jar." >&2; exit 1; }
    fi
    ARCHIVE="$(absolute_file "$GRAPHICS_JAR")" \
        || { echo "GraphicsLib archive not found: $GRAPHICS_JAR" >&2; exit 1; }
    ARCHIVE_ACTUAL="$(hash_file "$ARCHIVE")"
    [[ "$ARCHIVE_ACTUAL" == "$ARCHIVE_EXPECTED" ]] || {
        echo "GraphicsLib archive differs from the reviewed 1.12.1 build." >&2
        echo "Expected: $ARCHIVE_EXPECTED" >&2
        echo "Actual:   $ARCHIVE_ACTUAL" >&2
        exit 1
    }
    extract_class "$ARCHIVE" "org/dark/graphics/util/Tessellate.class" "$CLASS_FILE"
    CLASS_SHA="$(hash_file "$CLASS_FILE")"
    TARGET_CLASS="org/dark/graphics/util/Tessellate"
    cat >"$TARGETS" <<EOF
target issue-1153-graphicslib-tessellate-array-live
class org/dark/graphics/util/Tessellate
sha256 $CLASS_SHA
plan $PLAN_ID
source-kind MOD
source-suffix graphics.jar
source-sha256 $ARCHIVE_EXPECTED
loader-class java/net/URLClassLoader
method render (Lcom/fs/starfarer/api/combat/BoundsAPI;FFFLcom/fs/starfarer/api/combat/ShipAPI;)V
end
EOF
fi

FRAME_SYNC=false
TESS_ARRAY=false
if [[ "$VARIANT" == candidate ]]; then
    if [[ "$EXPERIMENT" == frame-sync ]]; then
        FRAME_SYNC=true
    else
        TESS_ARRAY=true
    fi
fi

JAVA_EXPERIMENT_OPTIONS="-Dpreflight.frameSync=$FRAME_SYNC -Dpreflight.graphicsLibTessellateArray=$TESS_ARRAY"
if [[ "$EXPERIMENT" == frame-sync ]]; then
    JAVA_EXPERIMENT_OPTIONS+=" -Dpreflight.frameSync.report='$REPORT'"
else
    JAVA_EXPERIMENT_OPTIONS+=" -Dpreflight.graphicsLibTessellateArray.report='$REPORT'"
fi
export _JAVA_OPTIONS="${_JAVA_OPTIONS:+$_JAVA_OPTIONS }$JAVA_EXPERIMENT_OPTIONS"

cat >"$METADATA" <<EOF
issue=1153
commit=$(git rev-parse HEAD)
experiment=$EXPERIMENT
variant=$VARIANT
route=$ROUTE
workloadId=$WORKLOAD_ID
label=$LABEL
game=$GAME
targetFile=$TARGETS
targetClass=$TARGET_CLASS
targetClassSha256=$CLASS_SHA
sourceArchive=$ARCHIVE
sourceArchiveSha256=$ARCHIVE_ACTUAL
candidateReport=$REPORT
frameSyncEnabled=$FRAME_SYNC
graphicsLibTessellateArrayEnabled=$TESS_ARRAY
EOF

echo "Issue #1153 renderer pilot"
echo "  experiment:  $EXPERIMENT"
echo "  variant:     $VARIANT"
echo "  route:       $ROUTE"
echo "  workload id: $WORKLOAD_ID"
echo "  target:      $TARGET_CLASS @ $CLASS_SHA"
echo "  source:      $ARCHIVE_ACTUAL"
echo "  session:     $SESSION"
echo
if [[ "$ROUTE" == symmetric-1040 ]]; then
    echo "Run the symmetric 1,040-DP stress route from #449/#1152."
    echo "Keep both sides, fleet composition, deployment, camera behavior, and duration identical across the pair."
else
    echo "Run the ordinary combat route from #449/#1152."
    echo "Keep save, simulation, fleet composition, deployment, camera behavior, and duration identical across the pair."
fi
echo "Use the same --workload-id for the matching baseline/candidate pair."
echo

PILOT_ARGS=(--game "$GAME" --label "$LABEL" --adapter-targets "$TARGETS")
PILOT_ARGS+=("${PILOT_EXTRA_ARGS[@]}")

set +e
scripts/run-gameplay-pilot.sh "${PILOT_ARGS[@]}" 2>&1 | tee "$CONSOLE"
PILOT_STATUS=${PIPESTATUS[0]}
set -e

PILOT_DIR="$(sed -n 's/^Full pilot data: //p' "$CONSOLE" | tail -1)"
if [[ -n "$PILOT_DIR" && -d "$PILOT_DIR" ]]; then
    echo "pilotDirectory=$PILOT_DIR" >>"$METADATA"
    cp "$TARGETS" "$PILOT_DIR/issue-1153-adapter-targets.txt"
    cp "$METADATA" "$PILOT_DIR/issue-1153-run.txt"
    [[ -f "$REPORT" ]] && cp "$REPORT" "$PILOT_DIR/issue-1153-${EXPERIMENT}.json"

    if [[ -f "$PILOT_DIR/adapter.json" && -f "$REPORT" ]]; then
        jq \
            --arg experiment "$EXPERIMENT" \
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
             {issue: 1153,
              experiment: $experiment,
              variant: $variant,
              route: $route,
              workloadId: $workloadId,
              commit: $commit,
              measurementOverhead: .frameTimes.measurementOverhead,
              combatActive: (.frameTimes.combatActive | combat_metrics),
              combatAfterCampaignActive: (.frameTimes.combatAfterCampaignActive | combat_metrics),
              candidate: $candidate[0],
              transformationsApplied,
              transformationsDeclined,
              containedFailures}' \
            "$PILOT_DIR/adapter.json" >"$SUMMARY"
        cp "$SUMMARY" "$PILOT_DIR/issue-1153-summary.json"
        echo
        echo "Issue #1153 summary:"
        jq . "$SUMMARY"
    elif [[ ! -f "$REPORT" ]]; then
        echo "Selected candidate report was not produced; use adapter.json exact-match diagnostics to see whether the target class loaded." >&2
    fi
fi

echo "Issue #1153 session: $SESSION"
exit "$PILOT_STATUS"
