#!/usr/bin/env bash
#
# Run one opt-in installed-game #1158 owner-tax/JFR discovery pass and build compact joins.
#
# Usage:
#   scripts/run-1158-owner-tax-discovery.sh [--game DIR] [--label NAME]
set -euo pipefail

GAME="${STARSECTOR_HOME:-/Applications/Starsector.app}"
LABEL="issue-1158-owner-tax"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --game) GAME="$2"; shift 2 ;;
        --label) LABEL="$2"; shift 2 ;;
        -h|--help)
            sed -n '2,6p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *) echo "Unknown option: $1" >&2; exit 2 ;;
    esac
done

[[ -f pom.xml ]] || { echo "Run this from the Preflight repository root." >&2; exit 1; }
[[ -d "$GAME" ]] || { echo "Starsector installation not found: $GAME" >&2; exit 1; }
[[ -f preflight-cli/target/preflight.jar ]] \
    || { echo "Build preflight-cli/target/preflight.jar before running discovery." >&2; exit 1; }
case "$LABEL" in
    *[!A-Za-z0-9._-]*) echo "--label may contain only letters, digits, '.', '_' and '-'." >&2; exit 2 ;;
esac

STATE_ROOT="${STARSECTOR_PREFLIGHT_HOME:-$HOME/.starsector-preflight}"
STAMP="$(date +%Y%m%d-%H%M%S)"
SESSION="$STATE_ROOT/issue-1158/$LABEL-$STAMP"
RUN_DIR="$STATE_ROOT/runs/$LABEL-$STAMP"
SCENARIO="scripts/scenarios/campaign-owner-tax-paused-unpaused.json"
HOT_PATTERNS="$SESSION/hot-patterns.json"
TRIAGE="$SESSION/mod-tax-triage.json"
JVM_JOIN="$SESSION/jvm-hitch-correlation.json"
INFLATION_FRAME_JOIN="$SESSION/fleet-inflation-frame-join.json"
AUTOFIT_FRAME_JOIN="$SESSION/core-autofit-frame-join.json"
SUMMARY="$SESSION/summary.json"
mkdir -p "$SESSION"

echo "Issue #1158 installed owner-tax discovery"
echo "  commit:  $(git rev-parse HEAD)"
echo "  session: $SESSION"
echo "  run:     $RUN_DIR"
echo

java -jar preflight-cli/target/preflight.jar classpath hot-patterns \
    --game "$GAME" --limit 500 --json "$HOT_PATTERNS"

SMOKE_COMMAND=(java -jar preflight-cli/target/preflight.jar desktop smoke launch
    "$SCENARIO" "$RUN_DIR" --game "$GAME")
set +e
if [[ "$(uname -s)" == "Darwin" ]] && command -v caffeinate >/dev/null 2>&1; then
    # Scope idle/display sleep prevention to the owned foreground run. This does not move the
    # pointer, bypass a locked console, or leave a helper alive after the smoke command exits.
    caffeinate -dimsu "${SMOKE_COMMAND[@]}"
else
    "${SMOKE_COMMAND[@]}"
fi
RUN_STATUS=$?
set -e
POSTPROCESS_STATUS=0

if [[ -f "$RUN_DIR/runtime-frame-report.json" ]]; then
    if ! python3 scripts/starsector_mod_tax_triage.py \
        "$RUN_DIR/runtime-frame-report.json" "$HOT_PATTERNS" >"$TRIAGE"; then
        echo "Owner-tax triage failed; preserving the installed-game run." >&2
        rm -f "$TRIAGE"
        POSTPROCESS_STATUS=1
    fi
fi
if [[ -f "$RUN_DIR/startup.jfr" && -f "$RUN_DIR/runtime-frame-report.json" ]]; then
    if ! python3 scripts/starsector_jvm_hitch_correlation.py \
        "$RUN_DIR/startup.jfr" "$RUN_DIR/runtime-frame-report.json" >"$JVM_JOIN"; then
        echo "JVM hitch correlation failed; preserving the installed-game run." >&2
        rm -f "$JVM_JOIN"
        POSTPROCESS_STATUS=1
    fi
fi
if [[ -f "$RUN_DIR/runtime-frame-report.json" ]]; then
    if ! python3 scripts/starsector_slow_span_frames.py \
        "$RUN_DIR/runtime-frame-report.json" \
        --telemetry fleetInflationTimes \
        --frame-series campaignUnpausedActive \
        --json >"$INFLATION_FRAME_JOIN"; then
        echo "Fleet-inflation frame join failed; preserving the installed-game run." >&2
        rm -f "$INFLATION_FRAME_JOIN"
        POSTPROCESS_STATUS=1
    fi
fi
if [[ -f "$RUN_DIR/runtime-frame-report.json" ]]; then
    if ! python3 scripts/starsector_slow_span_frames.py \
        "$RUN_DIR/runtime-frame-report.json" \
        --telemetry coreAutofitTimes \
        --frame-series campaignUnpausedActive \
        --json >"$AUTOFIT_FRAME_JOIN"; then
        echo "Core-autofit frame join failed; preserving the installed-game run." >&2
        rm -f "$AUTOFIT_FRAME_JOIN"
        POSTPROCESS_STATUS=1
    fi
fi

