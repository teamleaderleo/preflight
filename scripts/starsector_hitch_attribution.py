#!/usr/bin/env python3
"""Correlate exact retained hitch-trigger frames with JFR CPU, blocking, GC, and first-use evidence.

The hitch packet already records exact wall-clock anchors and per-frame offsets. This tool maps those
trigger-frame windows into the JFR recording clock, then reports only evidence that overlaps each
retained trigger. SAMPLE recordings provide execution/native samples, blocking, and GC pauses. FULL
recordings also provide compilation and class-load/define activity. Temporal overlap is a lead for the
next experiment; it is not by itself a causal claim.
"""
import argparse
import collections
import json
import os

from starsector_critical_path import (
    BLOCKING,
    _jfr_binary,
    clock_factor,
    events,
    instant,
    seconds,
    thread_of,
    top_frame,
)
from starsector_gameplay_hotspots import methods_of, interesting, recording_clock_windows


FIRST_USE_EVENTS = ("jdk.Compilation", "jdk.ClassLoad", "jdk.ClassDefine")
GC_EVENTS = ("jdk.GCPhasePause",)
SAMPLE_EVENTS = ("jdk.ExecutionSample", "jdk.NativeMethodSample")
MIN_BLOCKED_SHARE = 0.25
MIN_GC_SHARE = 0.20


def _number(value):
    return value if isinstance(value, (int, float)) else None


def _frame_pre_swap_share(frame):
    duration = _number(frame.get("durationMicros"))
    if not duration or duration <= 0:
        return 0.0
    if frame.get("limiterSplitComplete"):
        pre_swap = _number(frame.get("preSwapExcludingLimiterMicros"))
    else:
        pre_swap = _number(frame.get("preSwapMicros"))
    if pre_swap is None or pre_swap <= 0:
        return 0.0
    return pre_swap / duration


def hitch_trigger_windows(report_path, packet_indices=None, pre_swap_only=False, padding_ms=0.0):
    """Return exact wall-clock windows for retained trigger frames in runtime-frame-report.json."""
    try:
        with open(report_path, encoding="utf-8") as source:
            report = json.load(source)
    except (OSError, ValueError) as error:
        raise SystemExit(f"could not read runtime frame report {report_path}: {error}") from error

    frame_times = report.get("frameTimes") or {}
    hitch = frame_times.get("hitchPackets") or {}
    packets = hitch.get("packets") or []
    requested = set(packet_indices or [])
    padding = max(0.0, padding_ms) / 1000.0
    windows = []
    for packet in packets:
        packet_index = packet.get("index")
        if requested and packet_index not in requested:
            continue
        if pre_swap_only and packet.get("pause") != "paused":
            continue
        start_epoch_ms = _number(packet.get("startEpochMillis"))
        start_offset_ms = _number(packet.get("startOffsetMillis"))
        if start_epoch_ms is None or start_offset_ms is None:
            continue
        origin_epoch = (start_epoch_ms - start_offset_ms) / 1000.0
        for frame in packet.get("frameHistory") or []:
            if not frame.get("trigger"):
                continue
            if pre_swap_only and _frame_pre_swap_share(frame) < 0.45:
                continue
            frame_start_ms = _number(frame.get("startOffsetMillis"))
            frame_end_ms = _number(frame.get("endOffsetMillis"))
            duration_micros = _number(frame.get("durationMicros"))
            if (frame_start_ms is None or frame_end_ms is None or duration_micros is None
                    or frame_end_ms <= frame_start_ms or duration_micros <= 0):
                continue
            start = origin_epoch + frame_start_ms / 1000.0 - padding
            end = origin_epoch + frame_end_ms / 1000.0 + padding
            windows.append({
                "name": f"packet {packet_index} seq {frame.get('sequence')}",
                "packetIndex": packet_index,
                "sequence": frame.get("sequence"),
                "state": packet.get("state"),
                "pause": packet.get("pause"),
                "durationMicros": duration_micros,
                "preSwapShare": _frame_pre_swap_share(frame),
                "wallStart": start,
                "wallEnd": end,
            })
    if requested:
        found = {window["packetIndex"] for window in windows}
        missing = sorted(requested - found)
        if missing:
            raise SystemExit(
                f"requested packet indices have no usable trigger windows: {', '.join(map(str, missing))}")
    return windows


def event_interval(event):
    values = event.get("values", {})
    start = instant(values.get("startTime"))
    if start is None:
        return None
    duration = max(0.0, seconds(values.get("duration")))
    return start, start + duration


def overlaps(event, start, end):
    interval = event_interval(event)
    if interval is None:
        return False
    event_start, event_end = interval
    if event_end == event_start:
        return start <= event_start <= end
    return event_start < end and event_end > start


