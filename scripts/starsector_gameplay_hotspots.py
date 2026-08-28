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


def frame_report_windows(report_path, series_names):
    """Return the wall-clock extent of the worst frame in each requested report series."""
    try:
        with open(report_path, encoding="utf-8") as source:
            report = json.load(source)
    except (OSError, ValueError) as error:
        raise SystemExit(f"could not read runtime frame report {report_path}: {error}") from error
    frame_times = report.get("frameTimes") or {}
    windows = []
    for name in series_names:
        series = frame_times.get(name)
        worst = (series or {}).get("worstFrames") or []
        frame = worst[0] if worst else None
        duration_micros = (frame or {}).get("durationMicros")
        end_epoch_millis = (frame or {}).get("endEpochMillis")
        if (not isinstance(duration_micros, (int, float)) or duration_micros <= 0
                or not isinstance(end_epoch_millis, (int, float))):
            available = ", ".join(sorted(
                key for key, value in frame_times.items()
                if isinstance(value, dict) and value.get("worstFrames")))
            raise SystemExit(
                f"frame series {name!r} has no usable worst frame in {report_path}; "
                f"available: {available}")
        end = end_epoch_millis / 1000.0
        start = end - duration_micros / 1_000_000.0
        windows.append((f"worst frame {name}", start, end))
    return windows


def frame_report_cluster_windows(report_path, series_names, limit):
    """Return bounded repeated-slow-frame windows, ranked by total cluster duration."""
    try:
        with open(report_path, encoding="utf-8") as source:
            report = json.load(source)
    except (OSError, ValueError) as error:
        raise SystemExit(f"could not read runtime frame report {report_path}: {error}") from error
    frame_times = report.get("frameTimes") or {}
    windows = []
    for name in series_names:
        series = frame_times.get(name) or {}
        clusters = series.get("repeatedSlowFrameWindows") or []
        usable = []
        for cluster in clusters:
            frames = cluster.get("frames")
            duration_micros = cluster.get("durationMicros")
            excess_micros = cluster.get("excessSlowFrameMicros")
            start_epoch_millis = cluster.get("startEpochMillis")
            end_epoch_millis = cluster.get("endEpochMillis")
            if (not isinstance(frames, (int, float)) or frames < 2
                    or not isinstance(duration_micros, (int, float)) or duration_micros <= 0
                    or not isinstance(excess_micros, (int, float)) or excess_micros < 0
                    or not isinstance(start_epoch_millis, (int, float))
                    or not isinstance(end_epoch_millis, (int, float))
                    or end_epoch_millis <= start_epoch_millis):
                continue
            usable.append((duration_micros, frames, excess_micros,
                           start_epoch_millis / 1000.0, end_epoch_millis / 1000.0))
        usable.sort(reverse=True)
        if not usable:
            available = ", ".join(sorted(
                key for key, value in frame_times.items()
                if isinstance(value, dict) and value.get("repeatedSlowFrameWindows")))
            raise SystemExit(
                f"frame series {name!r} has no usable repeated slow-frame windows in "
                f"{report_path}; available: {available or '(none)'}")
        for rank, (duration, frames, excess, start, end) in enumerate(usable[:limit], 1):
            windows.append((
                f"repeated cluster {rank} {name} "
                f"({int(frames)} frames, {duration / 1000.0:.2f} ms total, "
                f"{excess / 1000.0:.2f} ms excess)",
                start,
                end,
            ))
    return windows


