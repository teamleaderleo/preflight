#!/usr/bin/env python3

import csv
import tempfile
import unittest
from pathlib import Path

import check_jacoco_thresholds as coverage


HEADER = [
    "GROUP",
    "PACKAGE",
    "CLASS",
    "INSTRUCTION_MISSED",
    "INSTRUCTION_COVERED",
    "BRANCH_MISSED",
    "BRANCH_COVERED",
    "LINE_MISSED",
    "LINE_COVERED",
    "COMPLEXITY_MISSED",
    "COMPLEXITY_COVERED",
    "METHOD_MISSED",
    "METHOD_COVERED",
]


def write_report(root: Path, module: str, rows: list[tuple[int, int]]) -> None:
    target = root / module / "target/site/jacoco"
    target.mkdir(parents=True)
    with (target / "jacoco.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow(HEADER)
        for index, (missed, covered) in enumerate(rows):
            writer.writerow(
                ["Preflight", "dev.starsector.preflight", f"Class{index}", 0, 0, 0, 0, missed, covered, 0, 0, 0, 0]
            )


class CoverageGateTest(unittest.TestCase):
    def test_line_coverage_aggregates_rows(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_report(root, "core", [(2, 8), (3, 7)])
            self.assertEqual(75.0, coverage.line_coverage(root / "core/target/site/jacoco/jacoco.csv"))

    def test_evaluate_accepts_floor(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_report(root, "core", [(2, 8)])
            self.assertEqual([], coverage.evaluate(root, {"core": 80.0}))

    def test_evaluate_rejects_regression(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_report(root, "core", [(3, 7)])
            failures = coverage.evaluate(root, {"core": 80.0})
            self.assertEqual(1, len(failures))
            self.assertIn("below the 80.00% floor", failures[0])

    def test_evaluate_rejects_missing_report(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            failures = coverage.evaluate(Path(directory), {"core": 80.0})
            self.assertEqual(1, len(failures))
            self.assertIn("missing", failures[0])


if __name__ == "__main__":
    unittest.main()
