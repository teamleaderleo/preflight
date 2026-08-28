import collections
import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path

import starsector_gameplay_hotspots as module


def event(thread, *methods):
    frames = []
    for value in methods:
        owner, name = value.rsplit(".", 1)
        frames.append({"method": {"type": {"name": owner.replace(".", "/")}, "name": name}})
    return {"values": {"sampledThread": {"javaName": thread}, "stackTrace": {"frames": frames}}}


def allocation_event(thread, object_class, weight, *methods, start_time=None):
    value = event(thread, *methods)
    value["values"]["objectClass"] = {"name": object_class.replace(".", "/")}
    value["values"]["weight"] = weight
    if start_time:
        value["values"]["startTime"] = start_time
    return value


class GameplayHotspotTest(unittest.TestCase):
    def test_loop_root_classifies_state_instead_of_an_incidental_package(self):
        campaign = [
            "com.fs.starfarer.combat.Helper.lookup",
            "com.fs.starfarer.campaign.CampaignState.advance",
        ]
        self.assertEqual("campaign", module.gameplay_state(campaign))
        self.assertEqual("combat", module.gameplay_state([
            "example.Mod.advance",
            "com.fs.starfarer.combat.CombatEngine.advance",
        ]))

    def test_report_normalizes_slashes_and_excludes_non_main_threads(self):
        original_events = module.events
        original_thread = module.thread_of
        try:
            module.events = lambda *args, **kwargs: [
                event("main", "mod.Costly.work", "com.fs.starfarer.campaign.CampaignState.advance"),
                event("worker", "mod.Worker.work", "com.fs.starfarer.campaign.CampaignState.advance"),
                event("main", "mod.Combat.work", "com.fs.starfarer.combat.CombatEngine.advance"),
            ]
            module.thread_of = lambda value: value["values"]["sampledThread"]["javaName"]
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                module.report("fixture.jfr", top=5)
            rendered = output.getvalue()
            self.assertIn("campaign=1, combat=1, other=0", rendered)
            self.assertIn("mod.Costly.work", rendered)
            self.assertIn("mod.Combat.work", rendered)
            self.assertNotIn("mod.Worker.work", rendered)
        finally:
            module.events = original_events
            module.thread_of = original_thread

    def test_execution_report_can_include_startup_and_unclassified_stacks(self):
        stacks = [event("main", "mod.Startup.work", "game.Loader.run")]
        original_thread = module.thread_of
        try:
            module.thread_of = lambda value: value["values"]["sampledThread"]["javaName"]
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                module.report_execution_events(stacks, top=5, include_other=True)
            rendered = output.getvalue()
            self.assertIn("other: 1 main-thread execution samples", rendered)
            self.assertIn("mod.Startup.work", rendered)
        finally:
            module.thread_of = original_thread

    def test_allocation_report_attributes_jdk_leaf_to_first_game_owner(self):
        original_events = module.events
        original_thread = module.thread_of
        try:
            module.events = lambda *args, **kwargs: [
                allocation_event(
                    "main", "java.lang.Object[]", 3 * 1024 * 1024,
                    "java.util.Arrays.copyOf",
                    "java.util.ArrayList.toArray",
                    "com.fs.starfarer.campaign.econ.Economy.getMarketsCopy",
                    "com.fs.starfarer.campaign.CampaignState.advance",
                ),
                allocation_event(
                    "worker", "java.lang.Object[]", 9 * 1024 * 1024,
                    "java.util.Arrays.copyOf",
                    "com.fs.starfarer.campaign.CampaignState.advance",
                ),
            ]
            module.thread_of = lambda value: value["values"]["sampledThread"]["javaName"]
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                module.report_allocations("fixture.jfr", top=5)
            rendered = output.getvalue()
            self.assertIn("campaign=1, combat=0, other=0", rendered)
            self.assertIn("3.0 MiB", rendered)
            self.assertIn("java.lang.Object[]", rendered)
            self.assertIn("java.util.Arrays.copyOf", rendered)
            self.assertIn("com.fs.starfarer.campaign.econ.Economy.getMarketsCopy", rendered)
            self.assertNotIn("9.0 MiB", rendered)
        finally:
            module.events = original_events
            module.thread_of = original_thread

    def test_filtered_allocation_report_attributes_weight_to_callers(self):
        samples = [
            (
                [
                    "java.util.LinkedHashMap.newNode",
                    "game.GridIterator.<init>",
                    "game.Grid.getCheckIterator",
                    "mod.Targeting.advance",
                    "game.CombatEngine.advance",
                ],
                "java.util.LinkedHashMap$Entry",
                6 * 1024 * 1024,
            ),
            (
                [
                    "game.Grid.getCheckIterator",
                    "game.Avoidance.advance",
                    "game.CombatEngine.advance",
                ],
                "game.GridIterator",
                2 * 1024 * 1024,
            ),
        ]
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            module.report_allocation_state(
                "combat", samples, top=10, contains="game.Grid.getCheckIterator")
        rendered = output.getvalue()
        self.assertIn("immediate callers:", rendered)
        self.assertIn("6.0 MiB  75.00%  mod.Targeting.advance", rendered)
        self.assertIn("2.0 MiB  25.00%  game.Avoidance.advance", rendered)
        self.assertIn("calling methods above the filter:", rendered)
        self.assertIn("8.0 MiB  100.00%  game.CombatEngine.advance", rendered)

    def test_cluster_enrichment_ranks_excess_presence_not_common_background(self):
        cluster = [event(
            "main", "mod.Hitch.work", "mod.Common.work",
            "com.fs.starfarer.combat.CombatEngine.advance") for _ in range(6)]
        cluster.extend(event(
            "main", "mod.Common.work",
            "com.fs.starfarer.combat.CombatEngine.advance") for _ in range(4))
        baseline = list(cluster)
        baseline.extend(event(
            "main", "mod.Common.work",
            "com.fs.starfarer.combat.CombatEngine.advance") for _ in range(80))
        baseline.extend(event(
            "main", "mod.Quiet.work",
            "com.fs.starfarer.combat.CombatEngine.advance") for _ in range(10))
        baseline.extend(event(
            "main", "mod.Hitch.work", "mod.Common.work",
            "com.fs.starfarer.combat.CombatEngine.advance") for _ in range(10))
        original_thread = module.thread_of
        try:
            module.thread_of = lambda value: value["values"]["sampledThread"]["javaName"]
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                module.report_cluster_enrichment(cluster, baseline, top=10)
            rendered = output.getvalue()
            self.assertIn("combat enrichment population: cluster=10, background=100", rendered)
            self.assertIn("mod.Hitch.work", rendered)
            self.assertIn("clusters  1/1", rendered)
            self.assertIn("6.00x", rendered)
            # Common work has the same 100% presence in both populations and no positive excess.
            self.assertNotIn("1.00x  mod.Common.work", rendered)
        finally:
            module.thread_of = original_thread

    def test_cluster_enrichment_requires_recurrence_and_positive_excess(self):
        rows = module.enrichment_rows(
            collections.Counter({"one-off": 1, "common": 4, "signal": 3}),
            collections.Counter({"common": 8, "signal": 1}),
            cluster_total=4,
            background_total=8,
            top=10,
        )
        self.assertEqual(["signal"], [row[-1] for row in rows])

    def test_cluster_breadth_distinguishes_one_long_hitch_from_recurrence(self):
        recurring_one = event(
            "main", "mod.Recurring.work",
            "com.fs.starfarer.combat.CombatEngine.advance")
        recurring_two = event(
            "main", "mod.Recurring.work",
            "com.fs.starfarer.combat.CombatEngine.advance")
        burst = [event(
            "main", "mod.Burst.work",
            "com.fs.starfarer.combat.CombatEngine.advance") for _ in range(4)]
        original_thread = module.thread_of
        try:
            module.thread_of = lambda value: value["values"]["sampledThread"]["javaName"]
            breadth = module.cluster_method_breadth([
                ("first", [recurring_one] + burst),
                ("second", [recurring_two]),
            ])
            self.assertEqual(2, breadth["combat"]["windows"])
            self.assertEqual(2, breadth["combat"]["leaves"]["mod.Recurring.work"])
            self.assertEqual(1, breadth["combat"]["leaves"]["mod.Burst.work"])
        finally:
            module.thread_of = original_thread

    def test_cluster_enrichment_api_requires_exact_cluster_and_step_boundaries(self):
        with self.assertRaisesRegex(SystemExit, "requires repeated clusters"):
            module.report("fixture.jfr", cluster_enrichment=True)
        with self.assertRaisesRegex(SystemExit, "requires exact scenario steps"):
            module.report("fixture.jfr", repeated_clusters=10, cluster_enrichment=True)

    def test_repeated_cluster_report_compares_with_same_step_background(self):
        original_events = module.events
        original_thread = module.thread_of
        original_selected = module.selected_wall_windows
        original_steps = module.scenario_step_windows
        original_recording = module.recording_clock_windows
        try:
            cluster_one = event(
                "main", "mod.Hitch.work", "com.fs.starfarer.combat.CombatEngine.advance")
            cluster_one["values"]["startTime"] = "2026-08-27T01:00:00.200Z"
            cluster_two = event(
                "main", "mod.Hitch.work", "com.fs.starfarer.combat.CombatEngine.advance")
            cluster_two["values"]["startTime"] = "2026-08-27T01:00:00.800Z"
            background = event(
                "main", "mod.Ordinary.work", "com.fs.starfarer.combat.CombatEngine.advance")
            background["values"]["startTime"] = "2026-08-27T01:00:01.500Z"
            sampled = [cluster_one, cluster_two, background]
            cluster_window = [(
                "cluster", module.instant("2026-08-27T01:00:00Z"),
                module.instant("2026-08-27T01:00:01Z"))]
            step_window = [(
                "combat", module.instant("2026-08-27T01:00:00Z"),
                module.instant("2026-08-27T01:00:02Z"))]
            module.events = lambda _path, names, **_kwargs: (
                sampled if names == ["jdk.ExecutionSample"] else [])
            module.thread_of = lambda value: value["values"]["sampledThread"]["javaName"]
            module.selected_wall_windows = lambda *_args, **_kwargs: cluster_window
            module.scenario_step_windows = lambda *_args, **_kwargs: step_window
            module.recording_clock_windows = lambda _path, windows, _jfr: (windows, 1.0)

            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                module.report(
                    "fixture.jfr", steps=["combat"], frame_report="frames.json",
                    repeated_clusters=10, cluster_enrichment=True)
            rendered = output.getvalue()
            self.assertIn("combat enrichment population: cluster=2, background=1", rendered)
            self.assertIn("mod.Hitch.work", rendered)
            self.assertNotIn("mod.Ordinary.work", rendered.split("overrepresented leaf methods:")[1])
        finally:
            module.events = original_events
            module.thread_of = original_thread
            module.selected_wall_windows = original_selected
            module.scenario_step_windows = original_steps
            module.recording_clock_windows = original_recording

    def test_allocation_window_uses_absolute_event_timestamps(self):
        samples = [
            allocation_event(
                "main", "java.lang.Object", 1, "mod.Work.run",
                start_time="2026-08-26T17:55:18.711333Z"),
            allocation_event(
                "main", "java.lang.Object", 1, "mod.Work.run",
                start_time="2026-08-27T01:55:30.000000+08:00"),
            allocation_event(
                "main", "java.lang.Object", 1, "mod.Work.run",
                start_time="2026-08-26T17:56:04.000000Z"),
        ]
        start = module.instant("2026-08-26T17:55:18.711333Z")
        end = module.instant("2026-08-26T17:56:03.712439Z")
        self.assertEqual(samples[:2], module.events_in_window(samples, start, end))

    def test_recording_clock_window_scales_elapsed_time_from_anchor(self):
        original_events = module.events
        original_clock_factor = module.clock_factor
        try:
            module.events = lambda _path, names, **_kwargs: [{
                "values": {"startTime": "2026-08-26T17:54:10Z"}
            }] if names == ["preflight.AgentStarted"] else []
            module.clock_factor = lambda _events: 2.5
            windows, factor = module.recording_clock_windows(
                "fixture.jfr",
                [("settled", module.instant("2026-08-26T17:55:10Z"),
                  module.instant("2026-08-26T17:56:10Z"))],
                "jfr",
            )
            self.assertEqual(2.5, factor)
            self.assertEqual(("settled", module.instant("2026-08-26T17:54:34Z"),
                              module.instant("2026-08-26T17:54:58Z")), windows[0])
        finally:
            module.events = original_events
            module.clock_factor = original_clock_factor

    def test_frame_report_window_uses_exact_worst_frame_extent(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "runtime-frame-report.json"
            report.write_text(json.dumps({
                "frameTimes": {
                    "allActive": {
                        "worstFrames": [{
                            "durationMicros": 6_155_433,
                            "endEpochMillis": 1_787_753_239_949,
                        }],
                    },
                },
            }), encoding="utf-8")
            windows = module.frame_report_windows(report, ["allActive"])
            self.assertEqual("worst frame allActive", windows[0][0])
            self.assertAlmostEqual(1_787_753_233.793567, windows[0][1], places=6)
            self.assertAlmostEqual(1_787_753_239.949, windows[0][2], places=6)

    def test_frame_report_defaults_to_all_active(self):
        original_frame_windows = module.frame_report_windows
        try:
            calls = []
            module.frame_report_windows = lambda path, names: calls.append((path, names)) or []
            module.selected_wall_windows("fixture.jfr", frame_report="frames.json")
            self.assertEqual([("frames.json", ["allActive"])], calls)
        finally:
            module.frame_report_windows = original_frame_windows

    def test_frame_report_cluster_windows_rank_bounded_repeated_clusters(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "runtime-frame-report.json"
            report.write_text(json.dumps({
                "frameTimes": {
                    "campaignUnpausedActive": {
                        "repeatedSlowFrameWindows": [
                            {
                                "frames": 3,
                                "durationMicros": 150_000,
                                "excessSlowFrameMicros": 50_000,
                                "startEpochMillis": 1_000,
                                "endEpochMillis": 1_150,
                            },
                            {
                                "frames": 2,
                                "durationMicros": 220_000,
                                "excessSlowFrameMicros": 153_333,
                                "startEpochMillis": 2_000,
                                "endEpochMillis": 2_220,
                            },
                        ],
                    },
                },
            }), encoding="utf-8")
            windows = module.frame_report_cluster_windows(
                report, ["campaignUnpausedActive"], 1)
            self.assertEqual(1, len(windows))
            self.assertIn("2 frames, 220.00 ms total", windows[0][0])
            self.assertEqual(2.0, windows[0][1])
            self.assertEqual(2.22, windows[0][2])

    def test_hitch_frame_windows_deduplicate_packets_and_group_consecutive_frames(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "runtime-frame-report.json"
            repeated = {
                "sequence": 11,
                "durationMicros": 120_000,
                "startOffsetMillis": 120.0,
                "endOffsetMillis": 240.0,
            }
            report.write_text(json.dumps({
                "frameTimes": {
                    "hitchPackets": {
                        "enabled": True,
                        "triggerMillis": 50,
                        "severeMillis": 100,
                        "packetTriggersDropped": 0,
                        "packets": [
                            {
                                "state": "combat",
                                "startEpochMillis": 10_000,
                                "startOffsetMillis": 100.0,
                                "severeTriggers": 1,
                                "frameHistory": [repeated],
                            },
                            {
                                "state": "combat",
                                "startEpochMillis": 10_020,
                                "startOffsetMillis": 120.0,
                                "severeTriggers": 1,
                                "frameHistory": [
                                    repeated,
                                    {
                                        "sequence": 12,
                                        "durationMicros": 150_000,
                                        "startOffsetMillis": 240.0,
                                        "endOffsetMillis": 390.0,
                                    },
                                ],
                            },
                        ],
                    },
                },
            }), encoding="utf-8")

            windows = module.frame_report_hitch_frame_windows(report, 100)
            self.assertEqual(1, len(windows))
            self.assertIn("sequences 11-12, 2 frames, 270.00 ms total", windows[0][0])
            self.assertAlmostEqual(10.02, windows[0][1], places=6)
            self.assertAlmostEqual(10.29, windows[0][2], places=6)

    def test_hitch_frame_windows_fail_when_trigger_population_is_incomplete(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "runtime-frame-report.json"
            report.write_text(json.dumps({
                "frameTimes": {
                    "hitchPackets": {
                        "enabled": True,
                        "triggerMillis": 50,
                        "severeMillis": 100,
                        "packetTriggersDropped": 1,
                        "packets": [],
                    },
                },
            }), encoding="utf-8")
            with self.assertRaisesRegex(SystemExit, "attribution is incomplete"):
                module.frame_report_hitch_frame_windows(report, 100)

    def test_events_in_windows_deduplicates_overlapping_windows(self):
        sample = event("main", "mod.Work.run")
        sample["values"]["startTime"] = "2026-08-26T17:55:30Z"
        start = module.instant("2026-08-26T17:55:00Z")
        end = module.instant("2026-08-26T17:56:00Z")
        selected = module.events_in_windows(
            [sample], [("one", start, end), ("two", start, end)])
        self.assertEqual([sample], selected)

    def test_covered_window_seconds_merges_overlap(self):
        self.assertEqual(7.0, module.covered_window_seconds([
            ("one", 1.0, 5.0),
            ("two", 3.0, 6.0),
            ("three", 8.0, 10.0),
        ]))

    def test_intersect_wall_windows_clips_to_exact_step(self):
        self.assertEqual([
            ("cluster inside step combat", 15.0, 20.0),
        ], module.intersect_wall_windows(
            [("cluster", 10.0, 20.0)],
            [("combat", 15.0, 25.0), ("later", 30.0, 40.0)]))

    def test_selected_repeated_clusters_are_intersected_with_steps(self):
        original_steps = module.scenario_step_windows
        original_clusters = module.frame_report_cluster_windows
        try:
            module.scenario_step_windows = lambda *_args, **_kwargs: [
                ("combat", 15.0, 25.0),
            ]
            module.frame_report_cluster_windows = lambda *_args, **_kwargs: [
                ("cluster", 10.0, 20.0),
            ]
            self.assertEqual([
                ("cluster inside step combat", 15.0, 20.0),
            ], module.selected_wall_windows(
                "fixture.jfr", steps=["combat"], frame_report="frames.json",
                frame_series=["combatAfterCampaignActive"], repeated_clusters=10))
        finally:
            module.scenario_step_windows = original_steps
            module.frame_report_cluster_windows = original_clusters

    def test_empty_step_and_frame_intersection_fails_closed(self):
        original_steps = module.scenario_step_windows
        original_frames = module.frame_report_windows
        try:
            module.scenario_step_windows = lambda *_args, **_kwargs: [
                ("combat", 30.0, 40.0),
            ]
            module.frame_report_windows = lambda *_args, **_kwargs: [
                ("worst frame", 10.0, 20.0),
            ]
            with self.assertRaisesRegex(SystemExit, "no requested frame window overlaps"):
                module.selected_wall_windows(
                    "fixture.jfr", steps=["combat"], frame_report="frames.json")
        finally:
            module.scenario_step_windows = original_steps
            module.frame_report_windows = original_frames

    def test_execution_report_can_select_a_scenario_step(self):
        original_events = module.events
        original_thread = module.thread_of
        original_windows = module.scenario_step_windows
        original_recording_windows = module.recording_clock_windows
        try:
            inside = event(
                "main", "mod.Combat.work", "com.fs.starfarer.combat.CombatEngine.advance")
            inside["values"]["startTime"] = "2026-08-26T17:55:30Z"
            outside = event(
                "main", "mod.Startup.work", "com.fs.starfarer.combat.CombatEngine.advance")
            outside["values"]["startTime"] = "2026-08-26T17:54:30Z"
            module.events = lambda _path, names, **_kwargs: (
                [inside, outside] if names == ["jdk.ExecutionSample"] else [])
            module.thread_of = lambda value: value["values"]["sampledThread"]["javaName"]
            start = module.instant("2026-08-26T17:55:00Z")
            end = module.instant("2026-08-26T17:56:00Z")
            module.scenario_step_windows = lambda *_args, **_kwargs: [("combat", start, end)]
            module.recording_clock_windows = lambda *_args, **_kwargs: (
                [("combat", start, end)], 1.0)

            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                module.report("fixture.jfr", top=5, steps=["combat"])
            rendered = output.getvalue()
            self.assertIn("window combat", rendered)
            self.assertIn("1 execution samples", rendered)
            self.assertIn("mod.Combat.work", rendered)
            self.assertNotIn("mod.Startup.work", rendered)
        finally:
            module.events = original_events
            module.thread_of = original_thread
            module.scenario_step_windows = original_windows
            module.recording_clock_windows = original_recording_windows


if __name__ == "__main__":
    unittest.main()
