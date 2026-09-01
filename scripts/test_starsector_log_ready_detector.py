import calendar
import importlib.util
import json
import os
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("starsector_log_ready_detector.py")
spec = importlib.util.spec_from_file_location("starsector_log_ready_detector", MODULE_PATH)
module = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.modules[spec.name] = module
spec.loader.exec_module(module)


class DetectorTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.log = self.root / "starsector.log"
        self.log.write_text("1 [main] INFO launcher - start\n", encoding="utf-8")
        self.snapshot = self.root / "snapshot.json"
        module.write_snapshot(self.root, self.snapshot)

    def tearDown(self):
        self.temp.cleanup()

    def append(self, *lines, path=None):
        target = self.log if path is None else path
        with target.open("a", encoding="utf-8") as stream:
            for line in lines:
                stream.write(line + "\n")
                stream.flush()

    def test_launcher_marker_then_quiet(self):
        output = self.root / "launcher.json"
        process_start = time.monotonic_ns()

        def writer():
            time.sleep(0.03)
            self.append("2000 [Thread-2] INFO loader - graphics/fonts/orbitron12_0.png")
            time.sleep(0.02)
            self.append("2100 [Thread-2] INFO loader - final launcher resource")

        thread = threading.Thread(target=writer)
        thread.start()
        accepted = module.watch_launcher(
            self.root,
            self.snapshot,
            output,
            os.getpid(),
            process_start,
            timeout_seconds=1.0,
            quiet_seconds=0.08,
            sleep_seconds=0.01,
        )
        thread.join()
        self.assertTrue(accepted)
        result = json.loads(output.read_text())
        self.assertTrue(result["detected"])
        self.assertEqual(2100, result["launcherReadyLogMillis"])
        self.assertGreater(result["launcherReadyMs"], 0)

    def test_main_menu_requires_every_marker(self):
        # All three have to land on one stream: the game-start marker, the save-descriptor read
        # that shows the menu populating, and GraphicsLib's preload report. The last of those is
        # the boundary; anything the game logs afterwards is no longer part of the load.
        output = self.root / "main-menu.json"

        def writer():
            time.sleep(0.02)
            self.append("7000 [Thread-3] INFO  com.fs.starfarer.StarfarerLauncher  - "
                        "Running with the following mods (in order of priority):")
            time.sleep(0.02)
            self.append(
                "9000 [Thread-3] INFO com.fs.starfarer.campaign.save.CampaignGameManager  - "
                "Reading save data from [save/descriptor.xml]"
            )
            time.sleep(0.02)
            self.append(
                "9500 [Thread-3] INFO org.dark.shaders.util.TextureData  - "
                "VRAM after unload/preload: 450555 bytes"
            )
            time.sleep(0.05)
            self.append("9700 [Thread-3] INFO loader - deferred texture")

        thread = threading.Thread(target=writer)
        thread.start()
        accepted = module.watch_main_menu(
            self.root,
            self.snapshot,
            output,
            os.getpid(),
            timeout_seconds=1.0,
            quiet_seconds=0.10,
            sleep_seconds=0.01,
        )
        thread.join()
        self.assertTrue(accepted)
        result = json.loads(output.read_text())
        self.assertTrue(result["detected"])
        self.assertTrue(result["saveDescriptorSeen"])
        self.assertTrue(result["graphicsPreloadSeen"])
        self.assertEqual(9500, result["mainMenuReadyLogMillis"])
        self.assertEqual(2500, result["gameLogMillisDelta"])
        self.assertGreater(result["gameLogStartToMainMenuMs"], 0)

    def test_a_stream_missing_one_marker_is_not_a_finished_load(self):
        output = self.root / "main-menu-partial.json"
        self.append("7000 [Thread-3] INFO  com.fs.starfarer.StarfarerLauncher  - "
                    "Running with the following mods (in order of priority):")
        self.append("9500 [Thread-3] INFO org.dark.shaders.util.TextureData  - "
                    "VRAM after unload/preload: 450555 bytes")
        accepted = module.watch_main_menu(
            self.root, self.snapshot, output, os.getpid(),
            timeout_seconds=0.3, quiet_seconds=0.05, sleep_seconds=0.01,
        )
        self.assertFalse(accepted)
        self.assertFalse(json.loads(output.read_text())["detected"])

    def test_fresh_install_can_waive_the_absent_save_descriptor(self):
        output = self.root / "main-menu-no-save.json"
        self.append("7000 [Thread-3] INFO  com.fs.starfarer.StarfarerLauncher  - "
                    "Running with the following mods (in order of priority):")
        self.append("9500 [Thread-3] INFO org.dark.shaders.util.TextureData  - "
                    "VRAM after unload/preload: 450555 bytes")

        accepted = module.watch_main_menu(
            self.root, self.snapshot, output, os.getpid(),
            timeout_seconds=0.3, quiet_seconds=0.05, sleep_seconds=0.01,
            allow_missing_save_descriptor=True,
        )

        self.assertTrue(accepted)
        result = json.loads(output.read_text())
        self.assertTrue(result["detected"])
        self.assertFalse(result["saveDescriptorSeen"])
        self.assertIsNone(result["saveDescriptorLine"])
        self.assertEqual("waived-no-campaign-save", result["saveDescriptorRequirement"])

    def test_post_preload_fatal_is_not_certified_as_main_menu(self):
        output = self.root / "main-menu-fatal-after-preload.json"
        self.append("7000 [main] INFO com.fs.starfarer.StarfarerLauncher  - "
                    "Running with the following mods (in order of priority):")
        self.append("9500 [main] INFO org.dark.shaders.util.TextureData  - "
                    "VRAM after unload/preload: 450555 bytes")
        self.append("9530 [main] ERROR com.fs.starfarer.combat.CombatMain  - "
                    "java.lang.IllegalArgumentException: Number of remaining buffer elements is 4800")

        accepted = module.watch_main_menu(
            self.root, self.snapshot, output, os.getpid(),
            timeout_seconds=0.3, quiet_seconds=0.05, sleep_seconds=0.01,
            allow_missing_save_descriptor=True,
        )

        self.assertFalse(accepted)
        result = json.loads(output.read_text())
        self.assertEqual("fatal-after-game-start", result["failure"])
        self.assertIn("Number of remaining buffer elements", result["fatalLine"])

    def test_main_menu_reports_absolute_instants_the_recorder_can_consume(self):
        # The benchmark recorder needs absolute milestones, but durations are measured on
        # the monotonic clock. Both boundaries must land inside the wall-clock window the
        # watch actually occupied, and their gap must match the reported duration.
        output = self.root / "main-menu-instants.json"
        before = time.time()

        def writer():
            # Separated in time: lines read in one poll share an observed stamp, and the
            # real game's boundary lines are seconds apart.
            time.sleep(0.02)
            self.append("7000 [Thread-3] INFO  com.fs.starfarer.StarfarerLauncher  - Running with the following mods (in order of priority):")
            time.sleep(0.05)
            self.append(
                "9000 [Thread-3] INFO com.fs.starfarer.campaign.save.CampaignGameManager  - "
                "Reading save data from [save/descriptor.xml]"
            )
            time.sleep(0.05)
            self.append(
                "9500 [Thread-3] INFO org.dark.shaders.util.TextureData  - "
                "VRAM after unload/preload: 450555 bytes"
            )

        thread = threading.Thread(target=writer)
        thread.start()
        accepted = module.watch_main_menu(
            self.root, self.snapshot, output, os.getpid(),
            timeout_seconds=2.0, quiet_seconds=0.10, sleep_seconds=0.01,
        )
        thread.join()
        after = time.time()
        self.assertTrue(accepted)
        result = json.loads(output.read_text())

        start = self.instant_seconds(result["gameStartInstant"])
        ready = self.instant_seconds(result["mainMenuReadyInstant"])
        self.assertLessEqual(before - 1.0, start)
        self.assertLessEqual(ready, after + 1.0)
        self.assertLess(start, ready)
        self.assertAlmostEqual(
            result["gameLogStartToMainMenuMs"] / 1000.0, ready - start, delta=0.05
        )

    def instant_seconds(self, text):
        self.assertTrue(text.endswith("Z"), text)
        parsed = time.strptime(text[:-5], "%Y-%m-%dT%H:%M:%S")
        return calendar.timegm(parsed) + int(text[-4:-1]) / 1000.0

    def test_the_load_ends_at_the_marker_and_does_not_wait_for_the_game_to_go_quiet(self):
        # The failure this replaces: a launch whose load finished at 94.8s was still emitting
        # "Cleaned buffer for texture" at 231.8s, in bursts with a 20.8s gap. Waiting for six
        # seconds of silence meant sitting on a completed measurement indefinitely -- which is
        # exactly what happened the first time a campaign ran with nobody there to quit the game.
        output = self.root / "main-menu-trailing.json"
        marker = ("INFO  com.fs.starfarer.StarfarerLauncher  - "
                  "Running with the following mods (in order of priority):")
        stop = threading.Event()

        def writer():
            self.append(f"7000 [Thread-3] {marker}")
            time.sleep(0.03)
            self.append(
                "9000 [Thread-3] INFO com.fs.starfarer.campaign.save.CampaignGameManager  - "
                "Reading save data from [save/descriptor.xml]"
            )
            self.append(
                "9500 [Thread-3] INFO org.dark.shaders.util.TextureData  - "
                "VRAM after unload/preload: 450555 bytes"
            )
            # Never goes quiet. The old detector would have run to its timeout.
            value = 10000
            while not stop.wait(0.01):
                value += 100
                self.append(f"{value} [Thread-3] INFO com.fs.graphics.TextureLoader  - "
                            f"Cleaned buffer for texture graphics/x{value}.png")

        thread = threading.Thread(target=writer)
        thread.start()
        try:
            accepted = module.watch_main_menu(
                self.root, self.snapshot, output, os.getpid(),
                timeout_seconds=5.0, quiet_seconds=90.0, sleep_seconds=0.01,
            )
        finally:
            stop.set()
            thread.join()

        self.assertTrue(accepted, "must complete without the log ever going quiet")
        result = json.loads(output.read_text())
        self.assertEqual("graphics-preload-marker", result["completedOn"])
        self.assertEqual(9500, result["mainMenuReadyLogMillis"])
        self.assertEqual(2500, result["gameLogMillisDelta"])
        # Both spellings of the boundary now name the same instant.
        self.assertEqual(result["gameLogStartToMainMenuMs"],
                         result["gameLogStartToGraphicsPreloadMs"])
        self.assertIsNone(result["trailingLogActivityMs"])

    def test_the_first_preload_is_the_boundary_not_the_last(self):
        # GraphicsLib can report more than once. starsector_log_load_times.py takes the first,
        # and that tool is the independent check this one has to agree with -- picking different
        # markers would make them disagree by construction.
        output = self.root / "main-menu-two-preloads.json"
        marker = ("INFO  com.fs.starfarer.StarfarerLauncher  - "
                  "Running with the following mods (in order of priority):")
        preload = "INFO org.dark.shaders.util.TextureData  - VRAM after unload/preload: 1 bytes"
        self.append(f"7000 [Thread-3] {marker}")
        self.append("9000 [Thread-3] INFO com.fs.starfarer.campaign.save.CampaignGameManager  - "
                    "Reading save data from [save/descriptor.xml]")
        self.append(f"9500 [Thread-3] {preload}")
        self.append(f"30000 [Thread-3] {preload}")

        accepted = module.watch_main_menu(
            self.root, self.snapshot, output, os.getpid(),
            timeout_seconds=2.0, quiet_seconds=0.05, sleep_seconds=0.01,
        )
        self.assertTrue(accepted)
        result = json.loads(output.read_text())
        self.assertEqual(9500, result["mainMenuReadyLogMillis"])

    def test_main_menu_uses_stream_containing_both_markers(self):
        output = self.root / "main-menu-stream.json"
        old_log = self.root / "starsector.log.1"
        self.log.rename(old_log)
        self.log.write_text("", encoding="utf-8")
        module.write_snapshot(self.root, self.snapshot)

        def writer():
            time.sleep(0.02)
            self.append("50000 [launcher] INFO launcher - click noise", path=old_log)
            self.append("100 [game] INFO  com.fs.starfarer.StarfarerLauncher  - Running with the following mods (in order of priority):")
            time.sleep(0.02)
            self.append(
                "200 [game] INFO com.fs.starfarer.campaign.save.CampaignGameManager  - "
                "Reading save data from [save/descriptor.xml]"
            )
            self.append(
                "300 [game] INFO org.dark.shaders.util.TextureData  - "
                "VRAM after unload/preload: 450555 bytes"
            )

        thread = threading.Thread(target=writer)
        thread.start()
        accepted = module.watch_main_menu(
            self.root,
            self.snapshot,
            output,
            os.getpid(),
            timeout_seconds=1.0,
            quiet_seconds=0.08,
            sleep_seconds=0.01,
        )
        thread.join()
        self.assertTrue(accepted)
        result = json.loads(output.read_text())
        self.assertEqual("starsector.log", result["gameLogFile"])
        self.assertEqual(100, result["gameStartLogMillis"])
        self.assertEqual(300, result["mainMenuReadyLogMillis"])
        self.assertEqual(200, result["gameLogMillisDelta"])

    def test_the_measurement_starts_where_the_game_does_not_where_the_snapshot_fell(self):
        # This is the 2026-07-31 artifact, reproduced. The launcher writes into the same log the
        # game does, so whether its lines have been flushed when the harness takes its snapshot
        # decides whether they land inside the measured interval. Anchoring on "first line after
        # the snapshot" made that flush timing a term in the result: across four campaigns every
        # run whose first line was the launcher's measured 92-99s and every run whose first line
        # came later measured 74-78s, with nothing else separating them.
        #
        # Here the launcher's preamble is delivered late, so a first-line anchor would start the
        # clock at 'launcher preamble' and swallow the whole early phase.
        output = self.root / "main-menu-anchor.json"

        def writer():
            time.sleep(0.02)
            self.append("1000 [Thread-3] INFO com.fs.starfarer.StarfarerLauncher  - "
                        "Loading mod list, resolution 1440x932")
            time.sleep(0.06)
            self.append("9000 [Thread-3] INFO  com.fs.starfarer.StarfarerLauncher  - "
                        "Running with the following mods (in order of priority):")
            time.sleep(0.05)
            self.append(
                "11000 [Thread-3] INFO com.fs.starfarer.campaign.save.CampaignGameManager  - "
                "Reading save data from [save/descriptor.xml]"
            )
            self.append(
                "11500 [Thread-3] INFO org.dark.shaders.util.TextureData  - "
                "VRAM after unload/preload: 450555 bytes"
            )

        thread = threading.Thread(target=writer)
        thread.start()
        accepted = module.watch_main_menu(
            self.root, self.snapshot, output, os.getpid(),
            timeout_seconds=3.0, quiet_seconds=0.10, sleep_seconds=0.01,
        )
        thread.join()
        self.assertTrue(accepted)
        result = json.loads(output.read_text())

        self.assertEqual(9000, result["gameStartLogMillis"])
        self.assertIn("Running with the following mods", result["gameStartLine"])
        self.assertEqual("game-start-log-marker", result["gameStartAnchor"])
        # The discarded anchor is still reported, and the gap between the two is the size of
        # what used to leak into the measurement.
        self.assertIn("Loading mod list", result["firstObservedLogLine"])
        self.assertGreater(
            result["firstObservedLogLineToGraphicsPreloadMs"]
            - result["gameLogStartToGraphicsPreloadMs"],
            40,
            "the launcher preamble must be excluded from the measured interval",
        )

    def test_a_launch_that_never_reaches_the_game_is_not_a_slow_load(self):
        # A stream with lines but no start marker means the launcher ran and the game never
        # did. Reporting that as "no candidate stream" would send someone looking at the load.
        output = self.root / "main-menu-never-started.json"
        self.append("1000 [Thread-3] INFO com.fs.starfarer.StarfarerLauncher  - launcher only")
        accepted = module.watch_main_menu(
            self.root, self.snapshot, output, os.getpid(),
            timeout_seconds=0.2, quiet_seconds=0.05, sleep_seconds=0.01,
        )
        self.assertFalse(accepted)
        result = json.loads(output.read_text())
        self.assertFalse(result["detected"])
        self.assertTrue(result["anyTimestampedLineSeen"])
        self.assertFalse(result["gameStartMarkerSeen"])

    def test_an_install_with_no_mods_still_anchors(self):
        # Starsector logs a different sentence when nothing is enabled. It comes from the same
        # method at the same point, and a vanilla install must not be unmeasurable.
        output = self.root / "main-menu-vanilla.json"

        def writer():
            time.sleep(0.02)
            self.append("9000 [Thread-3] INFO  com.fs.starfarer.StarfarerLauncher  - "
                        "Running vanilla game with no mods.")
            time.sleep(0.03)
            self.append(
                "11000 [Thread-3] INFO com.fs.starfarer.campaign.save.CampaignGameManager  - "
                "Reading save data from [save/descriptor.xml]"
            )
            self.append(
                "11500 [Thread-3] INFO org.dark.shaders.util.TextureData  - "
                "VRAM after unload/preload: 450555 bytes"
            )

        thread = threading.Thread(target=writer)
        thread.start()
        accepted = module.watch_main_menu(
            self.root, self.snapshot, output, os.getpid(),
            timeout_seconds=2.0, quiet_seconds=0.08, sleep_seconds=0.01,
        )
        thread.join()
        self.assertTrue(accepted)
        result = json.loads(output.read_text())
        self.assertEqual(9000, result["gameStartLogMillis"])

    def test_a_launch_split_by_log_rotation_is_still_one_launch(self):
        # log4j rotates starsector.log at 51 MB. A single launch writes more than that when the
        # previous one left the file nearly full, so a launch routinely straddles the rotation --
        # start marker in the archive, preload marker in the new file. Requiring all three markers
        # on one inode meant the run timed out holding the measurement in two halves. On
        # 2026-08-01 that stalled a campaign whose load had already finished at 92.4s.
        output = self.root / "main-menu-rotated.json"

        def writer():
            time.sleep(0.02)
            self.append("340 [main] INFO  com.fs.starfarer.StarfarerLauncher  - "
                        "Running with the following mods (in order of priority):")
            time.sleep(0.03)
            # log4j rotates: the live file becomes the archive, a fresh one takes its place.
            self.log.rename(self.root / "starsector.log.1")
            self.log.write_text("", encoding="utf-8")
            time.sleep(0.03)
            self.append(
                "90748 [main] INFO com.fs.starfarer.campaign.save.CampaignGameManager  - "
                "Reading save data from [../../../saves/save_x/descriptor.xml]"
            )
            self.append(
                "92404 [main] INFO org.dark.shaders.util.TextureData  - "
                "VRAM after unload/preload: 450555 bytes"
            )

        thread = threading.Thread(target=writer)
        thread.start()
        accepted = module.watch_main_menu(
            self.root, self.snapshot, output, os.getpid(),
            timeout_seconds=3.0, quiet_seconds=0.05, sleep_seconds=0.01,
        )
        thread.join()
        self.assertTrue(accepted, "rotation must not split one launch into two")
        result = json.loads(output.read_text())
        self.assertEqual(340, result["gameStartLogMillis"])
        self.assertEqual(92404, result["mainMenuReadyLogMillis"])
        self.assertEqual(92064, result["gameLogMillisDelta"])

    def test_an_earlier_launchs_markers_cannot_close_this_one(self):
        # A terminated game can still be flushing when the next snapshot is taken. Its lines
        # precede this launch's start marker, and nothing before that marker may end the load.
        output = self.root / "main-menu-straggler.json"
        self.append(
            "88000 [main] INFO com.fs.starfarer.campaign.save.CampaignGameManager  - "
            "Reading save data from [../../../saves/save_old/descriptor.xml]"
        )
        self.append("89000 [main] INFO org.dark.shaders.util.TextureData  - "
                    "VRAM after unload/preload: 1 bytes")
        self.append("340 [main] INFO  com.fs.starfarer.StarfarerLauncher  - "
                    "Running with the following mods (in order of priority):")

        accepted = module.watch_main_menu(
            self.root, self.snapshot, output, os.getpid(),
            timeout_seconds=0.3, quiet_seconds=0.05, sleep_seconds=0.01,
        )
        self.assertFalse(accepted, "the previous launch's markers must not complete this one")
        result = json.loads(output.read_text())
        self.assertTrue(result["gameStartMarkerSeen"])
        self.assertFalse(result["graphicsPreloadSeen"])

    def test_a_file_vanishing_mid_read_does_not_kill_the_detector(self):
        # log4j rotation renames the live file, shifts the archives down and unlinks the oldest,
        # so a path listed a microsecond ago can already be gone. This used to raise
        # FileNotFoundError out of the detector, which then wrote no result at all -- and the
        # harness reported "the main menu was never detected" for a launch that was loading fine.
        # Unattended campaigns rotate on nearly every launch, so it went from rare to routine.
        marker = ("INFO  com.fs.starfarer.StarfarerLauncher  - "
                  "Running with the following mods (in order of priority):")
        self.append(f"340 [main] {marker}")
        self.append("9000 [main] INFO com.fs.starfarer.campaign.save.CampaignGameManager  - "
                    "Reading save data from [save/descriptor.xml]")
        self.append("9500 [main] INFO org.dark.shaders.util.TextureData  - "
                    "VRAM after unload/preload: 1 bytes")

        real = module._files
        vanished = self.root / "starsector.log.9"

        def files_including_one_that_is_gone(log_dir):
            return sorted(real(log_dir) + [vanished])

        module._files = files_including_one_that_is_gone
        try:
            output = self.root / "main-menu-vanishing.json"
            accepted = module.watch_main_menu(
                self.root, self.snapshot, output, os.getpid(),
                timeout_seconds=2.0, quiet_seconds=0.05, sleep_seconds=0.01,
            )
        finally:
            module._files = real

        self.assertTrue(accepted, "a vanished rotation must not abort the watch")
        self.assertEqual(9500, json.loads(output.read_text())["mainMenuReadyLogMillis"])

    def test_a_snapshot_survives_a_rotation_landing_on_it(self):
        real = module._files
        module._files = lambda log_dir: sorted(real(log_dir) + [self.root / "starsector.log.9"])
        try:
            values = module.snapshot(self.root)
        finally:
            module._files = real
        self.assertIn("starsector.log", values)
        self.assertNotIn("starsector.log.9", values)

    def test_rotated_file_is_matched_by_inode(self):
        before = self.root / "before.json"
        module.write_snapshot(self.root, before)
        rotated = self.root / "starsector.log.1"
        self.log.rename(rotated)
        with rotated.open("a", encoding="utf-8") as stream:
            stream.write("2 [main] INFO old - appended after rename\n")
        self.log.write_text("1 [main] INFO new - fresh current log\n", encoding="utf-8")
        output = self.root / "delta.txt"
        module.extract_delta(self.root, before, output)
        text = output.read_text(encoding="utf-8")
        self.assertIn("appended after rename", text)
        self.assertIn("fresh current log", text)
        self.assertNotIn("launcher - start", text)


if __name__ == "__main__":
    unittest.main()
