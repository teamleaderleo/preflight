#!/usr/bin/env python3
"""Live progress and final statistics for the repeated startup benchmark.

Reads the JSONL one launch per line that run-startup-benchmark.sh appends, and reports
per-condition medians against the vanilla baseline. Every comparison carries the sample
size and an exact permutation p-value, because the point of this report is to say when
the evidence does not yet support a claim.
"""

from __future__ import annotations

import argparse
import itertools
import json
import statistics
import sys
from pathlib import Path

BASELINE = "vanilla"
CAMPAIGN_MINIMUM = 5
ORDER = ["vanilla", "agent", "enabled", "fast", "prepared", "prepared-unpadded", "profile"]
LABELS = {
    "vanilla": "vanilla (no preflight)",
    "agent": "agent only (recorder)",
    "enabled": "preflight + recorder",
    "fast": "preflight, no recorder",
    "prepared": "prepared pixels",
    "prepared-unpadded": "prepared pixels, unpadded",
    "profile": "sampling (diagnostic)",
}
# Conditions that exist to be analysed, not timed. They are reported so their runs are
# visible, but they never enter a comparison and never hold back the campaign gate: a
# sampling run is slower than an ordinary one by construction, so reading its median as a
# result would be a mistake, and requiring five of them would block a finished campaign.
DIAGNOSTIC = {"profile"}
# Comparisons worth naming, because the interesting ones are not against the baseline.
# The 2026-07-31 campaign reported only "enabled vs vanilla" and so reported -2.4%, hiding
# a texture cache worth -15% behind a recorder worth +24%. A comparison is only clean when
# the two conditions differ in one thing.
INTERESTING = [
    ("fast", "prepared", "the pixel conversion, cache and recorder held constant"),
    ("prepared", "prepared-unpadded", "removing the power-of-two padding"),
    ("agent", "enabled", "the texture cache, recorder held constant"),
    ("vanilla", "prepared", "what a user would actually feel, best path"),
    ("vanilla", "fast", "what a user would actually feel"),
    ("vanilla", "agent", "the cost of the recorder"),
    ("enabled", "fast", "the cost of the recorder, cache held constant"),
    ("vanilla", "enabled", "net, confounded by the recorder"),
]


def load(results: Path) -> list[dict]:
    if not results.is_file():
        return []
    runs = []
    for line in results.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line:
            runs.append(json.loads(line))
    return runs


def accepted(runs: list[dict], condition: str) -> list[float]:
    return [
        run["gameLogStartToMainMenuMs"] / 1000.0
        for run in runs
        if run["condition"] == condition
        and run["status"] == "accepted"
        and run.get("gameLogStartToMainMenuMs") is not None
    ]


def conditions_present(runs: list[dict]) -> list[str]:
    """Every condition with a recorded run, known ones first in ORDER.

    Unknown conditions are appended rather than dropped. Filtering to ORDER alone was a
    silent failure: adding a condition to the runner and not to this list produced a
    campaign that ran, recorded, and accepted every launch, then printed an empty table
    and "no pair of conditions" -- which reads as "the runs failed" when the data is fine.
    """
    seen = {run["condition"] for run in runs}
    known = [condition for condition in ORDER if condition in seen]
    return known + sorted(seen - set(ORDER))


def protocols(runs: list[dict]) -> list[str]:
    """Every launch protocol present, oldest sessions reporting none as 'clicked'.

    A results file that holds both is not comparable and must not be summarized as if it
    were. `clicked` waits for the launcher and an operator; `direct` uses Starsector's own
    launchDirect path and never builds a launcher at all, so the launcher's OpenGL context,
    font loading and window creation are in one and not the other. Reading a median across
    the two would be reading two quantities as one -- the same mistake, in a different place,
    as the anchor artifact of 2026-08-01.
    """
    return sorted({run.get("protocol") or "clicked" for run in runs})


def permutation_p(baseline: list[float], candidate: list[float]) -> float | None:
    """Exact two-sided p-value for a difference in medians.

    Enumerates every way the pooled samples could have been split between the two
    conditions. With three runs each there are only twenty distinct splits, so the
    smallest reachable p-value is 0.1 -- which is the useful part of reporting it.
    """
    total = len(baseline) + len(candidate)
    if not baseline or not candidate or total > 20:
        return None
    pooled = baseline + candidate
    observed = abs(statistics.median(candidate) - statistics.median(baseline))
    indices = range(total)
    extreme = considered = 0
    for chosen in itertools.combinations(indices, len(baseline)):
        left = [pooled[i] for i in chosen]
        right = [pooled[i] for i in indices if i not in set(chosen)]
        considered += 1
        if abs(statistics.median(right) - statistics.median(left)) >= observed - 1e-9:
            extreme += 1
    return extreme / considered if considered else None


