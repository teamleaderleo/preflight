#!/usr/bin/env python3
"""Join retained runtime hitch frames to JVM/JFR events without turning samples into elapsed time."""
import argparse
import collections
import json

from starsector_critical_path import (
    _jfr_binary,
    clock_factor,
    events,
    frames_of,
    instant,
    seconds,
    thread_of,
)

EVENT_TYPES = (
    "jdk.GCPhasePause",
    "jdk.GarbageCollection",
    "jdk.Compilation",
    "jdk.Deoptimization",
    "jdk.SafepointBegin",
    "jdk.ExecuteVMOperation",
    "jdk.ThreadPark",
    "jdk.ThreadSleep",
    "jdk.JavaMonitorWait",
    "jdk.JavaMonitorEnter",
    "jdk.ExecutionSample",
    "jdk.NativeMethodSample",
)
DURATION_TYPES = {
    "jdk.GCPhasePause",
    "jdk.GarbageCollection",
    "jdk.Compilation",
    "jdk.SafepointBegin",
    "jdk.ExecuteVMOperation",
    "jdk.ThreadPark",
    "jdk.ThreadSleep",
    "jdk.JavaMonitorWait",
    "jdk.JavaMonitorEnter",
}
MAIN_BLOCKING_TYPES = {
    "jdk.ThreadPark",
    "jdk.ThreadSleep",
    "jdk.JavaMonitorWait",
    "jdk.JavaMonitorEnter",
}
SAMPLE_TYPES = {"jdk.ExecutionSample", "jdk.NativeMethodSample"}


def event_type(event):
    return event.get("type", {}).get("name", "?")


def frame_windows(report):
    """Extract unique retained trigger frames in wall-clock epoch seconds."""
    frame_times = report.get("frameTimes") or {}
    hitch = frame_times.get("hitchPackets") or {}
    packets = hitch.get("packets") or []
    result = {}
    for packet in packets:
        packet_epoch = packet.get("startEpochMillis")
        packet_offset = packet.get("startOffsetMillis")
        if not isinstance(packet_epoch, (int, float)) or not isinstance(packet_offset, (int, float)):
            continue
        for frame in packet.get("frameHistory") or []:
            if not frame.get("trigger"):
                continue
            sequence = frame.get("sequence")
            start_offset = frame.get("startOffsetMillis")
            duration_micros = frame.get("durationMicros")
            if (not isinstance(sequence, (int, float))
                    or not isinstance(start_offset, (int, float))
                    or not isinstance(duration_micros, (int, float))
                    or duration_micros <= 0):
                continue
            start = (packet_epoch + start_offset - packet_offset) / 1000.0
            duration = duration_micros / 1_000_000.0
            candidate = {
                "sequence": int(sequence),
                "wallStart": start,
                "wallEnd": start + duration,
                "durationMillis": duration_micros / 1000.0,
                "severe": bool(frame.get("severe")),
            }
            # A trigger frame can appear in the history of later packets. Sequence is the stable key.
            current = result.get(candidate["sequence"])
            if current is None or candidate["durationMillis"] > current["durationMillis"]:
                result[candidate["sequence"]] = candidate
    return sorted(result.values(), key=lambda value: value["sequence"])


def recording_anchor(recording, jfr=None):
    started = events(recording, ["preflight.AgentStarted"], depth=1, jfr=jfr)
    anchor = instant(started[0].get("values", {}).get("startTime")) if started else None
    if anchor is None:
        raise SystemExit("recording has no usable preflight.AgentStarted clock anchor")
    calibration = events(recording, ["jdk.CPULoad"], depth=1, jfr=jfr)
    return anchor, clock_factor(calibration), recording_mode(started[0])


def recording_mode(started_event):
    value = started_event.get("values", {}).get("recordingMode")
    return value if isinstance(value, str) else "UNKNOWN"


def map_frames_to_recording(frames, anchor, factor):
    mapped = []
    for frame in frames:
        value = dict(frame)
        value["recordingStart"] = anchor + (frame["wallStart"] - anchor) / factor
        value["recordingEnd"] = anchor + (frame["wallEnd"] - anchor) / factor
        mapped.append(value)
    return mapped


def event_window(event):
    start = instant(event.get("values", {}).get("startTime"))
    if start is None:
        return None
    name = event_type(event)
    duration = seconds(event.get("values", {}).get("duration")) if name in DURATION_TYPES else 0.0
    return start, start + max(0.0, duration), duration


def overlaps(frame, start, end):
    if end <= start:
        return frame["recordingStart"] <= start <= frame["recordingEnd"]
    return min(frame["recordingEnd"], end) > max(frame["recordingStart"], start)


