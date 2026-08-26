#!/usr/bin/env python3
"""Rank observed gameplay execution or allocation samples for campaign and combat.

Whole-session sample rankings mix startup, campaign, menus, and combat. This tool uses the reviewed
game-loop roots already present in sampled stacks to separate the two gameplay states, then ranks
both leaf work and inclusive methods. With ``--allocations``, it instead ranks JFR's weighted
ObjectAllocationSample estimates by object class, allocation leaf, and first non-JDK owner. Counts
and percentages describe sampled populations; neither mode turns samples into exact elapsed time or
an exact byte census.
"""
import argparse
import collections
import json
import os

from starsector_critical_path import _jfr_binary, clock_factor, events, instant, thread_of


CAMPAIGN_ROOTS = (
    "com.fs.starfarer.campaign.CampaignState.advance",
    "com.fs.starfarer.campaign.CampaignState.render",
    "com.fs.starfarer.campaign.CampaignEngine.advance",
)
COMBAT_ROOTS = (
    "com.fs.starfarer.combat.CombatState.traverse",
    "com.fs.starfarer.combat.CombatEngine.advance",
    "com.fs.starfarer.combat.CombatEngine.render",
)


def methods_of(event):
    trace = event.get("values", {}).get("stackTrace") or {}
    result = []
    for frame in trace.get("frames") or []:
        method = frame.get("method") or {}
        kind = method.get("type") or {}
        type_name = kind.get("name", "?").replace("/", ".")
        result.append(f"{type_name}.{method.get('name', '?')}")
    return result


def gameplay_state(methods):
    # A campaign stack can mention combat data helpers. Loop roots, not arbitrary package presence,
    # identify which state owned the sampled main-thread tick.
    if any(method.startswith(COMBAT_ROOTS) for method in methods):
        return "combat"
    if any(method.startswith(CAMPAIGN_ROOTS) for method in methods):
        return "campaign"
    return "other"


def interesting(method):
    return not method.startswith((
        "java.", "javax.", "jdk.", "sun.", "com.sun.",
        "org.lwjgl.", "dev.starsector.preflight.",
    ))


def non_jdk(method):
    return not method.startswith((
        "java.", "javax.", "jdk.", "sun.", "com.sun.",
    ))


def allocated_class(event):
    allocated = event.get("values", {}).get("objectClass") or {}
    return allocated.get("name", "?").replace("/", ".")


def allocation_weight(event):
    value = event.get("values", {}).get("weight", 0)
    return value if isinstance(value, (int, float)) else 0


def first_non_jdk_owner(methods):
    """Return the allocating method, or the first useful owner above a JDK allocation helper."""
    return next((method for method in methods if non_jdk(method)), "(JDK-only stack)")


def format_bytes(value):
    units = ("B", "KiB", "MiB", "GiB", "TiB")
    amount = float(value)
    for unit in units:
        if abs(amount) < 1024 or unit == units[-1]:
            return f"{amount:.1f} {unit}"
        amount /= 1024


def scenario_step_windows(recording, step_names, evidence_path=None):
    evidence_path = evidence_path or os.path.join(os.path.dirname(recording), "smoke-evidence.json")
    try:
        with open(evidence_path, encoding="utf-8") as source:
            evidence = json.load(source)
    except (OSError, ValueError) as error:
        raise SystemExit(f"could not read scenario evidence {evidence_path}: {error}") from error
    available = {step.get("id"): step for step in evidence.get("steps", [])}
    windows = []
    for name in step_names:
        step = available.get(name)
        if not step:
            raise SystemExit(
                f"scenario step {name!r} not found in {evidence_path}; "
                f"available: {', '.join(sorted(filter(None, available)))}")
        start = instant(step.get("startedAt"))
        end = instant(step.get("completedAt"))
        if start is None or end is None or end < start:
            raise SystemExit(f"scenario step {name!r} has an invalid time window in {evidence_path}")
        windows.append((name, start, end))
    return windows


