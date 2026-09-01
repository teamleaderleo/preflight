#!/usr/bin/env bash
#
# Profile vanilla DynamicParticleGroup.render(FF)V for issue #1153 with an exact installed-core target.
#
# Usage:
#   scripts/run-1153-particle-probe.sh --route ordinary|symmetric-1040 [--workload-id NAME] [--game DIR] [--label NAME] [--core-jar FILE] [gameplay-pilot options]
set -euo pipefail

GAME="${STARSECTOR_HOME:-/Applications/Starsector.app}"
ROUTE=""
WORKLOAD_ID=""
LABEL=""
CORE_JAR="${STARSECTOR_CORE_JAR:-}"
PILOT_EXTRA_ARGS=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        --route) ROUTE="$2"; shift 2 ;;
        --workload-id) WORKLOAD_ID="$2"; shift 2 ;;
        --game) GAME="$2"; shift 2 ;;
        --label) LABEL="$2"; shift 2 ;;
        --core-jar) CORE_JAR="$2"; shift 2 ;;
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

case "$ROUTE" in
    ordinary|symmetric-1040) ;;
    *) echo "--route must be ordinary or symmetric-1040" >&2; exit 2 ;;
esac

[[ -f pom.xml ]] || { echo "Run this from the Preflight repository root." >&2; exit 1; }
[[ -d "$GAME" ]] || { echo "Starsector installation not found: $GAME" >&2; exit 1; }
[[ -x scripts/run-gameplay-pilot.sh ]] || { echo "Gameplay pilot is missing or not executable." >&2; exit 1; }

[[ -n "$WORKLOAD_ID" ]] || WORKLOAD_ID="$ROUTE"
[[ -n "$LABEL" ]] || LABEL="issue-1153-particle-probe-${ROUTE}"
case "$LABEL" in
    *[!A-Za-z0-9._-]*) echo "--label may contain only letters, digits, '.', '_' and '-'" >&2; exit 2 ;;
esac

STATE_ROOT="${STARSECTOR_PREFLIGHT_HOME:-$HOME/.starsector-preflight}"
SESSION="$STATE_ROOT/issue-1153/$LABEL-$(date +%Y%m%d-%H%M%S)"
TARGETS="$SESSION/adapter-targets.txt"
REPORT="$SESSION/particle-probe.json"
CONSOLE="$SESSION/pilot-console.log"
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

if [[ -z "$CORE_JAR" ]]; then
    matches="$(find "$GAME" -type f -name starfarer_obf.jar -print 2>/dev/null | head -2)"
    [[ "$(printf '%s\n' "$matches" | sed '/^$/d' | wc -l | tr -d '[:space:]')" == 1 ]] || {
        echo "Could not resolve exactly one starfarer_obf.jar under $GAME; pass --core-jar." >&2
        exit 1
    }
    CORE_JAR="$matches"
fi
CORE_JAR="$(absolute_file "$CORE_JAR")" || { echo "Core archive not found." >&2; exit 1; }

EXPECTED_CORE_SHA="a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149"
ACTUAL_CORE_SHA="$(hash_file "$CORE_JAR")"
[[ "$ACTUAL_CORE_SHA" == "$EXPECTED_CORE_SHA" ]] || {
    echo "Core archive differs from reviewed Starsector 0.98a-RC8." >&2
    echo "Expected: $EXPECTED_CORE_SHA" >&2
    echo "Actual:   $ACTUAL_CORE_SHA" >&2
    exit 1
}

CLASS_FILE="$TMP/DynamicParticleGroup.class"
extract_class "$CORE_JAR" "com/fs/graphics/particle/DynamicParticleGroup.class" "$CLASS_FILE"
CLASS_SHA="$(hash_file "$CLASS_FILE")"
cat >"$TARGETS" <<EOF
target issue-1153-dynamic-particle-group-probe
class com/fs/graphics/particle/DynamicParticleGroup
sha256 $CLASS_SHA
plan lwjgl-display-frame-time-probe-v1
source-kind STARSECTOR_CORE
source-suffix contents/resources/java/starfarer_obf.jar
source-sha256 $EXPECTED_CORE_SHA
loader-class jdk/internal/loader/ClassLoaders\$AppClassLoader
loader-name app
method render (FF)V
end
EOF

export _JAVA_OPTIONS="${_JAVA_OPTIONS:+$_JAVA_OPTIONS }-Dpreflight.frameSync=false -Dpreflight.graphicsLibTessellateArray=false -Dpreflight.graphicsLibTessellatePackedReplay=false -Dpreflight.dynamicParticleGroupProbe=true -Dpreflight.dynamicParticleGroupProbe.report='$REPORT'"

echo "Issue #1153 vanilla particle render probe"
echo "  route:       $ROUTE"
echo "  workload id: $WORKLOAD_ID"
echo "  class sha:   $CLASS_SHA"
echo "  core sha:    $ACTUAL_CORE_SHA"
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
    cp "$TARGETS" "$PILOT_DIR/issue-1153-particle-target.txt"
    [[ -f "$REPORT" ]] && cp "$REPORT" "$PILOT_DIR/issue-1153-particle-probe.json"
    if [[ -f "$PILOT_DIR/adapter.json" && -f "$REPORT" ]]; then
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
            "$PILOT_DIR/adapter.json" >"$SUMMARY"
        cp "$SUMMARY" "$PILOT_DIR/issue-1153-particle-probe-summary.json"
        jq . "$SUMMARY"
    fi
fi

echo "Issue #1153 particle probe session: $SESSION"
exit "$PILOT_STATUS"
