#!/usr/bin/env bash
#
# Run one opt-in installed-game #1158 owner-tax/JFR discovery pass and build compact joins.
#
# Usage:
#   scripts/run-1158-owner-tax-discovery.sh [--game DIR] [--label NAME]
#       [--focus nex-economy]
#       [--market-list-mode discovery|shadow|baseline|candidate]
set -euo pipefail

GAME="${STARSECTOR_HOME:-/Applications/Starsector.app}"
LABEL="issue-1158-owner-tax"
FOCUS="all"
MARKET_LIST_MODE="discovery"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --game) GAME="$2"; shift 2 ;;
        --label) LABEL="$2"; shift 2 ;;
        --focus) FOCUS="$2"; shift 2 ;;
        --market-list-mode) MARKET_LIST_MODE="$2"; shift 2 ;;
        -h|--help)
            sed -n '2,7p' "$0" | sed 's/^# \{0,1\}//'
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
case "$FOCUS" in
    all|nex-economy) ;;
    *) echo "--focus must be 'all' or 'nex-economy'." >&2; exit 2 ;;
esac
case "$MARKET_LIST_MODE" in
    discovery|shadow|baseline|candidate) ;;
    *) echo "--market-list-mode must be discovery, shadow, baseline, or candidate." >&2; exit 2 ;;
esac
if [[ "$MARKET_LIST_MODE" != "discovery" && "$FOCUS" != "nex-economy" ]]; then
    echo "Non-discovery market-list modes require --focus nex-economy." >&2
    exit 2
fi

STATE_ROOT="${STARSECTOR_PREFLIGHT_HOME:-$HOME/.starsector-preflight}"
STAMP="$(date +%Y%m%d-%H%M%S)"
SESSION="$STATE_ROOT/issue-1158/$LABEL-$STAMP"
RUN_DIR="$STATE_ROOT/runs/$LABEL-$STAMP"
SCENARIO="scripts/scenarios/campaign-owner-tax-paused-unpaused.json"
if [[ "$FOCUS" == "nex-economy" ]]; then
    SCENARIO="scripts/scenarios/campaign-nex-economy-info-paused-unpaused.json"
fi
if [[ "$MARKET_LIST_MODE" == "baseline" || "$MARKET_LIST_MODE" == "candidate" ]]; then
    SCENARIO="scripts/scenarios/campaign-nex-economy-info-paused-unpaused-thin.json"
fi
HOT_PATTERNS="$SESSION/hot-patterns.json"
TRIAGE="$SESSION/mod-tax-triage.json"
JVM_JOIN="$SESSION/jvm-hitch-correlation.json"
INFLATION_FRAME_JOIN="$SESSION/fleet-inflation-frame-join.json"
AUTOFIT_FRAME_JOIN="$SESSION/core-autofit-frame-join.json"
NEX_ECONOMY_FRAME_JOIN="$SESSION/nex-economy-info-frame-join.json"
SUMMARY="$SESSION/summary.json"
COMPACT_SUMMARY="$SESSION/compact-summary.json"
SAVE_IDENTITY_BEFORE="$SESSION/save-identity-before.json"
SAVE_IDENTITY="$SESSION/save-identity.json"
mkdir -p "$SESSION"

echo "Issue #1158 installed owner-tax discovery"
echo "  commit:  $(git rev-parse HEAD)"
echo "  session: $SESSION"
echo "  run:     $RUN_DIR"
echo "  focus:   $FOCUS"
echo "  market-list mode: $MARKET_LIST_MODE"
echo

# Name and hash the Continue save before launch. The post-run capture below proves that a thin
# comparison loaded the same bytes and did not silently autosave a different workload.
if ! python3 scripts/capture_loaded_save_identity.py --game "$GAME" \
    >"$SAVE_IDENTITY_BEFORE.tmp"; then
    rm -f "$SAVE_IDENTITY_BEFORE.tmp"
    if [[ "$MARKET_LIST_MODE" == "baseline" || "$MARKET_LIST_MODE" == "candidate" ]]; then
        echo "Pre-run loaded-save identity unavailable; refusing to waste a thin cohort launch." >&2
        exit 1
    fi
    echo "Pre-run loaded-save identity unavailable; discovery may proceed without a cohort claim." >&2
else
    mv "$SAVE_IDENTITY_BEFORE.tmp" "$SAVE_IDENTITY_BEFORE"
fi

if [[ "$FOCUS" == "all" ]]; then
    java -jar preflight-cli/target/preflight.jar classpath hot-patterns \
        --game "$GAME" --limit 500 --json "$HOT_PATTERNS"
fi

