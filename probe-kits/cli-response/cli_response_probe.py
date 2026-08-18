#!/usr/bin/env python3
"""Measure Preflight's ordinary terminal response paths as fresh processes."""

from __future__ import annotations

import argparse
import dataclasses
import datetime as dt
import json
import os
import platform
import statistics
import subprocess
import sys
import time
from pathlib import Path
from typing import Iterable, Sequence

ANSI_ESCAPE = "\x1b["


@dataclasses.dataclass(frozen=True)
class Case:
    name: str
    arguments: tuple[str, ...]
    expects_json: bool = False
    accepts_game: bool = False
    accepts_launcher: bool = False


@dataclasses.dataclass(frozen=True)
class Sample:
    elapsed_ms: float
    exit_code: int
    stdout: str
    stderr: str
    timed_out: bool


def build_cases(profile_preview: str | None = None) -> list[Case]:
    cases = [
        Case("help", ("--help",)),
        Case("doctor", ("doctor",), accepts_game=True, accepts_launcher=True),
        Case("profile-list", ("profile", "list"), accepts_game=True, accepts_launcher=True),
        Case(
            "profile-list-json",
            ("profile", "list", "--json"),
            expects_json=True,
            accepts_game=True,
            accepts_launcher=True,
        ),
        Case("cache-summary", ("cache",), accepts_game=True, accepts_launcher=True),
        Case(
            "cache-summary-json",
            ("cache", "--json"),
            expects_json=True,
            accepts_game=True,
            accepts_launcher=True,
        ),
        Case("prepare-plan", ("prepare", "--plan"), accepts_game=True),
        Case(
            "prepare-plan-json",
            ("prepare", "--plan", "--json"),
            expects_json=True,
            accepts_game=True,
        ),
        Case(
            "cache-repair-preview",
            ("cache", "repair"),
            accepts_game=True,
            accepts_launcher=True,
        ),
        Case(
            "cache-repair-preview-json",
            ("cache", "repair", "--json"),
            expects_json=True,
            accepts_game=True,
            accepts_launcher=True,
        ),
        Case(
            "cache-prune-preview",
            ("cache", "prune"),
            accepts_game=True,
            accepts_launcher=True,
        ),
        Case(
            "cache-prune-preview-json",
            ("cache", "prune", "--json"),
            expects_json=True,
            accepts_game=True,
            accepts_launcher=True,
        ),
        Case("uninstall-preview", ("uninstall",)),
        Case("uninstall-preview-json", ("uninstall", "--json"), expects_json=True),
    ]
    if profile_preview is not None:
        cases.extend(
            [
                Case(
                    "profile-activate-preview",
                    ("profile", "activate", profile_preview),
                    accepts_game=True,
                    accepts_launcher=True,
                ),
                Case(
                    "profile-activate-preview-json",
                    ("profile", "activate", profile_preview, "--json"),
                    expects_json=True,
                    accepts_game=True,
                    accepts_launcher=True,
                ),
            ]
        )
    return cases


def case_command(
    prefix: Sequence[str],
    case: Case,
    game: Path | None,
    launcher: Path | None,
) -> list[str]:
    command = list(prefix)
    command.extend(case.arguments)
    if game is not None and case.accepts_game:
        command.extend(("--game", str(game)))
    if launcher is not None and case.accepts_launcher:
        command.extend(("--launcher", str(launcher)))
    return command


def run_once(command: Sequence[str], timeout_seconds: float, environment: dict[str, str]) -> Sample:
    started = time.perf_counter_ns()
    try:
        completed = subprocess.run(
            command,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace",
            env=environment,
            timeout=timeout_seconds,
            check=False,
        )
        elapsed_ms = (time.perf_counter_ns() - started) / 1_000_000.0
        return Sample(
            elapsed_ms=elapsed_ms,
            exit_code=completed.returncode,
            stdout=completed.stdout,
            stderr=completed.stderr,
            timed_out=False,
        )
    except subprocess.TimeoutExpired as failure:
        elapsed_ms = (time.perf_counter_ns() - started) / 1_000_000.0
        stdout = _decoded_timeout_output(failure.stdout)
        stderr = _decoded_timeout_output(failure.stderr)
        return Sample(
            elapsed_ms=elapsed_ms,
            exit_code=124,
            stdout=stdout,
            stderr=stderr,
            timed_out=True,
        )


