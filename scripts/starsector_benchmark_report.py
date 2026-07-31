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
ORDER = ["vanilla", "agent", "enabled"]
LABELS = {
    "vanilla": "vanilla (no preflight)",
    "agent": "agent only (recorder)",
    "enabled": "preflight enabled",
}


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
    seen = {run["condition"] for run in runs}
    return [condition for condition in ORDER if condition in seen]


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

    comparisons: dict[str, dict] = {}
    baseline_samples = accepted(runs, BASELINE)
    for condition in present:
        if condition == BASELINE or not baseline_samples:
            continue
        samples = accepted(runs, condition)
        if not samples:
            continue
        delta = statistics.median(samples) - statistics.median(baseline_samples)
        comparisons[condition] = {
            "versus": BASELINE,
            "deltaSeconds": round(delta, 2),
            "improvementPercent": round(
                -delta / statistics.median(baseline_samples) * 100, 2
            ),
            "permutationP": permutation_p(baseline_samples, samples),
            "meetsCampaignMinimum": (
                len(samples) >= CAMPAIGN_MINIMUM
                and len(baseline_samples) >= CAMPAIGN_MINIMUM
            ),
        }

    return {
        "scenarioId": "main-menu-v1",
        "measured": "game log start to main menu ready",
        "campaignMinimumSuccessfulRunsPerCondition": CAMPAIGN_MINIMUM,
        "conditions": stats,
        "comparisonsVersusVanilla": comparisons,
        "benchmarkAccepted": bool(comparisons) and all(
            comparison["meetsCampaignMinimum"] for comparison in comparisons.values()
        ),
    }


def render(summary: dict, verbose: bool) -> str:
    lines: list[str] = []
    lines.append(f"{'condition':<24}{'n':>3}{'median':>10}{'min':>9}{'max':>9}")
    lines.append("-" * 55)
    for condition, values in summary["conditions"].items():
        median = values["medianSeconds"]
        lines.append(
            f"{LABELS.get(condition, condition):<24}"
            f"{values['successfulRuns']:>3}"
            + (
                f"{median:>9.2f}s{values['minimumSeconds']:>8.2f}s{values['maximumSeconds']:>8.2f}s"
                if median is not None
                else f"{'--':>10}{'--':>9}{'--':>9}"
            )
            + (f"   ({values['excludedRuns']} excluded)" if values["excludedRuns"] else "")
        )

    comparisons = summary["comparisonsVersusVanilla"]
    if comparisons:
        lines.append("")
        for condition, comparison in comparisons.items():
            delta = comparison["deltaSeconds"]
            direction = "faster" if delta < 0 else "slower"
            p_value = comparison["permutationP"]
            p_text = "p unavailable" if p_value is None else f"p = {p_value:.3f}"
            lines.append(
                f"{LABELS.get(condition, condition)} vs vanilla: "
                f"{abs(delta):.2f}s {direction} "
                f"({abs(comparison['improvementPercent']):.1f}%), {p_text}"
            )

    if verbose:
        lines.append("")
        if not comparisons:
            lines.append("No comparison yet: the vanilla baseline has no successful run.")
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