def frame_report_hitch_frame_windows(report_path, threshold_millis):
    """Return exact retained hitch-frame groups at or above a packet threshold.

    Hitch packets overlap because each packet includes bounded pre-trigger history. Deduplicate by
    frame sequence, verify the built-in 50/100 ms populations against the recorder's trigger
    counters, and group only consecutive qualifying frames. This keeps a sustained two-frame hitch
    distinct from unrelated severe frames while preserving the exact frame extents.
    """
    try:
        with open(report_path, encoding="utf-8") as source:
            report = json.load(source)
    except (OSError, ValueError) as error:
        raise SystemExit(f"could not read runtime frame report {report_path}: {error}") from error
    hitch = (report.get("frameTimes") or {}).get("hitchPackets") or {}
    trigger_millis = hitch.get("triggerMillis")
    severe_millis = hitch.get("severeMillis")
    if not hitch.get("enabled") or not isinstance(trigger_millis, (int, float)):
        raise SystemExit(f"runtime frame report {report_path} has no enabled hitch packets")
    if threshold_millis < trigger_millis:
        raise SystemExit(
            f"hitch-frame threshold {threshold_millis:g} ms is below the recorder's complete "
            f"{trigger_millis:g} ms trigger population")
    dropped = hitch.get("packetTriggersDropped", 0)
    if not isinstance(dropped, (int, float)) or dropped != 0:
        raise SystemExit(
            f"hitch packets dropped {dropped!r} triggers; exact hitch-frame attribution is incomplete")

    frames = {}
    origins = []
    expected = 0
    threshold_micros = threshold_millis * 1000.0
    packets = hitch.get("packets") or []
    for packet in packets:
        state = packet.get("state", "unknown")
        packet_start_epoch = packet.get("startEpochMillis")
        packet_start_offset = packet.get("startOffsetMillis")
        if (isinstance(packet_start_epoch, (int, float))
                and isinstance(packet_start_offset, (int, float))):
            origins.append(packet_start_epoch - packet_start_offset)
        if threshold_millis == trigger_millis:
            expected += packet.get("triggers", 0)
        elif isinstance(severe_millis, (int, float)) and threshold_millis == severe_millis:
            expected += packet.get("severeTriggers", 0)
        for frame in packet.get("frameHistory") or []:
            sequence = frame.get("sequence")
            duration = frame.get("durationMicros")
            start = frame.get("startOffsetMillis")
            end = frame.get("endOffsetMillis")
            if (not isinstance(sequence, int)
                    or not isinstance(duration, (int, float)) or duration < threshold_micros
                    or not isinstance(start, (int, float))
                    or not isinstance(end, (int, float)) or end <= start):
                continue
            candidate = (state, duration, start, end)
            previous = frames.get(sequence)
            if previous is not None and previous != candidate:
                raise SystemExit(
                    f"hitch frame sequence {sequence} disagrees across overlapping packets")
            frames[sequence] = candidate

    if not frames or not origins:
        raise SystemExit(
            f"runtime frame report {report_path} has no usable frames at or above "
            f"{threshold_millis:g} ms")
    if expected and len(frames) != expected:
        raise SystemExit(
            f"retained hitch history has {len(frames)} unique >= {threshold_millis:g} ms frames "
            f"but trigger counters require {expected}; attribution is incomplete")
    if max(origins) - min(origins) > 2.0:
        raise SystemExit("hitch packet epoch/offset origins disagree by more than 2 ms")
    origin_epoch_millis = sum(origins) / len(origins)

    groups = []
    current = []
    for sequence, frame in sorted(frames.items()):
        state, _duration, start, _end = frame
        if current:
            previous_sequence, previous_frame = current[-1]
            previous_state, _previous_duration, _previous_start, previous_end = previous_frame
            consecutive = sequence == previous_sequence + 1
            contiguous = start <= previous_end + 2.0
            if state != previous_state or not consecutive or not contiguous:
                groups.append(current)
                current = []
        current.append((sequence, frame))
    if current:
        groups.append(current)

    windows = []
    for rank, group in enumerate(groups, 1):
        first_sequence, first = group[0]
        last_sequence, last = group[-1]
        state = first[0]
        start_offset = first[2]
        end_offset = last[3]
        duration = sum(frame[1] for _sequence, frame in group)
        sequence = (str(first_sequence) if first_sequence == last_sequence
                    else f"{first_sequence}-{last_sequence}")
        windows.append((
            f">={threshold_millis:g} ms hitch group {rank} {state} "
            f"(sequences {sequence}, {len(group)} frames, {duration / 1000.0:.2f} ms total)",
            (origin_epoch_millis + start_offset) / 1000.0,
            (origin_epoch_millis + end_offset) / 1000.0,
        ))
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
    calling_methods = collections.Counter()
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
            calling_methods.update(set(
                method for method in methods[index + 1:] if interesting(method)))

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
        print("  calling methods above the filter:")
        for method, count in calling_methods.most_common(top):
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
    callers = collections.Counter()
    calling_methods = collections.Counter()
    for methods, object_class, weight in samples:
        by_class[object_class] += weight
        by_leaf[methods[0] if methods else "(no stack)"] += weight
        by_owner[first_non_jdk_owner(methods)] += weight
        if contains:
            index = next(index for index, method in enumerate(methods) if contains in method)
            if index + 1 < len(methods):
                callers[methods[index + 1]] += weight
            for method in set(methods[index + 1:]):
                if interesting(method):
                    calling_methods[method] += weight

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
    if contains:
        print("  immediate callers:")
        for method, weight in callers.most_common(top):
            print(f"    {format_bytes(weight):>12}  {weight / denominator * 100:5.2f}%  {method}")
        print("  calling methods above the filter:")
        for method, weight in calling_methods.most_common(top):
            print(f"    {format_bytes(weight):>12}  {weight / denominator * 100:5.2f}%  {method}")