def summarize(runs: list[dict]) -> dict:
    present = conditions_present(runs)
    stats: dict[str, dict] = {}
    for condition in present:
        samples = accepted(runs, condition)
        excluded = [
            run for run in runs
            if run["condition"] == condition and run["status"] != "accepted"
        ]
        stats[condition] = {
            "successfulRuns": len(samples),
            "excludedRuns": len(excluded),
            "exclusionReasons": sorted({run.get("reason") or "unknown" for run in excluded}),
            "medianSeconds": round(statistics.median(samples), 2) if samples else None,
            "minimumSeconds": round(min(samples), 2) if samples else None,
            "maximumSeconds": round(max(samples), 2) if samples else None,
            "samplesSeconds": [round(value, 2) for value in samples],
        }

    present_protocols = protocols(runs)
    mixed = len(present_protocols) > 1

    comparisons: dict[str, dict] = {}
    for baseline, candidate, why in INTERESTING:
        if mixed:
            # No comparison at all rather than a caveated one. A delta between two protocols
            # is not a small measurement error to note in passing; it is not a delta.
            break
        if baseline not in present or candidate not in present:
            continue
        left, right = accepted(runs, baseline), accepted(runs, candidate)
        if not left or not right:
            continue
        delta = statistics.median(right) - statistics.median(left)
        comparisons[f"{candidate} vs {baseline}"] = {
            "baseline": baseline,
            "candidate": candidate,
            "isolates": why,
            "deltaSeconds": round(delta, 2),
            "improvementPercent": round(-delta / statistics.median(left) * 100, 2),
            "permutationP": permutation_p(left, right),
            "meetsCampaignMinimum": (
                len(left) >= CAMPAIGN_MINIMUM and len(right) >= CAMPAIGN_MINIMUM
            ),
        }

    return {
        "scenarioId": "main-menu-v1",
        "measured": "game start log marker to graphics preload",
        "campaignMinimumSuccessfulRunsPerCondition": CAMPAIGN_MINIMUM,
        "launchProtocols": present_protocols,
        "protocolsMixed": mixed,
        "conditions": stats,
        "comparisons": comparisons,
        "benchmarkAccepted": (not mixed) and bool(comparisons) and all(
            values["successfulRuns"] >= CAMPAIGN_MINIMUM
            for condition, values in stats.items()
            if condition not in DIAGNOSTIC
        ),
    }


def render(summary: dict, verbose: bool) -> str:
    lines: list[str] = []
    lines.append(f"{'condition':<24}{'n':>3}{'median':>10}{'min':>9}{'max':>9}{'range':>9}")
    lines.append("-" * 64)
    for condition, values in summary["conditions"].items():
        median = values["medianSeconds"]
        lines.append(
            f"{LABELS.get(condition, condition):<24}"
            f"{values['successfulRuns']:>3}"
            + (
                f"{median:>9.2f}s{values['minimumSeconds']:>8.2f}s"
                f"{values['maximumSeconds']:>8.2f}s"
                f"{values['maximumSeconds'] - values['minimumSeconds']:>8.2f}s"
                if median is not None
                else f"{'--':>10}{'--':>9}{'--':>9}{'--':>9}"
            )
            + (f"   ({values['excludedRuns']} excluded)" if values["excludedRuns"] else "")
        )

    comparisons = summary["comparisons"]
    if comparisons:
        lines.append("")
        width = max(len(name) for name in comparisons)
        for name, comparison in comparisons.items():
            delta = comparison["deltaSeconds"]
            p_value = comparison["permutationP"]
            p_text = "p     --" if p_value is None else f"p = {p_value:.3f}"
            lines.append(
                f"{name:<{width}}  {-delta:+7.2f}s "
                f"({abs(comparison['improvementPercent']):5.1f}%)  {p_text}   "
                f"{comparison['isolates']}"
            )
        lines.append("")
        lines.append("A positive delta means the candidate was faster. Only a comparison whose two")
        lines.append("conditions differ in one thing isolates that thing.")

    if summary.get("protocolsMixed"):
        lines.append("")
        lines.append(
            "NOT comparable: this results file mixes the "
            + " and ".join(summary["launchProtocols"])
            + " launch protocols."
        )
        lines.append("They do not measure the same interval -- the direct protocol never builds")
        lines.append("a launcher -- so no median across them means anything. Run each protocol")
        lines.append("into its own session directory.")

    if verbose:
        lines.append("")
        if summary.get("protocolsMixed"):
            pass
        elif not comparisons:
            lines.append("No comparison yet: no pair of conditions both have a successful run.")
        elif not summary["benchmarkAccepted"]:
            shortfall = [
                f"{LABELS.get(condition, condition)} n={values['successfulRuns']}"
                for condition, values in summary["conditions"].items()
                if values["successfulRuns"] < CAMPAIGN_MINIMUM
            ]
            lines.append(
                "NOT a reportable result. The campaign threshold is "
                f"{CAMPAIGN_MINIMUM} successful runs per condition; short: "
                + ", ".join(shortfall)
            )
            lines.append(
                "With three runs per condition the smallest reachable p-value is 0.100, "
                "so no difference here can be significant regardless of its size."
            )
        else:
            lines.append(
                f"Every condition reached {CAMPAIGN_MINIMUM} successful runs. "
                "Treat p-values as descriptive: the conditions were interleaved, but this "
                "is one machine on one profile."
            )
        reasons = sorted(
            reason
            for values in summary["conditions"].values()
            for reason in values["exclusionReasons"]
        )
        if reasons:
            lines.append("Exclusions: " + ", ".join(reasons))
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)
    for name in ("progress", "summary"):
        sub = subparsers.add_parser(name)
        sub.add_argument("--results", type=Path, required=True)
        if name == "summary":
            sub.add_argument("--identity", type=Path)
            sub.add_argument("--output", type=Path)
    args = parser.parse_args(sys.argv[1:] if argv is None else argv)

    runs = load(args.results)
    if not runs:
        print("No runs recorded yet.")
        return 0
    summary = summarize(runs)
    print(render(summary, verbose=args.command == "summary"))

    if args.command == "summary":
        if args.identity and args.identity.is_file():
            summary["identity"] = json.loads(args.identity.read_text(encoding="utf-8"))
        summary["runs"] = runs
        if args.output:
            args.output.write_text(
                json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8"
            )
            print(f"\nWrote {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
