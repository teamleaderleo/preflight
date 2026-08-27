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
