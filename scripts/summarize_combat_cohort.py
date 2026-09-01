#!/usr/bin/env python3
"""Print a compact, gated comparison of thin combat benchmark runs."""
from __future__ import annotations

import argparse
import json
import statistics
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any


@dataclass(frozen=True)
class Run:
    label: str
    path: Path
    arm: str
    frames: int
    active_seconds: float
    average_fps: float
    p50_ms: float
    p95_ms: float
    p99_ms: float
    max_ms: float
    one_percent_low_fps: float
    over50_per_minute: float
    over100_per_minute: float
    stutter_burden: float
    combat_seconds: float
    begin_ships: int
    begin_side_zero_nonfighters: int
    begin_side_one_nonfighters: int
    begin_projectiles: int
    begin_missiles: int
    end_ships: int
    combat_over: bool
    suppressed_calls: int
    suppressed_percent: float | None
    causal_unit: str
    identity: str
    adapter_ok: bool


def read_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"expected JSON object: {path}")
    return value


def load_run(path: Path) -> Run:
    report_path = path / "runtime-frame-report.json"
    if not report_path.is_file():
        report_path = path / "desktop-smoke-frame-report.json"
    report = read_json(report_path)
    run = read_json(path / "run.json")
    profile = read_json(path / "profile.json")
    smoke = read_json(path / "smoke-evidence.json")
    frame = report["frameTimes"]
    window = frame["measurementWindow"]
    workload = frame["combatWorkloadFingerprint"]
    begin = workload.get("begin")
    end = workload.get("end")
    if not isinstance(begin, dict) or not isinstance(end, dict):
        raise ValueError(f"combat workload fingerprint is incomplete: {path}")
    if "openGlMatrixIdentityElision" in frame:
        probe = frame["openGlMatrixIdentityElision"]
        causal_unit = "identity matrix transforms"
    elif "openGlTextureBindDedup" in frame:
        probe = frame["openGlTextureBindDedup"]
        causal_unit = "texture binds"
    else:
        raise ValueError(f"no supported optimization counter: {path}")
    requested = bool(probe["requested"])
    active_seconds = float(window["totalActiveNanos"]) / 1_000_000_000.0
    if active_seconds <= 0:
        raise ValueError(f"measurement window is empty: {path}")
    presentation = frame["presentationPolicy"]
    viewport = next(
        (step.get("detail") for step in smoke.get("steps", [])
         if step.get("id") == "verify-zoom-out" and step.get("status") == "passed"),
        None,
    )
    identity = json.dumps({
        "preflightJarSha256": run.get("preflightJarSha256"),
        "wrapperJava": run.get("javaVersion"),
        "installRoot": run.get("installRoot"),
        "profileFingerprint": profile.get("profileFingerprint"),
        "resolvedModCount": profile.get("resolvedModCount"),
        "textureProfileFingerprint": run.get("textureProfileFingerprint"),
        "textureManifestSha256": run.get("textureManifestSha256"),
        "textureIndexSha256": run.get("textureIndexSha256"),
        "directLaunchSettings": run.get("directLaunchSettings"),
        "optimizationPreset": run.get("optimizationPreset"),
        "disabledOptimizationDomains": run.get("disabledOptimizationDomains"),
        "adapterMode": run.get("adapterMode"),
        "combatJvmSafeguard": run.get("combatJvmSafeguard", {}).get("active"),
        "macRosettaGcPolicy": run.get("macRosettaGcPolicy", {}).get("active"),
        "scenario": smoke.get("scenario"),
        "viewport": viewport,
        "swapInterval": presentation.get("lastSwapInterval"),
        "forceVsyncOff": presentation.get("forceVsyncOff"),
        "frameRateCap": presentation.get("frameRateCap"),
    }, sort_keys=True, separators=(",", ":"))
    adapter_ok = (
        smoke.get("status") == "passed"
        and run.get("outcome") == "COMPLETED"
        and not run.get("lifecycleEvidence", {}).get("fatalDetected", True)
        and not probe.get("runtimeDisabled", False)
        and not probe.get("problem")
        and int(probe.get("unexpectedThreadCalls", 0)) == 0
        and bool(probe.get("active")) == requested
        and (not requested or int(probe.get("installedTargetCount", 0)) > 0)
        and (not requested or int(probe.get("installedMethodCount", 0)) > 0)
    )
    return Run(
        label=path.name,
        path=path,
        arm="B" if requested else "A",
        frames=int(window["frames"]),
        active_seconds=active_seconds,
        average_fps=float(window["averageFps"]),
        p50_ms=float(window["p50Micros"]) / 1000.0,
        p95_ms=float(window["p95Micros"]) / 1000.0,
        p99_ms=float(window["p99Micros"]) / 1000.0,
        max_ms=float(window["maximumMicros"]) / 1000.0,
        one_percent_low_fps=float(window["onePercentLowFps"]),
        over50_per_minute=float(window["over50Millis"]) * 60.0 / active_seconds,
        over100_per_minute=float(window["over100Millis"]) * 60.0 / active_seconds,
        stutter_burden=float(window["stutterProfile"]["stutterBurdenMillisPerSecond"]),
        combat_seconds=float(workload["combatSecondsElapsed"]),
        begin_ships=int(begin["ships"]),
        begin_side_zero_nonfighters=int(begin["sideZero"]["aliveNonFighters"]),
        begin_side_one_nonfighters=int(begin["sideOne"]["aliveNonFighters"]),
        begin_projectiles=int(begin["projectiles"]),
        begin_missiles=int(begin["missiles"]),
        end_ships=int(end["ships"]),
        combat_over=bool(end["combatOver"]),
        suppressed_calls=int(probe["suppressedCalls"]),
        suppressed_percent=(None if probe["suppressedPercent"] is None
                            else float(probe["suppressedPercent"])),
        causal_unit=causal_unit,
        identity=identity,
        adapter_ok=adapter_ok,
    )