def report_execution_events(sampled, top=30, contains=None, include_other=False):
    states = collections.defaultdict(list)
    for event in sampled:
        if thread_of(event) != "main":
            continue
        methods = methods_of(event)
        states[gameplay_state(methods)].append(methods)

    print("classification: " + ", ".join(
        f"{name}={len(states[name])}" for name in ("campaign", "combat", "other")))
    names = ("campaign", "combat", "other") if include_other else ("campaign", "combat")
    for name in names:
        if states[name]:
            report_state(name, states[name], top, contains=contains)


def method_presence(stacks):
    """Count useful leaves and inclusive methods at most once per sampled stack."""
    leaves = collections.Counter()
    inclusive = collections.Counter()
    for methods in stacks:
        useful = [method for method in methods if interesting(method)]
        if useful:
            leaves[useful[0]] += 1
        inclusive.update(set(useful))
    return leaves, inclusive


def enrichment_rows(cluster_counts, background_counts, cluster_total, background_total, top):
    """Rank cluster overrepresentation by excess samples, not unstable rare-event lift."""
    if cluster_total <= 0 or background_total <= 0:
        return []
    rows = []
    for method, cluster_count in cluster_counts.items():
        # One sampled appearance is a lead for inspection, not a recurring-cluster ranking.
        if cluster_count < 2:
            continue
        background_count = background_counts[method]
        cluster_share = cluster_count / cluster_total
        background_share = background_count / background_total
        excess = cluster_count - cluster_total * background_share
        if excess <= 0:
            continue
        lift = cluster_share / background_share if background_share else float("inf")
        rows.append((excess, cluster_count, lift, background_count, method))
    rows.sort(key=lambda row: (row[0], row[1], row[2], row[4]), reverse=True)
    return rows[:top]


def cluster_method_breadth(cluster_groups, contains=None):
    """Count distinct sampled clusters containing each leaf or inclusive method by state."""
    breadth = collections.defaultdict(lambda: {
        "windows": 0,
        "leaves": collections.Counter(),
        "inclusive": collections.Counter(),
    })
    for _name, sampled in cluster_groups:
        stacks = collections.defaultdict(list)
        for event in sampled:
            if thread_of(event) != "main":
                continue
            methods = methods_of(event)
            if contains and not any(contains in method for method in methods):
                continue
            stacks[gameplay_state(methods)].append(methods)
        for state, state_stacks in stacks.items():
            if not state_stacks:
                continue
            leaves, inclusive = method_presence(state_stacks)
            breadth[state]["windows"] += 1
            breadth[state]["leaves"].update(leaves.keys())
            breadth[state]["inclusive"].update(inclusive.keys())
    return breadth


