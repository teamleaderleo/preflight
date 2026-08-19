#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import unittest

import required_merge_gate as gate


REPOSITORY = Path(__file__).resolve().parents[1]
MERGE_GATE_WORKFLOW = REPOSITORY / ".github" / "workflows" / "merge-gate.yml"
SOURCE = ".github/workflows/source-boundary.yml"
CI = ".github/workflows/ci.yml"
DESKTOP = ".github/workflows/desktop-application.yml"
STATIC = ".github/workflows/java-static-analysis.yml"
REPRO = ".github/workflows/java-reproducibility.yml"
LAUNCHER = ".github/workflows/launcher-ownership-boundary.yml"
TRUSTED = frozenset({SOURCE, CI, DESKTOP, STATIC, REPRO, LAUNCHER})


def run(run_id: int, path: str, name: str, conclusion: str = "success") -> dict:
    return {
        "id": run_id,
        "path": path,
        "name": name,
        "head_sha": "a" * 40,
        "head_branch": "topic",
        "head_repository": {"id": 17},
        "status": "completed",
        "conclusion": conclusion,
    }


def pull_request() -> dict:
    return {"head": {"sha": "a" * 40, "ref": "topic", "repo": {"id": 17}}}


class RunSelectionTest(unittest.TestCase):
    def test_run_must_match_exact_pull_request_head_identity(self):
        expected = pull_request()
        self.assertTrue(gate.belongs_to_pr(run(1, CI, "CI"), expected))

        wrong_branch = run(2, CI, "CI")
        wrong_branch["head_branch"] = "other"
        self.assertFalse(gate.belongs_to_pr(wrong_branch, expected))

        wrong_repo = run(3, CI, "CI")
        wrong_repo["head_repository"] = {"id": 18}
        self.assertFalse(gate.belongs_to_pr(wrong_repo, expected))

    def test_latest_rerun_wins_by_trusted_workflow_path(self):
        latest = gate.latest_by_path(
            [run(1, CI, "CI", "failure"), run(2, CI, "CI", "success")],
            TRUSTED,
        )
        self.assertEqual({CI}, set(latest))
        self.assertEqual(2, latest[CI]["id"])

    def test_pr_supplied_same_name_workflow_cannot_mask_trusted_failure(self):
        spoof = run(99, ".github/workflows/pr-spoof.yml", "Source boundary", "success")
        state, paths, latest = gate.evaluate(
            [run(1, SOURCE, "Source boundary", "failure"), spoof],
            TRUSTED,
        )
        self.assertEqual("failed", state)
        self.assertEqual([SOURCE], paths)
        self.assertEqual({SOURCE}, set(latest))


class EvaluationTest(unittest.TestCase):
    def test_source_boundary_is_always_required(self):
        state, details, _ = gate.evaluate([], TRUSTED)
        self.assertEqual("pending", state)
        self.assertEqual([SOURCE], details)

    def test_all_observed_scoped_workflows_must_pass(self):
        state, details, _ = gate.evaluate(
            [
                run(1, SOURCE, "Source boundary"),
                run(2, DESKTOP, "Desktop application", "failure"),
                run(3, STATIC, "Java static analysis"),
            ],
            TRUSTED,
        )
        self.assertEqual("failed", state)
        self.assertEqual([DESKTOP], details)

    def test_success_accepts_whichever_base_known_scoped_workflows_ran(self):
        state, details, latest = gate.evaluate(
            [
                run(1, SOURCE, "Source boundary"),
                run(2, CI, "CI"),
                run(3, REPRO, "Java reproducibility"),
                run(4, LAUNCHER, "Launcher ownership boundary"),
            ],
            TRUSTED,
        )
        self.assertEqual("success", state)
        self.assertEqual([], details)
        self.assertEqual({SOURCE, CI, REPRO, LAUNCHER}, set(latest))


class FailureClassificationTest(unittest.TestCase):
    def test_checkout_only_failure_is_labeled_bootstrap(self):
        kind, steps = gate.failure_kind(
            {"conclusion": "failure"},
            [{
                "conclusion": "failure",
                "steps": [{"name": "Run actions/checkout@v7", "conclusion": "failure"}],
            }],
        )
        self.assertEqual("runner/action bootstrap failure", kind)
        self.assertEqual(["Run actions/checkout@v7"], steps)

    def test_product_step_failure_is_labeled_product(self):
        kind, steps = gate.failure_kind(
            {"conclusion": "failure"},
            [{
                "conclusion": "failure",
                "steps": [{"name": "Run tests with JaCoCo", "conclusion": "failure"}],
            }],
        )
        self.assertEqual("product/check failure", kind)
        self.assertEqual(["Run tests with JaCoCo"], steps)

    def test_startup_failure_is_labeled_bootstrap_without_steps(self):
        kind, steps = gate.failure_kind({"conclusion": "startup_failure"}, [])
        self.assertEqual("runner/action bootstrap failure", kind)
        self.assertEqual([], steps)

    def test_cancelled_workflow_is_labeled_rerun_required(self):
        kind, steps = gate.failure_kind({"conclusion": "cancelled"}, [])
        self.assertEqual("workflow cancellation; rerun required", kind)
        self.assertEqual([], steps)


class WorkflowTrustBoundaryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = MERGE_GATE_WORKFLOW.read_text(encoding="utf-8").replace("\r\n", "\n")

    def test_publisher_runs_from_trusted_base_and_never_checks_out_pr_head(self):
        self.assertIn("pull_request_target:", self.workflow)
        self.assertIn("ref: ${{ github.event.pull_request.base.sha }}", self.workflow)
        self.assertNotIn("ref: ${{ github.event.pull_request.head.sha }}", self.workflow)
        self.assertIn("statuses: write", self.workflow)

    def test_required_context_is_published_to_latest_pr_head(self):
        self.assertIn("HEAD_SHA: ${{ github.event.pull_request.head.sha }}", self.workflow)
        self.assertIn("GATE_CONTEXT: Merge gate", self.workflow)
        self.assertIn('"https://api.github.com/repos/$GITHUB_REPOSITORY/statuses/$HEAD_SHA"', self.workflow)
        self.assertIn("post_status pending", self.workflow)
        self.assertIn("post_status success", self.workflow)
        self.assertIn("post_status failure", self.workflow)


if __name__ == "__main__":
    unittest.main()