def events_in_window(sampled, start, end):
    selected = []
    for event in sampled:
        timestamp = instant(event.get("values", {}).get("startTime"))
        if timestamp is not None and start <= timestamp <= end:
            selected.append(event)
    return selected


def recording_clock_windows(path, wall_windows, jfr):
    """Map wall-clock scenario receipts onto a recording whose clock may run slow under Rosetta."""
    started = events(path, ["preflight.AgentStarted"], depth=1, jfr=jfr)
    calibration = events(path, ["jdk.CPULoad"], depth=1, jfr=jfr)
    anchor = instant(started[0].get("values", {}).get("startTime")) if started else None
    if anchor is None:
        raise SystemExit("recording has no usable preflight.AgentStarted clock anchor")
    factor = clock_factor(calibration)
    mapped = [
        (name, anchor + (start - anchor) / factor, anchor + (end - anchor) / factor)
        for name, start, end in wall_windows
    ]
    return mapped, factor


def report_state(name, stacks, top, contains=None):
    state_total = len(stacks)
    if contains:
        stacks = [methods for methods in stacks if any(contains in method for method in methods)]
        print(f"\n{name} filter {contains!r}: {len(stacks)}/{state_total} sampled stacks")
        if not stacks:
            return
    leaves = collections.Counter()
    inclusive = collections.Counter()
    callees = collections.Counter()
    callers = collections.Counter()
    for methods in stacks:
        useful = [method for method in methods if interesting(method)]
        if useful:
            leaves[useful[0]] += 1
        inclusive.update(set(useful))
        if contains:
            index = next(index for index, method in enumerate(methods) if contains in method)
            callees.update(set(method for method in methods[:index] if interesting(method)))
            if index + 1 < len(methods):
                callers[methods[index + 1]] += 1

    total = len(stacks)
    if not contains:
        print(f"\n{name}: {total} main-thread execution samples")
    print("  leaf methods:")
    for method, count in leaves.most_common(top):
        print(f"    {count:>5}  {count / total * 100:5.2f}%  {method}")
    print("  inclusive methods (at most once per sampled stack):")
    for method, count in inclusive.most_common(top):
        print(f"    {count:>5}  {count / total * 100:5.2f}%  {method}")
    if contains:
        print("  work below the filtered method:")
        for method, count in callees.most_common(top):
            print(f"    {count:>5}  {count / total * 100:5.2f}%  {method}")
        print("  immediate callers:")
        for method, count in callers.most_common(top):
            print(f"    {count:>5}  {count / total * 100:5.2f}%  {method}")


def report_allocation_state(name, samples, top, contains=None):
    state_total = len(samples)
    if contains:
        samples = [sample for sample in samples
                   if any(contains in method for method in sample[0])]
        print(f"\n{name} allocation filter {contains!r}: {len(samples)}/{state_total} samples")
        if not samples:
            return
    by_class = collections.Counter()
    by_leaf = collections.Counter()
    by_owner = collections.Counter()
    for methods, object_class, weight in samples:
        by_class[object_class] += weight
        by_leaf[methods[0] if methods else "(no stack)"] += weight
        by_owner[first_non_jdk_owner(methods)] += weight

    total = sum(weight for _methods, _object_class, weight in samples)
    denominator = total or 1
    if not contains:
        print(f"\n{name}: {len(samples)} allocation samples, {format_bytes(total)} weighted")
    print("  allocated object classes:")
    for object_class, weight in by_class.most_common(top):
        print(f"    {format_bytes(weight):>12}  {weight / denominator * 100:5.2f}%  {object_class}")
    print("  allocating leaf methods:")
    for method, weight in by_leaf.most_common(top):
        print(f"    {format_bytes(weight):>12}  {weight / denominator * 100:5.2f}%  {method}")
    print("  first non-JDK owners:")
    for method, weight in by_owner.most_common(top):
        print(f"    {format_bytes(weight):>12}  {weight / denominator * 100:5.2f}%  {method}")


