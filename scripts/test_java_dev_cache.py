#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


SCRIPT = Path(__file__).with_name("java-dev-cache.py")
SPEC = importlib.util.spec_from_file_location("java_dev_cache", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
java_dev_cache = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = java_dev_cache
SPEC.loader.exec_module(java_dev_cache)


class JavaDevCacheTest(unittest.TestCase):
    def valid_cache(self, root: Path, identity: str = "a" * 64) -> tuple[Path, Path]:
        namespace = (
            root
            / java_dev_cache.REUSE_FORMAT
            / "entries"
            / identity
            / java_dev_cache.APACHE_IMPLEMENTATION_VERSION
            / "dev.starsector.preflight"
            / "preflight-core"
            / ("b" * 64)
            / "local"
        )
        namespace.mkdir(parents=True)
        (namespace / "buildinfo.xml").write_text("<build/>", encoding="utf-8")
        (namespace / "preflight-core.jar").write_bytes(b"artifact")
        locks = root / java_dev_cache.REUSE_FORMAT / "locks"
        locks.mkdir()
        lock = locks / f"cache-{identity}.lock"
        lock.touch()
        (locks / f"worktree-{'c' * 64}.lock").touch()
        return namespace, lock

    def test_absent_cache_is_a_complete_zero_state_and_is_not_created(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve() / "absent"

            report, status = java_dev_cache.inspect_cache(root)

            self.assertEqual(status, 0)
            self.assertEqual(report["status"], "absent")
            self.assertTrue(report["inventoryComplete"])
            self.assertFalse(root.exists())

    def test_existing_empty_root_is_distinct_from_an_absent_root(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve() / "cache"
            root.mkdir()

            report, status = java_dev_cache.inspect_cache(root)

            self.assertEqual(status, 0)
            self.assertEqual(report["status"], "empty")
            self.assertFalse(report["currentFormatPresent"])

    def test_incomplete_current_format_is_refused(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve() / "cache"
            (root / java_dev_cache.REUSE_FORMAT).mkdir(parents=True)

            report, status = java_dev_cache.inspect_cache(root)

            self.assertEqual(status, 2)
            self.assertEqual(report["status"], "refused")
            self.assertEqual(
                {item["code"] for item in report["anomalies"]},
                {"entriesDirectoryMissing", "locksDirectoryMissing"},
            )

    def test_valid_cache_reports_opaque_namespace_layout_and_bytes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve() / "cache"
            self.valid_cache(root)

            report, status = java_dev_cache.inspect_cache(root)

            self.assertEqual(status, 0)
            self.assertEqual(report["status"], "ok")
            self.assertFalse(report["selectorNamesAvailable"])
            self.assertFalse(report["retentionAuthority"])
            self.assertEqual(report["deletionCandidates"], [])
            self.assertEqual(len(report["namespaces"]), 1)
            namespace = report["namespaces"][0]
            self.assertEqual(namespace["projectCount"], 1)
            self.assertEqual(namespace["generationCount"], 1)
            self.assertEqual(namespace["buildInfoFiles"], 1)
            self.assertEqual(namespace["projects"][0]["groupId"], "dev.starsector.preflight")
            self.assertEqual(namespace["projects"][0]["artifactId"], "preflight-core")
            self.assertEqual(namespace["projects"][0]["generationCount"], 1)
            self.assertEqual(namespace["projects"][0]["generationsWithoutBuildInfo"], 0)
            self.assertEqual(namespace["lockState"], "sharedLockAcquired")
            self.assertEqual(report["locks"]["cache"]["sharedLockAcquired"], 1)
            self.assertEqual(report["locks"]["worktree"]["sharedLockAcquired"], 1)
            self.assertGreaterEqual(report["totals"]["logicalFileBytes"], len("<build/>") + 8)
            self.assertIn("not last access", report["timestampSemantics"])

    def test_symlink_is_reported_without_following_its_target(self):
        with tempfile.TemporaryDirectory() as temporary:
            temporary_root = Path(temporary).resolve()
            root = temporary_root / "cache"
            namespace, _ = self.valid_cache(root)
            outside = temporary_root / "outside"
            outside.write_bytes(b"x" * 10_000)
            (namespace / "linked-output").symlink_to(outside)

            report, status = java_dev_cache.inspect_cache(root)

            self.assertEqual(status, 2)
            self.assertEqual(report["status"], "refused")
            self.assertIn("symlinkRefused", {item["code"] for item in report["anomalies"]})
            self.assertLess(report["totals"]["logicalFileBytes"], 10_000)

    def test_symlinked_root_is_refused(self):
        with tempfile.TemporaryDirectory() as temporary:
            temporary_root = Path(temporary).resolve()
            target = temporary_root / "target"
            self.valid_cache(target)
            root = temporary_root / "linked-cache"
            root.symlink_to(target, target_is_directory=True)

            report, status = java_dev_cache.inspect_cache(root)

            self.assertEqual(status, 2)
            self.assertEqual(report["status"], "refused")
            self.assertIn(
                "symlinkedRootComponentRefused",
                {item["code"] for item in report["anomalies"]},
            )

    def test_symlinked_root_ancestor_is_refused_before_traversal(self):
        with tempfile.TemporaryDirectory() as temporary:
            temporary_root = Path(temporary).resolve()
            real_parent = temporary_root / "real-parent"
            target = real_parent / "cache"
            self.valid_cache(target)
            linked_parent = temporary_root / "linked-parent"
            linked_parent.symlink_to(real_parent, target_is_directory=True)

            report, status = java_dev_cache.inspect_cache(linked_parent / "cache")

            self.assertEqual(status, 2)
            self.assertEqual(report["scannedEntries"], 0)
            self.assertIn(
                "symlinkedRootComponentRefused",
                {item["code"] for item in report["anomalies"]},
            )

    def test_future_format_and_apache_version_fail_closed(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve() / "cache"
            namespace, _ = self.valid_cache(root)
            (root / "preflight-java-dev-reuse-v2").mkdir()
            version = namespace.parents[3]
            (version.parent / "v9.0").mkdir()

            report, status = java_dev_cache.inspect_cache(root)

            self.assertEqual(status, 2)
            codes = {item["code"] for item in report["anomalies"]}
            self.assertIn("unknownCacheFormat", codes)
            self.assertIn("unknownApacheImplementation", codes)

    def test_busy_namespace_is_not_scanned_or_called_stable(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve() / "cache"
            namespace, lock = self.valid_cache(root)
            secret = namespace / "must-not-be-counted.bin"
            secret.write_bytes(b"x" * 20_000)
            holder = subprocess.Popen(
                [
                    sys.executable,
                    "-c",
                    "import fcntl,sys; f=open(sys.argv[1]); "
                    "fcntl.flock(f,fcntl.LOCK_EX); print('ready',flush=True); sys.stdin.read(1)",
                    str(lock),
                ],
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                text=True,
            )
            try:
                assert holder.stdout is not None
                self.assertEqual(holder.stdout.readline().strip(), "ready")
                report, status = java_dev_cache.inspect_cache(root)
            finally:
                assert holder.stdin is not None
                holder.stdin.write("x")
                holder.stdin.close()
                holder.wait(timeout=5)
                holder.stdout.close()

            self.assertEqual(status, 2)
            self.assertFalse(report["inventoryComplete"])
            self.assertEqual(report["namespaces"][0]["lockState"], "exclusiveWriterObserved")
            self.assertEqual(report["locks"]["cache"]["exclusiveWriterObserved"], 1)
            self.assertLess(report["totals"]["logicalFileBytes"], secret.stat().st_size)

    def test_missing_lock_and_lock_without_namespace_are_both_visible(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve() / "cache"
            _, lock = self.valid_cache(root)
            lock.unlink()
            orphan = root / java_dev_cache.REUSE_FORMAT / "locks" / f"cache-{'d' * 64}.lock"
            orphan.touch()

            report, status = java_dev_cache.inspect_cache(root)

            self.assertEqual(status, 2)
            codes = {item["code"] for item in report["anomalies"]}
            self.assertIn("namespaceLockMissing", codes)
            self.assertIn("cacheLockWithoutNamespace", codes)
            self.assertEqual(report["locks"]["cache"]["missingForNamespace"], 1)
            self.assertEqual(report["locks"]["cache"]["withoutNamespace"], 1)

    def test_generation_without_exact_build_info_is_incomplete(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve() / "cache"
            namespace, _ = self.valid_cache(root)
            (namespace / "buildinfo.xml").unlink()

            report, status = java_dev_cache.inspect_cache(root)

            self.assertEqual(status, 2)
            self.assertIn(
                "generationWithoutBuildInfo",
                {item["code"] for item in report["anomalies"]},
            )
            self.assertEqual(
                report["namespaces"][0]["projects"][0]["generationsWithoutBuildInfo"],
                1,
            )

    def test_entry_budget_refuses_instead_of_walking_an_unbounded_tree(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve() / "cache"
            self.valid_cache(root)

            with mock.patch.object(java_dev_cache, "MAX_ENTRIES", 3):
                report, status = java_dev_cache.inspect_cache(root)

            self.assertEqual(status, 2)
            self.assertLessEqual(report["scannedEntries"], 3)
            self.assertIn(
                "entryLimitExceeded",
                {item["code"] for item in report["anomalies"]},
            )

    def test_relative_roots_are_refused(self):
        with self.assertRaisesRegex(ValueError, "must be absolute"):
            java_dev_cache.inspect_cache(Path("relative/cache"))

    def test_cache_root_matches_helper_override_contract(self):
        self.assertEqual(
            java_dev_cache.cache_root({"PREFLIGHT_JAVA_DEV_CACHE": "/tmp/exact-cache"}),
            Path("/tmp/exact-cache"),
        )
        with self.assertRaisesRegex(ValueError, "must be absolute"):
            java_dev_cache.cache_root({"PREFLIGHT_JAVA_DEV_CACHE": "relative/cache"})

    def test_inspector_contract_matches_the_reuse_helper(self):
        helper = SCRIPT.with_name("java-dev.py").read_text(encoding="utf-8")

        self.assertIn(f'REUSE_FORMAT = "{java_dev_cache.REUSE_FORMAT}"', helper)
        self.assertIn(
            f'REUSE_EXTENSION_VERSION = "{java_dev_cache.APACHE_EXTENSION_VERSION}"',
            helper,
        )


if __name__ == "__main__":
    unittest.main()
