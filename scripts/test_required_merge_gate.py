#!/usr/bin/env python3
from __future__ import annotations

import unittest

import required_merge_gate as gate


class RunSelectionTest(unittest.TestCase):
    def test_run_must_belong_to_current_pull_request(self):
        run = {"pull_requests": [{"number": 607}]}
        self.assertTrue(gate.belongs_to_pr(run, 607))
        self.assertFalse(gate.belongs_to_pr(run, 608))

    def test_latest_rerun_wins_and_aggregate_excludes_itself(self):
        latest = gate.latest_by_name(
            [
                {"id": 1, "name": "CI", "status": "completed", "conclusion": "failure"},
                {"id": 2, "name": "CI", "status": "completed", "conclusion": "success"},
                {"id": 3, "name": gate.AGGREGATE_WORKFLOW, "status": "in_progress"},
            ]
        )
        self.assertEqual({"CI"}, set(latest))
        self.assertEqual(2, latest["CI"]["id"])


class EvaluationTest(unittest.TestCase):
    def test_source_boundary_is_always_required(self):
        state, details, _ = gate.evaluate([])
        self.assertEqual("pending", state)
        self.assertEqual(["Source boundary: missing"], details)

    def test_all_observed_scoped_workflows_must_pass(self):
        state, details, _ = gate.evaluate(
            [
                {"id": 1, "name": "Source boundary", "status": "completed", "conclusion": "success"},
                {"id": 2, "name": "Desktop application", "status": "completed", "conclusion": "failure"},
                {"id": 3, "name": "Java static analysis", "status": "completed", "conclusion": "success"},
            ]
        )
        self.assertEqual("failed", state)
        self.assertEqual(["Desktop application: failure"], details)

    def test_success_accepts_whichever_scoped_workflows_ran(self):
        state, details, latest = gate.evaluate(
            [
                {"id": 1, "name": "Source boundary", "status": "completed", "conclusion": "success"},
                {"id": 2, "name": "CI", "status": "completed", "conclusion": "success"},
                {"id": 3, "name": "Java reproducibility", "status": "completed", "conclusion": "success"},
                {"id": 4, "name": "Launcher ownership boundary", "status": "completed", "conclusion": "success"},
            ]
        )
        self.assertEqual("success", state)
        self.assertEqual([], details)
        self.assertEqual(
            {"Source boundary", "CI", "Java reproducibility", "Launcher ownership boundary"},
            set(latest),
        )


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


if __name__ == "__main__":
    unittest.main()
