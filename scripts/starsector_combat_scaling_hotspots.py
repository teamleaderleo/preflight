#!/usr/bin/env python3
"""Rank exact combat methods whose sampled presence rises with a workload predictor across runs.

This is owner-attribution evidence beside the workload-law fitter. JFR ExecutionSample shares are
sample-composition evidence, never elapsed CPU or wall time. Use this only after a workload family
has a repeatable scaling signal, then inspect the returned exact methods and their semantics before
building an intervention.
"""
from __future__ import annotations

import argparse
import collections
import json
import math
import pathlib
import statistics
from dataclasses import dataclass
from typing import Callable

from starsector_critical_path import _jfr_binary, events, thread_of
from starsector_gameplay_hotspots import gameplay_state, interesting, methods_of


@dataclass(frozen=True)
class RunObservation:
    run_id: str
    cell_id: str
    predictor: float
    advance_micros: float
    combat_samples: int
    inclusive_shares: dict[str, float]
    leaf_shares: dict[str, float]


@dataclass(frozen=True)
class MethodAssociation:
    method: str
    correlation: float
    slope_share_points_per_unit: float
    low_share_percent: float
    high_share_percent: float
    high_minus_low_points: float
    mean_share_percent: float
    runs_with_samples: int

    @property
    def score(self) -> float:
        return max(0.0, self.correlation) * max(0.0, self.high_minus_low_points)


def numeric(value) -> float | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, (int, float)) and math.isfinite(float(value)):
        return float(value)
    return None


def load_workload(path: str | pathlib.Path, predictor: str) -> tuple[str, str, float, float]:
    path = pathlib.Path(path)
    payload = json.loads(path.read_text(encoding="utf-8"))
    if "combatWorkload" in payload:
        payload = payload["combatWorkload"]
    run_id = str(payload.get("runId") or path.stem)
    cell_id = str(payload.get("cellId") or "unspecified")
    report_value = numeric(payload.get(predictor))
    predictor_values = []
    advance_values = []
    for sample in payload.get("samples") or []:
        value = numeric(sample.get(predictor))
        if value is not None:
            predictor_values.append(value)
        advance = numeric(sample.get("advanceMicros"))
        if advance is not None:
            advance_values.append(advance)
    if predictor_values:
        predictor_value = statistics.median(predictor_values)
    elif report_value is not None:
        predictor_value = report_value
    else:
        raise ValueError(f"{path}: predictor {predictor!r} has no numeric observations")
    if not advance_values:
        raise ValueError(f"{path}: no advanceMicros observations")
    return run_id, cell_id, predictor_value, statistics.median(advance_values)


def observe_run(
        workload_path: str | pathlib.Path,
        recording_path: str | pathlib.Path,
        predictor: str,
        depth: int = 96,
        event_loader: Callable = events,
) -> RunObservation:
    run_id, cell_id, predictor_value, advance = load_workload(workload_path, predictor)
    sampled = event_loader(str(recording_path), ["jdk.ExecutionSample"], depth=depth, jfr=_jfr_binary())
    stacks = []
    for event in sampled:
        if thread_of(event) != "main":
            continue
        methods = methods_of(event)
        if gameplay_state(methods) == "combat":
            stacks.append(methods)
    if not stacks:
        raise ValueError(f"{recording_path}: no main-thread combat ExecutionSample stacks")

    inclusive = collections.Counter()
    leaves = collections.Counter()
    for methods in stacks:
        useful = [method for method in methods if interesting(method)]
        if useful:
            leaves[useful[0]] += 1
        inclusive.update(set(useful))
    total = len(stacks)
    return RunObservation(
        run_id,
        cell_id,
        predictor_value,
        advance,
        total,
        {method: count / total for method, count in inclusive.items()},
        {method: count / total for method, count in leaves.items()},
    )


def pearson(left: list[float], right: list[float]) -> float:
    if len(left) != len(right) or len(left) < 2:
        return 0.0
    left_mean = statistics.fmean(left)
    right_mean = statistics.fmean(right)
    left_var = sum((value - left_mean) ** 2 for value in left)
    right_var = sum((value - right_mean) ** 2 for value in right)
    if left_var <= 1e-18 or right_var <= 1e-18:
        return 0.0
    covariance = sum((a - left_mean) * (b - right_mean) for a, b in zip(left, right))
    return covariance / math.sqrt(left_var * right_var)