SMOKE_COMMAND=(java -jar preflight-cli/target/preflight.jar desktop smoke launch
    "$SCENARIO" "$RUN_DIR" --game "$GAME")
if [[ "$FOCUS" == "nex-economy" ]]; then
    FOCUS_DISABLED_PLANS="campaign-catch-up-call-time-probe-v1,campaign-engine-call-time-probe-v1,campaign-location-economy-call-time-probe-v1,campaign-market-fleet-call-time-probe-v1,vanilla-fleet-ai-profiler-label-v1,vanilla-tactical-fleet-ai-time-probe-v1,vanilla-default-fleet-inflater-time-probe-v1,vanilla-core-autofit-time-probe-v1"
    if [[ -n "${PREFLIGHT_DISABLE_ADAPTER_PLANS:-}" ]]; then
        FOCUS_DISABLED_PLANS="$PREFLIGHT_DISABLE_ADAPTER_PLANS,$FOCUS_DISABLED_PLANS"
    fi
    if [[ "$MARKET_LIST_MODE" == "baseline" ]]; then
        FOCUS_DISABLED_PLANS="$FOCUS_DISABLED_PLANS,nexerelin-market-list-scope-v1"
    fi
    SMOKE_COMMAND=(env "PREFLIGHT_DISABLE_ADAPTER_PLANS=$FOCUS_DISABLED_PLANS"
        "${SMOKE_COMMAND[@]}")
fi
if [[ "$MARKET_LIST_MODE" == "shadow" || "$MARKET_LIST_MODE" == "candidate" ]]; then
    MARKET_LIST_PROPERTY="-Dpreflight.campaign.nexMarketListScope=true"
    if [[ "$MARKET_LIST_MODE" == "shadow" ]]; then
        MARKET_LIST_PROPERTY="-Dpreflight.campaign.nexMarketListScope.shadow=true"
    fi
    MARKET_LIST_JAVA_OPTIONS="${JAVA_TOOL_OPTIONS:-}"
    MARKET_LIST_JAVA_OPTIONS="${MARKET_LIST_JAVA_OPTIONS:+$MARKET_LIST_JAVA_OPTIONS }$MARKET_LIST_PROPERTY"
    SMOKE_COMMAND=(env "JAVA_TOOL_OPTIONS=$MARKET_LIST_JAVA_OPTIONS" "${SMOKE_COMMAND[@]}")
fi
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

SAVE_IDENTITY_COMMAND=(python3 scripts/capture_loaded_save_identity.py --game "$GAME")
if [[ -f "$SAVE_IDENTITY_BEFORE" ]]; then
    SAVE_IDENTITY_COMMAND+=(--before "$SAVE_IDENTITY_BEFORE")
fi
if ! "${SAVE_IDENTITY_COMMAND[@]}" >"$SAVE_IDENTITY.tmp"; then
    echo "Post-run loaded-save identity unavailable; preserving the installed-game run." >&2
    rm -f "$SAVE_IDENTITY.tmp"
    if [[ "$MARKET_LIST_MODE" == "baseline" || "$MARKET_LIST_MODE" == "candidate" ]]; then
        POSTPROCESS_STATUS=1
    fi
else
    mv "$SAVE_IDENTITY.tmp" "$SAVE_IDENTITY"
    [[ -d "$RUN_DIR" ]] && cp "$SAVE_IDENTITY" "$RUN_DIR/save-identity.json"
    if [[ "$MARKET_LIST_MODE" == "baseline" || "$MARKET_LIST_MODE" == "candidate" ]]; then
        if ! jq -e '.comparison.beforeAvailable == true
            and .comparison.sameSelectedSave == true
            and .comparison.contentUnchanged == true' "$SAVE_IDENTITY" >/dev/null; then
            echo "Thin comparison save identity changed or lacks a pre-run identity; rejecting the run." >&2
            POSTPROCESS_STATUS=1
        fi
    fi
fi

if [[ -f "$RUN_DIR/runtime-frame-report.json" && -f "$HOT_PATTERNS" ]]; then
    if ! python3 scripts/starsector_mod_tax_triage.py \
        "$RUN_DIR/runtime-frame-report.json" "$HOT_PATTERNS" >"$TRIAGE"; then
        echo "Owner-tax triage failed; preserving the installed-game run." >&2
        rm -f "$TRIAGE"
        POSTPROCESS_STATUS=1
    fi
