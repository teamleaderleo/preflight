#!/usr/bin/env python3
"""Exact-profile Starsector log snapshot, delta, classification, and readiness detection."""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Iterable

LOG_GLOB = "starsector.log*"
TIMESTAMP = re.compile(r"^\s*(\d+)\s+\[")
LAUNCHER_MARKER = "graphics/fonts/orbitron12_0.png"
SAVE_DESCRIPTOR_PARTS = ("CampaignGameManager", "Reading save data from [")
PRELOAD_PARTS = ("TextureData", "VRAM after unload/preload:")

# The first thing Starsector's game-start method logs, and therefore the exact boundary between
# "the launcher is up" and "the game is loading". Both spellings come from the same method: the
# second is what an install with no mods enabled prints instead.
#
# This anchor replaced "the first timestamped line after the snapshot", which was a race rather
# than a measurement. The snapshot is taken when the launcher looks ready; whether this line has
# been flushed by then decides whether the ~18s of early loading lands inside the measured
# interval or outside it. Across the 2026-07-31 campaigns the split was total: every run anchored
# on this line measured 92-99s, every run anchored on a later line measured 74-78s, and nothing
# else distinguished them. See docs/evidence/2026-08-01-the-bimodality-was-the-anchor.md.
GAME_START_MARKERS = (
    "Running with the following mods (in order of priority):",
    "Running vanilla game with no mods.",
)


@dataclass(frozen=True)
class LogLine:
    inode: int
    file_name: str
    text: str
    observed_ns: int
    log_ms: int | None


@dataclass
class TailState:
    offsets: dict[int, int]
    partial: dict[int, bytes] = field(default_factory=dict)
    observed_lines: int = 0
    last_activity_ns: dict[int, int] = field(default_factory=dict)
    last_log_ms: dict[int, int] = field(default_factory=dict)


def _files(log_dir: Path) -> list[Path]:
    if not log_dir.is_dir():
        return []
    return sorted(path for path in log_dir.glob(LOG_GLOB) if path.is_file())


def snapshot(log_dir: Path) -> dict[str, dict[str, int]]:
    values: dict[str, dict[str, int]] = {}
    for path in _files(log_dir):
        stat = path.stat()
        values[path.name] = {"inode": stat.st_ino, "size": stat.st_size}
    return values


def write_snapshot(log_dir: Path, output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(snapshot(log_dir), indent=2, sort_keys=True) + "\n", encoding="utf-8")


def load_offsets(snapshot_file: Path) -> dict[int, int]:
    raw = json.loads(snapshot_file.read_text(encoding="utf-8"))
    return {int(value["inode"]): int(value["size"]) for value in raw.values()}


def _read_new_lines(log_dir: Path, state: TailState) -> list[LogLine]:
    lines: list[LogLine] = []
    for path in _files(log_dir):
        stat = path.stat()
        inode = int(stat.st_ino)
        offset = state.offsets.get(inode, 0)
        if stat.st_size < offset:
            offset = 0
            state.partial.pop(inode, None)
        if stat.st_size == offset:
            continue
        with path.open("rb") as stream:
            stream.seek(offset)
            data = stream.read()
        state.offsets[inode] = stat.st_size
        if not data:
            continue
        combined = state.partial.get(inode, b"") + data
        chunks = combined.splitlines(keepends=True)
        state.partial[inode] = b""
        if chunks and not chunks[-1].endswith((b"\n", b"\r")):
            state.partial[inode] = chunks.pop()
        observed_ns = time.monotonic_ns()
        for chunk in chunks:
            text = chunk.decode("utf-8", errors="replace").rstrip("\r\n")
            match = TIMESTAMP.match(text)
            log_ms = int(match.group(1)) if match else None
            lines.append(LogLine(inode, path.name, text, observed_ns, log_ms))
            state.observed_lines += 1
            state.last_activity_ns[inode] = observed_ns
            if log_ms is not None:
                state.last_log_ms[inode] = log_ms
    return lines


def _pid_alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True


def _write_result(output: Path, result: dict[str, object]) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")


