"""Both defects covered here shipped a wrong published finding on 2026-08-01.

The clock case: Starsector launches with -XX:+UseFastUnorderedTimeStamps, under which JFR's
tick-to-nanosecond conversion is wrong by a constant factor, so every duration in the recording is
compressed by it. Read uncorrected, a Thread.sleep(10) reads as 4.7ms.

The ranking case: ranked by raw blocked time, six permanently-idle daemons outrank the loading
thread, and a top-8 cut dropped it off the report. That absence was published as "the loading
thread never blocks", when it was in fact waiting 27 wall seconds on the game's image prefetcher.
"""
import collections
import datetime
import io
import contextlib
import unittest

import starsector_critical_path as module


def iso(epoch_seconds):
    stamp = datetime.datetime.fromtimestamp(epoch_seconds, datetime.timezone.utc)
    # JFR emits nanosecond precision, which datetime.fromisoformat rejects; the parser must trim.
    return stamp.isoformat().replace("+00:00", "123+00:00")


def cpu_load(period_seconds, count=40, start=1_000_000.0):
    return [{"values": {"startTime": iso(start + index * period_seconds)}} for index in range(count)]


class ClockCalibrationTest(unittest.TestCase):
    def test_a_clock_that_matches_real_time_is_not_rescaled(self):
        self.assertAlmostEqual(1.0, module.clock_factor(cpu_load(1.0)), places=2)

    def test_a_compressed_clock_is_measured_from_the_known_period(self):
        # The reviewed install records CPULoad's fixed 1-second period as ~0.4015s.
        self.assertAlmostEqual(2.4907, module.clock_factor(cpu_load(0.4015)), places=2)

    def test_the_correction_turns_a_short_sleep_back_into_a_real_one(self):
        # main's Thread.sleep(10) polls recorded a 4.744ms median. A sleep(10) cannot return early,
        # which is the independent check that the factor is a clock error and not a finding.
        corrected = 4.744 * module.clock_factor(cpu_load(0.4015))
        self.assertTrue(10.0 <= corrected <= 15.0, corrected)

    def test_too_few_calibration_points_reports_the_recording_as_is(self):
        # Inventing a correction from two points is worse than reporting raw numbers.
        self.assertEqual(1.0, module.clock_factor(cpu_load(0.4, count=2)))
        self.assertEqual(1.0, module.clock_factor([]))

    def test_unparseable_timestamps_do_not_crash_the_report(self):
        self.assertEqual(1.0, module.clock_factor([{"values": {"startTime": "not a time"}}] * 5))


class BlockedRankingTest(unittest.TestCase):
    def setUp(self):
        counter = collections.Counter
        self.idle = {f"idle-daemon-{index}": counter({"jdk.JavaMonitorWait": 30.0 + index})
                     for index in range(8)}
        self.totals = dict(self.idle)
        self.totals[module.LOADING_THREAD] = counter({"jdk.ThreadSleep": 10.91})
        self.sites = {name: counter({"java.lang.Object.wait": 30.0}) for name in self.idle}
        self.sites[module.LOADING_THREAD] = counter(
            {"java.lang.Thread.sleep <- com.fs.graphics.L.class": 10.91})
        self._blocked = module.blocked_by_thread
        self._events = module.events
        module.blocked_by_thread = lambda *a, **k: (self.totals, self.sites)
        module.events = lambda *a, **k: []

    def tearDown(self):
        module.blocked_by_thread = self._blocked
        module.events = self._events

    def render(self):
        buffer = io.StringIO()
        with contextlib.redirect_stdout(buffer):
            module.report("ignored.jfr", top=8, jfr="/nonexistent/jfr")
        return buffer.getvalue()

    def test_the_loading_thread_survives_a_ranking_it_does_not_place_in(self):
        out = self.render()
        self.assertIn(module.LOADING_THREAD, out)
        self.assertIn("loading thread", out)

    def test_the_blocking_site_is_reported_so_idle_waits_are_distinguishable(self):
        # Total blocked time alone cannot separate a daemon parked on its own empty queue from a
        # thread waiting on another thread's result. Only the stack can.
        out = self.render()
        self.assertIn("com.fs.graphics.L.class", out)
        self.assertIn("java.lang.Object.wait", out)


if __name__ == "__main__":
    unittest.main()
