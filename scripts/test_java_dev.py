#!/usr/bin/env python3

from __future__ import annotations

import contextlib
import importlib.util
import io
import subprocess
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("java-dev.py")
SPEC = importlib.util.spec_from_file_location("java_dev", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
java_dev = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(java_dev)


class JavaDevTest(unittest.TestCase):
    def parse(self, *arguments: str):
        return java_dev.parser().parse_args(arguments)

    def test_exact_unit_test_uses_verify_and_required_parents(self):
        command, scope, full = java_dev.command_for(
            self.parse("test", "agent", "AgentOptionsTest#parsesOptions")
        )

        self.assertEqual(command[-1], "verify")
        self.assertIn("-pl", command)
        self.assertIn("preflight-agent", command)
        self.assertIn("-am", command)
        self.assertIn("-Dtest=AgentOptionsTest#parsesOptions", command)
        self.assertIn("exact JUnit", scope)
        self.assertFalse(full)

    def test_packaged_child_test_uses_it_selector(self):
        command, _, _ = java_dev.command_for(
            self.parse("it", "AdapterAgentIT#flushZeroLiveStopRetainsTheStoppingEvent")
        )

        self.assertIn("preflight-cli", command)
        self.assertIn(
            "-Dit.test=AdapterAgentIT#flushZeroLiveStopRetainsTheStoppingEvent", command
        )
        self.assertIn("-Dtest=__PreflightNoUnitTestMatches__", command)

    def test_module_and_dependency_scopes_are_distinct(self):
        module_command, module_scope, _ = java_dev.command_for(
            self.parse("module", "core")
        )
        deps_command, deps_scope, _ = java_dev.command_for(self.parse("deps", "core"))

        self.assertNotIn("-am", module_command)
        self.assertIn("-am", deps_command)
        self.assertIn("only", module_scope)
        self.assertIn("required parents", deps_scope)

    def test_full_parallelism_is_opt_in_and_explicit(self):
        serial, _, full = java_dev.command_for(self.parse("full"))
        parallel, scope, _ = java_dev.command_for(
            self.parse("full", "--threads", "2", "--forks", "4")
        )

        self.assertEqual(serial[-1], "verify")
        self.assertNotIn("-T", serial)
        self.assertNotIn("-DforkCount=4", serial)
        self.assertIn("-T", parallel)
        self.assertIn("-DforkCount=4", parallel)
        self.assertIn("4 test forks", scope)
        self.assertTrue(full)

    def test_focused_output_names_integration_oracle(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            status = java_dev.main(["--dry-run", "deps", "synthetic"])

        self.assertEqual(status, 0)
        self.assertIn("FOCUSED FEEDBACK", output.getvalue())
        self.assertIn("Integration oracle: ./scripts/java-dev.py full", output.getvalue())

    def test_maven_failure_is_returned_to_the_caller(self):
        calls = []

        def fail(command, **kwargs):
            calls.append((command, kwargs))
            return subprocess.CompletedProcess(command, 17)

        with contextlib.redirect_stdout(io.StringIO()):
            status = java_dev.main(["test", "core", "HashingTest"], runner=fail)

        self.assertEqual(status, 17)
        self.assertEqual(calls[0][1]["cwd"], java_dev.ROOT)


if __name__ == "__main__":
    unittest.main()
