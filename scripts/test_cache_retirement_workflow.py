#!/usr/bin/env python3

import unittest
from pathlib import Path


WORKFLOW = (
    Path(__file__).resolve().parents[1]
    / ".github"
    / "workflows"
    / "cache-retirement.yml"
).read_text(encoding="utf-8")


class CacheRetirementWorkflowTest(unittest.TestCase):
    def test_only_closed_pull_requests_trigger_retirement(self):
        self.assertIn("types: [closed]", WORKFLOW)
        self.assertNotIn("pull_request_target", WORKFLOW)
        self.assertNotIn("workflow_dispatch", WORKFLOW)

    def test_scope_is_bound_to_the_closed_pull_request_ref(self):
        self.assertIn('[[ "$PR_NUMBER" =~ ^[1-9][0-9]*$ ]]', WORKFLOW)
        self.assertIn('pr_ref="refs/pull/$PR_NUMBER/merge"', WORKFLOW)
        self.assertIn('gh cache list --ref "$pr_ref"', WORKFLOW)

    def test_only_cache_ids_are_deleted_with_minimal_permission(self):
        self.assertIn("actions: write", WORKFLOW)
        self.assertIn("GH_TOKEN: ${{ github.token }}", WORKFLOW)
        self.assertIn('gh cache delete "$cache_id"', WORKFLOW)
        self.assertNotIn("actions/checkout", WORKFLOW)
        self.assertNotIn("contents: write", WORKFLOW)


if __name__ == "__main__":
    unittest.main()
