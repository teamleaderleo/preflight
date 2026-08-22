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


def bar(fraction: float, width: int = 24) -> str:
    filled = int(round(fraction * width))
    return "#" * filled + "." * (width - filled)


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
