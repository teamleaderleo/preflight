#!/usr/bin/env python3
"""Enforce conservative line-coverage floors for selected Java modules."""

from __future__ import annotations

import csv
import sys
from pathlib import Path


THRESHOLDS = {
    "preflight-core": 80.0,
    "preflight-agent": 78.0,
    "preflight-cli": 65.0,
}


def line_coverage(csv_path: Path) -> float:
    missed = 0
    covered = 0
    with csv_path.open(newline="", encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            missed += int(row["LINE_MISSED"])
            covered += int(row["LINE_COVERED"])
    total = missed + covered
    if total == 0:
        raise ValueError(f"{csv_path} contains no executable lines")
    return covered * 100.0 / total


def evaluate(root: Path, thresholds: dict[str, float] = THRESHOLDS) -> list[str]:
    failures: list[str] = []
    for module, floor in thresholds.items():
        report = root / module / "target/site/jacoco/jacoco.csv"
        if not report.is_file():
            failures.append(f"{module}: missing {report}")
            continue
        coverage = line_coverage(report)
        print(f"{module}: {coverage:.2f}% line coverage (floor {floor:.2f}%)")
        if coverage + 1e-9 < floor:
            failures.append(
                f"{module}: {coverage:.2f}% line coverage is below the {floor:.2f}% floor"
            )
    return failures


def main() -> int:
    failures = evaluate(Path.cwd())
    if failures:
        for failure in failures:
            print(f"coverage gate: {failure}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
