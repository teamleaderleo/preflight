"""The operator checks are a hand-written list, so a test file can exist and never run.

Both failures below were live on 2026-08-15. `test_startup_benchmark.py`,
`test_starsector_log_load_times.py`, and `test_verify_linux_glibc_floor.py` were never
invoked by any workflow, and `test_startup_benchmark.py` also placed `unittest.main()`
above four of its own classes, so those four did not run even when the file was executed
directly. Neither failure is visible from a green run: an unreferenced suite and a
suite that silently drops half its cases both report success.

Keeping the workflow list explicit is deliberate -- it shows what CI runs -- so this
guards the list rather than replacing it with discovery.
"""

import ast
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent
WORKFLOWS = SCRIPTS.parent / ".github" / "workflows"


def test_files() -> list[Path]:
    return sorted(SCRIPTS.glob("test_*.py"))


def workflow_text() -> str:
    return "\n".join(
        path.read_text(encoding="utf-8")
        for path in sorted(WORKFLOWS.glob("*.yml"))
    )


class WorkflowCoverageTest(unittest.TestCase):
    def test_every_script_test_is_invoked_by_a_workflow(self):
        text = workflow_text()
        unreferenced = [path.name for path in test_files() if path.name not in text]
        self.assertEqual(
            [], unreferenced,
            "these test files never run in CI; add them to a workflow or delete them: "
            + ", ".join(unreferenced),
        )


class MainPlacementTest(unittest.TestCase):
    def test_no_test_class_is_defined_below_unittest_main(self):
        offenders = []
        for path in test_files():
            tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
            guard = None
            for node in tree.body:
                if isinstance(node, ast.If) and "__main__" in ast.dump(node.test):
                    guard = node.lineno
            if guard is None:
                continue
            for node in tree.body:
                if isinstance(node, ast.ClassDef) and node.lineno > guard:
                    offenders.append(f"{path.name}:{node.lineno} {node.name}")
        self.assertEqual(
            [], offenders,
            "these classes are defined after unittest.main() and never run when the file "
            "is executed directly: " + ", ".join(offenders),
        )


if __name__ == "__main__":
    unittest.main()