def report_execution_events(sampled, top=30, contains=None):
    states = collections.defaultdict(list)
    for event in sampled:
        if thread_of(event) != "main":
            continue
        methods = methods_of(event)
        states[gameplay_state(methods)].append(methods)

    print("classification: " + ", ".join(
        f"{name}={len(states[name])}" for name in ("campaign", "combat", "other")))
    for name in ("campaign", "combat"):
        if states[name]:
            report_state(name, states[name], top, contains=contains)


def report(path, top=30, depth=96, contains=None, steps=None, evidence_path=None):
    jfr = _jfr_binary()
    sampled = events(path, ["jdk.ExecutionSample"], depth=depth, jfr=jfr)
    print(f"recording: {path}")
    print("interpretation: percentages are shares of observed ExecutionSample events")
    if not steps:
        report_execution_events(sampled, top=top, contains=contains)
        return
    wall_windows = scenario_step_windows(path, steps, evidence_path=evidence_path)
    windows, factor = recording_clock_windows(path, wall_windows, jfr)
    print(f"recording-clock calibration: {factor:.3f}x wall time per recorded second")
    for (name, wall_start, wall_end), (_mapped_name, start, end) in zip(wall_windows, windows):
        selected = events_in_window(sampled, start, end)
        print(f"\nscenario step {name}: {wall_end - wall_start:.3f}s wall, "
              f"{end - start:.3f}s recorded, {len(selected)} execution samples")
        report_execution_events(selected, top=top, contains=contains)


def report_allocation_events(sampled, top=30, contains=None):
    states = collections.defaultdict(list)
    for event in sampled:
        if thread_of(event) != "main":
            continue
        methods = methods_of(event)
        states[gameplay_state(methods)].append(
            (methods, allocated_class(event), allocation_weight(event)))

    print("classification: " + ", ".join(
        f"{name}={len(states[name])}" for name in ("campaign", "combat", "other")))
    for name in ("campaign", "combat"):
        if states[name]:
            report_allocation_state(name, states[name], top, contains=contains)


def report_allocations(path, top=30, depth=96, contains=None, steps=None, evidence_path=None):
    jfr = _jfr_binary()
    sampled = events(path, ["jdk.ObjectAllocationSample"], depth=depth, jfr=jfr)
    print(f"recording: {path}")
    print("interpretation: bytes are JFR ObjectAllocationSample weights, not an exact allocation census")
    if not steps:
        report_allocation_events(sampled, top=top, contains=contains)
        return
    wall_windows = scenario_step_windows(path, steps, evidence_path=evidence_path)
    windows, factor = recording_clock_windows(path, wall_windows, jfr)
    print(f"recording-clock calibration: {factor:.3f}x wall time per recorded second")
    for (name, wall_start, wall_end), (_mapped_name, start, end) in zip(wall_windows, windows):
        selected = events_in_window(sampled, start, end)
        print(f"\nscenario step {name}: {wall_end - wall_start:.3f}s wall, "
              f"{end - start:.3f}s recorded, {len(selected)} allocation samples")
        report_allocation_events(selected, top=top, contains=contains)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("recording", help="a SAMPLE-mode startup.jfr")
    parser.add_argument("--top", type=int, default=30, help="rows per ranking")
    parser.add_argument("--depth", type=int, default=96, help="JFR stack depth")
    parser.add_argument("--contains", help="only stacks containing this method/class substring")
    parser.add_argument("--allocations", action="store_true",
                        help="rank weighted ObjectAllocationSample events instead of CPU samples")
    parser.add_argument("--step", action="append", default=[],
                        help="limit the ranking to a scenario step; may be repeated")
    parser.add_argument("--scenario-evidence",
                        help="smoke-evidence.json path (defaults beside the recording)")
    args = parser.parse_args()
    if args.allocations:
        report_allocations(
            args.recording, top=args.top, depth=args.depth, contains=args.contains,
            steps=args.step, evidence_path=args.scenario_evidence)
    else:
        report(
            args.recording, top=args.top, depth=args.depth, contains=args.contains,
            steps=args.step, evidence_path=args.scenario_evidence)


if __name__ == "__main__":
    main()
