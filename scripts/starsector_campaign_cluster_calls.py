#!/usr/bin/env python3
"""Correlate opt-in campaign call timers with exact repeated slow-frame windows."""

import argparse
import json
from pathlib import Path

from starsector_gameplay_hotspots import frame_report_cluster_windows, scenario_step_windows


def load_json(path, description):
    try:
        with open(path, encoding="utf-8") as source:
            return json.load(source)
    except (OSError, ValueError) as error:
        raise SystemExit(f"could not read {description} {path}: {error}") from error


def timer_groups(value, path=()):
    """Yield named telemetry groups containing bounded slowest-call windows."""
    if isinstance(value, dict):
        name = value.get("name")
        calls = value.get("slowestCalls")
        if isinstance(name, str) and isinstance(calls, list):
            yield ".".join(path + (name,)), calls
        for key, child in value.items():
            if key not in {"slowestCalls", "name"}:
                yield from timer_groups(child, path + (str(key),))
    elif isinstance(value, list):
        for child in value:
            yield from timer_groups(child, path)


def call_window(call):
    duration = call.get("durationMillis")
    end = call.get("endEpochMillis")
    start = call.get("startEpochMillis")
    if not isinstance(duration, (int, float)) or duration <= 0:
        return None
    if not isinstance(end, (int, float)):
        return None
    if not isinstance(start, (int, float)):
        start = end - duration
    if end <= start:
        return None
    return start / 1000.0, end / 1000.0, float(duration)


def overlap_rankings(health, clusters):
    """Rank timer groups by summed exact overlap, without treating nesting as additive CPU."""
    rankings = []
    for name, calls in timer_groups(health):
        retained = 0
        overlapping = 0
        overlap_seconds = 0.0
        maximum_call_millis = 0.0
        cluster_names = set()
        for call in calls:
            window = call_window(call) if isinstance(call, dict) else None
            if window is None:
                continue
            retained += 1
            start, end, duration_millis = window
            call_overlapped = False
            for cluster_name, cluster_start, cluster_end in clusters:
                overlap = min(end, cluster_end) - max(start, cluster_start)
                if overlap > 0:
                    overlap_seconds += overlap
                    call_overlapped = True
                    cluster_names.add(cluster_name)
            if call_overlapped:
                overlapping += 1
                maximum_call_millis = max(maximum_call_millis, duration_millis)
        if overlapping:
            rankings.append({
                "name": name,
                "retainedCalls": retained,
                "overlappingCalls": overlapping,
                "overlapMillis": round(overlap_seconds * 1000.0, 6),
                "maximumOverlappingCallMillis": maximum_call_millis,
                "clusters": sorted(cluster_names),
            })
    rankings.sort(key=lambda value: (
        -value["overlapMillis"],
        -value["maximumOverlappingCallMillis"],
        value["name"],
    ))
    return rankings


def intersect_cluster_windows(clusters, step_windows):
    """Clip cluster windows to exact scenario steps, preserving every nonempty intersection."""
    selected = []
    for cluster_name, cluster_start, cluster_end in clusters:
        for step_name, step_start, step_end in step_windows:
            start = max(cluster_start, step_start)
            end = min(cluster_end, step_end)
            if end > start:
                selected.append((f"{cluster_name} inside step {step_name}", start, end))
    return selected


def main():
    parser = argparse.ArgumentParser(
        description="Rank bounded campaign timer calls that overlap repeated slow-frame clusters.")
    parser.add_argument("frame_report", type=Path)
    parser.add_argument("adapter_health", type=Path)
    parser.add_argument("--frame-series", default="campaignUnpausedAfter30SecondsActive")
    parser.add_argument("--repeated-clusters", type=int, default=10, metavar="N")
    parser.add_argument("--scenario-evidence", type=Path)
    parser.add_argument("--step", action="append", dest="steps",
                        help="keep only cluster time inside this exact scenario step; repeatable")
    parser.add_argument("--top", type=int, default=30)
    parser.add_argument("--json", action="store_true", help="emit machine-readable rankings")
    args = parser.parse_args()
    if args.repeated_clusters <= 0:
        parser.error("--repeated-clusters must be positive")
    if args.top <= 0:
        parser.error("--top must be positive")

    clusters = frame_report_cluster_windows(
        args.frame_report, [args.frame_series], args.repeated_clusters)
    if args.steps:
        step_windows = scenario_step_windows(
            str(args.frame_report), args.steps,
            evidence_path=str(args.scenario_evidence) if args.scenario_evidence else None)
        clusters = intersect_cluster_windows(clusters, step_windows)
    # Campaign timers are written to the frame report, while older seam timers and
    # adapter diagnostics may live in adapter health. Search both without assuming
    # that every runtime version emits the same telemetry document split.
    frame_report = load_json(args.frame_report, "runtime frame report")
    adapter_health = load_json(args.adapter_health, "runtime adapter health")
    rankings = overlap_rankings({
        "runtimeFrameReport": frame_report,
        "runtimeAdapterHealth": adapter_health,
    }, clusters)
    if args.json:
        print(json.dumps({
            "frameSeries": args.frame_series,
            "clusterWindows": [
                {"name": name, "startEpochSeconds": start, "endEpochSeconds": end}
                for name, start, end in clusters
            ],
            "rankings": rankings[:args.top],
            "interpretation": (
                "Nested inclusive timers overlap each other; overlapMillis must not be summed "
                "across rows as additive CPU time."
            ),
        }, indent=2))
        return

    print(f"frame series: {args.frame_series}")
    print(f"repeated clusters: {len(clusters)}")
    if args.steps:
        print(f"scenario steps: {', '.join(args.steps)}")
    print("interpretation: timers are inclusive and nested; row overlap is not additive CPU time")
    if not rankings:
        print("no retained >=1ms campaign timer call overlapped the selected clusters")
        return
    print("\n overlap ms  calls  max call ms  timer")
    for value in rankings[:args.top]:
        print(f" {value['overlapMillis']:10.2f}  {value['overlappingCalls']:5d}  "
              f"{value['maximumOverlappingCallMillis']:11.2f}  {value['name']}")


if __name__ == "__main__":
    main()
