#!/usr/bin/env python3
"""Print a compact, gated comparison of thin Nex market-list A/B runs."""
from __future__ import annotations

import argparse
import json
import statistics
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any


PLAN_ID = "nexerelin-market-list-scope-v1"


@dataclass(frozen=True)
class Run:
    label: str
    arm: str
    frames: int
    active_seconds: float
    average_fps: float
    p50_ms: float
    p95_ms: float
    p99_ms: float
    maximum_ms: float
    one_percent_low_fps: float
    over50_per_minute: float
    over100_per_minute: float
    stutter_burden: float
    paused_seconds: float
    paused_p99_ms: float
    paused_one_percent_low_fps: float
    presentation_pre_swap_p99_ms: float
    presentation_swap_p99_ms: float
    probe_overhead_micros: float
    scopes: int
    stores: int
    misses: int
    hits: int
    maximum_entries: int
    identity_fields: dict[str, Any]
    identity: str
    adapter_problems: tuple[str, ...]
    adapter_ok: bool


def read_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"expected JSON object: {path}")
    return value


def number(value: Any) -> float:
    return float(value or 0)


def normalized_disabled_plans(adapter: dict[str, Any]) -> list[str]:
    plans = adapter.get("planControl", {}).get("disabledPlans", [])
    if not isinstance(plans, list):
        raise ValueError("adapter planControl.disabledPlans is not a list")
    return sorted(str(plan) for plan in plans if plan != PLAN_ID)


def frame_metrics(window: dict[str, Any]) -> dict[str, float]:
    active_seconds = number(window["totalActiveNanos"]) / 1_000_000_000.0
    if active_seconds <= 0:
        raise ValueError("frame window is empty")
    return {
        "frames": int(window["frames"]),
        "active_seconds": active_seconds,
        "average_fps": number(window["averageFps"]),
        "p50_ms": number(window["p50Micros"]) / 1000.0,
        "p95_ms": number(window["p95Micros"]) / 1000.0,
        "p99_ms": number(window["p99Micros"]) / 1000.0,
        "maximum_ms": number(window["maximumMicros"]) / 1000.0,
        "one_percent_low_fps": number(window["onePercentLowFps"]),
        "over50_per_minute": number(window["over50Millis"]) * 60.0 / active_seconds,
        "over100_per_minute": number(window["over100Millis"]) * 60.0 / active_seconds,
        "stutter_burden": number(
            window["stutterProfile"]["stutterBurdenMillisPerSecond"]
        ),
    }


def zero_health_failures(health: dict[str, Any]) -> bool:
    fields = (
        "sourceBindingRejected",
        "unavailablePlans",
        "transformationsDeclined",
        "containedFailures",
        "cacheRejectionSignals",
        "wrapperFailureSignals",
        "runtimeIntegrityFailures",
    )
    return all(int(health.get(field) or 0) == 0 for field in fields)


def probe_adapter_ok(
    arm: str,
    probe: dict[str, Any],
    adapter: dict[str, Any],
) -> bool:
    common = (
        probe.get("planId") == PLAN_ID
        and bool(probe.get("healthy"))
        and not bool(probe.get("shadowRequested"))
        and not bool(probe.get("shadowEnabled"))
        and int(probe.get("nestedScopes") or 0) == 0
        and int(probe.get("outsideScopeDeclines") or 0) == 0
        and int(probe.get("shadowMatches") or 0) == 0
        and int(probe.get("shadowMismatches") or 0) == 0
        and int(probe.get("failures") or 0) == 0
    )
    disabled = adapter.get("planControl", {}).get("disabledPlans", [])
    if arm == "A":
        return (
            common
            and PLAN_ID in disabled
            and not bool(probe.get("requested"))
            and not bool(probe.get("enabled"))
            and not bool(probe.get("nexInstalled"))
            and not bool(probe.get("coreInstalled"))
            and all(
                int(probe.get(field) or 0) == 0
                for field in ("scopesBegun", "scopesEnded", "misses", "stores", "hits")
            )
        )
    scopes = int(probe.get("scopesBegun") or 0)
    stores = int(probe.get("stores") or 0)
    return (
        common
        and PLAN_ID not in disabled
        and bool(probe.get("requested"))
        and bool(probe.get("enabled"))
        and bool(probe.get("nexInstalled"))
        and bool(probe.get("coreInstalled"))
        and scopes > 0
        and int(probe.get("scopesEnded") or 0) == scopes
        and stores > 0
        and int(probe.get("misses") or 0) == stores
        and int(probe.get("hits") or 0) > 0
        and int(probe.get("maximumEntries") or 0) > 0
    )