def report_cluster_enrichment(cluster_events, baseline_events, top=30,
                              contains=None, include_other=False, cluster_groups=None):
    """Compare exact-cluster stack presence with the non-cluster portion of the same step."""
    cluster_ids = {id(event) for event in cluster_events}
    states = collections.defaultdict(lambda: {"cluster": [], "background": []})
    populations = (
        ("cluster", cluster_events),
        ("background", [event for event in baseline_events if id(event) not in cluster_ids]),
    )
    for population, sampled in populations:
        for event in sampled:
            if thread_of(event) != "main":
                continue
            methods = methods_of(event)
            if contains and not any(contains in method for method in methods):
                continue
            states[gameplay_state(methods)][population].append(methods)

    print("\ncluster enrichment: exact-cluster samples versus non-cluster samples "
          "inside the same selected scenario step")
    print("ranking: excess cluster samples; state-specific distinct-cluster breadth, coverage, "
          "and lift are context, not causality")
    breadth = cluster_method_breadth(
        cluster_groups or [("aggregate", cluster_events)], contains=contains)
    names = ("campaign", "combat", "other") if include_other else ("campaign", "combat")
    for name in names:
        cluster_stacks = states[name]["cluster"]
        background_stacks = states[name]["background"]
        if not cluster_stacks:
            continue
        print(f"\n{name} enrichment population: cluster={len(cluster_stacks)}, "
              f"background={len(background_stacks)}")
        if not background_stacks:
            print("  unavailable: the exact step has no non-cluster background samples")
            continue
        cluster_leaves, cluster_inclusive = method_presence(cluster_stacks)
        background_leaves, background_inclusive = method_presence(background_stacks)
        for label, cluster_counts, background_counts, breadth_key in (
                ("leaf methods", cluster_leaves, background_leaves, "leaves"),
                ("inclusive methods", cluster_inclusive, background_inclusive, "inclusive")):
            print(f"  overrepresented {label}:")
            rows = enrichment_rows(
                cluster_counts, background_counts,
                len(cluster_stacks), len(background_stacks), top)
            if not rows:
                print("    (none with at least two cluster samples and positive excess)")
                continue
            for excess, cluster_count, lift, background_count, method in rows:
                lift_text = "inf" if lift == float("inf") else f"{lift:.2f}x"
                method_breadth = breadth[name][breadth_key][method]
                breadth_total = breadth[name]["windows"]
                print(
                    f"    +{excess:6.2f}  "
                    f"clusters {method_breadth:>2}/{breadth_total:<2}  "
                    f"cluster {cluster_count:>4}/{len(cluster_stacks):<4} "
                    f"({cluster_count / len(cluster_stacks) * 100:5.2f}%)  "
                    f"background {background_count:>4}/{len(background_stacks):<4} "
                    f"({background_count / len(background_stacks) * 100:5.2f}%)  "
                    f"{lift_text:>7}  {method}")


def selected_wall_windows(path, steps=None, evidence_path=None,
                          frame_report=None, frame_series=None, repeated_clusters=0,
                          hitch_frame_millis=0):
    step_windows = scenario_step_windows(
        path, steps, evidence_path=evidence_path) if steps else []
    requested_series = frame_series or (["allActive"] if frame_report else [])
    if requested_series and not frame_report:
        raise SystemExit("--frame-series requires --frame-report")
    if repeated_clusters and not frame_report:
        raise SystemExit("--repeated-clusters requires --frame-report")
    if hitch_frame_millis and not frame_report:
        raise SystemExit("--hitch-frame-millis requires --frame-report")
    if repeated_clusters and hitch_frame_millis:
        raise SystemExit("--repeated-clusters and --hitch-frame-millis are mutually exclusive")
    if hitch_frame_millis and frame_series:
        raise SystemExit("--hitch-frame-millis reads packet state directly; omit --frame-series")
    if hitch_frame_millis:
        frame_windows = frame_report_hitch_frame_windows(frame_report, hitch_frame_millis)
    elif repeated_clusters:
        frame_windows = frame_report_cluster_windows(
            frame_report, requested_series, repeated_clusters)
    elif frame_report:
        frame_windows = frame_report_windows(frame_report, requested_series)
    else:
        frame_windows = []
    if step_windows and frame_windows:
        selected = intersect_wall_windows(frame_windows, step_windows)
        if not selected:
            raise SystemExit("no requested frame window overlaps the selected scenario steps")
        return selected
    return step_windows or frame_windows


def intersect_wall_windows(windows, constraints):
    """Clip measurement windows to named constraints, preserving nonempty intersections."""
    selected = []
    for name, start, end in windows:
        for constraint_name, constraint_start, constraint_end in constraints:
            clipped_start = max(start, constraint_start)
            clipped_end = min(end, constraint_end)
            if clipped_end > clipped_start:
                selected.append((
                    f"{name} inside step {constraint_name}", clipped_start, clipped_end))
    return selected


def events_in_windows(sampled, windows):
    """Select each event once when it falls in any non-overlapping or overlapping window."""
    selected = []
    for event in sampled:
        timestamp = instant(event.get("values", {}).get("startTime"))
        if timestamp is not None and any(start <= timestamp <= end for _name, start, end in windows):
            selected.append(event)
    return selected