def slope(left: list[float], right: list[float]) -> float:
    mean = statistics.fmean(left)
    denominator = sum((value - mean) ** 2 for value in left)
    if denominator <= 1e-18:
        return 0.0
    right_mean = statistics.fmean(right)
    numerator = sum((x - mean) * (y - right_mean) for x, y in zip(left, right))
    return numerator / denominator


def associations(observations: list[RunObservation], leaf: bool = False) -> list[MethodAssociation]:
    if len(observations) < 4:
        raise ValueError("at least four paired workload/JFR runs are required")
    predictors = [item.predictor for item in observations]
    if len({round(value, 12) for value in predictors}) < 2:
        raise ValueError("predictor does not vary across paired runs")
    ordered = sorted(range(len(observations)), key=lambda index: predictors[index])
    quartile = max(1, len(observations) // 4)
    low_indexes = ordered[:quartile]
    high_indexes = ordered[-quartile:]

    methods = set()
    for item in observations:
        methods.update(item.leaf_shares if leaf else item.inclusive_shares)
    result = []
    for method in methods:
        shares = [
            (item.leaf_shares if leaf else item.inclusive_shares).get(method, 0.0)
            for item in observations
        ]
        runs_with_samples = sum(share > 0.0 for share in shares)
        if runs_with_samples < 2:
            continue
        correlation = pearson(predictors, shares)
        fitted_slope = slope(predictors, shares) * 100.0
        low_share = statistics.fmean(shares[index] for index in low_indexes) * 100.0
        high_share = statistics.fmean(shares[index] for index in high_indexes) * 100.0
        result.append(MethodAssociation(
            method=method,
            correlation=correlation,
            slope_share_points_per_unit=fitted_slope,
            low_share_percent=low_share,
            high_share_percent=high_share,
            high_minus_low_points=high_share - low_share,
            mean_share_percent=statistics.fmean(shares) * 100.0,
            runs_with_samples=runs_with_samples,
        ))
    result.sort(key=lambda item: (-item.score, -item.correlation, item.method))
    return result


def render(observations: list[RunObservation], ranked: list[MethodAssociation], predictor: str,
           top: int, leaf: bool) -> str:
    mode = "leaf" if leaf else "inclusive"
    predictor_values = [item.predictor for item in observations]
    advance_values = [item.advance_micros for item in observations]
    lines = [
        "# Combat scaling owner attribution",
        "",
        f"Predictor: `{predictor}`; paired runs={len(observations)}; JFR mode={mode}.",
        f"Predictor vs median sampled `CombatEngine.advance`: r={pearson(predictor_values, advance_values):.3f}.",
        "ExecutionSample shares below describe observed sample composition only; they are not elapsed CPU/wall time.",
        "",
        "## Paired runs",
        "",
        "| run | cell | predictor | median advance µs | combat samples |",
        "| --- | --- | ---: | ---: | ---: |",
    ]
    for item in sorted(observations, key=lambda value: (value.predictor, value.run_id, value.cell_id)):
        lines.append(
            f"| `{item.run_id}` | `{item.cell_id}` | {item.predictor:.3f} | "
            f"{item.advance_micros:.3f} | {item.combat_samples} |")
    lines.extend([
        "",
        "## Methods rising with predictor",
        "",
        "| rank | exact method | r | low share % | high share % | Δ pp | slope pp/unit | runs present |",
        "| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: |",
    ])
    for index, item in enumerate(ranked[:top], 1):
        lines.append(
            f"| {index} | `{item.method}` | {item.correlation:.3f} | "
            f"{item.low_share_percent:.3f} | {item.high_share_percent:.3f} | "
            f"{item.high_minus_low_points:.3f} | {item.slope_share_points_per_unit:.6f} | "
            f"{item.runs_with_samples} |")
    lines.append("")
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--pair", action="append", nargs=2, metavar=("WORKLOAD_JSON", "STARTUP_JFR"),
                        required=True, help="repeat for each paired run/cell")
    parser.add_argument("--predictor", required=True,
                        help="numeric workload field, e.g. missiles or nearbyEntitiesMean")
    parser.add_argument("--top", type=int, default=30, help="methods to print")
    parser.add_argument("--depth", type=int, default=96, help="JFR stack depth")
    parser.add_argument("--leaf", action="store_true", help="rank leaf share instead of inclusive presence")
    args = parser.parse_args()

    observations = [
        observe_run(workload, recording, args.predictor, depth=args.depth)
        for workload, recording in args.pair
    ]
    ranked = associations(observations, leaf=args.leaf)
    print(render(observations, ranked, args.predictor, args.top, args.leaf))


if __name__ == "__main__":
    main()