def load_run(path: Path) -> Run:
    report_path = path / "runtime-frame-report.json"
    if not report_path.is_file():
        report_path = path / "desktop-smoke-frame-report.json"
    report = read_json(report_path)
    run = read_json(path / "run.json")
    profile = read_json(path / "profile.json")
    smoke = read_json(path / "smoke-evidence.json")
    adapter = read_json(path / "adapter.json")
    health = read_json(path / "adapter-health.json")
    save = read_json(path / "save-identity.json")
    frame = report["frameTimes"]
    probe = report["nexMarketListScope"]
    arm = "B" if bool(probe.get("requested")) else "A"
    active = frame_metrics(frame["measurementWindow"])
    paused = frame_metrics(frame["campaignPausedAfter30SecondsActive"])
    presentation = frame["displayPhases"]["campaignAfter30SecondsActive"]
    context = frame["openGlContext"]
    identity_fields = {
        "preflightJarSha256": run.get("preflightJarSha256"),
        "wrapperRuntime": run.get("wrapperRuntime"),
        "platform": run.get("platform"),
        "installRoot": run.get("installRoot"),
        "profileFingerprint": profile.get("profileFingerprint"),
        "resolvedModCount": profile.get("resolvedModCount"),
        "textureProfileFingerprint": run.get("textureProfileFingerprint"),
        "textureManifestSha256": run.get("textureManifestSha256"),
        "textureIndexSha256": run.get("textureIndexSha256"),
        "directLaunchSettings": run.get("directLaunchSettings"),
        "optimizationPreset": run.get("optimizationPreset"),
        "disabledOptimizationDomains": run.get("disabledOptimizationDomains"),
        "recordingMode": run.get("recordingMode"),
        "campaignTimes": run.get("campaignTimes"),
        "smoothFramePacing": run.get("smoothFramePacing"),
        "adapterMode": run.get("adapterMode"),
        "adapterPlanScope": run.get("adapterPlanScope"),
        "combatJvmSafeguard": run.get("combatJvmSafeguard", {}).get("active"),
        "macRosettaGcPolicy": run.get("macRosettaGcPolicy", {}).get("active"),
        "scenario": smoke.get("scenario"),
        "save": {
            "selectedSave": save.get("selectedSave"),
            "treeSha256": save.get("tree", {}).get("treeSha256"),
        },
        "adapterDisabledPlans": normalized_disabled_plans(adapter),
        "presentation": {
            "lastSwapInterval": frame["presentationPolicy"].get("lastSwapInterval"),
            "forceVsyncOff": frame["presentationPolicy"].get("forceVsyncOff"),
            "frameRateCap": frame["presentationPolicy"].get("frameRateCap"),
        },
        "openGlContext": {
            "vendor": context.get("vendor"),
            "renderer": context.get("renderer"),
            "version": context.get("version"),
        },
    }
    identity = json.dumps(
        identity_fields,
        sort_keys=True,
        separators=(",", ":"),
    )
    lifecycle = run.get("lifecycleEvidence", {})
    save_comparison = save.get("comparison", {})
    intrusive_requested = [
        name
        for name in (
            "gpuFrameTime",
            "openGlCommands",
            "openGlStateReissues",
            "openGlMatrixOperations",
        )
        if bool((frame.get(name) or {}).get("requested"))
    ]
    problems: list[str] = []
    if smoke.get("status") != "passed":
        problems.append("semantic route did not pass")
    if (
        run.get("outcome") != "COMPLETED"
        or int(run.get("exitCode") or 0) != 0
        or lifecycle.get("fatalDetected") is not False
    ):
        problems.append("process lifecycle was not a clean exit")
    if (
        health.get("status") != "ACTIVE"
        or health.get("mode") != "ENABLED"
        or not bool(health.get("transformerInstalled"))
        or bool(health.get("killSwitchActive"))
        or not zero_health_failures(health)
    ):
        problems.append("common adapter health/fallback gate failed")
    if not probe_adapter_ok(arm, probe, adapter):
        problems.append("Nex candidate arm/service/counter gate failed")
    if (
        run.get("recordingMode") != "OFF"
        or run.get("campaignTimes") is not False
        or run.get("smoothFramePacing") is not False
        or bool((report.get("nexEconomyInfoTimes") or {}).get("installed"))
        or intrusive_requested
    ):
        problems.append("intrusive discovery instrumentation was active")
    if (
        active["frames"] < 100
        or active["active_seconds"] < 30.0
        or paused["frames"] < 100
        or paused["active_seconds"] < 30.0
    ):
        problems.append("paused or unpaused thin frame window was incomplete")
    if (
        save_comparison.get("beforeAvailable") is not True
        or save_comparison.get("sameSelectedSave") is not True
        or save_comparison.get("contentUnchanged") is not True
    ):
        problems.append("loaded save identity was unavailable or changed")
    if (
        save.get("format") != "starsector-preflight-loaded-save-identity-v1"
        or save.get("installRoot") != run.get("installRoot")
        or not save.get("selectedSave")
        or not save.get("tree", {}).get("treeSha256")
    ):
        problems.append("loaded save evidence format or install binding was invalid")
    overhead = frame.get("measurementOverhead", {})
    return Run(
        label=path.name,
        arm=arm,
        frames=int(active["frames"]),
        active_seconds=active["active_seconds"],
        average_fps=active["average_fps"],
        p50_ms=active["p50_ms"],
        p95_ms=active["p95_ms"],
        p99_ms=active["p99_ms"],
        maximum_ms=active["maximum_ms"],
        one_percent_low_fps=active["one_percent_low_fps"],
        over50_per_minute=active["over50_per_minute"],
        over100_per_minute=active["over100_per_minute"],
        stutter_burden=active["stutter_burden"],
        paused_seconds=paused["active_seconds"],
        paused_p99_ms=paused["p99_ms"],
        paused_one_percent_low_fps=paused["one_percent_low_fps"],
        presentation_pre_swap_p99_ms=number(presentation["preSwap"]["p99Micros"]) / 1000.0,
        presentation_swap_p99_ms=number(presentation["nativeSwap"]["p99Micros"]) / 1000.0,
        probe_overhead_micros=number(overhead.get("averageMicros")),
        scopes=int(probe.get("scopesBegun") or 0),
        stores=int(probe.get("stores") or 0),
        misses=int(probe.get("misses") or 0),
        hits=int(probe.get("hits") or 0),
        maximum_entries=int(probe.get("maximumEntries") or 0),
        identity_fields=identity_fields,
        identity=identity,
        adapter_problems=tuple(problems),
        adapter_ok=not problems,
    )


