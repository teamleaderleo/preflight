import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("starsector_log_load_times.py")
spec = importlib.util.spec_from_file_location("starsector_log_load_times", MODULE_PATH)
module = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.modules[spec.name] = module
spec.loader.exec_module(module)

START = "INFO  com.fs.starfarer.StarfarerLauncher  - Running with the following mods (in order of priority):"
PRELOAD = "INFO org.dark.shaders.util.TextureData  - VRAM after unload/preload: 450555 bytes"


class LogLoadTimesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def write(self, name, *lines):
        (self.root / name).write_text("\n".join(lines) + "\n", encoding="utf-8")

    def test_reads_rotations_oldest_first(self):
        # log4j numbers the archives by age, so plain sorting reads them backwards and every
        # launch would look like a restart of the one after it.
        self.write("starsector.log.2", f"1000 [Thread-3] {START}", f"3000 [Thread-3] {PRELOAD}")
        self.write("starsector.log.1", f"1000 [Thread-3] {START}", f"5000 [Thread-3] {PRELOAD}")
        self.write("starsector.log", f"1000 [Thread-3] {START}", f"9000 [Thread-3] {PRELOAD}")

        self.assertEqual(
            ["starsector.log.2", "starsector.log.1", "starsector.log"],
            [p.name for p in module.rotation_order(self.root)],
        )
        self.assertEqual([2000, 4000, 8000],
                         [launch["loadMillis"] for launch in module.launches(self.root)])

    def test_a_launch_spanning_a_rotation_is_still_one_launch(self):
        # The boundary falls wherever the file filled up, which is routinely mid-load. Splitting
        # there would report the load as truncated and lose the only complete measurement.
        self.write("starsector.log.1", f"1000 [Thread-3] {START}", "2000 [Thread-3] INFO loading")
        self.write("starsector.log", "3000 [Thread-3] INFO loading", f"4000 [Thread-3] {PRELOAD}")

        found = module.launches(self.root)
        self.assertEqual(1, len(found))
        self.assertEqual(3000, found[0]["loadMillis"])

    def test_a_restart_is_detected_by_the_counter_going_backwards(self):
        self.write(
            "starsector.log",
            f"9000 [Thread-3] {START}",
            f"99000 [Thread-3] {PRELOAD}",
            f"1000 [Thread-3] {START}",
            f"81000 [Thread-3] {PRELOAD}",
        )
        self.assertEqual([90000, 80000],
                         [launch["loadMillis"] for launch in module.launches(self.root)])

    def test_a_truncated_launch_is_reported_as_such_rather_than_as_a_failure(self):
        # The oldest surviving file usually starts mid-run. Counting that as a launch that never
        # loaded would drag every summary down with an artifact of log rotation.
        self.write("starsector.log", f"1000 [Thread-3] {START}", "2000 [Thread-3] INFO loading")
        found = module.launches(self.root)
        self.assertEqual(1, len(found))
        self.assertIsNone(found[0]["loadMillis"])

    def test_preload_lines_before_any_start_marker_belong_to_no_launch(self):
        # Log rotation regularly leaves the tail of an earlier run at the head of a file. Those
        # markers must not be attached to the next launch, which would report a negative load.
        self.write("starsector.log", f"70000 [Thread-3] {PRELOAD}", f"1000 [Thread-3] {START}",
                   f"91000 [Thread-3] {PRELOAD}")
        found = module.launches(self.root)
        self.assertEqual(1, len(found))
        self.assertEqual(90000, found[0]["loadMillis"])

    def test_an_install_with_no_mods_is_measurable(self):
        self.write(
            "starsector.log",
            "1000 [Thread-3] INFO  com.fs.starfarer.StarfarerLauncher  - "
            "Running vanilla game with no mods.",
            f"61000 [Thread-3] {PRELOAD}",
        )
        self.assertEqual([60000],
                         [launch["loadMillis"] for launch in module.launches(self.root)])


if __name__ == "__main__":
    unittest.main()