def overlap_seconds(frame, start, end):
    if end <= start:
        return 0.0
    return max(0.0, min(frame["recordingEnd"], end) - max(frame["recordingStart"], start))


def method_name(value):
    if not isinstance(value, dict):
        return "?"
    kind = value.get("type") or {}
    owner = kind.get("name", "?").replace("/", ".")
    return f"{owner}.{value.get('name', '?')}"


def event_detail(event, name):
    values = event.get("values", {})
    if name == "jdk.GCPhasePause":
        return str(values.get("name") or values.get("gcId") or "GC pause")
    if name == "jdk.GarbageCollection":
        return str(values.get("name") or values.get("gcId") or "GC")
    if name == "jdk.Compilation":
        method = values.get("method")
        return method_name(method) if method else str(values.get("compileId") or "compilation")
    if name == "jdk.Deoptimization":
        method = values.get("method")
        return method_name(method) if method else str(values.get("reason") or "deoptimization")
    if name in MAIN_BLOCKING_TYPES or name in SAMPLE_TYPES:
        return " <- ".join(frames_of(event, 3))
    if name == "jdk.ExecuteVMOperation":
        return str(values.get("operation") or values.get("safepoint") or "VM operation")
    return str(values.get("name") or name)


def correlate(frames, sampled_events, factor):
    """Return per-frame correlations and aggregate counters. factor converts JFR duration to wall."""
    per_frame = []
    event_counts = collections.Counter()
    hitch_counts = collections.Counter()
    details = collections.defaultdict(collections.Counter)
    duration_overlap = collections.Counter()
    seen_types = collections.Counter(event_type(event) for event in sampled_events)

    for frame in frames:
        row = {
            "sequence": frame["sequence"],
            "durationMillis": frame["durationMillis"],
            "severe": frame["severe"],
            "events": {},
        }
        matched_types = set()
        for event in sampled_events:
            name = event_type(event)
            window = event_window(event)
            if window is None:
                continue
            start, end, _duration = window
            if not overlaps(frame, start, end):
                continue
            if name in MAIN_BLOCKING_TYPES and thread_of(event) != "main":
                continue
            matched_types.add(name)
            event_counts[name] += 1
            details[name][event_detail(event, name)] += 1
            if name in DURATION_TYPES:
                duration_overlap[name] += overlap_seconds(frame, start, end) * factor
            event_row = row["events"].setdefault(name, {"count": 0, "wallOverlapMillis": 0.0})
            event_row["count"] += 1
            if name in DURATION_TYPES:
                event_row["wallOverlapMillis"] += overlap_seconds(frame, start, end) * factor * 1000.0
        for name in matched_types:
            hitch_counts[name] += 1
        per_frame.append(row)

    summary = {}
    for name in EVENT_TYPES:
        summary[name] = {
            "recordedEvents": seen_types[name],
            "hitchFramesWithEvent": hitch_counts[name],
            "eventAssociations": event_counts[name],
            "wallOverlapMillis": duration_overlap[name] * 1000.0,
            "topDetails": [
                {"detail": detail, "associations": count}
                for detail, count in details[name].most_common(8)
            ],
        }
    return per_frame, summary


def report(recording, frame_report, jfr=None):
    with open(frame_report, encoding="utf-8") as source:
        runtime = json.load(source)
    frames = frame_windows(runtime)
    if not frames:
        raise SystemExit("runtime frame report contains no retained trigger frames")

    jfr = jfr or _jfr_binary()
    anchor, factor, mode = recording_anchor(recording, jfr=jfr)
    mapped = map_frames_to_recording(frames, anchor, factor)
    sampled_events = events(recording, list(EVENT_TYPES), depth=4, jfr=jfr)
    per_frame, summary = correlate(mapped, sampled_events, factor)

    severe = sum(1 for frame in frames if frame["severe"])
    result = {
        "format": "starsector-preflight-jvm-hitch-correlation-v1",
        "classification": (
            "diagnostic correlation: duration overlap is elapsed JFR event time; execution/native "
            "samples are observed sample associations only"
        ),
        "recordingMode": mode,
        "clockFactor": factor,
        "retainedHitchFrames": len(frames),
        "retainedSevereHitchFrames": severe,
        "coverage": {
            "GCPhasePauseExpected": True,
            "CompilationExpected": mode != "SAMPLE",
            "sampledEventTypesSeen": sorted(name for name, value in summary.items()
                                            if value["recordedEvents"] > 0),
        },
        "summary": summary,
        "hitches": per_frame,
    }
    print(json.dumps(result, indent=2, sort_keys=True))
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("recording", help="a Preflight JFR recording")
    parser.add_argument("frame_report", help="matching runtime frame report JSON")
    args = parser.parse_args(argv)
    report(args.recording, args.frame_report)


if __name__ == "__main__":
    main()