class WallClock:
    """Converts the monotonic stamps used for measurement into absolute instants.

    Durations are measured on the monotonic clock so a system time adjustment mid-run
    cannot distort them. The benchmark recorder needs absolute milestones, so one
    reference pair taken at watch start maps the two clocks onto each other.
    """

    def __init__(self) -> None:
        self.epoch_ns = time.time_ns()
        self.monotonic_ns = time.monotonic_ns()

    def epoch_millis(self, observed_ns: int) -> int:
        return round((self.epoch_ns + (observed_ns - self.monotonic_ns)) / 1_000_000)

    def iso(self, observed_ns: int) -> str:
        millis = self.epoch_millis(observed_ns)
        seconds, remainder = divmod(millis, 1000)
        moment = time.gmtime(seconds)
        return f"{time.strftime('%Y-%m-%dT%H:%M:%S', moment)}.{remainder:03d}Z"


def watch_launcher(
    log_dir: Path,
    snapshot_file: Path,
    output: Path,
    pid: int,
    process_start_ns: int,
    timeout_seconds: float,
    quiet_seconds: float,
    sleep_seconds: float = 0.05,
) -> bool:
    state = TailState(load_offsets(snapshot_file))
    deadline = time.monotonic() + timeout_seconds
    marker_line: LogLine | None = None
    while time.monotonic() < deadline:
        for line in _read_new_lines(log_dir, state):
            if LAUNCHER_MARKER in line.text:
                marker_line = line
        now_ns = time.monotonic_ns()
        quiet_ns = int(quiet_seconds * 1_000_000_000)
        if marker_line is not None:
            last_activity_ns = state.last_activity_ns.get(marker_line.inode)
            if last_activity_ns is not None and now_ns - last_activity_ns >= quiet_ns:
                result = {
                    "phase": "launcher",
                    "detected": True,
                    "launcherMarker": LAUNCHER_MARKER,
                    "launcherMarkerLine": marker_line.text[:1000],
                    "launcherLogFile": marker_line.file_name,
                    "launcherLogInode": marker_line.inode,
                    "launcherReadyMs": round((last_activity_ns - process_start_ns) / 1_000_000, 3),
                    "launcherReadyLogMillis": state.last_log_ms.get(marker_line.inode),
                    "quietConfirmationMillis": round(quiet_seconds * 1000, 3),
                    "observedLines": state.observed_lines,
                }
                _write_result(output, result)
                return True
        if not _pid_alive(pid):
            break
        time.sleep(sleep_seconds)
    _write_result(output, {
        "phase": "launcher",
        "detected": False,
        "launcherMarker": LAUNCHER_MARKER,
        "markerSeen": marker_line is not None,
        "observedLines": state.observed_lines,
        "timeoutSeconds": timeout_seconds,
        "processAlive": _pid_alive(pid),
    })
    return False


def _contains_all(line: str, parts: Iterable[str]) -> bool:
    return all(part in line for part in parts)