def overlap_seconds(event, start, end):
    interval = event_interval(event)
    if interval is None:
        return 0.0
    event_start, event_end = interval
    if event_end <= event_start:
        return 0.0
    return max(0.0, min(event_end, end) - max(event_start, start))


def recording_mode(path, jfr):
    started = events(path, ["preflight.AgentStarted"], depth=1, jfr=jfr)
    if not started:
        return "unknown"
    value = started[0].get("values", {}).get("recordingMode")
    return str(value).lower() if value else "unknown"


def _top_java_samples(sampled, top):
    leaves = collections.Counter()
    inclusive = collections.Counter()
    for event in sampled:
        if thread_of(event) != "main":
            continue
        methods = methods_of(event)
        useful = [method for method in methods if interesting(method)]
        if useful:
            leaves[useful[0]] += 1
        inclusive.update(set(useful))
    return {
        "mainSamples": sum(leaves.values()),
        "topLeaves": leaves.most_common(top),
        "topInclusive": inclusive.most_common(top),
    }


def _top_native_samples(sampled, top):
    frames = collections.Counter(
        top_frame(event) for event in sampled if thread_of(event) == "main")
    return {
        "mainNativeSamples": sum(frames.values()),
        "topNativeFrames": frames.most_common(top),
    }


def _event_count(events_by_name, names, start, end):
    return sum(
        1 for name in names for event in events_by_name.get(name, []) if overlaps(event, start, end))


def summarize_window(window, mapped_start, mapped_end, factor, events_by_name, top=8):
    duration_wall = max(0.0, window["wallEnd"] - window["wallStart"])
    execution = [
        event for event in events_by_name.get("jdk.ExecutionSample", [])
        if mapped_start <= (instant(event.get("values", {}).get("startTime")) or -1) <= mapped_end
    ]
    native = [
        event for event in events_by_name.get("jdk.NativeMethodSample", [])
        if mapped_start <= (instant(event.get("values", {}).get("startTime")) or -1) <= mapped_end
    ]
    java = _top_java_samples(execution, top)
    native_summary = _top_native_samples(native, top)

    blocked_recorded = 0.0
    blocked_kinds = {}
    for name in BLOCKING:
        overlapping = [
            event for event in events_by_name.get(name, [])
            if thread_of(event) == "main" and overlaps(event, mapped_start, mapped_end)
        ]
        recorded = sum(overlap_seconds(event, mapped_start, mapped_end) for event in overlapping)
        if overlapping:
            blocked_kinds[name] = {
                "events": len(overlapping),
                "overlapWallMillis": recorded * factor * 1000.0,
            }
        blocked_recorded += recorded

    gc_recorded = sum(
        overlap_seconds(event, mapped_start, mapped_end)
        for name in GC_EVENTS for event in events_by_name.get(name, [])
        if overlaps(event, mapped_start, mapped_end))
    gc_count = _event_count(events_by_name, GC_EVENTS, mapped_start, mapped_end)
    compilation_count = _event_count(
        events_by_name, ("jdk.Compilation",), mapped_start, mapped_end)
    class_load_count = _event_count(
        events_by_name, ("jdk.ClassLoad",), mapped_start, mapped_end)
    class_define_count = _event_count(
        events_by_name, ("jdk.ClassDefine",), mapped_start, mapped_end)

    blocked_wall = blocked_recorded * factor
    gc_wall = gc_recorded * factor
    duration_denominator = duration_wall or 1.0
    tags = []
    if gc_wall >= 0.002 and gc_wall / duration_denominator >= MIN_GC_SHARE:
        tags.append("GC_OVERLAP")
    if blocked_wall >= 0.002 and blocked_wall / duration_denominator >= MIN_BLOCKED_SHARE:
        tags.append("MAIN_BLOCKED")
    if compilation_count or class_load_count or class_define_count:
        tags.append("JIT_OR_CLASS_ACTIVITY")
    if java["mainSamples"]:
        tags.append("JAVA_CPU_SAMPLED")
    if native_summary["mainNativeSamples"]:
        tags.append("NATIVE_CPU_SAMPLED")
    if not tags:
        tags.append("NO_JFR_OWNER")

    result = dict(window)
    result.update({
        "recordedStart": mapped_start,
        "recordedEnd": mapped_end,
        "evidenceTags": tags,
        "gcPauseEvents": gc_count,
        "gcOverlapWallMillis": gc_wall * 1000.0,
        "mainBlockedWallMillis": blocked_wall * 1000.0,
        "mainBlockedKinds": blocked_kinds,
        "compilationEvents": compilation_count,
        "classLoadEvents": class_load_count,
        "classDefineEvents": class_define_count,
    })
    result.update(java)
    result.update(native_summary)
    return result


