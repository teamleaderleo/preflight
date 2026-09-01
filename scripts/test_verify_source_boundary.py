import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("verify_source_boundary.py")
spec = importlib.util.spec_from_file_location("verify_source_boundary", MODULE_PATH)
assert spec and spec.loader
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class SourceBoundaryTest(unittest.TestCase):
    def git(self, repository: Path, *args: str) -> None:
        subprocess.run(
            ["git", *args],
            cwd=repository,
            check=True,
            text=True,
            capture_output=True,
        )

    def init_repository(self, repository: Path) -> None:
        self.git(repository, "init", "-b", "main")
        self.git(repository, "config", "user.name", "Source Boundary Test")
        self.git(repository, "config", "user.email", "source-boundary@example.invalid")

    def test_rejects_game_content_paths(self):
        for path in (
            "mods/example/data/config/settings.json",
            "saves/save_Example/campaign.xml",
            "capture/starsector.log",
            "lib/starfarer.api.jar",
            "crashes/java.hprof",
            "fixtures/copied-mod.ship",
        ):
            with self.subTest(path=path), self.assertRaises(module.SourceBoundaryError):
                module.validate_path(path)

    def test_rejects_unexpected_binary(self):
        with self.assertRaisesRegex(module.SourceBoundaryError, "unexpected binary"):
            module.validate_blob("docs/capture.dat", b"prefix\x00private")

    def test_allows_reviewed_desktop_icons(self):
        module.validate_blob("preflight-desktop/src-tauri/icons/icon.png", b"\x89PNG\r\n\x1a\n\x00")
        module.validate_blob("preflight-desktop/src/assets/mark.png", b"\x89PNG\r\n\x1a\n\x00")

    def test_allows_only_an_exact_reviewed_oversized_blob(self):
        name = "preflight-desktop/src-tauri/icons/synthetic-reviewed.icns"
        data = b"x" * (module.MAX_REVIEWED_BLOB_BYTES + 1)
        module.REVIEWED_OVERSIZED_BLOBS[name] = frozenset(
            {(len(data), module.hashlib.sha256(data).hexdigest())}
        )
        try:
            module.validate_blob(name, data)
            with self.assertRaisesRegex(module.SourceBoundaryError, "exceeds"):
                module.validate_blob(name, data[:-1] + b"y")
        finally:
            del module.REVIEWED_OVERSIZED_BLOBS[name]

    def test_allows_only_exact_reviewed_documentation_images(self):
        repository = MODULE_PATH.parent.parent
        for name in module.REVIEWED_DOCUMENTATION_IMAGES:
            with self.subTest(name=name):
                data = (repository / name).read_bytes()
                module.validate_blob(name, data)
                expected = (
                    "exceeds"
                    if len(data) > module.MAX_REVIEWED_BLOB_BYTES
                    else "unexpected binary"
                )
                with self.assertRaisesRegex(module.SourceBoundaryError, expected):
                    module.validate_blob(name, data[:-1] + bytes([data[-1] ^ 1]))

    def test_rejects_oversized_blob_before_content_inspection(self):
        with self.assertRaisesRegex(module.SourceBoundaryError, "exceeds"):
            module.validate_blob("docs/large.txt", b"a" * (module.MAX_REVIEWED_BLOB_BYTES + 1))

    def test_history_audit_ignores_unrelated_sibling_branch(self):
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            self.init_repository(repository)
            (repository / "safe.txt").write_text("safe\n", encoding="utf-8")
            self.git(repository, "add", "safe.txt")
            self.git(repository, "commit", "-m", "safe main")

            self.git(repository, "switch", "-c", "sibling")
            forbidden = repository / "mods" / "private.txt"
            forbidden.parent.mkdir()
            forbidden.write_text("not part of main\n", encoding="utf-8")
            self.git(repository, "add", "mods/private.txt")
            self.git(repository, "commit", "-m", "forbidden sibling")
            self.git(repository, "switch", "main")

            report = module.validate_repository(repository)
            self.assertEqual(report["trackedFiles"], 1)
            self.assertGreater(report["historicalBlobs"], 0)

    def test_history_audit_rejects_removed_forbidden_head_ancestor(self):
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            self.init_repository(repository)
            forbidden = repository / "mods" / "private.txt"
            forbidden.parent.mkdir()
            forbidden.write_text("was reachable from HEAD\n", encoding="utf-8")
            self.git(repository, "add", "mods/private.txt")
            self.git(repository, "commit", "-m", "forbidden ancestor")
            self.git(repository, "rm", "mods/private.txt")
            self.git(repository, "commit", "-m", "remove forbidden file")

            with self.assertRaisesRegex(module.SourceBoundaryError, "forbidden repository path segment"):
                module.validate_repository(repository)

    def test_cli_audits_current_tree_and_reachable_history(self):
        repository = MODULE_PATH.parent.parent
        shallow = subprocess.run(
            ["git", "rev-parse", "--is-shallow-repository"],
            cwd=repository,
            check=True,
            text=True,
            capture_output=True,
        ).stdout.strip()
        if shallow == "true":
            self.skipTest("full-history CLI check runs in Distribution from a complete checkout")
        result = subprocess.run(
            [sys.executable, str(MODULE_PATH)],
            check=True,
            text=True,
            capture_output=True,
        )
        report = json.loads(result.stdout)
        self.assertEqual(result.stderr, "")
        self.assertGreater(report["trackedFiles"], 0)
        self.assertGreater(report["historicalBlobs"], 0)
        self.assertGreater(report["reviewedBinaryFiles"], 0)


if __name__ == "__main__":
    unittest.main()
