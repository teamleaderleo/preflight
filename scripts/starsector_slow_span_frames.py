#!/usr/bin/env python3
"""Join bounded runtime slow spans to exact retained worst-frame intervals."""

import argparse
import json
from pathlib import Path


def load_json(path):
    try:
        with open(path, encoding="utf-8") as source:
            return json.load(source)
    except (OSError, ValueError) as error:
        raise SystemExit(f"could not read runtime frame report {path}: {error}") from error


def nested_value(value, dotted_path):
    current = value
    for key in dotted_path.split("."):
        if not isinstance(current, dict) or key not in current:
            raise SystemExit(f"telemetry path is absent: {dotted_path}")
        current = current[key]
    return current


def slow_spans(value, path=()):
    """Yield named bounded spans while preserving their telemetry hierarchy and phase."""
    if isinstance(value, dict):
        spans = value.get("slowSpans")
        if isinstance(spans, list):
            for span in spans:
                if not isinstance(span, dict):
                    continue
                phase = span.get("phase")
                name = ".".join(path + ((str(phase) if phase else "slowSpan"),))
                yield name, span
        for key, child in value.items():
            if key != "slowSpans":
                yield from slow_spans(child, path + (str(key),))
    elif isinstance(value, list):
        for child in value:
            yield from slow_spans(child, path)


def interval(value, duration_key="durationMillis"):
    duration = value.get(duration_key)
    end = value.get("endEpochMillis")
    start = value.get("startEpochMillis")
    if not isinstance(duration, (int, float)) or duration <= 0:
        return None
    if not isinstance(end, (int, float)):
        return None
    if not isinstance(start, (int, float)):
        start = end - duration
    if end <= start:
        return None
    return float(start), float(end), float(duration)


def worst_frame_intervals(report, frame_series):
    frames = nested_value(report, f"frameTimes.{frame_series}.worstFrames")
    if not isinstance(frames, list):
        raise SystemExit(f"frame series has no worstFrames list: {frame_series}")
    result = []
    for frame in frames:
        if not isinstance(frame, dict):
            continue
        duration_micros = frame.get("durationMicros")
        end = frame.get("endEpochMillis")
        if not isinstance(duration_micros, (int, float)) or duration_micros <= 0:
            continue
        if not isinstance(end, (int, float)):
            continue
        duration_millis = float(duration_micros) / 1000.0
        result.append((float(end) - duration_millis, float(end), duration_millis, frame))
    return result


def join_spans_to_frames(spans, frames):
    """Join only exact interval overlaps; absence is not treated as a negative result."""
    joins = []
    valid_spans = 0
    for name, span in spans:
        span_interval = interval(span)
        if span_interval is None:
            continue
        valid_spans += 1
        span_start, span_end, duration_millis = span_interval
        candidates = []
        for frame_start, frame_end, frame_millis, frame in frames:
            overlap = min(span_end, frame_end) - max(span_start, frame_start)
            if overlap > 0:
                candidates.append((overlap, frame_start, frame_end, frame_millis, frame))
        if not candidates:
            continue
        overlap, frame_start, frame_end, frame_millis, frame = max(
            candidates, key=lambda candidate: candidate[0])
        joins.append({
            "span": name,
            "durationMillis": duration_millis,
            "startEpochMillis": span_start,
            "endEpochMillis": span_end,
            "frameDurationMillis": frame_millis,
            "frameStartEpochMillis": frame_start,
            "frameEndEpochMillis": frame_end,
            "overlapMillis": overlap,
            "containedByFrame": span_start >= frame_start and span_end <= frame_end,
            "overlapShareOfFramePercent": round(overlap / frame_millis * 100.0, 2),
            "spanShareOfFramePercent": (
                round(duration_millis / frame_millis * 100.0, 2)
                if span_start >= frame_start and span_end <= frame_end else None
            ),
            "frame": frame,
        })
    joins.sort(key=lambda value: (-value["durationMillis"], value["span"]))
    return valid_spans, joins


def main():
    parser = argparse.ArgumentParser(
        description="Join bounded runtime slow spans to exact retained worst-frame intervals.")
    parser.add_argument("frame_report", type=Path)
    parser.add_argument("--telemetry", default="fleetInflationTimes",
                        help="dotted runtime-report path containing slowSpans")
    parser.add_argument("--frame-series", default="campaignUnpausedActive")
    parser.add_argument("--top", type=int, default=32)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    if args.top <= 0:
        parser.error("--top must be positive")

    report = load_json(args.frame_report)
    telemetry = nested_value(report, args.telemetry)
    spans = list(slow_spans(telemetry, (args.telemetry,)))
    frames = worst_frame_intervals(report, args.frame_series)
    valid_spans, joins = join_spans_to_frames(spans, frames)
    result = {
        "format": "starsector-preflight-slow-span-frame-join-v1",
        "telemetry": args.telemetry,
        "frameSeries": args.frame_series,
        "retainedSlowSpans": valid_spans,
        "retainedWorstFrames": len(frames),
        "exactOverlaps": len(joins),
        "joins": joins[:args.top],
        "interpretation": (
            "Rows are exact interval overlaps in the shared epoch clock. Nested spans are "
            "inclusive and must not be summed. A span absent from the retained worst-frame "
            "population is unclassified, not evidence that it missed every frame. Epoch "
            "timestamps have millisecond resolution, so sub-millisecond edge placement is "
            "approximate."
        ),
    }
    if args.json:
        print(json.dumps(result, indent=2))
        return

    print(f"telemetry: {args.telemetry}")
    print(f"frame series: {args.frame_series}")
    print(f"retained spans: {valid_spans}; exact overlaps: {len(joins)}")
    if not joins:
        print("no exact overlap with the bounded retained worst-frame population")
        return
    print("\n span ms  frame ms  share  contained  span")
    for value in joins[:args.top]:
        contained = "yes" if value["containedByFrame"] else "crossed"
        share = value["spanShareOfFramePercent"]
        share_text = f"{share:5.1f}%" if share is not None else "  n/a "
        print(f" {value['durationMillis']:7.2f}  {value['frameDurationMillis']:8.2f}  "
              f"{share_text}  {contained:9s}  {value['span']}")


if __name__ == "__main__":
    main()