if [[ -f "$RUN_DIR/runtime-frame-report.json" ]]; then
    TRIAGE_INPUT=/dev/null
    JVM_INPUT=/dev/null
    INFLATION_FRAME_INPUT=/dev/null
    AUTOFIT_FRAME_INPUT=/dev/null
    [[ -f "$TRIAGE" ]] && TRIAGE_INPUT="$TRIAGE"
    [[ -f "$JVM_JOIN" ]] && JVM_INPUT="$JVM_JOIN"
    [[ -f "$INFLATION_FRAME_JOIN" ]] && INFLATION_FRAME_INPUT="$INFLATION_FRAME_JOIN"
    [[ -f "$AUTOFIT_FRAME_JOIN" ]] && AUTOFIT_FRAME_INPUT="$AUTOFIT_FRAME_JOIN"
    jq \
        --arg commit "$(git rev-parse HEAD)" \
        --arg run "$RUN_DIR" \
        --slurpfile triage "$TRIAGE_INPUT" \
        --slurpfile jvm "$JVM_INPUT" \
        --slurpfile inflationFrames "$INFLATION_FRAME_INPUT" \
        --slurpfile autofitFrames "$AUTOFIT_FRAME_INPUT" \
        '{issue:1158,
          classification:"intrusive-discovery-no-fps-claim",
          commit:$commit,
          runDirectory:$run,
          frameTimes:(.frameTimes | {
            measurementOverhead,
            campaignFirst30SecondsActive,
            campaignAfter30SecondsActive,
            campaignPausedActive,
            campaignPausedAfter30SecondsActive,
            campaignUnpausedActive,
            campaignUnpausedAfter30SecondsActive,
            measurementWindow
          }),
          ownerTaxFamilies:{
            campaignEngineTimesOwnerTax:.campaignEngineTimes.scriptOwnerTax,
            campaignLocationEconomyTimesOwnerTax:{
              locationScriptClassesOwnerTax:.campaignLocationEconomyTimes.locationScriptClassesOwnerTax,
              entityActiveClassesOwnerTax:.campaignLocationEconomyTimes.entityActiveClassesOwnerTax,
              entityPausedClassesOwnerTax:.campaignLocationEconomyTimes.entityPausedClassesOwnerTax
            },
            campaignMarketFleetTimesOwnerTax:{
              marketConditionClassesOwnerTax:.campaignMarketFleetTimes.marketConditionClassesOwnerTax,
              marketIndustryClassesOwnerTax:.campaignMarketFleetTimes.marketIndustryClassesOwnerTax,
              marketSubmarketClassesOwnerTax:.campaignMarketFleetTimes.marketSubmarketClassesOwnerTax,
              fleetAiClassesOwnerTax:.campaignMarketFleetTimes.fleetAiClassesOwnerTax,
              fleetHullmodFleetClassesOwnerTax:.campaignMarketFleetTimes.fleetHullmodFleetClassesOwnerTax,
              fleetHullmodShipClassesOwnerTax:.campaignMarketFleetTimes.fleetHullmodShipClassesOwnerTax
            }
          },
          fleetAiModuleTimes:.fleetAiModuleTimes,
          tacticalFleetAiTimes:.tacticalFleetAiTimes,
          fleetInflationTimes:.fleetInflationTimes,
          fleetInflationFrameJoin:($inflationFrames[0] // null),
          coreAutofitTimes:.coreAutofitTimes,
          coreAutofitFrameJoin:($autofitFrames[0] // null),
          triage:($triage[0] // null),
          jvmHitchCorrelation:($jvm[0] // null)}' \
        "$RUN_DIR/runtime-frame-report.json" >"$SUMMARY"
    cp "$SUMMARY" "$RUN_DIR/issue-1158-owner-tax-summary.json"
    jq '{issue,
         classification,
         commit,
         runDirectory,
         frameTimes,
         runtimeOwnerTaxFamilies:(.triage.runtimeOwnerTaxFamilies // 0),
         runtimeObservedModCount:(.triage.runtimeObservedModCount // 0),
         unresolvedRuntimeOwnerCount:(.triage.unresolvedRuntimeOwners | length),
         fleetAiModuleTimes,
         tacticalFleetAiTimes,
         fleetInflation:{
           installed:.fleetInflationTimes.installed,
           membersVisited:.fleetInflationTimes.membersVisited,
           phases:(.fleetInflationTimes.phases | map({
             name,calls,totalMillis,maximumMillis,over16Millis,over33Millis,over50Millis,over100Millis
           }))
         },
         fleetInflationFrameJoins:(((.fleetInflationFrameJoin.joins // [])[:8]) | map({
           span,durationMillis,frameDurationMillis,overlapShareOfFramePercent,
           spanShareOfFramePercent,containedByFrame
         })),
         coreAutofit:{
           installed:.coreAutofitTimes.installed,
           phases:(.coreAutofitTimes.phases | map({
             name,calls,totalMillis,maximumMillis,over16Millis,over33Millis,over50Millis,over100Millis
           }))
         },
         coreAutofitFrameJoins:(((.coreAutofitFrameJoin.joins // [])[:8]) | map({
           span,durationMillis,frameDurationMillis,overlapShareOfFramePercent,
           spanShareOfFramePercent,containedByFrame
         })),
         topMods:(.triage.mods[:12] | map({modId,priority,bestFrameTaxRank})),
         retainedHitchFrames:(.jvmHitchCorrelation.retainedHitchFrames // 0),
         retainedSevereHitchFrames:(.jvmHitchCorrelation.retainedSevereHitchFrames // 0),
         jvmHitchSummary:(.jvmHitchCorrelation.summary // null)}' "$SUMMARY"
fi

echo "Issue #1158 session: $SESSION"
echo "Installed-game run: $RUN_DIR"
if [[ "$RUN_STATUS" -ne 0 ]]; then
    exit "$RUN_STATUS"
fi
exit "$POSTPROCESS_STATUS"