def within_tolerance(values: list[float]) -> bool:
    if not values:
        return False
    limit = max(2.0, statistics.median(values) * 0.05)
    return max(values) - min(values) <= limit


def workload_gate(runs: list[Run]) -> bool:
    candidates = [run for run in runs if run.arm == "B"]
    recurrence = {(run.scopes, run.stores, run.hits, run.maximum_entries) for run in candidates}
    return (
        within_tolerance([run.active_seconds for run in runs])
        and within_tolerance([run.paused_seconds for run in runs])
        and bool(candidates)
        and len(recurrence) == 1
    )


def design_gate(runs: list[Run]) -> bool:
    arms = [run.arm for run in runs]
    transitions = sum(left != right for left, right in zip(arms, arms[1:]))
    return arms.count("A") >= 2 and arms.count("B") >= 2 and transitions >= 2


def median_for(runs: list[Run], field: str) -> float:
    return float(statistics.median(getattr(run, field) for run in runs))


def delta(candidate: float, baseline: float) -> str:
    if baseline == 0:
        return "same zero" if candidate == 0 else f"new {candidate:.2f}"
    return f"{100.0 * (candidate - baseline) / baseline:+.1f}%"


def gates(runs: list[Run]) -> tuple[bool, bool, bool, bool]:
    return (
        len({run.identity for run in runs}) == 1,
        all(run.adapter_ok for run in runs),
        workload_gate(runs),
        design_gate(runs),
    )


