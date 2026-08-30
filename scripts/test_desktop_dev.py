#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


SCRIPT = Path(__file__).with_name("desktop-dev.py")
SPEC = importlib.util.spec_from_file_location("desktop_dev", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
desktop_dev = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = desktop_dev
SPEC.loader.exec_module(desktop_dev)


class DesktopDevTest(unittest.TestCase):
    def fixture(self) -> tuple[tempfile.TemporaryDirectory[str], Path, Path, Path]:
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name).resolve() / "repository"
        desktop = root / "preflight-desktop"
        source = desktop / "src"
        source.mkdir(parents=True)
        return temporary, root, desktop, source

    def select(self, paths: list[str], root: Path, desktop: Path, source: Path):
        return desktop_dev.select_frontend_tests(
            paths,
            root=root,
            desktop=desktop,
            source_root=source,
        )

    def test_source_selects_and_sorts_all_adjacent_tests(self):
        temporary, root, desktop, source = self.fixture()
        self.addCleanup(temporary.cleanup)
        owner = source / "Panel.tsx"
        owner.touch()
        ordinary = source / "Panel.test.tsx"
        recovery = source / "Panel.recovery.test.tsx"
        unrelated = source / "Other.test.tsx"
        for path in (ordinary, recovery, unrelated):
            path.touch()

        selected = self.select(["src/Panel.tsx"], root, desktop, source)

        self.assertEqual(selected, sorted([ordinary, recovery]))

    def test_exact_tests_and_multiple_sources_form_one_deduplicated_batch(self):
        temporary, root, desktop, source = self.fixture()
        self.addCleanup(temporary.cleanup)
        first_source = source / "first.ts"
        first_test = source / "first.test.ts"
        second_test = source / "second.test.tsx"
        for path in (first_source, first_test, second_test):
            path.touch()

        selected = self.select(
            [
                "preflight-desktop/src/first.ts",
                "src/first.test.ts",
                "src/second.test.tsx",
            ],
            root,
            desktop,
            source,
        )

        self.assertEqual(selected, [first_test, second_test])

    def test_one_unmapped_source_refuses_the_entire_batch(self):
        temporary, root, desktop, source = self.fixture()
        self.addCleanup(temporary.cleanup)
        (source / "mapped.ts").touch()
        (source / "mapped.test.ts").touch()
        (source / "unmapped.ts").touch()

        with self.assertRaisesRegex(desktop_dev.SelectionError, "unmapped.ts"):
            self.select(["src/mapped.ts", "src/unmapped.ts"], root, desktop, source)

    def test_out_of_tree_and_non_typescript_paths_are_refused(self):
        temporary, root, desktop, source = self.fixture()
        self.addCleanup(temporary.cleanup)
        outside = root / "outside.ts"
        outside.touch()
        text = source / "notes.txt"
        text.touch()

        with self.assertRaisesRegex(desktop_dev.SelectionError, "escapes"):
            self.select([str(outside)], root, desktop, source)
        with self.assertRaisesRegex(desktop_dev.SelectionError, "not TypeScript"):
            self.select(["src/notes.txt"], root, desktop, source)

    def test_symlinked_input_is_refused(self):
        temporary, root, desktop, source = self.fixture()
        self.addCleanup(temporary.cleanup)
        target = source / "real.ts"
        target.touch()
        linked = source / "linked.ts"
        try:
            linked.symlink_to(target)
        except OSError as error:
            self.skipTest(f"symlinks unavailable: {error}")

        with self.assertRaisesRegex(desktop_dev.SelectionError, "symbolic link"):
            self.select(["src/linked.ts"], root, desktop, source)

    def test_runner_uses_exact_inventory_without_a_shell(self):
        temporary, _, desktop, source = self.fixture()
        self.addCleanup(temporary.cleanup)
        test = source / "format.test.ts"
        test.touch()
        vitest_package = desktop / "node_modules" / "vitest" / "package.json"
        vitest_package.parent.mkdir(parents=True)
        vitest_package.touch()
        completed = subprocess.CompletedProcess([], 7)
        runner = mock.Mock(return_value=completed)

        status = desktop_dev.run_frontend_tests(
            [test],
            desktop=desktop,
            runner=runner,
            which=lambda _: "/tools/npm",
        )

        self.assertEqual(status, 7)
        runner.assert_called_once_with(
            ["/tools/npm", "test", "--", "src/format.test.ts"],
            cwd=desktop,
        )

    def test_missing_dependencies_refuse_before_spawning(self):
        temporary, _, desktop, source = self.fixture()
        self.addCleanup(temporary.cleanup)
        test = source / "format.test.ts"
        test.touch()
        runner = mock.Mock()

        with self.assertRaisesRegex(desktop_dev.SelectionError, "npm ci"):
            desktop_dev.run_frontend_tests([test], desktop=desktop, runner=runner)

        runner.assert_not_called()


if __name__ == "__main__":
    unittest.main()
