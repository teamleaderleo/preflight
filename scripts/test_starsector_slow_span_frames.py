import unittest

import starsector_slow_span_frames as module


class SlowSpanFrameJoinTest(unittest.TestCase):
    def test_slow_spans_preserve_hierarchy_and_phase(self):
        telemetry = {
            "outer": {
                "slowSpans": [
                    {"phase": "autofit", "durationMillis": 10, "endEpochMillis": 200},
                    {"phase": "sync", "durationMillis": 5, "endEpochMillis": 300},
                ]
            }
        }
        self.assertEqual(
            ["root.outer.autofit", "root.outer.sync"],
            [name for name, _span in module.slow_spans(telemetry, ("root",))])

    def test_join_uses_exact_interval_and_largest_overlap(self):
        spans = [("total", {"durationMillis": 40, "endEpochMillis": 1_100})]
        frames = [
            (1_000, 1_050, 50, {"durationMicros": 50_000}),
            (1_050, 1_120, 70, {"durationMicros": 70_000}),
        ]
        retained, joins = module.join_spans_to_frames(spans, frames)
        self.assertEqual(1, retained)
        self.assertEqual(1, len(joins))
        self.assertEqual(70, joins[0]["frameDurationMillis"])
        self.assertTrue(joins[0]["containedByFrame"])
        self.assertEqual(57.14, joins[0]["spanShareOfFramePercent"])
        self.assertEqual(57.14, joins[0]["overlapShareOfFramePercent"])

    def test_crossing_span_reports_only_overlap_share(self):
        spans = [("total", {"durationMillis": 80, "endEpochMillis": 1_080})]
        frames = [(1_050, 1_100, 50, {"durationMicros": 50_000})]
        _retained, joins = module.join_spans_to_frames(spans, frames)
        self.assertFalse(joins[0]["containedByFrame"])
        self.assertIsNone(joins[0]["spanShareOfFramePercent"])
        self.assertEqual(60.0, joins[0]["overlapShareOfFramePercent"])

    def test_nonoverlap_is_unclassified(self):
        spans = [("total", {"durationMillis": 10, "endEpochMillis": 2_000})]
        retained, joins = module.join_spans_to_frames(
            spans, [(1_000, 1_100, 100, {"durationMicros": 100_000})])
        self.assertEqual(1, retained)
        self.assertEqual([], joins)

    def test_worst_frame_intervals_derive_start_from_duration(self):
        report = {
            "frameTimes": {
                "campaignUnpausedActive": {
                    "worstFrames": [{
                        "durationMicros": 50_000,
                        "endEpochMillis": 2_000,
                    }]
                }
            }
        }
        frames = module.worst_frame_intervals(report, "campaignUnpausedActive")
        self.assertEqual((1_950.0, 2_000.0, 50.0), frames[0][:3])


if __name__ == "__main__":
    unittest.main()