def covered_window_seconds(windows):
    """Return the union duration so overlapping requested series are not counted twice."""
    ordered = sorted((start, end) for _name, start, end in windows if end > start)
    if not ordered:
        return 0.0
    covered = 0.0
    current_start, current_end = ordered[0]
    for start, end in ordered[1:]:
        if start <= current_end:
            current_end = max(current_end, end)
        else:
            covered += current_end - current_start
            current_start, current_end = start, end
    return covered + current_end - current_start


def report(path, top=30, depth=96, contains=None, steps=None, evidence_path=None,
           frame_report=None, frame_series=None, repeated_clusters=0, include_other=False,
           cluster_enrichment=False, hitch_frame_millis=0):
    if cluster_enrichment and not (repeated_clusters or hitch_frame_millis):
        raise SystemExit("cluster enrichment requires repeated clusters or hitch frames")
    if cluster_enrichment and not steps:
        raise SystemExit("cluster enrichment requires exact scenario steps")
    jfr = _jfr_binary()
    sampled = events(path, ["jdk.ExecutionSample"], depth=depth, jfr=jfr)
    print(f"recording: {path}")
    print("interpretation: percentages are shares of observed ExecutionSample events")
    wall_windows = selected_wall_windows(
        path, steps=steps, evidence_path=evidence_path,
        frame_report=frame_report, frame_series=frame_series,
        repeated_clusters=repeated_clusters, hitch_frame_millis=hitch_frame_millis)
    if not wall_windows:
        report_execution_events(
            sampled, top=top, contains=contains, include_other=include_other)
        return
    windows, factor = recording_clock_windows(path, wall_windows, jfr)
    print(f"recording-clock calibration: {factor:.3f}x wall time per recorded second")
    if repeated_clusters or hitch_frame_millis:
        for name, start, end in wall_windows:
            print(f"  {name}: {end - start:.3f}s wall")
        selected = events_in_windows(sampled, windows)
        cluster_groups = [
            (name, events_in_window(sampled, start, end))
            for name, start, end in windows
        ]
        wall_seconds = covered_window_seconds(wall_windows)
        label = "repeated clusters" if repeated_clusters else "hitch-frame groups"
        print(f"\naggregate {label}: {len(windows)} windows, "
              f"{wall_seconds:.3f}s wall, {len(selected)} execution samples")
        report_execution_events(
            selected, top=top, contains=contains, include_other=include_other)
        if cluster_enrichment:
            baseline_wall_windows = scenario_step_windows(
                path, steps, evidence_path=evidence_path)
            baseline_windows, _baseline_factor = recording_clock_windows(
                path, baseline_wall_windows, jfr)
            baseline = events_in_windows(sampled, baseline_windows)
            report_cluster_enrichment(
                selected, baseline, top=top, contains=contains,
                include_other=include_other, cluster_groups=cluster_groups)
        return
    for (name, wall_start, wall_end), (_mapped_name, start, end) in zip(wall_windows, windows):
        selected = events_in_window(sampled, start, end)
        print(f"\nwindow {name}: {wall_end - wall_start:.3f}s wall, "
              f"{end - start:.3f}s recorded, {len(selected)} execution samples")
        report_execution_events(
            selected, top=top, contains=contains, include_other=include_other)


def report_allocation_events(sampled, top=30, contains=None, include_other=False):
    states = collections.defaultdict(list)
    for event in sampled:
        if thread_of(event) != "main":
            continue
        methods = methods_of(event)
        states[gameplay_state(methods)].append(
            (methods, allocated_class(event), allocation_weight(event)))

    print("classification: " + ", ".join(
        f"{name}={len(states[name])}" for name in ("campaign", "combat", "other")))
    names = ("campaign", "combat", "other") if include_other else ("campaign", "combat")
    for name in names:
        if states[name]:
            report_allocation_state(name, states[name], top, contains=contains)


