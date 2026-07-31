"""Tests for the repeated startup benchmark harness.

The 2026-07-23 pilots failed for three reasons this harness has to keep out: one sample
per mode, a blocked order that let drift line up with a condition, and a single bad half
destroying the whole session. These cover all three, plus the report's refusal to call an
underpowered result reportable.
"""

import contextlib
import importlib.util
import io
import json
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT_PATH = Path(__file__).with_name("run-startup-benchmark.sh")
SCRIPT_TEXT = SCRIPT_PATH.read_text(encoding="utf-8")

MODULE_PATH = Path(__file__).with_name("starsector_benchmark_report.py")
spec = importlib.util.spec_from_file_location("starsector_benchmark_report", MODULE_PATH)
report = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.modules[spec.name] = report
spec.loader.exec_module(report)


def runs(*specs) -> list[dict]:
    """(condition, iteration, seconds or None, status) tuples into run records."""
    records = []
    for condition, iteration, seconds, status in specs:
        records.append({
            "condition": condition,
            "iteration": iteration,
            "status": status,
            "reason": None if status == "accepted" else "profile-drift",
            "gameLogStartToMainMenuMs": None if seconds is None else seconds * 1000,
            "launcherReadyMs": 9000,
            "exitCode": 0,
        })
    return records


class ConditionTest(unittest.TestCase):
    def test_vanilla_launches_without_the_agent_attached(self):
        # JAVA_TOOL_OPTIONS is how preflight injects the agent. If an inherited value
        # survives into the baseline, the baseline is not a baseline.
        block = re.search(r"vanilla\)\n(?P<body>.*?);;", SCRIPT_TEXT, re.DOTALL)
        self.assertIsNotNone(block, "vanilla condition not found")
        self.assertIn("env -u JAVA_TOOL_OPTIONS", block.group("body"))

    def test_agent_condition_carries_the_recorder_but_no_adapter(self):
        block = re.search(r"agent\)\n(?P<body>.*?);;", SCRIPT_TEXT, re.DOTALL)
        self.assertIsNotNone(block, "agent condition not found")
        body = block.group("body")
        self.assertIn("--no-adapter", body)
        self.assertNotIn("--texture-auto", body)

    def test_enabled_condition_uses_the_collectable_texture_path(self):
        # BenchmarkCollectedRunComparison rejects an enabled record without textureAuto.
        block = re.search(r"enabled\)\n(?P<body>.*?);;", SCRIPT_TEXT, re.DOTALL)
        self.assertIsNotNone(block, "enabled condition not found")
        body = block.group("body")
        self.assertIn("--adapter", body)
        self.assertIn("--texture-auto", body)


class OrderTest(unittest.TestCase):
    def shuffle(self, seed: int, offset: int, items: list[str]) -> list[str]:
        source = re.search(
            r"shuffle_conditions\(\) \{.*?python3 -c '(?P<body>.*?)' \"\$SEED\"",
            SCRIPT_TEXT, re.DOTALL,
        )
        self.assertIsNotNone(source, "shuffle_conditions not found")
        result = subprocess.run(
            [sys.executable, "-c", source.group("body"), str(seed), str(offset)],
            input="\n".join(items), capture_output=True, text=True, check=True,
        )
        return result.stdout.split()

    def test_order_is_reproducible_from_the_recorded_seed(self):
        items = ["vanilla", "agent", "enabled"]
        self.assertEqual(self.shuffle(7, 1, items), self.shuffle(7, 1, items))
        self.assertCountEqual(self.shuffle(7, 1, items), items)

    def test_rounds_do_not_all_share_one_order(self):
        # A fixed order across rounds is a blocked design wearing a shuffle's clothes.
        items = ["vanilla", "agent", "enabled"]
        orders = {tuple(self.shuffle(11, offset, items)) for offset in range(1, 9)}
        self.assertGreater(len(orders), 1)

    def test_the_loop_shuffles_every_round_rather_than_once(self):
        self.assertIn('done < <(shuffle_conditions "$round")', SCRIPT_TEXT)