def _decoded_timeout_output(value: str | bytes | None) -> str:
    if value is None:
        return ""
    if isinstance(value, bytes):
        return value.decode("utf-8", errors="replace")
    return value


def redirected_has_ansi(text: str) -> bool:
    return ANSI_ESCAPE in text


def parses_json_document(text: str) -> bool:
    try:
        json.loads(text)
        return True
    except (TypeError, json.JSONDecodeError):
        return False


def percentile(values: Sequence[float], fraction: float) -> float:
    ordered = sorted(values)
    if not ordered:
        return 0.0
    index = max(0, min(len(ordered) - 1, int((len(ordered) - 1) * fraction + 0.999999)))
    return ordered[index]


def summarize_case(case: Case, command: Sequence[str], samples: Sequence[Sample]) -> dict[str, object]:
    first = samples[0]
    repeated = list(samples[1:]) or [first]
    repeat_times = [sample.elapsed_ms for sample in repeated]
    stdout_ansi = any(redirected_has_ansi(sample.stdout) for sample in samples)
    stderr_ansi = any(redirected_has_ansi(sample.stderr) for sample in samples)
    json_valid = None
    if case.expects_json:
        json_valid = all(parses_json_document(sample.stdout.strip()) for sample in samples)
    return {
        "name": case.name,
        "arguments": list(case.arguments),
        "command": list(command),
        "expectsJson": case.expects_json,
        "firstProcessMs": round(first.elapsed_ms, 3),
        "repeatMedianMs": round(statistics.median(repeat_times), 3),
        "repeatP95Ms": round(percentile(repeat_times, 0.95), 3),
        "minMs": round(min(sample.elapsed_ms for sample in samples), 3),
        "maxMs": round(max(sample.elapsed_ms for sample in samples), 3),
        "exitCodes": [sample.exit_code for sample in samples],
        "timedOut": any(sample.timed_out for sample in samples),
        "stdoutBytes": [len(sample.stdout.encode("utf-8")) for sample in samples],
        "stderrBytes": [len(sample.stderr.encode("utf-8")) for sample in samples],
        "redirectedStdoutHasAnsi": stdout_ansi,
        "redirectedStderrHasAnsi": stderr_ansi,
        "jsonValid": json_valid,
    }


def measure_case(
    prefix: Sequence[str],
    case: Case,
    *,
    rounds: int,
    timeout_seconds: float,
    environment: dict[str, str],
    game: Path | None = None,
    launcher: Path | None = None,
) -> dict[str, object]:
    command = case_command(prefix, case, game, launcher)
    samples = [run_once(command, timeout_seconds, environment) for _ in range(rounds)]
    return summarize_case(case, command, samples)


def command_prefix(arguments: argparse.Namespace) -> list[str]:
    if arguments.jar is not None:
        return [arguments.java, "-jar", str(arguments.jar)]
    return [str(arguments.preflight)]