def report_allocations(path, top=30, depth=96, contains=None, steps=None, evidence_path=None,
                       frame_report=None, frame_series=None, repeated_clusters=0,
                       include_other=False, hitch_frame_millis=0):
    jfr = _jfr_binary()
    sampled = events(path, ["jdk.ObjectAllocationSample"], depth=depth, jfr=jfr)
    print(f"recording: {path}")
    print("interpretation: bytes are JFR ObjectAllocationSample weights, not an exact allocation census")
    wall_windows = selected_wall_windows(
        path, steps=steps, evidence_path=evidence_path,
        frame_report=frame_report, frame_series=frame_series,
        repeated_clusters=repeated_clusters, hitch_frame_millis=hitch_frame_millis)
    if not wall_windows:
        report_allocation_events(
            sampled, top=top, contains=contains, include_other=include_other)
        return
    windows, factor = recording_clock_windows(path, wall_windows, jfr)
    print(f"recording-clock calibration: {factor:.3f}x wall time per recorded second")
    if repeated_clusters or hitch_frame_millis:
        for name, start, end in wall_windows:
            print(f"  {name}: {end - start:.3f}s wall")
        selected = events_in_windows(sampled, windows)
        wall_seconds = covered_window_seconds(wall_windows)
        label = "repeated clusters" if repeated_clusters else "hitch-frame groups"
        print(f"\naggregate {label}: {len(windows)} windows, "
              f"{wall_seconds:.3f}s wall, {len(selected)} allocation samples")
        report_allocation_events(
            selected, top=top, contains=contains, include_other=include_other)
        return
    for (name, wall_start, wall_end), (_mapped_name, start, end) in zip(wall_windows, windows):
        selected = events_in_window(sampled, start, end)
        print(f"\nwindow {name}: {wall_end - wall_start:.3f}s wall, "
              f"{end - start:.3f}s recorded, {len(selected)} allocation samples")
        report_allocation_events(
            selected, top=top, contains=contains, include_other=include_other)


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
    parser.add_argument("--frame-report",
                        help="runtime-frame-report.json containing exact stalled-frame times")
    parser.add_argument("--frame-series", action="append", default=[],
                        help="rank the worst frame in this report series; may be repeated; "
                             "defaults to allActive")
    parser.add_argument("--repeated-clusters", type=int, default=0, metavar="N",
                        help="aggregate samples inside the N longest repeated slow-frame "
                             "clusters for each frame series instead of selecting one worst frame")
    parser.add_argument("--hitch-frame-millis", type=float, default=0, metavar="MS",
                        help="aggregate exact retained hitch frames at or above MS, grouping only "
                             "consecutive frames and deduplicating overlapping packets")
    parser.add_argument("--cluster-enrichment", action="store_true",
                        help="with repeated clusters or hitch frames and exact steps, rank methods overrepresented "
                             "against non-cluster samples from the same step")
    parser.add_argument("--include-other", action="store_true",
                        help="also rank startup, menu, and unclassified main-thread stacks")
    args = parser.parse_args()
    if args.repeated_clusters < 0:
        parser.error("--repeated-clusters must be non-negative")
    if args.hitch_frame_millis < 0:
        parser.error("--hitch-frame-millis must be non-negative")
    if args.repeated_clusters and args.hitch_frame_millis:
        parser.error("--repeated-clusters and --hitch-frame-millis are mutually exclusive")
    if args.cluster_enrichment and not (args.repeated_clusters or args.hitch_frame_millis):
        parser.error("--cluster-enrichment requires --repeated-clusters or --hitch-frame-millis")
    if args.cluster_enrichment and not args.step:
        parser.error("--cluster-enrichment requires at least one --step")
    if args.cluster_enrichment and args.allocations:
        parser.error("--cluster-enrichment currently ranks execution samples, not allocations")
    if args.allocations:
        report_allocations(
            args.recording, top=args.top, depth=args.depth, contains=args.contains,
            steps=args.step, evidence_path=args.scenario_evidence,
            frame_report=args.frame_report, frame_series=args.frame_series,
            repeated_clusters=args.repeated_clusters,
            include_other=args.include_other, hitch_frame_millis=args.hitch_frame_millis)
    else:
        report(
            args.recording, top=args.top, depth=args.depth, contains=args.contains,
            steps=args.step, evidence_path=args.scenario_evidence,
            frame_report=args.frame_report, frame_series=args.frame_series,
            repeated_clusters=args.repeated_clusters,
            include_other=args.include_other,
            cluster_enrichment=args.cluster_enrichment,
            hitch_frame_millis=args.hitch_frame_millis)


if __name__ == "__main__":
    main()