class ResilienceTest(unittest.TestCase):
    def test_one_bad_launch_does_not_abort_the_campaign(self):
        loop = re.search(
            r"for round in \$\(seq 1 \"\$ROUNDS\"\); do(?P<body>.*?)\ndone", SCRIPT_TEXT, re.DOTALL
        )
        self.assertIsNotNone(loop, "round loop not found")
        self.assertIn("|| true", loop.group("body"))

    def test_a_failed_launch_still_records_an_excluded_run(self):
        for reason in ("launcher-not-ready", "main-menu-not-detected"):
            self.assertIn(f'"excluded" "{reason}"', SCRIPT_TEXT)

    def test_completed_runs_are_skipped_on_resume(self):
        self.assertIn('if completed "$condition" "$round"; then', SCRIPT_TEXT)
        self.assertIn('.status == "accepted"', SCRIPT_TEXT)


class ReportTest(unittest.TestCase):
    def test_medians_and_delta_are_measured_against_vanilla(self):
        summary = report.summarize(runs(
            ("vanilla", 1, 100.0, "accepted"),
            ("vanilla", 2, 102.0, "accepted"),
            ("vanilla", 3, 104.0, "accepted"),
            ("enabled", 1, 90.0, "accepted"),
            ("enabled", 2, 92.0, "accepted"),
            ("enabled", 3, 94.0, "accepted"),
        ))
        self.assertEqual(102.0, summary["conditions"]["vanilla"]["medianSeconds"])
        self.assertEqual(92.0, summary["conditions"]["enabled"]["medianSeconds"])
        comparison = summary["comparisons"]["enabled vs vanilla"]
        self.assertEqual(-10.0, comparison["deltaSeconds"])
        self.assertAlmostEqual(9.8, comparison["improvementPercent"], places=1)

    def test_excluded_runs_stay_out_of_the_statistics_but_are_reported(self):
        summary = report.summarize(runs(
            ("vanilla", 1, 100.0, "accepted"),
            ("vanilla", 2, None, "excluded"),
            ("vanilla", 3, 200.0, "accepted"),
        ))
        vanilla = summary["conditions"]["vanilla"]
        self.assertEqual(2, vanilla["successfulRuns"])
        self.assertEqual(1, vanilla["excludedRuns"])
        self.assertEqual(["profile-drift"], vanilla["exclusionReasons"])
        self.assertEqual(150.0, vanilla["medianSeconds"])

    def test_three_rounds_can_never_be_reportable(self):
        # Exactly the defect that made the July pair worthless, generalised: a sample this
        # small cannot support a claim no matter how large the measured gap.
        summary = report.summarize(runs(
            *[("vanilla", i, 100.0, "accepted") for i in range(1, 4)],
            *[("enabled", i, 10.0, "accepted") for i in range(1, 4)],
        ))
        self.assertFalse(summary["benchmarkAccepted"])
        self.assertGreaterEqual(summary["comparisons"]["enabled vs vanilla"]["permutationP"], 0.1)

    def test_five_clean_rounds_per_condition_are_reportable(self):
        summary = report.summarize(runs(
            *[("vanilla", i, 100.0 + i, "accepted") for i in range(1, 6)],
            *[("enabled", i, 80.0 + i, "accepted") for i in range(1, 6)],
        ))
        self.assertTrue(summary["benchmarkAccepted"])
        self.assertTrue(
            summary["comparisons"]["enabled vs vanilla"]["meetsCampaignMinimum"]
        )

    def test_a_condition_short_of_the_threshold_blocks_acceptance(self):
        summary = report.summarize(runs(
            *[("vanilla", i, 100.0, "accepted") for i in range(1, 6)],
            *[("enabled", i, 80.0, "accepted") for i in range(1, 6)],
            *[("agent", i, 99.0, "accepted") for i in range(1, 4)],
        ))
        self.assertFalse(summary["benchmarkAccepted"])

    def test_no_baseline_means_no_comparison_rather_than_a_crash(self):
        summary = report.summarize(runs(("enabled", 1, 80.0, "accepted")))
        self.assertEqual({}, summary["comparisons"])
        self.assertFalse(summary["benchmarkAccepted"])
        self.assertIn("No comparison yet", report.render(summary, verbose=True))

    def test_summary_round_trips_through_the_written_file(self):
        with tempfile.TemporaryDirectory() as directory:
            results = Path(directory) / "results.jsonl"
            results.write_text(
                "\n".join(
                    json.dumps(record)
                    for record in runs(
                        ("vanilla", 1, 100.0, "accepted"),
                        ("enabled", 1, 90.0, "accepted"),
                    )
                ) + "\n",
                encoding="utf-8",
            )
            output = Path(directory) / "summary.json"
            with contextlib.redirect_stdout(io.StringIO()):
                report.main(["summary", "--results", str(results), "--output", str(output)])
            written = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(2, len(written["runs"]))
            self.assertEqual(100.0, written["conditions"]["vanilla"]["medianSeconds"])