def parse_arguments(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Measure Preflight CLI response time and redirected-output boundaries."
    )
    launcher = parser.add_mutually_exclusive_group(required=True)
    launcher.add_argument("--preflight", type=Path, help="installed preflight executable")
    launcher.add_argument("--jar", type=Path, help="packaged preflight.jar")
    parser.add_argument("--java", default="java", help="Java executable used with --jar")
    parser.add_argument("--game", type=Path, help="explicit Starsector installation")
    parser.add_argument("--launcher", type=Path, help="explicit Starsector launcher")
    parser.add_argument(
        "--profile-preview",
        metavar="NAME",
        help="also measure preview-only profile activation for this existing named profile",
    )
    parser.add_argument("--rounds", type=int, default=5, help="fresh processes per case (default: 5)")
    parser.add_argument("--timeout-seconds", type=float, default=30.0)
    parser.add_argument(
        "--case",
        action="append",
        dest="cases",
        help="measure only the named case; repeat this option to select several",
    )
    parser.add_argument(
        "--set-no-color",
        action="store_true",
        help="set NO_COLOR=1 in measured child processes",
    )
    parser.add_argument("--json", action="store_true", help="emit the probe report as JSON")
    parser.add_argument("--output", type=Path, help="also write the JSON report to this path")
    parsed = parser.parse_args(argv)
    if parsed.rounds < 2:
        parser.error("--rounds must be at least 2 so first-process and repeat-process timings stay distinct")
    if parsed.timeout_seconds <= 0:
        parser.error("--timeout-seconds must be positive")
    return parsed


def selected_cases(all_cases: Iterable[Case], requested: Sequence[str] | None) -> list[Case]:
    cases = list(all_cases)
    if not requested:
        return cases
    by_name = {case.name: case for case in cases}
    missing = [name for name in requested if name not in by_name]
    if missing:
        choices = ", ".join(sorted(by_name))
        raise ValueError(f"Unknown case(s): {', '.join(missing)}. Available: {choices}")
    return [by_name[name] for name in requested]


def emit_human(report: dict[str, object]) -> None:
    cases = report["cases"]
    assert isinstance(cases, list)
    print("CASE                         FIRST ms  REPEAT med  P95 ms  EXIT       JSON  ANSI")
    for item in cases:
        assert isinstance(item, dict)
        exits = ",".join(str(value) for value in sorted(set(item["exitCodes"])))
        json_status = "-"
        if item["expectsJson"]:
            json_status = "ok" if item["jsonValid"] else "BAD"
        ansi_status = "yes" if item["redirectedStdoutHasAnsi"] or item["redirectedStderrHasAnsi"] else "no"
        print(
            f"{item['name']:<28}"
            f" {item['firstProcessMs']:>8.1f}"
            f" {item['repeatMedianMs']:>11.1f}"
            f" {item['repeatP95Ms']:>7.1f}"
            f"  {exits:<9}"
            f" {json_status:>4}"
            f"  {ansi_status}"
        )


def main(argv: Sequence[str] | None = None) -> int:
    arguments = parse_arguments(sys.argv[1:] if argv is None else argv)
    prefix = command_prefix(arguments)
    environment = dict(os.environ)
    if arguments.set_no_color:
        environment["NO_COLOR"] = "1"

    try:
        cases = selected_cases(build_cases(arguments.profile_preview), arguments.cases)
    except ValueError as failure:
        print(f"cli-response-probe: {failure}", file=sys.stderr)
        return 2

    results = [
        measure_case(
            prefix,
            case,
            rounds=arguments.rounds,
            timeout_seconds=arguments.timeout_seconds,
            environment=environment,
            game=arguments.game,
            launcher=arguments.launcher,
        )
        for case in cases
    ]
    report: dict[str, object] = {
        "format": "starsector-preflight-cli-response-v1",
        "capturedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        "platform": platform.platform(),
        "python": platform.python_version(),
        "rounds": arguments.rounds,
        "timeoutSeconds": arguments.timeout_seconds,
        "stdoutRedirected": True,
        "stderrRedirected": True,
        "noColorPresent": "NO_COLOR" in environment,
        "cases": results,
    }
    rendered = json.dumps(report, indent=2, sort_keys=True) + "\n"
    if arguments.output is not None:
        arguments.output.parent.mkdir(parents=True, exist_ok=True)
        arguments.output.write_text(rendered, encoding="utf-8")
    if arguments.json:
        sys.stdout.write(rendered)
    else:
        emit_human(report)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
