#!/usr/bin/env bash
#
# Compare one issue #1153 baseline/candidate summary pair.
#
# Usage:
#   scripts/compare-1153-render-pilots.sh BASELINE_SUMMARY CANDIDATE_SUMMARY [OUTPUT_JSON]
#
# Both inputs must come from the same experiment, route, and workload id. The comparison uses the
# combat-after-campaign bucket so title/loading/campaign intervals stay outside the retained metric.
set -euo pipefail

if [[ $# -lt 2 || $# -gt 3 ]]; then
    sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//' >&2
    exit 2
fi

BASELINE="$1"
CANDIDATE="$2"
OUTPUT="${3:-}"
[[ -f "$BASELINE" ]] || { echo "Baseline summary not found: $BASELINE" >&2; exit 1; }
[[ -f "$CANDIDATE" ]] || { echo "Candidate summary not found: $CANDIDATE" >&2; exit 1; }
command -v jq >/dev/null 2>&1 || { echo "jq is required." >&2; exit 1; }

BASE_VARIANT="$(jq -r '.variant // empty' "$BASELINE")"
CAND_VARIANT="$(jq -r '.variant // empty' "$CANDIDATE")"
[[ "$BASE_VARIANT" == baseline ]] \
    || { echo "First summary must have variant=baseline (found: ${BASE_VARIANT:-missing})." >&2; exit 2; }
[[ "$CAND_VARIANT" == candidate ]] \
    || { echo "Second summary must have variant=candidate (found: ${CAND_VARIANT:-missing})." >&2; exit 2; }

BASE_KEY="$(jq -r '[.experiment, .route, .workloadId] | @tsv' "$BASELINE")"
CAND_KEY="$(jq -r '[.experiment, .route, .workloadId] | @tsv' "$CANDIDATE")"
[[ "$BASE_KEY" == "$CAND_KEY" ]] || {
    echo "A/B identity mismatch." >&2
    echo "Baseline:  $BASE_KEY" >&2
    echo "Candidate: $CAND_KEY" >&2
    exit 2
}

COMPARISON="$(jq -n \
    --slurpfile baseline "$BASELINE" \
    --slurpfile candidate "$CANDIDATE" \
    'def pct_delta($a; $b):
         if ($a == null or $b == null or $a == 0) then null else (($b - $a) * 100 / $a) end;
     def metric_delta($name):
         ($baseline[0].combatAfterCampaignActive[$name]) as $a
         | ($candidate[0].combatAfterCampaignActive[$name]) as $b
         | {baseline: $a, candidate: $b, delta: (if ($a == null or $b == null) then null else ($b - $a) end),
            percentDelta: pct_delta($a; $b)};
     {issue: 1153,
      experiment: $baseline[0].experiment,
      route: $baseline[0].route,
      workloadId: $baseline[0].workloadId,
      baselineCommit: $baseline[0].commit,
      candidateCommit: $candidate[0].commit,
      frameCount: metric_delta("frames"),
      averageFps: metric_delta("averageFps"),
      p50Micros: metric_delta("p50Micros"),
      p95Micros: metric_delta("p95Micros"),
      p99Micros: metric_delta("p99Micros"),
      onePercentLowFps: metric_delta("onePercentLowFps"),
      over50Per1000: metric_delta("over50Per1000"),
      over100Per1000: metric_delta("over100Per1000"),
      measurementOverheadMicros: {
          baseline: $baseline[0].measurementOverhead.averageMicros,
          candidate: $candidate[0].measurementOverhead.averageMicros,
          delta: (if ($baseline[0].measurementOverhead.averageMicros == null
                      or $candidate[0].measurementOverhead.averageMicros == null)
                  then null
                  else ($candidate[0].measurementOverhead.averageMicros
                        - $baseline[0].measurementOverhead.averageMicros) end)},
      directionalChecks: {
          averageFpsHigher: ($candidate[0].combatAfterCampaignActive.averageFps
                             > $baseline[0].combatAfterCampaignActive.averageFps),
          p99Lower: ($candidate[0].combatAfterCampaignActive.p99Micros
                     < $baseline[0].combatAfterCampaignActive.p99Micros),
          onePercentLowHigher: ($candidate[0].combatAfterCampaignActive.onePercentLowFps
                                > $baseline[0].combatAfterCampaignActive.onePercentLowFps),
          over50RateLower: ($candidate[0].combatAfterCampaignActive.over50Per1000
                            < $baseline[0].combatAfterCampaignActive.over50Per1000),
          over100RateLower: ($candidate[0].combatAfterCampaignActive.over100Per1000
                             < $baseline[0].combatAfterCampaignActive.over100Per1000)},
      baselineCandidateTelemetry: $baseline[0].candidate,
      candidateTelemetry: $candidate[0].candidate,
      baselineTransformationsApplied: $baseline[0].transformationsApplied,
      candidateTransformationsApplied: $candidate[0].transformationsApplied,
      baselineContainedFailures: $baseline[0].containedFailures,
      candidateContainedFailures: $candidate[0].containedFailures}' )"

if [[ -n "$OUTPUT" ]]; then
    mkdir -p "$(dirname "$OUTPUT")"
    printf '%s\n' "$COMPARISON" >"$OUTPUT"
    echo "Comparison written to $OUTPUT"
fi
printf '%s\n' "$COMPARISON" | jq .