def collect_event_sets(path, jfr, depth):
    names = list(SAMPLE_EVENTS) + list(BLOCKING) + list(GC_EVENTS) + list(FIRST_USE_EVENTS)
    return {name: events(path, [name], depth=depth, jfr=jfr) for name in names}


def analyze(path, report_path, packet_indices=None, pre_swap_only=False,
            padding_ms=0.0, top=8, depth=96):
    jfr = _jfr_binary()
    windows = hitch_trigger_windows(
        report_path, packet_indices=packet_indices,
        pre_swap_only=pre_swap_only, padding_ms=padding_ms)
    if not windows:
        raise SystemExit(f"no usable retained hitch trigger windows in {report_path}")
    wall_windows = [(window["name"], window["wallStart"], window["wallEnd"]) for window in windows]
    mapped, factor = recording_clock_windows(path, wall_windows, jfr)
    event_sets = collect_event_sets(path, jfr, depth)
    summaries = []
    for window, (_name, start, end) in zip(windows, mapped):
        summaries.append(summarize_window(window, start, end, factor, event_sets, top=top))
    return {
        "format": "starsector-preflight-hitch-jfr-attribution-v1",
        "recording": path,
        "frameReport": report_path,
        "recordingMode": recording_mode(path, jfr),
        "recordingClockFactor": factor,
        "paddingMillis": max(0.0, padding_ms),
        "preSwapOnly": bool(pre_swap_only),
        "interpretation": (
            "temporal overlap and sampled-stack presence route the next experiment; "
            "they do not prove causality or exact elapsed CPU time"),
        "triggers": summaries,
    }


def print_summary(result):
    print(f"recording: {result['recording']}")
    print(f"frame report: {result['frameReport']}")
    print(f"recording mode: {result['recordingMode']}")
    print(f"recording-clock calibration: {result['recordingClockFactor']:.3f}x")
    if result["recordingMode"] == "sample":
        print("first-use note: SAMPLE mode intentionally omits compilation/class-load events")
    print("interpretation: temporal overlap routes the next causal experiment; sample counts are observed samples")
    for trigger in result["triggers"]:
        tags = ", ".join(trigger["evidenceTags"])
        print(
            f"\n{trigger['name']}: {trigger['durationMicros'] / 1000.0:.3f} ms, "
            f"pre-swap share {trigger['preSwapShare'] * 100:.1f}%, tags [{tags}]")
        print(
            f"  GC {trigger['gcOverlapWallMillis']:.3f} ms/{trigger['gcPauseEvents']} events; "
            f"main blocked {trigger['mainBlockedWallMillis']:.3f} ms; "
            f"compile/load/define {trigger['compilationEvents']}/"
            f"{trigger['classLoadEvents']}/{trigger['classDefineEvents']}")
        print(
            f"  main samples Java/native: {trigger['mainSamples']}/"
            f"{trigger['mainNativeSamples']}")
        for method, count in trigger["topLeaves"]:
            print(f"    java leaf {count:>3}  {method}")
        for method, count in trigger["topNativeFrames"]:
            print(f"    native    {count:>3}  {method}")


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("recording", help="startup.jfr for the same gameplay run")
    parser.add_argument("--frame-report", required=True,
                        help="runtime-frame-report.json for the same gameplay run")
    parser.add_argument("--packet", action="append", type=int, default=[],
                        help="limit to this hitch packet index; may be repeated")
    parser.add_argument("--pre-swap-only", action="store_true",
                        help="select paused trigger frames with >=45%% pre-swap share")
    parser.add_argument("--padding-ms", type=float, default=0.0,
                        help="expand each exact trigger frame window by this many milliseconds")
    parser.add_argument("--top", type=int, default=8, help="top Java/native sampled frames per trigger")
    parser.add_argument("--depth", type=int, default=96, help="JFR stack depth")
    parser.add_argument("--json-out", help="optional machine-readable output path")
    args = parser.parse_args()
    if args.padding_ms < 0:
        parser.error("--padding-ms must be non-negative")
    result = analyze(
        args.recording, args.frame_report, packet_indices=args.packet,
        pre_swap_only=args.pre_swap_only, padding_ms=args.padding_ms,
        top=args.top, depth=args.depth)
    print_summary(result)
    if args.json_out:
        parent = os.path.dirname(os.path.abspath(args.json_out))
        if parent:
            os.makedirs(parent, exist_ok=True)
        with open(args.json_out, "w", encoding="utf-8") as destination:
            json.dump(result, destination, indent=2, sort_keys=True)
            destination.write("\n")


if __name__ == "__main__":
    main()
