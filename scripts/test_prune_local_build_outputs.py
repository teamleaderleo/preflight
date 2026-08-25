#!/usr/bin/env python3

import os
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

import prune_local_build_outputs as prune


class SelectionTest(unittest.TestCase):
    def test_default_cli_does_not_reserve_a_completed_build_set(self):
        with patch.object(sys, "argv", ["prune_local_build_outputs.py"]):
            self.assertEqual(0, prune.parse_args().keep_completed)

    def test_isolated_ui_browser_runtime_is_generated_output(self):
        self.assertIn(
            "preflight-desktop/node_modules/.preflight-ui-layout",
            prune.GENERATED_PATHS,
        )

    def build(
        self,
        name: str,
        age_hours: float,
        *,
        current: bool = False,
        dirty: bool = False,
    ) -> prune.BuildSet:
        now = 1_000_000.0
        root = Path("/worktrees") / name
        return prune.BuildSet(
            root=root,
            outputs=(root / "preflight-desktop/src-tauri/target",),
            newest_mtime=now - age_hours * 3600,
            current=current,
            dirty=dirty,
        )

    def test_current_and_newest_completed_are_retained_but_old_dirty_outputs_are_not(self):
        now = 1_000_000.0
        builds = [
            self.build("current", 100, current=True),
            self.build("dirty", 100, dirty=True),
            self.build("newest", 30),
            self.build("second", 40),
            self.build("old", 50),
        ]
        decisions = prune.choose_build_sets(
            builds,
            now=now,
            keep_completed=1,
            minimum_age_hours=24,
        )
        by_name = {decision.build.root.name: decision for decision in decisions}

        self.assertEqual("keep", by_name["current"].action)
        self.assertEqual("remove", by_name["dirty"].action)
        self.assertIn("source changes remain untouched", by_name["dirty"].reason)
        self.assertEqual("keep", by_name["newest"].action)
        self.assertIn("expires at 72 hours", by_name["newest"].reason)
        self.assertEqual("remove", by_name["second"].action)
        self.assertEqual("remove", by_name["old"].action)

    def test_recent_build_is_retained_even_when_completed_slots_are_zero(self):
        decision = prune.choose_build_sets(
            [self.build("recent", 2)],
            now=1_000_000.0,
            keep_completed=0,
            minimum_age_hours=24,
        )[0]
        self.assertEqual("keep", decision.action)

    def test_newest_completed_build_expires_at_the_hard_retention_limit(self):
        decision = prune.choose_build_sets(
            [self.build("only-completed", 72)],
            now=1_000_000.0,
            keep_completed=1,
            minimum_age_hours=24,
            maximum_age_hours=72,
        )[0]

        self.assertEqual("remove", decision.action)
        self.assertIn("beyond 72-hour retention limit", decision.reason)

    def test_expired_dirty_build_preserves_its_source_changes(self):
        decision = prune.choose_build_sets(
            [self.build("dirty", 96, dirty=True)],
            now=1_000_000.0,
            keep_completed=1,
            minimum_age_hours=24,
            maximum_age_hours=72,
        )[0]

        self.assertEqual("remove", decision.action)
        self.assertIn("source changes remain untouched", decision.reason)

    def test_hard_retention_limit_cannot_undercut_the_recent_build_floor(self):
        with self.assertRaisesRegex(ValueError, "at least minimum_age_hours"):
            prune.choose_build_sets(
                [self.build("recent", 2)],
                now=1_000_000.0,
                keep_completed=1,
                minimum_age_hours=24,
                maximum_age_hours=12,
            )

    def test_explicit_retirement_removes_clean_current_output_without_waiting(self):
        decision = prune.choose_build_sets(
            [self.build("current", 0, current=True)],
            now=1_000_000.0,
            keep_completed=1,
            minimum_age_hours=24,
            retire_current=True,
        )[0]

        self.assertEqual("remove", decision.action)
        self.assertEqual("explicitly retiring clean current worktree", decision.reason)

    def test_explicit_retirement_keeps_a_current_worktree_with_source_changes(self):
        decision = prune.choose_build_sets(
            [self.build("current", 100, current=True, dirty=True)],
            now=1_000_000.0,
            keep_completed=0,
            minimum_age_hours=0,
            retire_current=True,
        )[0]

        self.assertEqual("keep", decision.action)
        self.assertEqual("current worktree has source changes", decision.reason)

    def test_age_boundary_is_fail_safe(self):
        now = 1_000_000.0
        decision = prune.choose_build_sets(
            [self.build("boundary", 24)],
            now=now,
            keep_completed=0,
            minimum_age_hours=24,
        )[0]
        self.assertEqual("remove", decision.action)

    def test_binary_sizes_use_readable_units(self):
        self.assertEqual("0.0 B", prune.format_bytes(0))
        self.assertEqual("1.5 KiB", prune.format_bytes(1536))
        self.assertEqual("2.0 GiB", prune.format_bytes(2 * 1024 ** 3))

    def test_nested_binary_timestamp_defines_build_activity(self):
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary) / "target"
            binary = target / "release" / "deps" / "preflight"
            binary.parent.mkdir(parents=True)
            binary.write_bytes(b"binary")
            old = time.time() - 72 * 3600
            os.utime(target, (old, old))

            total_bytes, newest_mtime = prune.output_metrics(target)

            self.assertGreaterEqual(total_bytes, len(b"binary"))
            self.assertGreater(newest_mtime, old + 48 * 3600)


class RemovalTest(unittest.TestCase):
    def test_removes_only_named_output_directories(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            target = root / "preflight-cli" / "target"
            target.mkdir(parents=True)
            (target / "preflight.jar").write_bytes(b"binary")
            source = root / "preflight-cli" / "src" / "Keep.java"
            source.parent.mkdir(parents=True)
            source.write_text("class Keep {}", encoding="utf-8")
            build = prune.BuildSet(root, (target,), time.time())

            prune.remove_outputs(build)

            self.assertFalse(target.exists())
            self.assertEqual("class Keep {}", source.read_text(encoding="utf-8"))

    def test_refuses_symlinked_output(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            ordinary = root / "preflight-core" / "target"
            ordinary.mkdir(parents=True)
            external = root / "external"
            external.mkdir()
            target = root / "preflight-cli" / "target"
            target.parent.mkdir(parents=True)
            target.symlink_to(external, target_is_directory=True)
            build = prune.BuildSet(root, (ordinary, target), time.time())

            with self.assertRaisesRegex(RuntimeError, "symlinked"):
                prune.remove_outputs(build)
            self.assertTrue(ordinary.exists())
            self.assertTrue(external.exists())


if __name__ == "__main__":
    unittest.main()
