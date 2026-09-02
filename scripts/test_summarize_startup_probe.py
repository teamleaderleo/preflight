import contextlib
import importlib.util
import io
import sys
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("summarize_startup_probe.py")
SPEC = importlib.util.spec_from_file_location("summarize_startup_probe", SCRIPT)
summary = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = summary
SPEC.loader.exec_module(summary)


class StartupProbeSummaryTest(unittest.TestCase):
    def sample(self):
        return {
            "phases": [
                {"name": "resource-batches-start", "elapsedMillis": 10_000,
                 "sincePreviousMillis": 10},
                {"name": "progress-100", "elapsedMillis": 32_781,
                 "sincePreviousMillis": 22_781},
            ],
            "resourceLoads": {
                "calls": 4,
                "byType": [
                    {"type": "TEXTURE", "calls": 3, "durationMillis": 20_000,
                     "maxCallMillis": 8_000},
                    {"type": "FONT", "calls": 1, "durationMillis": 500,
                     "maxCallMillis": 500},
                ],
                "first": [
                    {"ordinal": 1, "type": "TEXTURE", "path": "graphics/a.png",
                     "weight": 3, "durationMillis": 8_000,
                     "startedAtMillis": 10_000, "completedAtMillis": 18_000},
                ],
                "slowest": [
                    {"ordinal": 1, "type": "TEXTURE", "path": "graphics/a.png",
                     "weight": 3, "durationMillis": 8_000,
                     "startedAtMillis": 10_000, "completedAtMillis": 18_000},
                ],
            },
        }

    def test_reconciles_loop_wall_named_calls_and_residual(self):
        result = summary.resource_reconciliation(self.sample())
        self.assertIsNotNone(result)
        self.assertEqual(22_781, result["spanMillis"])
        self.assertEqual(20_500, result["timedMillis"])
        self.assertEqual(2_281, result["residualMillis"])
        self.assertAlmostEqual(89.9872700935, result["coveragePercent"])

    def test_prints_resource_types_first_slowest_and_coverage_warning(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            summary.print_resource_loads(self.sample())
        rendered = output.getvalue()
        self.assertIn("resource-batches-start -> progress-100: 22.781 s", rendered)
        self.assertIn("outside timed resource calls:            2.281 s", rendered)
        self.assertIn("named-call coverage:                      89.99%", rendered)
        self.assertIn("TEXTURE", rendered)
        self.assertIn("first resource calls", rendered)
        self.assertIn("slowest resource calls", rendered)
        self.assertIn("graphics/a.png", rendered)

    def test_missing_boundary_keeps_resource_totals_without_inventing_a_span(self):
        data = self.sample()
        data["phases"] = data["phases"][:1]
        result = summary.resource_reconciliation(data)
        self.assertIsNone(result["spanMillis"])
        self.assertIsNone(result["residualMillis"])
        self.assertEqual(20_500, result["timedMillis"])

    def test_negative_residual_is_exposed_instead_of_clamped(self):
        data = self.sample()
        data["resourceLoads"]["byType"][0]["durationMillis"] = 23_000
        result = summary.resource_reconciliation(data)
        self.assertEqual(-719, result["residualMillis"])
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            summary.print_resource_loads(data)
        self.assertIn("WARNING: timed resource totals exceed", output.getvalue())


if __name__ == "__main__":
    unittest.main()
