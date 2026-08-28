#!/usr/bin/env python3
"""Rank exact combat methods whose sampled presence rises with a workload predictor.

Whole-run mode compares repeated sweep cells. Window mode aligns JFR combat samples to the same
elapsed-combat buckets used by the scaling-law fitter, with JFR timestamp drift corrected from the
recording's periodic clock calibration. Window associations are centered inside each run so late
battle wreck/effect/duration growth is separated from persistent between-run sample-share offsets.

JFR samples that land inside the workload probe's measured wall-clock interval are discarded so
reflection, public game getters, and collision-grid reads performed by the probe cannot become
false owners. ExecutionSample shares are sample-composition evidence, never elapsed CPU or wall
time. Inspect exact returned methods and their semantics before building an intervention.
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

from starsector_critical_path import (
    CALIBRATION_EVENT,
    _jfr_binary,
    clock_factor,
    events,
    instant,
    thread_of,
)
from starsector_gameplay_hotspots import gameplay_state, interesting, methods_of


PROBE_INTERVAL_MARGIN_SECONDS = 0.005


@dataclass(frozen=True)
class RunObservation:
    run_id: str
    cell_id: str
    predictor: float
    advance_micros: float
    combat_samples: int
    inclusive_shares: dict[str, float]
    leaf_shares: dict[str, float]
    window: str = "whole-run"
    excluded_probe_samples: int = 0


@dataclass(frozen=True)
class WorkloadWindow:
    run_id: str
    cell_id: str
    battle_id: int
    bucket: int
    predictor: float
    advance_micros: float
    center_epoch_seconds: float
    workload_samples: int

    @property
    def label(self) -> str:
        return f"battle-{self.battle_id}/bucket-{self.bucket}"


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


def workload_payload(path: str | pathlib.Path) -> tuple[pathlib.Path, dict]:
    path = pathlib.Path(path)
    payload = json.loads(path.read_text(encoding="utf-8"))
    if "combatWorkload" in payload:
        payload = payload["combatWorkload"]
    return path, payload


def probe_intervals(
        path: str | pathlib.Path,
        margin_seconds: float = PROBE_INTERVAL_MARGIN_SECONDS,
) -> list[tuple[float, float]]:
    _, payload = workload_payload(path)
    result = []
    for sample in payload.get("samples") or []:
        epoch_millis = numeric(sample.get("epochMillis"))
        overhead_micros = numeric(sample.get("sampleOverheadMicros"))
        if epoch_millis is None or overhead_micros is None:
            continue
        end = epoch_millis / 1000.0 + margin_seconds
        start = epoch_millis / 1000.0 - overhead_micros / 1_000_000.0 - margin_seconds
        result.append((start, end))
    return sorted(result)


def inside_intervals(epoch_seconds: float, intervals: list[tuple[float, float]]) -> bool:
    for start, end in intervals:
        if epoch_seconds < start:
            return False
        if start <= epoch_seconds <= end:
            return True
    return False


def load_workload(path: str | pathlib.Path, predictor: str) -> tuple[str, str, float, float]:
    path, payload = workload_payload(path)
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


def workload_windows(
        path: str | pathlib.Path,
        predictor: str,
        bucket_seconds: float = 10.0,
) -> list[WorkloadWindow]:
    path, payload = workload_payload(path)
    run_id = str(payload.get("runId") or path.stem)
    cell_id = str(payload.get("cellId") or "unspecified")
    report_value = numeric(payload.get(predictor))
    grouped = collections.defaultdict(list)
    for sample in payload.get("samples") or []:
        elapsed = numeric(sample.get("combatElapsedSeconds"))
        epoch_millis = numeric(sample.get("epochMillis"))
        advance = numeric(sample.get("advanceMicros"))
        value = numeric(sample.get(predictor))
        if value is None:
            value = report_value
        if elapsed is None or epoch_millis is None or advance is None or value is None:
            continue
        battle_id = int(numeric(sample.get("battleId")) or 0)
        bucket = int(elapsed // max(bucket_seconds, 0.001))
        grouped[(battle_id, bucket)].append((value, advance, epoch_millis / 1000.0))

    result = []
    for (battle_id, bucket), values in sorted(grouped.items()):
        result.append(WorkloadWindow(
            run_id=run_id,
            cell_id=cell_id,
            battle_id=battle_id,
            bucket=bucket,
            predictor=statistics.median(value[0] for value in values),
            advance_micros=statistics.median(value[1] for value in values),
            center_epoch_seconds=statistics.median(value[2] for value in values),
            workload_samples=len(values),
        ))
    return result


def load_jfr_events(
        recording_path: str | pathlib.Path,
        depth: int,
        event_loader: Callable = events,
) -> tuple[list[dict], list[dict]]:
    jfr = _jfr_binary() if event_loader is events else None
    sampled = event_loader(
        str(recording_path), ["jdk.ExecutionSample"], depth=depth, jfr=jfr)
    calibration = event_loader(
        str(recording_path), [CALIBRATION_EVENT], depth=1, jfr=jfr)
    return sampled, calibration


def combat_sample_stacks(sampled: list[dict]) -> list[tuple[int, list[str]]]:
    result = []
    for index, event in enumerate(sampled):
        if thread_of(event) != "main":
            continue
        methods = methods_of(event)
        if gameplay_state(methods) == "combat":
            result.append((index, methods))
    return result


def sample_shares(stacks: list[list[str]]) -> tuple[dict[str, float], dict[str, float]]:
    inclusive = collections.Counter()
    leaves = collections.Counter()
    for methods in stacks:
        useful = [method for method in methods if interesting(method)]
        if useful:
            leaves[useful[0]] += 1
        inclusive.update(set(useful))
    total = len(stacks)
    if total == 0:
        return {}, {}
    return (
        {method: count / total for method, count in inclusive.items()},
        {method: count / total for method, count in leaves.items()},
    )


def corrected_event_times(sampled: list[dict], calibration: list[dict]) -> dict[int, float]:
    raw = [(index, instant(event.get("values", {}).get("startTime")))
           for index, event in enumerate(sampled)]
    raw = [(index, stamp) for index, stamp in raw if stamp is not None]
    if not raw:
        return {}
    factor = clock_factor(calibration)
    calibration_stamps = sorted(
        stamp for event in calibration
        if (stamp := instant(event.get("values", {}).get("startTime"))) is not None)
    anchor = calibration_stamps[0] if calibration_stamps else raw[0][1]
    return {index: anchor + (stamp - anchor) * factor for index, stamp in raw}


def probe_clean_combat_stacks(
        sampled: list[dict],
        calibration: list[dict],
        intervals: list[tuple[float, float]],
) -> tuple[list[tuple[int, list[str]]], int, dict[int, float]]:
    corrected = corrected_event_times(sampled, calibration)
    kept = []
    excluded = 0
    for index, methods in combat_sample_stacks(sampled):
        timestamp = corrected.get(index)
        if timestamp is not None and inside_intervals(timestamp, intervals):
            excluded += 1
            continue
        kept.append((index, methods))
    return kept, excluded, corrected


def observe_run(
        workload_path: str | pathlib.Path,
        recording_path: str | pathlib.Path,
        predictor: str,
        depth: int = 96,
        event_loader: Callable = events,
) -> RunObservation:
    run_id, cell_id, predictor_value, advance = load_workload(workload_path, predictor)
    sampled, calibration = load_jfr_events(recording_path, depth, event_loader=event_loader)
    clean, excluded, _ = probe_clean_combat_stacks(
        sampled, calibration, probe_intervals(workload_path))
    stacks = [methods for _, methods in clean]
    if not stacks:
        raise ValueError(f"{recording_path}: no probe-clean main-thread combat ExecutionSample stacks")
    inclusive, leaves = sample_shares(stacks)
    return RunObservation(
        run_id,
        cell_id,
        predictor_value,
        advance,
        len(stacks),
        inclusive,
        leaves,
        excluded_probe_samples=excluded,
    )


def nearest_window_index(windows: list[WorkloadWindow], epoch_seconds: float,
                         maximum_distance: float) -> int | None:
    if not windows:
        return None
    index = min(range(len(windows)),
                key=lambda candidate: abs(windows[candidate].center_epoch_seconds - epoch_seconds))
    if abs(windows[index].center_epoch_seconds - epoch_seconds) > maximum_distance:
        return None
    return index


def observe_windows(
        workload_path: str | pathlib.Path,
        recording_path: str | pathlib.Path,
        predictor: str,
        bucket_seconds: float = 10.0,
        depth: int = 96,
        minimum_combat_samples: int = 3,
        event_loader: Callable = events,
) -> list[RunObservation]:
    windows = workload_windows(workload_path, predictor, bucket_seconds=bucket_seconds)
    if len(windows) < 2:
        raise ValueError(f"{workload_path}: fewer than two timestamped workload windows")
    sampled, calibration = load_jfr_events(recording_path, depth, event_loader=event_loader)
    clean, excluded, corrected = probe_clean_combat_stacks(
        sampled, calibration, probe_intervals(workload_path))
    by_window: list[list[list[str]]] = [[] for _ in windows]
    maximum_distance = max(bucket_seconds, 1.0)
    for event_index, methods in clean:
        if event_index not in corrected:
            continue
        window_index = nearest_window_index(
            windows, corrected[event_index], maximum_distance=maximum_distance)
        if window_index is not None:
            by_window[window_index].append(methods)

    result = []
    retained_total = sum(len(stacks) for stacks in by_window)
    for window, stacks in zip(windows, by_window):
        if len(stacks) < minimum_combat_samples:
            continue
        inclusive, leaves = sample_shares(stacks)
        excluded_share = round(excluded * len(stacks) / retained_total) if retained_total else 0
        result.append(RunObservation(
            run_id=window.run_id,
            cell_id=window.cell_id,
            predictor=window.predictor,
            advance_micros=window.advance_micros,
            combat_samples=len(stacks),
            inclusive_shares=inclusive,
            leaf_shares=leaves,
            window=window.label,
            excluded_probe_samples=excluded_share,
        ))
    if len(result) < 4:
        raise ValueError(
            f"{recording_path}: only {len(result)} workload windows had "
            f">={minimum_combat_samples} aligned probe-clean combat samples")
    return result


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


def centered(values: list[float], observations: list[RunObservation]) -> list[float]:
    grouped = collections.defaultdict(list)
    for index, observation in enumerate(observations):
        grouped[observation.run_id].append(index)
    result = list(values)
    for indexes in grouped.values():
        mean = statistics.fmean(values[index] for index in indexes)
        for index in indexes:
            result[index] = values[index] - mean
    return result


def associations(
        observations: list[RunObservation],
        leaf: bool = False,
        center_within_run: bool = False,
) -> list[MethodAssociation]:
    if len(observations) < 4:
        raise ValueError("at least four paired workload/JFR observations are required")
    predictors = [item.predictor for item in observations]
    if center_within_run:
        predictors = centered(predictors, observations)
    if len({round(value, 12) for value in predictors}) < 2:
        raise ValueError("predictor does not vary across paired observations")
    ordered = sorted(range(len(observations)), key=lambda index: predictors[index])
    quartile = max(1, len(observations) // 4)
    low_indexes = ordered[:quartile]
    high_indexes = ordered[-quartile:]

    methods = set()
    for item in observations:
        methods.update(item.leaf_shares if leaf else item.inclusive_shares)
    result = []
    for method in methods:
        raw_shares = [
            (item.leaf_shares if leaf else item.inclusive_shares).get(method, 0.0)
            for item in observations
        ]
        runs_with_samples = len({
            item.run_id for item, share in zip(observations, raw_shares) if share > 0.0
        })
        if sum(share > 0.0 for share in raw_shares) < 2:
            continue
        shares = centered(raw_shares, observations) if center_within_run else raw_shares
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
            mean_share_percent=statistics.fmean(raw_shares) * 100.0,
            runs_with_samples=runs_with_samples,
        ))
    result.sort(key=lambda item: (-item.score, -item.correlation, item.method))
    return result


def render(
        observations: list[RunObservation],
        ranked: list[MethodAssociation],
        predictor: str,
        top: int,
        leaf: bool,
        center_within_run: bool = False,
) -> str:
    mode = "leaf" if leaf else "inclusive"
    predictor_values = [item.predictor for item in observations]
    advance_values = [item.advance_micros for item in observations]
    if center_within_run:
        predictor_values = centered(predictor_values, observations)
        advance_values = centered(advance_values, observations)
    scope = "aligned workload windows" if any(item.window != "whole-run" for item in observations) \
        else "paired runs"
    share_label = "run-centered sample share" if center_within_run else "sample share"
    lines = [
        "# Combat scaling owner attribution",
        "",
        f"Predictor: `{predictor}`; {scope}={len(observations)}; JFR mode={mode}.",
        f"Predictor vs sampled `CombatEngine.advance`: r={pearson(predictor_values, advance_values):.3f}.",
        f"Association basis: {share_label}.",
        f"Probe-time combat samples excluded: {sum(item.excluded_probe_samples for item in observations)}.",
        "ExecutionSample shares below describe observed sample composition only; they are not elapsed CPU/wall time.",
        "",
        "## Paired observations",
        "",
        "| run | cell | window | predictor | median advance µs | combat samples | probe samples excluded |",
        "| --- | --- | --- | ---: | ---: | ---: | ---: |",
    ]
    for item in sorted(observations,
                       key=lambda value: (value.run_id, value.cell_id, value.window, value.predictor)):
        lines.append(
            f"| `{item.run_id}` | `{item.cell_id}` | `{item.window}` | {item.predictor:.3f} | "
            f"{item.advance_micros:.3f} | {item.combat_samples} | {item.excluded_probe_samples} |")
    lines.extend([
        "",
        "## Methods rising with predictor",
        "",
        "| rank | exact method | r | low share % | high share % | Δ pp | slope pp/unit | runs present | mean raw share % |",
        "| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
    ])
    for index, item in enumerate(ranked[:top], 1):
        lines.append(
            f"| {index} | `{item.method}` | {item.correlation:.3f} | "
            f"{item.low_share_percent:.3f} | {item.high_share_percent:.3f} | "
            f"{item.high_minus_low_points:.3f} | {item.slope_share_points_per_unit:.6f} | "
            f"{item.runs_with_samples} | {item.mean_share_percent:.3f} |")
    lines.append("")
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--pair", action="append", nargs=2, metavar=("WORKLOAD_JSON", "STARTUP_JFR"),
                        required=True, help="repeat for each paired run/cell")
    parser.add_argument("--predictor", required=True,
                        help="numeric workload field, e.g. missiles or nearbyEntitiesMean")
    parser.add_argument("--bucket-seconds", type=float,
                        help="align JFR samples to elapsed-combat windows; centers associations within run")
    parser.add_argument("--min-window-samples", type=int, default=3,
                        help="minimum aligned combat ExecutionSamples per workload window (default 3)")
    parser.add_argument("--top", type=int, default=30, help="methods to print")
    parser.add_argument("--depth", type=int, default=96, help="JFR stack depth")
    parser.add_argument("--leaf", action="store_true", help="rank leaf share instead of inclusive presence")
    args = parser.parse_args()

    observations = []
    if args.bucket_seconds:
        for workload, recording in args.pair:
            observations.extend(observe_windows(
                workload,
                recording,
                args.predictor,
                bucket_seconds=args.bucket_seconds,
                depth=args.depth,
                minimum_combat_samples=args.min_window_samples,
            ))
        center_within_run = True
    else:
        observations = [
            observe_run(workload, recording, args.predictor, depth=args.depth)
            for workload, recording in args.pair
        ]
        center_within_run = False

    ranked = associations(observations, leaf=args.leaf, center_within_run=center_within_run)
    print(render(
        observations,
        ranked,
        args.predictor,
        args.top,
        args.leaf,
        center_within_run=center_within_run,
    ))


if __name__ == "__main__":
    main()
