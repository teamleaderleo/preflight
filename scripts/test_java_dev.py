#!/usr/bin/env python3

from __future__ import annotations

import contextlib
import importlib.util
import io
import subprocess
import tempfile
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

    def test_reuse_is_literal_and_adds_mandatory_clean(self):
        command, _, _ = java_dev.command_for(
            self.parse("test", "core", "HashesTest#hashesBytes", "--reuse")
        )

        self.assertEqual(command[-2:], ["clean", "verify"])
        with self.assertRaisesRegex(ValueError, "one literal Java class"):
            java_dev.command_for(
                self.parse("test", "core", "HashesTest,ContentFingerprintTest", "--reuse")
            )
        with self.assertRaisesRegex(ValueError, "one literal Java class"):
            java_dev.command_for(self.parse("test", "core", "*Test", "--reuse"))

    def test_reuse_identity_partitions_selector_toolchain_and_policy(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / ".mvn/wrapper").mkdir(parents=True)
            (root / "mvnw").write_text("wrapper-a", encoding="utf-8")
            (root / ".mvn/maven.config").write_text("--also-make\n", encoding="utf-8")
            (root / ".mvn/wrapper/maven-wrapper.properties").write_text(
                "distributionUrl=https://example.invalid/maven-a.zip\n", encoding="utf-8"
            )
            command = [*java_dev.MAVEN_PREFIX, "-pl", "preflight-core", "clean", "verify"]
            first, payload = java_dev.reuse_identity(
                "preflight-core",
                "HashesTest",
                command,
                {"executable": "/jdk/a/java", "probeSha256": "a" * 64},
                {"MAVEN_OPTS": "-Xmx1g"},
                root,
            )
            selector_change, _ = java_dev.reuse_identity(
                "preflight-core",
                "ContentFingerprintTest",
                command,
                {"executable": "/jdk/a/java", "probeSha256": "a" * 64},
                {"MAVEN_OPTS": "-Xmx1g"},
                root,
            )
            toolchain_change, _ = java_dev.reuse_identity(
                "preflight-core",
                "HashesTest",
                command,
                {"executable": "/jdk/b/java", "probeSha256": "b" * 64},
                {"MAVEN_OPTS": "-Xmx1g"},
                root,
            )
            environment_change, _ = java_dev.reuse_identity(
                "preflight-core",
                "HashesTest",
                command,
                {"executable": "/jdk/a/java", "probeSha256": "a" * 64},
                {"MAVEN_OPTS": "-Xmx1g", "CUSTOM_TEST_INPUT": "changed"},
                root,
            )

        self.assertEqual(payload["format"], java_dev.REUSE_FORMAT)
        self.assertEqual(len(first), 64)
        self.assertNotEqual(first, selector_change)
        self.assertNotEqual(first, toolchain_change)
        self.assertNotEqual(first, environment_change)

    def test_reuse_environment_excludes_only_helper_controls(self):
        inherited = {
            "MAVEN_BASEDIR": "/wrong/base",
            "PREFLIGHT_JAVA_DEV_CACHE": "/private/cache",
            "CUSTOM_TEST_INPUT": "visible-to-test",
        }

        normalized = java_dev.reuse_maven_environment(inherited)

        self.assertEqual(normalized, {"CUSTOM_TEST_INPUT": "visible-to-test"})
        self.assertEqual(inherited["PREFLIGHT_JAVA_DEV_CACHE"], "/private/cache")

    def test_reuse_classification_requires_report_or_exact_restore(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            reports = root / "preflight-core/target/surefire-reports"
            reports.mkdir(parents=True)
            report = reports / "TEST-dev.starsector.preflight.core.HashesTest.xml"
            report.write_text(
                '<testsuite name="dev.starsector.preflight.core.HashesTest">'
                '<testcase name="hashesBytes"/></testsuite>',
                encoding="utf-8",
            )
            disposition, found = java_dev.classify_reuse(
                "preflight-core", "HashesTest#hashesBytes", "", root
            )
            report.unlink()
            restored, none = java_dev.classify_reuse(
                "preflight-core",
                "HashesTest",
                "[INFO] ----< dev.starsector.preflight:preflight-core >----\n"
                "Found cached build, restoring dev.starsector.preflight:preflight-core "
                "from cache by checksum abc\n"
                "Skipping plugin execution (cached): surefire:test",
                root,
            )
            wrong_module_skip, _ = java_dev.classify_reuse(
                "preflight-core",
                "HashesTest",
                "[INFO] ----< dev.starsector.preflight:a-dependency >----\n"
                "Skipping plugin execution (cached): surefire:test\n"
                "[INFO] ----< dev.starsector.preflight:preflight-core >----\n"
                "Found cached build, restoring dev.starsector.preflight:preflight-core "
                "from cache by checksum abc\n"
                "[INFO] Reactor Summary",
                root,
            )
            unknown, _ = java_dev.classify_reuse(
                "preflight-core", "HashesTest", "BUILD SUCCESS", root
            )

        self.assertEqual(disposition, "executed")
        self.assertEqual(len(found), 1)
        self.assertEqual(restored, "reused")
        self.assertEqual(none, [])
        self.assertEqual(wrong_module_skip, "not_established")
        self.assertEqual(unknown, "not_established")

    def test_reuse_configuration_stays_in_external_base(self):
        with tempfile.TemporaryDirectory() as repository, tempfile.TemporaryDirectory() as temp:
            root = Path(repository)
            (root / ".mvn").mkdir()
            (root / ".mvn/maven.config").write_text("--also-make\n", encoding="utf-8")
            configuration = java_dev.prepare_extension_base(Path(temp), root)

            self.assertTrue((configuration / "extensions.xml").is_file())
            self.assertEqual(
                (configuration / "maven.config").read_text(encoding="utf-8"),
                "--also-make\n",
            )
            self.assertFalse((root / ".mvn/extensions.xml").exists())

    def test_cache_override_must_be_absolute(self):
        with self.assertRaisesRegex(RuntimeError, "must be absolute"):
            java_dev.cache_root({"PREFLIGHT_JAVA_DEV_CACHE": "relative/cache"})
        self.assertEqual(
            java_dev.cache_root({"PREFLIGHT_JAVA_DEV_CACHE": "/tmp/preflight-java-cache"}),
            Path("/tmp/preflight-java-cache"),
        )

    def test_invalid_reuse_selector_is_a_clean_refusal(self):
        errors = io.StringIO()
        with contextlib.redirect_stderr(errors):
            status = java_dev.main(
                ["--dry-run", "test", "core", "HashesTest,*", "--reuse"]
            )

        self.assertEqual(status, 2)
        self.assertIn("Refused:", errors.getvalue())


if __name__ == "__main__":
    unittest.main()
