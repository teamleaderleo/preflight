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
    def test_default_cli_does_not_reserve_a_completed_worktree(self):
        with patch.object(sys, "argv", ["prune_local_build_outputs.py"]):
            self.assertEqual(0, prune.parse_args().keep_completed)

    def test_isolated_ui_browser_runtime_is_generated_output(self):
        self.assertIn(
            "preflight-desktop/node_modules/.preflight-ui-layout",
            prune.GENERATED_PATHS,
        )

    def test_python_operator_bytecode_is_generated_output(self):
        self.assertIn("scripts/__pycache__", prune.GENERATED_PATHS)
        self.assertIn("preflight-desktop/scripts/__pycache__", prune.GENERATED_PATHS)
        self.assertIn("probe-kits/gpu-capability/__pycache__", prune.GENERATED_PATHS)
        self.assertIn("docs/design/hangar-light/__pycache__", prune.GENERATED_PATHS)

    def test_probe_binaries_and_generated_native_metadata_are_generated_output(self):
        self.assertIn("probe-kits/gpu-capability/.probe-build", prune.GENERATED_PATHS)
        self.assertIn(
            "probe-kits/gpu-capability/block-conformance-vector.bin",
            prune.GENERATED_PATHS,
        )
        self.assertIn("probe-kits/texture-pipeline/.probe-build", prune.GENERATED_PATHS)
        self.assertIn("preflight-desktop/src-tauri/gen", prune.GENERATED_PATHS)
        self.assertIn(
            "probe-kits/gpu-capability/gpu-capability-report-*.txt",
            prune.GENERATED_GLOBS,
        )

    def test_ignored_wrapper_and_generated_icon_outputs_are_bounded(self):
        self.assertIn(".wrangler", prune.GENERATED_PATHS)
        self.assertIn("report-intake/.wrangler", prune.GENERATED_PATHS)
        self.assertIn("preflight-desktop/.wrangler", prune.GENERATED_PATHS)
        self.assertIn("preflight-desktop/src-tauri/icons/64x64.png", prune.GENERATED_PATHS)
        self.assertIn("preflight-desktop/src-tauri/icons/StoreLogo.png", prune.GENERATED_PATHS)
        self.assertIn("preflight-desktop/src-tauri/icons/android", prune.GENERATED_PATHS)
        self.assertIn("preflight-desktop/src-tauri/icons/ios", prune.GENERATED_PATHS)
        self.assertIn(
            "preflight-desktop/src-tauri/icons/Square*Logo.png",
            prune.GENERATED_GLOBS,
        )

    def test_generated_store_icon_glob_does_not_select_maintained_source_artwork(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            icons = root / "preflight-desktop" / "src-tauri" / "icons"
            icons.mkdir(parents=True)
            generated = icons / "Square150x150Logo.png"
            generated.write_bytes(b"generated")
            maintained = icons / "icon.png"
            maintained.write_bytes(b"maintained")

            outputs = prune.rebuildable_outputs(root, root)

            self.assertIn(generated, outputs)
            self.assertNotIn(maintained, outputs)

    def test_timestamped_probe_reports_are_selected_without_matching_source_notes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            probe = root / "probe-kits" / "gpu-capability"
            probe.mkdir(parents=True)
            report = probe / "gpu-capability-report-20260826-120000.txt"
            report.write_text("measurement", encoding="utf-8")
            notes = probe / "gpu-capability-report-notes.md"
            notes.write_text("keep", encoding="utf-8")

            outputs = prune.rebuildable_outputs(root, root)

            self.assertIn(report, outputs)
            self.assertNotIn(notes, outputs)

    def test_duplicate_dependencies_are_bounded_only_outside_the_current_worktree(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary).resolve()
            current = base / "current"
            sibling = base / "sibling"
            current_dependencies = current / "preflight-desktop" / "node_modules"
            sibling_dependencies = sibling / "preflight-desktop" / "node_modules"
            current_dependencies.mkdir(parents=True)
            sibling_dependencies.mkdir(parents=True)
            current_isolated_runtime = current_dependencies / ".preflight-ui-layout"
            current_isolated_runtime.mkdir()

            self.assertNotIn(current_dependencies, prune.rebuildable_outputs(current, current))
            self.assertIn(current_isolated_runtime, prune.rebuildable_outputs(current, current))
            self.assertIn(sibling_dependencies, prune.rebuildable_outputs(sibling, current))

    def test_discovery_ages_generated_outputs_independently(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary).resolve()
            current = base / "current"
            current.mkdir()
            sibling = base / "sibling"
            old_target = sibling / "preflight-cli" / "target"
            old_binary = old_target / "preflight.jar"
            old_binary.parent.mkdir(parents=True)
            old_binary.write_bytes(b"old binary")
            source = sibling / "preflight-cli" / "src" / "Keep.java"
            source.parent.mkdir(parents=True)
            source.write_text("class Keep {}", encoding="utf-8")
            recent_cache = sibling / "scripts" / "__pycache__"
            recent_bytecode = recent_cache / "operator.pyc"
            recent_bytecode.parent.mkdir(parents=True)
            recent_bytecode.write_bytes(b"recent bytecode")
            now = time.time()
            old = now - 100 * 3600
            recent = now - 2 * 3600
            os.utime(old_binary, (old, old))
            os.utime(old_target, (old, old))
            os.utime(recent_bytecode, (recent, recent))
            os.utime(recent_cache, (recent, recent))

            with (
                patch.object(prune, "registered_worktrees", return_value=[sibling]),
                patch.object(prune, "has_source_changes", return_value=True),
            ):
                builds = prune.discover_build_sets(current)

            self.assertEqual(2, len(builds))
            decisions = prune.choose_build_sets(
                builds,
                now=now,
                keep_completed=0,
                minimum_age_hours=24,
                maximum_age_hours=72,
            )
            by_output = {
                decision.build.outputs[0].relative_to(sibling).as_posix(): decision
                for decision in decisions
            }
            self.assertEqual("remove", by_output["preflight-cli/target"].action)
            self.assertIn(
                "source changes remain untouched",
                by_output["preflight-cli/target"].reason,
            )
            self.assertEqual("keep", by_output["scripts/__pycache__"].action)

            prune.remove_outputs(by_output["preflight-cli/target"].build)

            self.assertFalse(old_target.exists())
            self.assertTrue(recent_cache.exists())
            self.assertEqual("class Keep {}", source.read_text(encoding="utf-8"))

    def test_parent_dependency_tree_replaces_nested_generated_output(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary).resolve()
            current = base / "current"
            sibling = base / "sibling"
            dependency_tree = sibling / "preflight-desktop" / "node_modules"
            nested_output = dependency_tree / ".preflight-ui-layout"
            nested_output.mkdir(parents=True)

            outputs = prune.rebuildable_outputs(sibling, current)

            self.assertIn(dependency_tree, outputs)
            self.assertNotIn(nested_output, outputs)

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

    def test_completed_slots_retain_distinct_worktrees_not_individual_outputs(self):
        now = 1_000_000.0
        first_root = Path("/worktrees/first")
        second_root = Path("/worktrees/second")
        builds = [
            prune.BuildSet(
                first_root,
                (first_root / "preflight-cli/target",),
                now - 30 * 3600,
            ),
            prune.BuildSet(
                first_root,
                (first_root / "scripts/__pycache__",),
                now - 31 * 3600,
            ),
            prune.BuildSet(
                second_root,
                (second_root / "preflight-cli/target",),
                now - 32 * 3600,
            ),
        ]

        decisions = prune.choose_build_sets(
            builds,
            now=now,
            keep_completed=2,
            minimum_age_hours=24,
        )

        self.assertTrue(all(decision.action == "keep" for decision in decisions))

    def test_retained_worktree_does_not_extend_an_individual_output_past_hard_limit(self):
        now = 1_000_000.0
        root = Path("/worktrees/retained")
        builds = [
            prune.BuildSet(
                root,
                (root / "preflight-cli/target",),
                now - 100 * 3600,
            ),
            prune.BuildSet(
                root,
                (root / "scripts/__pycache__",),
                now - 30 * 3600,
            ),
        ]

        decisions = prune.choose_build_sets(
            builds,
            now=now,
            keep_completed=1,
            minimum_age_hours=24,
            maximum_age_hours=72,
        )
        by_output = {decision.build.outputs[0].name: decision for decision in decisions}

        self.assertEqual("remove", by_output["target"].action)
        self.assertEqual("keep", by_output["__pycache__"].action)
        self.assertIn("retained clean worktree", by_output["__pycache__"].reason)

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

    def test_single_generated_file_contributes_its_bytes_and_timestamp(self):
        with tempfile.TemporaryDirectory() as temporary:
            binary = Path(temporary) / "block-conformance-vector.bin"
            binary.write_bytes(b"vector")

            total_bytes, newest_mtime = prune.output_metrics(binary)

            self.assertEqual(len(b"vector"), total_bytes)
            self.assertEqual(binary.stat().st_mtime, newest_mtime)


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

    def test_removes_named_generated_files_and_directories_together(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            build_directory = root / "probe-kits" / "gpu-capability" / ".probe-build"
            build_directory.mkdir(parents=True)
            (build_directory / "Probe.class").write_bytes(b"class")
            vector = root / "probe-kits" / "gpu-capability" / "block-conformance-vector.bin"
            vector.write_bytes(b"vector")
            source = root / "probe-kits" / "gpu-capability" / "Probe.java"
            source.write_text("class Probe {}", encoding="utf-8")
            build = prune.BuildSet(root, (build_directory, vector), time.time())

            prune.remove_outputs(build)

            self.assertFalse(build_directory.exists())
            self.assertFalse(vector.exists())
            self.assertEqual("class Probe {}", source.read_text(encoding="utf-8"))

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
