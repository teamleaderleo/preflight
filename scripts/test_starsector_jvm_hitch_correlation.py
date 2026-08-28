#!/usr/bin/env python3
"""Synthetic regression tests for starsector_jvm_hitch_correlation.py."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import starsector_jvm_hitch_correlation as correlation


def event(event_name, start, duration="PT0S", thread=None, frames=None, **values):
    payload = {"startTime": start, "duration": duration, **values}
    if thread:
        payload["eventThread"] = {"javaName": thread}
    if frames:
        payload["stackTrace"] = {
            "frames": [
                {"method": {"type": {"name": owner}, "name": method}}
                for owner, method in frames
            ]
        }
    return {"type": {"name": event_name}, "values": payload}


def test_frame_window_deduplication():
    report = {
        "frameTimes": {
            "hitchPackets": {
                "packets": [
                    {
                        "startEpochMillis": 1_000_000,
                        "startOffsetMillis": 100.0,
                        "frameHistory": [
                            {
                                "sequence": 7,
                                "trigger": True,
                                "severe": False,
                                "startOffsetMillis": 110.0,
                                "durationMicros": 60_000.0,
                            }
                        ],
                    },
                    {
                        "startEpochMillis": 1_000_050,
                        "startOffsetMillis": 150.0,
                        "frameHistory": [
                            {
                                "sequence": 7,
                                "trigger": True,
                                "severe": False,
                                "startOffsetMillis": 160.0,
                                "durationMicros": 60_000.0,
                            },
                            {
                                "sequence": 8,
                                "trigger": True,
                                "severe": True,
                                "startOffsetMillis": 220.0,
                                "durationMicros": 120_000.0,
                            },
                        ],
                    },
                ]
            }
        }
    }
    frames = correlation.frame_windows(report)
    assert [frame["sequence"] for frame in frames] == [7, 8]
    assert abs(frames[0]["durationMillis"] - 60.0) < 0.001
    assert frames[1]["severe"] is True


def test_correlation_keeps_duration_and_samples_distinct():
    frames = [
        {
            "sequence": 7,
            "durationMillis": 60.0,
            "severe": False,
            "recordingStart": 10.000,
            "recordingEnd": 10.060,
        },
        {
            "sequence": 8,
            "durationMillis": 120.0,
            "severe": True,
            "recordingStart": 11.000,
            "recordingEnd": 11.120,
        },
    ]
    sampled = [
        event("jdk.GCPhasePause", "1970-01-01T00:00:10.010000Z", "PT0.020S", name="G1Pause"),
        event(
            "jdk.ThreadSleep",
            "1970-01-01T00:00:10.030000Z",
            "PT0.015S",
            thread="main",
            frames=[("game.Loop", "advance")],
        ),
        event(
            "jdk.ThreadSleep",
            "1970-01-01T00:00:10.030000Z",
            "PT0.020S",
            thread="worker",
            frames=[("worker.Loop", "run")],
        ),
        event(
            "jdk.ExecutionSample",
            "1970-01-01T00:00:10.040000Z",
            thread="main",
            frames=[("mod.Script", "advance")],
        ),
        event(
            "jdk.NativeMethodSample",
            "1970-01-01T00:00:11.050000Z",
            thread="main",
            frames=[("org.lwjgl.opengl.MacOSXContextImplementation", "nSwapBuffers")],
        ),
    ]

    per_frame, summary = correlation.correlate(frames, sampled, factor=2.0)

    assert per_frame[0]["events"]["jdk.GCPhasePause"]["count"] == 1
    assert abs(per_frame[0]["events"]["jdk.GCPhasePause"]["wallOverlapMillis"] - 40.0) < 0.001
    assert per_frame[0]["events"]["jdk.ThreadSleep"]["count"] == 1
    assert abs(per_frame[0]["events"]["jdk.ThreadSleep"]["wallOverlapMillis"] - 30.0) < 0.001
    assert per_frame[0]["events"]["jdk.ExecutionSample"]["count"] == 1
    assert per_frame[0]["events"]["jdk.ExecutionSample"]["wallOverlapMillis"] == 0.0
    assert per_frame[1]["events"]["jdk.NativeMethodSample"]["count"] == 1
    assert summary["jdk.ThreadSleep"]["recordedEvents"] == 2
    assert summary["jdk.ThreadSleep"]["eventAssociations"] == 1
    assert summary["jdk.NativeMethodSample"]["hitchFramesWithEvent"] == 1


def test_recording_clock_mapping_uses_calibrated_factor():
    frames = [{
        "sequence": 1,
        "wallStart": 101.0,
        "wallEnd": 103.0,
        "durationMillis": 2000.0,
        "severe": True,
    }]
    mapped = correlation.map_frames_to_recording(frames, anchor=100.0, factor=2.0)
    assert mapped[0]["recordingStart"] == 100.5
    assert mapped[0]["recordingEnd"] == 101.5


def main():
    test_frame_window_deduplication()
    test_correlation_keeps_duration_and_samples_distinct()
    test_recording_clock_mapping_uses_calibrated_factor()
    print("starsector_jvm_hitch_correlation: ok")


if __name__ == "__main__":
    main()