class DetectorContractTest(unittest.TestCase):
    """The script drives the detector across a process boundary, so a renamed or newly
    required flag would only show up at launch time."""

    def test_every_detector_invocation_parses(self):
        detector_path = Path(__file__).with_name("starsector_log_ready_detector.py")
        detector_spec = importlib.util.spec_from_file_location("detector_under_test", detector_path)
        detector = importlib.util.module_from_spec(detector_spec)
        assert detector_spec.loader is not None
        # dataclasses resolves annotations through sys.modules during class creation.
        sys.modules[detector_spec.name] = detector
        detector_spec.loader.exec_module(detector)

        invocations = []
        lines = SCRIPT_TEXT.splitlines()
        for index, line in enumerate(lines):
            if 'python3 "$DETECTOR" ' not in line:
                continue
            call = line.split('python3 "$DETECTOR" ', 1)[1]
            cursor = index
            while call.rstrip().endswith("\\"):  # shell line continuation
                cursor += 1
                call = call.rstrip().removesuffix("\\") + " " + lines[cursor]
            invocations.append(call)

        self.assertGreaterEqual(len(invocations), 3, "expected snapshot and both watches")
        seen = set()
        for call in invocations:
            tokens = call.split()
            subcommand, flags = tokens[0], [token for token in tokens if token.startswith("--")]
            seen.add(subcommand)
            argv = [subcommand]
            for flag in flags:
                argv += [flag, "1"]  # parses as a path, an int, and a float alike
            # Raises SystemExit on an unknown flag or a missing required one.
            detector.parse_args(argv)
        self.assertEqual({"snapshot", "watch-launcher", "watch-main-menu"}, seen)


class AdapterEvidenceTest(unittest.TestCase):
    """A fail-open adapter run looks exactly like a baseline run from the outside, so
    nothing but this check stops it from being counted as a prepared measurement."""

    def served(self, telemetry) -> bool:
        body = re.search(
            r"served_prepared_textures\(\) \{(?P<body>.*?)\n\}", SCRIPT_TEXT, re.DOTALL
        )
        self.assertIsNotNone(body, "served_prepared_textures not found")
        with tempfile.TemporaryDirectory() as directory:
            run_dir = Path(directory)
            if telemetry is not None:
                (run_dir / "adapter.json").write_text(
                    json.dumps({"textureCompatibility": telemetry}), encoding="utf-8"
                )
            script = (
                "set -uo pipefail\n"
                "bad() { :; }\nnote() { :; }\n"
                f'served_prepared_textures() {{{body.group("body")}\n}}\n'
                f'served_prepared_textures "{run_dir}"\n'
            )
            result = subprocess.run(["bash", "-c", script], capture_output=True, text=True)
            return result.returncode == 0

    def healthy(self, **overrides) -> dict:
        telemetry = {
            "ready": True, "hits": 4956, "misses": 0, "fallbacks": 0,
            "internalErrors": 0, "bytesServed": 12345, "disableReasons": [],
        }
        telemetry.update(overrides)
        return telemetry

    def test_a_serving_run_is_accepted(self):
        self.assertTrue(self.served(self.healthy()))

    def test_a_run_that_served_nothing_is_rejected(self):
        self.assertFalse(self.served(self.healthy(hits=0)))

    def test_a_disabled_adapter_is_rejected(self):
        self.assertFalse(self.served(self.healthy(disableReasons=["circuit-breaker"])))

    def test_internal_errors_reject_the_run(self):
        self.assertFalse(self.served(self.healthy(internalErrors=1)))

    def test_an_unready_adapter_is_rejected(self):
        self.assertFalse(self.served(self.healthy(ready=False)))

    def test_a_missing_report_is_rejected_rather_than_assumed_healthy(self):
        self.assertFalse(self.served(None))

    def test_both_cache_serving_conditions_are_checked_and_no_others(self):
        # vanilla and agent legitimately have no adapter evidence; enabled and fast both
        # serve from the cache, so both have to prove they did.
        guard = re.search(
            r'elif \[\[ (?P<test>[^\]]*) \]\] \\?\s*\n?\s*&& ! served_prepared_textures',
            SCRIPT_TEXT,
        )
        self.assertIsNotNone(guard, "adapter guard not found")
        condition = guard.group("test")
        self.assertIn("enabled", condition)
        self.assertIn("fast", condition)
        self.assertNotIn("vanilla", condition)
        self.assertNotIn("agent", condition)


