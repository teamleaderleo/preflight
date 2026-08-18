#!/usr/bin/env python3
"""Rank observed gameplay execution samples separately for campaign and combat.

Whole-session sample rankings mix startup, campaign, menus, and combat. This tool uses the reviewed
game-loop roots already present in sampled stacks to separate the two gameplay states, then ranks
both leaf work and inclusive methods. Counts and percentages describe the observed ExecutionSample
population. Absolute wall-clock time requires an independent elapsed-time measurement.
"""
import argparse
import collections

from starsector_critical_path import _jfr_binary, events, thread_of


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


def report(path, top=30, depth=96, contains=None):
    sampled = events(path, ["jdk.ExecutionSample"], depth=depth, jfr=_jfr_binary())
    states = collections.defaultdict(list)
    for event in sampled:
        if thread_of(event) != "main":
            continue
        methods = methods_of(event)
        states[gameplay_state(methods)].append(methods)

    print(f"recording: {path}")
    print("interpretation: percentages are shares of observed ExecutionSample events")
    print("classification: " + ", ".join(
        f"{name}={len(states[name])}" for name in ("campaign", "combat", "other")))
    for name in ("campaign", "combat"):
        if states[name]:
            report_state(name, states[name], top, contains=contains)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("recording", help="a SAMPLE-mode startup.jfr")
    parser.add_argument("--top", type=int, default=30, help="rows per ranking")
    parser.add_argument("--depth", type=int, default=96, help="JFR stack depth")
    parser.add_argument("--contains", help="only stacks containing this method/class substring")
    args = parser.parse_args()
    report(args.recording, top=args.top, depth=args.depth, contains=args.contains)


if __name__ == "__main__":
    main()