def watch_main_menu(
    log_dir: Path,
    snapshot_file: Path,
    output: Path,
    pid: int,
    timeout_seconds: float,
    quiet_seconds: float,
    sleep_seconds: float = 0.05,
) -> bool:
    state = TailState(load_offsets(snapshot_file))
    clock = WallClock()
    deadline = time.monotonic() + timeout_seconds
    starts: dict[int, LogLine] = {}
    first_lines: dict[int, LogLine] = {}
    descriptor_lines: dict[int, LogLine] = {}
    preload_lines: dict[int, LogLine] = {}
    candidate_inode: int | None = None

    while time.monotonic() < deadline:
        for line in _read_new_lines(log_dir, state):
            if line.log_ms is not None:
                first_lines.setdefault(line.inode, line)
                if any(marker in line.text for marker in GAME_START_MARKERS):
                    starts.setdefault(line.inode, line)
            if _contains_all(line.text, SAVE_DESCRIPTOR_PARTS):
                descriptor_lines[line.inode] = line
            if _contains_all(line.text, PRELOAD_PARTS):
                preload_lines[line.inode] = line
            if candidate_inode is None:
                matching = set(descriptor_lines) & set(preload_lines) & set(starts)
                if matching:
                    candidate_inode = min(
                        matching,
                        key=lambda inode: preload_lines[inode].observed_ns,
                    )

        now_ns = time.monotonic_ns()
        quiet_ns = int(quiet_seconds * 1_000_000_000)
        if candidate_inode is not None:
            start = starts[candidate_inode]
            descriptor = descriptor_lines[candidate_inode]
            preload = preload_lines[candidate_inode]
            last_activity_ns = state.last_activity_ns.get(candidate_inode)
            if last_activity_ns is not None and now_ns - last_activity_ns >= quiet_ns:
                observed_delta_ms = round((last_activity_ns - start.observed_ns) / 1_000_000, 3)
                end_log_ms = state.last_log_ms.get(candidate_inode)
                log_delta_ms = None
                if start.log_ms is not None and end_log_ms is not None:
                    log_delta_ms = end_log_ms - start.log_ms
                _write_result(output, {
                    "phase": "main-menu",
                    "detected": True,
                    "timingMethod": "automatic-starsector-log-phase-detection",
                    "gameLogFile": start.file_name,
                    "gameLogInode": candidate_inode,
                    "gameStartLine": start.text[:1000],
                    "gameStartLogMillis": start.log_ms,
                    "mainMenuReadyLogMillis": end_log_ms,
                    "gameStartInstant": clock.iso(start.observed_ns),
                    "mainMenuReadyInstant": clock.iso(last_activity_ns),
                    "gameLogStartToMainMenuMs": observed_delta_ms,
                    # The measurement boundary. "Last line before the quiet window" turned out
                    # to include whatever the game happened to log afterwards, which on the
                    # 2026-07-31 campaign ranged from 0.0 to 9.3 seconds of pure noise across
                    # otherwise identical runs. The preload marker is the actual end of the
                    # load: it lands 0.5-0.7s after the save-descriptor read in every observed
                    # run. The quiet window still has to pass -- it is what proves the phase
                    # ended -- it just no longer sets the timestamp.
                    "gameLogStartToGraphicsPreloadMs": round(
                        (preload.observed_ns - start.observed_ns) / 1_000_000, 3),
                    "graphicsPreloadInstant": clock.iso(preload.observed_ns),
                    "gameStartAnchor": "game-start-log-marker",
                    # The anchor this replaced, kept so a session can be compared against the
                    # older ones and so the size of the artifact stays visible rather than
                    # becoming a claim in a document. On a contaminated run the two differ by
                    # the whole early-loading phase; on a clean one they are the same line.
                    "firstObservedLogLine": first_lines[candidate_inode].text[:1000],
                    "firstObservedLogLineToGraphicsPreloadMs": round(
                        (preload.observed_ns - first_lines[candidate_inode].observed_ns)
                        / 1_000_000, 3),
                    "trailingLogActivityMs": round(
                        (last_activity_ns - preload.observed_ns) / 1_000_000, 3),
                    "gameLogMillisDelta": log_delta_ms,
                    "saveDescriptorSeen": True,
                    "saveDescriptorLine": descriptor.text[:1000],
                    "graphicsPreloadSeen": True,
                    "graphicsPreloadLine": preload.text[:1000],
                    "quietConfirmationMillis": round(quiet_seconds * 1000, 3),
                    "observedLines": state.observed_lines,
                })
                return True
        if not _pid_alive(pid):
            break
        time.sleep(sleep_seconds)

    _write_result(output, {
        "phase": "main-menu",
        "detected": False,
        "candidateLogStreamSeen": candidate_inode is not None,
        # Distinguishing these two is what tells "the game never started" from "the game started
        # and never finished loading": the first has streams but no start markers.
        "timestampedLogStreams": len(first_lines),
        "gameStartMarkerStreams": len(starts),
        "saveDescriptorStreams": len(descriptor_lines),
        "graphicsPreloadStreams": len(preload_lines),
        "observedLines": state.observed_lines,
        "timeoutSeconds": timeout_seconds,
        "processAlive": _pid_alive(pid),
    })
    return False


