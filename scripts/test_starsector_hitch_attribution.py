import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(__file__))

import starsector_hitch_attribution as attribution


class HitchAttributionTest(unittest.TestCase):
    def write_report(self, packets):
        handle = tempfile.NamedTemporaryFile("w", encoding="utf-8", delete=False)
        with handle:
            json.dump({"frameTimes": {"hitchPackets": {"packets": packets}}}, handle)
        self.addCleanup(lambda: os.path.exists(handle.name) and os.unlink(handle.name))
        return handle.name

    def test_trigger_windows_reconstruct_exact_wall_clock_and_filter_pre_swap(self):
        report = self.write_report([{
            "index": 3,
            "state": "campaign",
            "pause": "paused",
            "startEpochMillis": 1_700_000_001_000,
            "startOffsetMillis": 1_000.0,
            "frameHistory": [
                {
                    "sequence": 40,
                    "trigger": False,
                    "durationMicros": 20_000,
                    "startOffsetMillis": 1_450.0,
                    "endOffsetMillis": 1_470.0,
                },
                {
                    "sequence": 41,
                    "trigger": True,
                    "durationMicros": 50_000,
                    "startOffsetMillis": 1_500.0,
                    "endOffsetMillis": 1_550.0,
                    "limiterSplitComplete": True,
                    "preSwapExcludingLimiterMicros": 49_000,
                },
            ],
        }, {
            "index": 4,
            "state": "campaign",
            "pause": "unpaused",
            "startEpochMillis": 1_700_000_002_000,
            "startOffsetMillis": 2_000.0,
            "frameHistory": [{
                "sequence": 50,
                "trigger": True,
                "durationMicros": 60_000,
                "startOffsetMillis": 2_100.0,
                "endOffsetMillis": 2_160.0,
                "preSwapMicros": 55_000,
            }],
        }])

        windows = attribution.hitch_trigger_windows(
            report, pre_swap_only=True, padding_ms=2.0)

        self.assertEqual(1, len(windows))
        window = windows[0]
        self.assertEqual(3, window["packetIndex"])
        self.assertEqual(41, window["sequence"])
        self.assertAlmostEqual(0.98, window["preSwapShare"])
        self.assertAlmostEqual(1_700_000_000.0 + 1.5 - 0.002, window["wallStart"])
        self.assertAlmostEqual(1_700_000_000.0 + 1.55 + 0.002, window["wallEnd"])

    def test_requested_packet_without_usable_trigger_is_rejected(self):
        report = self.write_report([{
            "index": 1,
            "state": "campaign",
            "pause": "paused",
            "startEpochMillis": 1_000,
            "startOffsetMillis": 0.0,
            "frameHistory": [],
        }])
        with self.assertRaises(SystemExit):
            attribution.hitch_trigger_windows(report, packet_indices=[1])

    def test_overlap_uses_event_duration_and_clips_to_window(self):
        event = self.event("1970-01-01T00:16:39.990000Z", "PT0.030S")
        self.assertTrue(attribution.overlaps(event, 1000.0, 1000.050))
        self.assertAlmostEqual(0.020, attribution.overlap_seconds(event, 1000.0, 1000.050))

    def test_summary_surfaces_gc_blocking_first_use_and_cpu_native_leads(self):
        window = {
            "name": "packet 0 seq 7",
            "packetIndex": 0,
            "sequence": 7,
            "state": "campaign",
            "pause": "paused",
            "durationMicros": 50_000,
            "preSwapShare": 0.95,
            "wallStart": 1000.0,
            "wallEnd": 1000.050,
        }
        event_sets = {
            "jdk.ExecutionSample": [self.sample(
                "1970-01-01T00:16:40.010000Z",
                "com/fs/starfarer/campaign/TestOwner", "work")],
            "jdk.NativeMethodSample": [self.sample(
                "1970-01-01T00:16:40.020000Z",
                "org/lwjgl/opengl/GL11", "nglCall")],
            "jdk.ThreadPark": [self.event(
                "1970-01-01T00:16:40.000000Z", "PT0.020S", thread="main")],
            "jdk.ThreadSleep": [],
            "jdk.JavaMonitorWait": [],
            "jdk.JavaMonitorEnter": [],
            "jdk.GCPhasePause": [self.event(
                "1970-01-01T00:16:40.025000Z", "PT0.015S")],
            "jdk.Compilation": [self.event(
                "1970-01-01T00:16:40.030000Z", "PT0.001S")],
            "jdk.ClassLoad": [self.event(
                "1970-01-01T00:16:40.031000Z", "PT0.001S")],
            "jdk.ClassDefine": [],
        }

        result = attribution.summarize_window(
            window, 1000.0, 1000.050, 1.0, event_sets, top=4)

        self.assertIn("GC_OVERLAP", result["evidenceTags"])
        self.assertIn("MAIN_BLOCKED", result["evidenceTags"])
        self.assertIn("JIT_OR_CLASS_ACTIVITY", result["evidenceTags"])
        self.assertIn("JAVA_CPU_SAMPLED", result["evidenceTags"])
        self.assertIn("NATIVE_CPU_SAMPLED", result["evidenceTags"])
        self.assertAlmostEqual(15.0, result["gcOverlapWallMillis"], places=5)
        self.assertAlmostEqual(20.0, result["mainBlockedWallMillis"], places=5)
        self.assertEqual(1, result["compilationEvents"])
        self.assertEqual(1, result["classLoadEvents"])
        self.assertEqual(1, result["mainSamples"])
        self.assertEqual(1, result["mainNativeSamples"])
        self.assertEqual("com.fs.starfarer.campaign.TestOwner.work", result["topLeaves"][0][0])

    def test_empty_event_window_is_explicit(self):
        window = {
            "name": "packet 0 seq 8",
            "packetIndex": 0,
            "sequence": 8,
            "state": "campaign",
            "pause": "paused",
            "durationMicros": 50_000,
            "preSwapShare": 0.95,
            "wallStart": 1000.0,
            "wallEnd": 1000.050,
        }
        result = attribution.summarize_window(window, 1000.0, 1000.050, 1.0, {}, top=4)
        self.assertEqual(["NO_JFR_OWNER"], result["evidenceTags"])

    @staticmethod
    def event(start, duration=None, thread=None):
        values = {"startTime": start}
        if duration is not None:
            values["duration"] = duration
        if thread is not None:
            values["eventThread"] = {"javaName": thread}
        return {"values": values}

    @staticmethod
    def sample(start, owner, method):
        return {"values": {
            "startTime": start,
            "sampledThread": {"javaName": "main"},
            "stackTrace": {"frames": [{
                "method": {"name": method, "type": {"name": owner}}
            }]},
        }}


if __name__ == "__main__":
    unittest.main()
