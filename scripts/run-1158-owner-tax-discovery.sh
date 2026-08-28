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
SUMMARY="$SESSION/summary.json"
mkdir -p "$SESSION"

echo "Issue #1158 installed owner-tax discovery"
echo "  commit:  $(git rev-parse HEAD)"
echo "  session: $SESSION"
echo "  run:     $RUN_DIR"
echo

java -jar preflight-cli/target/preflight.jar classpath hot-patterns \
    --game "$GAME" --limit 500 --json "$HOT_PATTERNS"

set +e
java -jar preflight-cli/target/preflight.jar desktop smoke launch \
    "$SCENARIO" "$RUN_DIR" --game "$GAME"
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
    TRIAGE_INPUT=/dev/null
    JVM_INPUT=/dev/null
    [[ -f "$TRIAGE" ]] && TRIAGE_INPUT="$TRIAGE"
    [[ -f "$JVM_JOIN" ]] && JVM_INPUT="$JVM_JOIN"
    jq \
        --arg commit "$(git rev-parse HEAD)" \
        --arg run "$RUN_DIR" \
        --slurpfile triage "$TRIAGE_INPUT" \
        --slurpfile jvm "$JVM_INPUT" \
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