def extract_delta(log_dir: Path, snapshot_file: Path, output: Path) -> None:
    before = json.loads(snapshot_file.read_text(encoding="utf-8"))
    prior_by_inode = {int(value["inode"]): int(value["size"]) for value in before.values()}
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("wb") as stream:
        if not log_dir.is_dir():
            stream.write(b"log directory unavailable\n")
            return
        for path in _files(log_dir):
            stat = path.stat()
            offset = prior_by_inode.get(int(stat.st_ino), 0)
            if stat.st_size < offset:
                offset = 0
            stream.write(f"\n===== {path.name} offset={offset} size={stat.st_size} =====\n".encode())
            with path.open("rb") as source:
                source.seek(offset)
                while chunk := source.read(1024 * 1024):
                    stream.write(chunk)


def classify(input_file: Path, output: Path) -> None:
    text = input_file.read_text(encoding="utf-8", errors="replace")
    lines = text.splitlines()
    patterns: dict[str, Callable[[str], bool]] = {
        "normalMapBufferFailures": lambda line: (
            "Failed to load texture when generating normal map" in line
            and "Number of remaining buffer elements is 4, must be at least 16" in line
        ),
        "shaderCreationErrors": lambda line: "ERROR org.dark.shaders.util.ShaderLib  - Error creating shader:" in line,
        "musicSourceWarnings": lambda line: "WARN  com.fs.starfarer.D.K  - Error initializing music source" in line,
        "graphicsLibErrors": lambda line: "ERROR org.dark.shaders" in line,
        "graphicsLibWarnings": lambda line: "WARN  org.dark.shaders" in line,
    }
    counts = {name: sum(1 for line in lines if predicate(line)) for name, predicate in patterns.items()}
    matched: list[str] = []
    for line in lines:
        if any(predicate(line) for predicate in patterns.values()):
            matched.append(line[:1000])
            if len(matched) == 64:
                break
    _write_result(output, {"counts": counts, "firstMatchedLines": matched, "bytes": input_file.stat().st_size})


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)

    snapshot_parser = subparsers.add_parser("snapshot")
    snapshot_parser.add_argument("--log-dir", type=Path, required=True)
    snapshot_parser.add_argument("--output", type=Path, required=True)

    extract_parser = subparsers.add_parser("extract")
    extract_parser.add_argument("--log-dir", type=Path, required=True)
    extract_parser.add_argument("--snapshot", type=Path, required=True)
    extract_parser.add_argument("--output", type=Path, required=True)

    classify_parser = subparsers.add_parser("classify")
    classify_parser.add_argument("--input", type=Path, required=True)
    classify_parser.add_argument("--output", type=Path, required=True)

    for name in ("watch-launcher", "watch-main-menu"):
        watch = subparsers.add_parser(name)
        watch.add_argument("--log-dir", type=Path, required=True)
        watch.add_argument("--snapshot", type=Path, required=True)
        watch.add_argument("--output", type=Path, required=True)
        watch.add_argument("--pid", type=int, required=True)
        watch.add_argument("--timeout-seconds", type=float, required=True)
        watch.add_argument("--quiet-seconds", type=float, required=True)
        if name == "watch-launcher":
            watch.add_argument("--process-start-ns", type=int, required=True)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    if args.command == "snapshot":
        write_snapshot(args.log_dir, args.output)
        return 0
    if args.command == "extract":
        extract_delta(args.log_dir, args.snapshot, args.output)
        return 0
    if args.command == "classify":
        classify(args.input, args.output)
        return 0
    if args.command == "watch-launcher":
        return 0 if watch_launcher(
            args.log_dir, args.snapshot, args.output, args.pid, args.process_start_ns,
            args.timeout_seconds, args.quiet_seconds,
        ) else 1
    if args.command == "watch-main-menu":
        return 0 if watch_main_menu(
            args.log_dir, args.snapshot, args.output, args.pid,
            args.timeout_seconds, args.quiet_seconds,
        ) else 1
    raise AssertionError(args.command)


if __name__ == "__main__":
    raise SystemExit(main())