fi
if [[ -f "$RUN_DIR/runtime-frame-report.json" ]]; then
    if ! python3 scripts/starsector_slow_span_frames.py \
        "$RUN_DIR/runtime-frame-report.json" \
        --telemetry nexEconomyInfoTimes \
        --frame-series campaignUnpausedActive \
        --json >"$NEX_ECONOMY_FRAME_JOIN"; then
        echo "Nexerelin economy-info frame join failed; preserving the installed-game run." >&2
        rm -f "$NEX_ECONOMY_FRAME_JOIN"
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
    NEX_ECONOMY_FRAME_INPUT=/dev/null
    SAVE_IDENTITY_INPUT=/dev/null
    [[ -f "$TRIAGE" ]] && TRIAGE_INPUT="$TRIAGE"
    [[ -f "$JVM_JOIN" ]] && JVM_INPUT="$JVM_JOIN"
    [[ -f "$INFLATION_FRAME_JOIN" ]] && INFLATION_FRAME_INPUT="$INFLATION_FRAME_JOIN"
    [[ -f "$AUTOFIT_FRAME_JOIN" ]] && AUTOFIT_FRAME_INPUT="$AUTOFIT_FRAME_JOIN"
    [[ -f "$NEX_ECONOMY_FRAME_JOIN" ]] && NEX_ECONOMY_FRAME_INPUT="$NEX_ECONOMY_FRAME_JOIN"
    [[ -f "$SAVE_IDENTITY" ]] && SAVE_IDENTITY_INPUT="$SAVE_IDENTITY"
    jq \
        --arg commit "$(git rev-parse HEAD)" \
        --arg run "$RUN_DIR" \
        --arg marketListMode "$MARKET_LIST_MODE" \
        --slurpfile triage "$TRIAGE_INPUT" \
        --slurpfile jvm "$JVM_INPUT" \
        --slurpfile inflationFrames "$INFLATION_FRAME_INPUT" \
        --slurpfile autofitFrames "$AUTOFIT_FRAME_INPUT" \
        --slurpfile nexEconomyFrames "$NEX_ECONOMY_FRAME_INPUT" \
        --slurpfile saveIdentity "$SAVE_IDENTITY_INPUT" \
        '{issue:1158,
          classification:(if ($marketListMode == "baseline" or $marketListMode == "candidate")
            then "thin-candidate-measurement" else "intrusive-discovery-no-fps-claim" end),
          marketListMode:$marketListMode,
          commit:$commit,
          runDirectory:$run,
          saveIdentity:($saveIdentity[0] // null),
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
          nexEconomyInfoTimes:.nexEconomyInfoTimes,
          nexMarketListScope:.nexMarketListScope,
          nexEconomyInfoFrameJoin:($nexEconomyFrames[0] // null),
          triage:($triage[0] // null),
          jvmHitchCorrelation:($jvm[0] // null)}' \
        "$RUN_DIR/runtime-frame-report.json" >"$SUMMARY"
    cp "$SUMMARY" "$RUN_DIR/issue-1158-owner-tax-summary.json"
    RUN_INPUT=/dev/null
    SMOKE_INPUT=/dev/null
    [[ -f "$RUN_DIR/run.json" ]] && RUN_INPUT="$RUN_DIR/run.json"
    [[ -f "$RUN_DIR/smoke-evidence.json" ]] && SMOKE_INPUT="$RUN_DIR/smoke-evidence.json"
    jq \
        --slurpfile run "$RUN_INPUT" \
        --slurpfile smoke "$SMOKE_INPUT" \
        'def frameSummary:
           if . == null then null else {
             frames,
             activeSeconds:((.totalActiveNanos // 0) / 1000000000),
             p50Millis:((.p50Micros // 0) / 1000),
             p95Millis:((.p95Micros // 0) / 1000),
             p99Millis:((.p99Micros // 0) / 1000),
             maximumMillis:((.maximumMicros // 0) / 1000),
             averageFps,
             onePercentLowFps,
             over50Millis,
             over100Millis,
             repeatedSlowFrameClusters:(.stutterProfile.repeatedSlowFrameClusters // 0),
             stutterBurdenMillisPerSecond:(.stutterProfile.stutterBurdenMillisPerSecond // 0)
           } end;
         def eventSummary:
           if . == null then null else {
             eventAssociations,
             hitchFramesWithEvent,
             wallOverlapMillis
           } end;
         {issue,
         classification,
         marketListMode,
         commit,
         runDirectory,
         saveIdentity:(if .saveIdentity == null then null else {
           selectedSave:.saveIdentity.selectedSave,
           treeSha256:.saveIdentity.tree.treeSha256,
           bytes:.saveIdentity.tree.bytes,
           files:.saveIdentity.tree.files,
           comparison:.saveIdentity.comparison
         } end),
         routeStatus:($smoke[0].status // "unavailable"),
         runOutcome:($run[0].outcome // "unavailable"),
         runExitCode:($run[0].exitCode // null),
         fatalLifecycleMatches:($run[0].lifecycleEvidence.matches // []),
         frameTimes:{
           measurementOverhead:.frameTimes.measurementOverhead,
           pausedSettled:(.frameTimes.campaignPausedAfter30SecondsActive | frameSummary),
           unpausedSettled:(.frameTimes.campaignUnpausedAfter30SecondsActive | frameSummary)
         },
         runtimeOwnerTaxFamilies:(.triage.runtimeOwnerTaxFamilies // 0),
         runtimeObservedModCount:(.triage.runtimeObservedModCount // 0),
         unresolvedRuntimeOwnerCount:(.triage.unresolvedRuntimeOwners | length),
         fleetAiModuleTimes:{
           installed:.fleetAiModuleTimes.installed,
           phases:(.fleetAiModuleTimes.phases | map({name,calls,totalMillis,maximumMillis})),
           slowSpans:(.fleetAiModuleTimes.slowSpans[:5])
         },
         tacticalFleetAiTimes:{
           installed:.tacticalFleetAiTimes.installed,
           candidateFleetsVisited:.tacticalFleetAiTimes.candidateFleetsVisited,
           nearbyCandidatesVisited:.tacticalFleetAiTimes.nearbyCandidatesVisited,
           strengthModeCalls:.tacticalFleetAiTimes.strengthModeCalls,
           fleetPointModeCalls:.tacticalFleetAiTimes.fleetPointModeCalls,
           preEncounterDeclines:.tacticalFleetAiTimes.preEncounterDeclines,
           phases:(.tacticalFleetAiTimes.phases | map({name,calls,totalMillis,maximumMillis})),
           slowSpans:(.tacticalFleetAiTimes.slowSpans[:5])
         },
         fleetInflation:{
           installed:.fleetInflationTimes.installed,
           membersVisited:.fleetInflationTimes.membersVisited,
           phases:(.fleetInflationTimes.phases | map({name,calls,totalMillis,maximumMillis}))
         },
         fleetInflationFrameJoins:(((.fleetInflationFrameJoin.joins // [])[:5]) | map({
           span,durationMillis,frameDurationMillis,overlapShareOfFramePercent,
           spanShareOfFramePercent,containedByFrame
         })),
         coreAutofit:{
           installed:.coreAutofitTimes.installed,
           phases:(.coreAutofitTimes.phases | map(
             select(.name == "total" or .name == "setupModules" or .name == "primaryFit" or
                    .name == "fighterFitCalls" or .name == "weaponFitCalls") |
             {name,calls,totalMillis,maximumMillis}
           ))
         },
         coreAutofitFrameJoins:(((.coreAutofitFrameJoin.joins // [])[:5]) | map({
           span,durationMillis,frameDurationMillis,overlapShareOfFramePercent,
           spanShareOfFramePercent,containedByFrame
         })),
         nexEconomyInfo:{
           installed:.nexEconomyInfoTimes.installed,
           firstRunCalls:.nexEconomyInfoTimes.firstRunCalls,
           refreshCalls:.nexEconomyInfoTimes.refreshCalls,
           cardinality:.nexEconomyInfoTimes.cardinality,
           phases:(.nexEconomyInfoTimes.phases | map({name,calls,totalMillis,maximumMillis}))
         },
         nexMarketListScope,
         nexEconomyInfoFrameJoins:(((.nexEconomyInfoFrameJoin.joins // [])[:8]) | map({
           span,durationMillis,frameDurationMillis,overlapShareOfFramePercent,
           spanShareOfFramePercent,containedByFrame
         })),
         topMods:((.triage.mods // [])[:8] | map({modId,priority,bestFrameTaxRank})),
         retainedHitchFrames:(.jvmHitchCorrelation.retainedHitchFrames // 0),
         retainedSevereHitchFrames:(.jvmHitchCorrelation.retainedSevereHitchFrames // 0),
         jvmHitchAssociations:{
           garbageCollection:(.jvmHitchCorrelation.summary["jdk.GarbageCollection"] | eventSummary),
           vmOperations:(.jvmHitchCorrelation.summary["jdk.ExecuteVMOperation"] | eventSummary),
           nativeSamples:(.jvmHitchCorrelation.summary["jdk.NativeMethodSample"] | eventSummary)
         }}' "$SUMMARY" >"$COMPACT_SUMMARY"
    jq . "$COMPACT_SUMMARY"
fi

echo "Issue #1158 session: $SESSION"
echo "Installed-game run: $RUN_DIR"
if [[ "$RUN_STATUS" -ne 0 ]]; then
    exit "$RUN_STATUS"
fi
exit "$POSTPROCESS_STATUS"