def workload_gate(runs: list[Run]) -> bool:
    begin_ships = [run.begin_ships for run in runs]
    begin_side_zero_nonfighters = [run.begin_side_zero_nonfighters for run in runs]
    begin_side_one_nonfighters = [run.begin_side_one_nonfighters for run in runs]
    begin_projectiles = [run.begin_projectiles for run in runs]
    begin_missiles = [run.begin_missiles for run in runs]
    combat_seconds = [run.combat_seconds for run in runs]
    end_ships = [run.end_ships for run in runs]
    seconds_limit = max(2.0, statistics.median(combat_seconds) * 0.10)
    end_ship_limit = max(2.0, statistics.median(end_ships) * 0.15)
    return (
        min(begin_side_zero_nonfighters) == max(begin_side_zero_nonfighters)
        and min(begin_side_one_nonfighters) == max(begin_side_one_nonfighters)
        # Fighters may launch during the sub-second boundary handshake. Bound
        # that timing noise while requiring the deployed combatants exactly.
        and max(begin_ships) - min(begin_ships) <= 8
        and max(begin_projectiles) - min(begin_projectiles) <= 5
        and max(begin_missiles) - min(begin_missiles) <= 5
        and max(combat_seconds) - min(combat_seconds) <= seconds_limit
        and max(end_ships) - min(end_ships) <= end_ship_limit
        and not any(run.combat_over for run in runs)
    )


def median_for(runs: list[Run], field: str) -> float:
    return float(statistics.median(getattr(run, field) for run in runs))


def percent_delta(candidate: float, baseline: float) -> float:
    return 100.0 * (candidate - baseline) / baseline


def render(runs: list[Run]) -> str:
    lines = [
        "run                              arm avg   p50   p95   p99   1%low max    >50/m >100/m sim-s",
    ]
    for run in runs:
        lines.append(
            f"{run.label[-32:]:<32} {run.arm:>3} "
            f"{run.average_fps:>5.2f} {run.p50_ms:>5.1f} {run.p95_ms:>5.1f} "
            f"{run.p99_ms:>5.1f} {run.one_percent_low_fps:>6.2f} "
            f"{run.max_ms:>6.1f} {run.over50_per_minute:>6.1f} "
            f"{run.over100_per_minute:>7.1f} {run.combat_seconds:>5.1f}"
        )
    identity_ok = len({run.identity for run in runs}) == 1
    workload_ok = workload_gate(runs)
    adapter_ok = all(run.adapter_ok for run in runs)
    lines.append(
        f"gates: identity={'PASS' if identity_ok else 'FAIL'}  "
        f"workload={'PASS' if workload_ok else 'FAIL'}  "
        f"adapter={'PASS' if adapter_ok else 'FAIL'}"
    )
    arms = {arm: [run for run in runs if run.arm == arm] for arm in ("A", "B")}
    if arms["A"] and arms["B"]:
        fields = (
            ("p99_ms", "p99"),
            ("one_percent_low_fps", "1%low"),
            ("over50_per_minute", ">50/m"),
            ("over100_per_minute", ">100/m"),
            ("stutter_burden", "stutter"),
            ("average_fps", "avg"),
        )
        deltas = []
        for field, label in fields:
            baseline = median_for(arms["A"], field)
            candidate = median_for(arms["B"], field)
            deltas.append(f"{label} {percent_delta(candidate, baseline):+.1f}%")
        lines.append("B vs A (arm medians): " + ", ".join(deltas))
        suppressed = [run for run in arms["B"] if run.suppressed_percent is not None]
        if suppressed:
            units = {run.causal_unit for run in suppressed}
            unit = units.pop() if len(units) == 1 else "operations"
            lines.append(
                "causal counter: median "
                f"{median_for(suppressed, 'suppressed_percent'):.2f}% {unit} suppressed "
                f"({int(statistics.median(run.suppressed_calls for run in suppressed)):,} calls/run)"
            )
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("runs", nargs="+", type=Path, metavar="RUN_DIRECTORY")
    args = parser.parse_args(argv)
    try:
        runs = [load_run(path.resolve()) for path in args.runs]
    except (OSError, KeyError, TypeError, ValueError, json.JSONDecodeError) as failure:
        print(f"combat cohort: {failure}", file=sys.stderr)
        return 1
    print(render(runs))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