def render(runs: list[Run]) -> str:
    lines = [
        "run                              arm avg   p50   p95   p99   1%low max    >50/m >100/m",
    ]
    for run in runs:
        lines.append(
            f"{run.label[-32:]:<32} {run.arm:>3} "
            f"{run.average_fps:>5.2f} {run.p50_ms:>5.1f} {run.p95_ms:>5.1f} "
            f"{run.p99_ms:>5.1f} {run.one_percent_low_fps:>6.2f} "
            f"{run.maximum_ms:>6.1f} {run.over50_per_minute:>6.1f} "
            f"{run.over100_per_minute:>7.1f}"
        )
    identity_ok, adapter_ok, workload_ok, design_ok = gates(runs)
    lines.append(
        f"gates: identity={'PASS' if identity_ok else 'FAIL'}  "
        f"adapter={'PASS' if adapter_ok else 'FAIL'}  "
        f"workload={'PASS' if workload_ok else 'FAIL'}  "
        f"cohort={'PASS' if design_ok else 'INCOMPLETE'}"
    )
    if not identity_ok:
        first = runs[0].identity_fields
        changed = sorted(
            key
            for key in first
            if any(run.identity_fields.get(key) != first.get(key) for run in runs[1:])
        )
        lines.append("reject: identity drift in " + ", ".join(changed))
    for run in runs:
        if run.adapter_problems:
            lines.append(f"reject: {run.label}: " + "; ".join(run.adapter_problems))
    if not workload_ok:
        candidates = [run for run in runs if run.arm == "B"]
        recurrence = sorted(
            {(run.scopes, run.stores, run.hits, run.maximum_entries) for run in candidates}
        )
        lines.append(
            "reject: workload duration drift or candidate recurrence mismatch "
            f"(B scope/store/hit/max-entry tuples={recurrence})"
        )
    if not design_ok:
        arm_sequence = "".join(run.arm for run in runs)
        lines.append(f"incomplete: need >=2 A, >=2 B, and >=2 transitions (order={arm_sequence})")
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
        lines.append(
            "B vs A (arm medians): "
            + ", ".join(
                f"{label} {delta(median_for(arms['B'], field), median_for(arms['A'], field))}"
                for field, label in fields
            )
        )
        lines.append(
            "presentation context p99 (pre-swap/native-swap): "
            f"A {median_for(arms['A'], 'presentation_pre_swap_p99_ms'):.1f}/"
            f"{median_for(arms['A'], 'presentation_swap_p99_ms'):.1f} ms, "
            f"B {median_for(arms['B'], 'presentation_pre_swap_p99_ms'):.1f}/"
            f"{median_for(arms['B'], 'presentation_swap_p99_ms'):.1f} ms"
        )
        lines.append(
            "paused context (p99/1% low): "
            f"A {median_for(arms['A'], 'paused_p99_ms'):.1f} ms/"
            f"{median_for(arms['A'], 'paused_one_percent_low_fps'):.2f} FPS, "
            f"B {median_for(arms['B'], 'paused_p99_ms'):.1f} ms/"
            f"{median_for(arms['B'], 'paused_one_percent_low_fps'):.2f} FPS"
        )
        candidate_hits = int(statistics.median(run.hits for run in arms["B"]))
        candidate_stores = int(statistics.median(run.stores for run in arms["B"]))
        hit_share = 100.0 * candidate_hits / (candidate_hits + candidate_stores)
        lines.append(
            f"causal counter: median {hit_share:.2f}% repeated list builds avoided "
            f"({candidate_hits:,} hits, {candidate_stores:,} first stores, "
            f"{int(statistics.median(run.scopes for run in arms['B']))} scopes/run)"
        )
        lines.append(
            "thin recorder overhead: "
            f"A {median_for(arms['A'], 'probe_overhead_micros'):.2f}, "
            f"B {median_for(arms['B'], 'probe_overhead_micros'):.2f} us/frame"
        )
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("runs", nargs="+", type=Path, metavar="RUN_DIRECTORY")
    args = parser.parse_args(argv)
    try:
        runs = [load_run(path.resolve()) for path in args.runs]
    except (OSError, KeyError, TypeError, ValueError, json.JSONDecodeError) as failure:
        print(f"Nex market-list cohort: {failure}", file=sys.stderr)
        return 1
    print(render(runs))
    return 0 if all(gates(runs)) else 2


if __name__ == "__main__":
    raise SystemExit(main())
