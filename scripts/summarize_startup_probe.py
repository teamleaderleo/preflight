#!/usr/bin/env python3
"""Summarise one `--startup-phase-probe` run: where the launch spent its time.

The probe writes every boundary it reaches to `adapter-startup-phases.json`, plus one entry per mod
callback. Read on its own that file is a wall of milliseconds. What you actually want to know is
which few blocks own the launch, and the answer is nearly always a handful of them -- so this sorts
by cost and prints the rest as a remainder rather than padding the table with 60 zero-millisecond
plugins.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any


def bar(fraction: float, width: int = 24) -> str:
    filled = int(round(fraction * width))
    return "#" * filled + "." * (width - filled)


def phase_elapsed_millis(phases: list[dict[str, Any]], name: str) -> int | None:
    for phase in phases:
        if phase.get("name") == name:
            elapsed = phase.get("elapsedMillis")
            return elapsed if isinstance(elapsed, int) else None
    return None


def resource_reconciliation(data: dict[str, Any]) -> dict[str, Any] | None:
    resources = data.get("resourceLoads")
    if not isinstance(resources, dict):
        return None
    by_type = resources.get("byType")
    if not isinstance(by_type, list):
        by_type = []
    timed_millis = sum(
        row.get("durationMillis", 0)
        for row in by_type
        if isinstance(row, dict) and isinstance(row.get("durationMillis", 0), (int, float))
    )
    phases = data.get("phases")
    if not isinstance(phases, list):
        phases = []
    start_millis = phase_elapsed_millis(phases, "resource-batches-start")
    end_millis = phase_elapsed_millis(phases, "progress-100")
    span_millis = None
    residual_millis = None
    coverage_percent = None
    if start_millis is not None and end_millis is not None and end_millis >= start_millis:
        span_millis = end_millis - start_millis
        residual_millis = span_millis - timed_millis
        if span_millis > 0:
            coverage_percent = timed_millis * 100.0 / span_millis
    return {
        "calls": resources.get("calls", 0),
        "spanMillis": span_millis,
        "timedMillis": timed_millis,
        "residualMillis": residual_millis,
        "coveragePercent": coverage_percent,
        "byType": by_type,
        "first": resources.get("first") if isinstance(resources.get("first"), list) else [],
        "slowest": resources.get("slowest") if isinstance(resources.get("slowest"), list) else [],
    }


def print_resource_loads(data: dict[str, Any], limit: int = 10) -> None:
    summary = resource_reconciliation(data)
    if summary is None:
        return
    print("\n== resource loop reconciliation ==")
    span = summary["spanMillis"]
    if span is None:
        print("  resource-batches-start -> progress-100: unavailable (missing phase boundary)")
    else:
        print(f"  resource-batches-start -> progress-100: {span / 1000:.3f} s")
    print(f"  timed resource calls:                    {summary['timedMillis'] / 1000:.3f} s"
          f"  ({summary['calls']} calls)")
    residual = summary["residualMillis"]
    coverage = summary["coveragePercent"]
    if residual is not None and coverage is not None:
        print(f"  outside timed resource calls:            {residual / 1000:.3f} s")
        print(f"  named-call coverage:                      {coverage:.2f}%")
        if residual < 0:
            print("  WARNING: timed resource totals exceed the retained loop span; inspect anchors/report identity")
        elif coverage < 90.0:
            print("  WARNING: named resource calls account for under 90% of the retained loop span")

    by_type = [row for row in summary["byType"] if isinstance(row, dict)]
    if by_type:
        print("\n== resource types, most wall time first ==")
        for row in sorted(by_type, key=lambda item: -item.get("durationMillis", 0)):
            print(f"  {row.get('durationMillis', 0) / 1000:>8.3f}s  "
                  f"{row.get('calls', 0):>7} calls  "
                  f"{row.get('maxCallMillis', 0) / 1000:>7.3f}s max  "
                  f"{row.get('type', '<unknown>')}")

    def print_calls(title: str, calls: list[Any]) -> None:
        retained = [call for call in calls if isinstance(call, dict)]
        if not retained:
            return
        print(f"\n== {title} (showing {min(limit, len(retained))} of {len(retained)} retained) ==")
        for call in retained[:limit]:
            print(f"  #{call.get('ordinal', 0):>5}  "
                  f"{call.get('durationMillis', 0) / 1000:>7.3f}s  "
                  f"{call.get('startedAtMillis', 0) / 1000:>8.3f}s -> "
                  f"{call.get('completedAtMillis', 0) / 1000:>8.3f}s  "
                  f"w={call.get('weight', 0):>3}  {call.get('type', '<unknown>')}  "
                  f"{call.get('path', '<unknown>')}")

    print_calls("first resource calls", summary["first"])
    print_calls("slowest resource calls", summary["slowest"])


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: summarize_startup_probe.py RUN_DIRECTORY", file=sys.stderr)
        return 2
    run = Path(sys.argv[1])
    probe = run / "adapter-startup-phases.json"
    if not probe.is_file():
        print(f"No phase probe in {run}", file=sys.stderr)
        return 1
    data = json.loads(probe.read_text())

    menu = run / "menu.json"
    if menu.is_file():
        detected = json.loads(menu.read_text())
        total = detected.get("gameLogStartToGraphicsPreloadMs")
        if total:
            print(f"\ngame log start to main menu: {total / 1000:.2f} s")

    phases = data.get("phases", [])
    print("\n== phases, most expensive first ==")
    ranked = sorted(phases, key=lambda p: -p.get("sincePreviousMillis", 0))
    span = max((p.get("sincePreviousMillis", 0) for p in phases), default=1) or 1
    shown = 0
    for phase in ranked:
        cost = phase.get("sincePreviousMillis", 0)
        if cost < 100:
            break
        shown += cost
        print(f"  {cost / 1000:>7.2f}s  {bar(cost / span)}  {phase['name']}")
    remainder = sum(p.get("sincePreviousMillis", 0) for p in phases) - shown
    print(f"  {remainder / 1000:>7.2f}s  {'':24}  everything under 100 ms, combined")

    print_resource_loads(data)

    plugins = data.get("plugins", [])
    if plugins:
        total = sum(p.get("durationMillis", 0) for p in plugins)
        print(f"\n== mod callbacks: {len(plugins)} plugins, {total / 1000:.2f} s total ==")
        ranked = sorted(plugins, key=lambda p: -p.get("durationMillis", 0))
        span = max((p.get("durationMillis", 0) for p in plugins), default=1) or 1
        shown = 0
        for plugin in ranked:
            cost = plugin.get("durationMillis", 0)
            if cost < 100:
                break
            shown += cost
            share = f"{100 * cost / total:.0f}%" if total else "-"
            note = "" if plugin.get("completed") else "  <- DID NOT COMPLETE"
            print(f"  {cost / 1000:>7.2f}s {share:>5}  {bar(cost / span)}  "
                  f"{plugin['className']}{note}")
        rest = total - shown
        print(f"  {rest / 1000:>7.2f}s {'':5}  {'':24}  "
              f"the other {sum(1 for p in plugins if p.get('durationMillis', 0) < 100)} plugins")

    hot_calls = data.get("hotCalls", [])
    if hot_calls:
        print("\n== exact callback call sites ==")
        for call in sorted(hot_calls, key=lambda item: -item.get("durationMillis", 0)):
            cost = call.get("durationMillis", 0)
            maximum = call.get("maxCallMillis", 0)
            print(f"  {cost / 1000:>7.2f}s  {call.get('calls', 0):>7} calls  "
                  f"{maximum / 1000:>7.2f}s max  {call['label']}")

    sampled_hot_calls = data.get("sampledHotCalls", [])
    if sampled_hot_calls and any(call.get("calls", 0) for call in sampled_hot_calls):
        print("\n== sampled exact loader calls ==")
        for call in sorted(sampled_hot_calls,
                           key=lambda item: -item.get("estimatedDurationMillis", 0)):
            if not call.get("calls", 0):
                continue
            print(f"  ~{call.get('estimatedDurationMillis', 0) / 1000:>6.2f}s  "
                  f"{call.get('calls', 0):>7} calls  "
                  f"1/{call.get('sampleRate', 0)} sampled  "
                  f"{call.get('recurringSampledMeanNanos', call.get('sampledMeanNanos', 0)) / 1000:>7.2f} us recurring  "
                  f"{call.get('firstCallNanos', 0) / 1_000_000:>7.2f} ms first  "
                  f"{call.get('sampledMaxNanos', 0) / 1_000_000:>7.2f} ms max  "
                  f"{call['label']}")

    hot_paths = data.get("hotPaths", [])
    if hot_paths:
        print("\n== exact callback path cardinality ==")
        for paths in sorted(hot_paths, key=lambda item: -item.get("calls", 0)):
            print(f"  {paths.get('calls', 0):>7} calls  "
                  f"{paths.get('distinctPaths', 0):>7} distinct  {paths['label']}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
