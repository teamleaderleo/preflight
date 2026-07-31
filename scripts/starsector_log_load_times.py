#!/usr/bin/env python3
"""Recover every launch's load time from Starsector's own logs, with no harness involved.

The benchmark harness measures a launch it is driving. This reads what the game wrote
afterwards, which makes it the independent check on that harness -- and it is how the
2026-08-01 anchor artifact was found: the harness reported a bimodal 74-99s while the same
launches, read straight out of the log, were a unimodal 89.6-99.1s.

Two boundaries, both logged by the game itself:

  start   the first line of Starsector's game-start method, which is the exact instant the
          launcher hands over. Everything before it is launcher and JVM startup.
  end     GraphicsLib's post-preload VRAM report, the same marker the harness uses.

Rotation is handled by reading oldest file first and treating a large backwards jump in the
log's own millisecond counter as a new JVM. Timing uses those counters rather than wall clock,
so a rotated or copied log still yields the same answer.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

TIMESTAMP = re.compile(r"^\s*(\d+)\s+\[")
GAME_START_MARKERS = (
    "Running with the following mods (in order of priority):",
    "Running vanilla game with no mods.",
)
PRELOAD_MARKER = "VRAM after unload/preload:"
# A JVM's counter only ever increases. A drop of more than this is a restart rather than the
# out-of-order arrival that concurrent loggers can produce within a single run.
RESTART_BACKSTEP_MS = 5_000


def rotation_order(log_dir: Path) -> list[Path]:
    """Oldest first. log4j keeps the live file unsuffixed and gives older ones higher numbers."""

    def age(path: Path) -> int:
        suffix = path.name.rsplit(".", 1)[-1]
        return -int(suffix) if suffix.isdigit() else 0

    return sorted((p for p in log_dir.glob("starsector.log*") if p.is_file()), key=age)


def launches(log_dir: Path) -> list[dict[str, object]]:
    found: list[dict[str, object]] = []
    current: dict[str, object] | None = None
    previous_ms = -1
    for path in rotation_order(log_dir):
        with path.open(errors="replace") as stream:
            for line in stream:
                match = TIMESTAMP.match(line)
                if not match:
                    continue
                log_ms = int(match.group(1))
                if log_ms + RESTART_BACKSTEP_MS < previous_ms:
                    current = None
                previous_ms = log_ms
                if any(marker in line for marker in GAME_START_MARKERS):
                    current = {
                        "startLogMillis": log_ms,
                        "logFile": path.name,
                        "preloadLogMillis": [],
                    }
                    found.append(current)
                elif current is not None and PRELOAD_MARKER in line:
                    current["preloadLogMillis"].append(log_ms)
    for launch in found:
        preloads = launch["preloadLogMillis"]
        launch["loadMillis"] = (
            preloads[0] - launch["startLogMillis"] if preloads else None
        )
    return found


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--log-dir",
        type=Path,
        default=Path("/Applications/Starsector.app/logs"),
        help="Directory holding starsector.log and its rotations.",
    )
    parser.add_argument("--json", action="store_true", help="Emit the raw records.")
    arguments = parser.parse_args(argv)

    if not arguments.log_dir.is_dir():
        print(f"No such log directory: {arguments.log_dir}", file=sys.stderr)
        return 2

    found = launches(arguments.log_dir)
    if arguments.json:
        print(json.dumps(found, indent=2))
        return 0

    completed = [launch["loadMillis"] for launch in found if launch["loadMillis"] is not None]
    for launch in found:
        millis = launch["loadMillis"]
        # A launch with no preload marker is one the log does not cover to the end -- usually
        # the oldest surviving file starting mid-run. It is not a failed launch, and counting
        # it as one would be worse than saying so.
        measured = f"{millis / 1000:6.1f}s" if millis is not None else "  (truncated)"
        print(f"  start@{launch['startLogMillis']:>8}ms  {launch['logFile']:<20} {measured}")
    if completed:
        ordered = sorted(completed)
        middle = len(ordered) // 2
        median = (
            ordered[middle]
            if len(ordered) % 2
            else (ordered[middle - 1] + ordered[middle]) / 2
        )
        print()
        print(f"  {len(completed)} complete launches"
              f"  min {min(completed) / 1000:.1f}s"
              f"  median {median / 1000:.1f}s"
              f"  max {max(completed) / 1000:.1f}s")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
