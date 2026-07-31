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

    def test_main_menu_requires_both_markers_and_sustained_quiet(self):
        output = self.root / "main-menu.json"

        def writer():
            time.sleep(0.02)
            self.append("7000 [Thread-3] INFO game - first post-click line")
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
        self.assertEqual(9700, result["mainMenuReadyLogMillis"])
        self.assertEqual(2700, result["gameLogMillisDelta"])
        self.assertGreater(result["gameLogStartToMainMenuMs"], 0)

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
            self.append("7000 [Thread-3] INFO game - first post-click line")
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

    def test_main_menu_uses_stream_containing_both_markers(self):
        output = self.root / "main-menu-stream.json"
        old_log = self.root / "starsector.log.1"
        self.log.rename(old_log)
        self.log.write_text("", encoding="utf-8")
        module.write_snapshot(self.root, self.snapshot)

        def writer():
            time.sleep(0.02)
            self.append("50000 [launcher] INFO launcher - click noise", path=old_log)
            self.append("100 [game] INFO game - first game line")
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