class RecordedShapeTest(unittest.TestCase):
    """The shell writes these records and the Python reads them. Nothing else checks
    that the two agree, so a renamed field would otherwise surface 35 minutes into a
    session with the game already running."""

    def record(self, directory: Path, *args: str, recording: str = "true") -> None:
        body = re.search(
            r"record_run\(\) \{(?P<body>.*?)\n\}", SCRIPT_TEXT, re.DOTALL
        )
        self.assertIsNotNone(body, "record_run not found")
        script = (
            'set -euo pipefail\n'
            f'ROOT="{directory}"\n'
            f'RECORDING={recording}\n'
            'RESULTS="$ROOT/results.jsonl"\n'
            f'record_run() {{{body.group("body")}\n}}\n'
            'record_run "$@"\n'
        )
        subprocess.run(
            ["bash", "-c", script, "record_run", *args],
            check=True, capture_output=True, text=True,
        )

    def test_an_accepted_record_reaches_the_report_intact(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.record(root, "enabled", "2", "accepted", "", "81234", "9000", "0")
            written = json.loads((root / "results.jsonl").read_text(encoding="utf-8"))
            self.assertEqual("enabled", written["condition"])
            self.assertEqual(2, written["iteration"])
            self.assertIsNone(written["reason"])
            summary = report.summarize([written])
            self.assertEqual(81.23, summary["conditions"]["enabled"]["medianSeconds"])

    def test_the_discarded_settling_launch_never_reaches_the_results(self):
        # It is the slowest launch of the session -- it is the one that regenerates the
        # GraphicsLib cache -- so counting it would drag its condition's median up.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.record(root, "enabled", "0", "accepted", "", "120000", "9000", "0",
                        recording="false")
            self.assertFalse((root / "results.jsonl").exists())

    def test_the_warmup_disables_recording_around_the_settling_launch(self):
        block = re.search(
            r"RECORDING=false\n\s*launch_once enabled 0 [^\n]*\n\s*RECORDING=true",
            SCRIPT_TEXT,
        )
        self.assertIsNotNone(block, "settling launch is not wrapped in RECORDING=false")

    def test_an_excluded_record_carries_its_reason_and_no_timing(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.record(root, "vanilla", "1", "excluded", "profile-drift",
                        "null", "null", "null")
            written = json.loads((root / "results.jsonl").read_text(encoding="utf-8"))
            self.assertEqual("profile-drift", written["reason"])
            self.assertIsNone(written["gameLogStartToMainMenuMs"])
            summary = report.summarize([written])
            self.assertEqual(0, summary["conditions"]["vanilla"]["successfulRuns"])
            self.assertEqual(["profile-drift"],
                             summary["conditions"]["vanilla"]["exclusionReasons"])


if __name__ == "__main__":
    unittest.main()
