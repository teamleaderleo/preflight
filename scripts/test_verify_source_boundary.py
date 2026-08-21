import importlib.util
import json
import subprocess
import sys
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("verify_source_boundary.py")
spec = importlib.util.spec_from_file_location("verify_source_boundary", MODULE_PATH)
assert spec and spec.loader
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class SourceBoundaryTest(unittest.TestCase):
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
                with self.assertRaisesRegex(module.SourceBoundaryError, "unexpected binary"):
                    module.validate_blob(name, data[:-1] + bytes([data[-1] ^ 1]))

    def test_rejects_oversized_blob_before_content_inspection(self):
        with self.assertRaisesRegex(module.SourceBoundaryError, "exceeds"):
            module.validate_blob("docs/large.txt", b"a" * (module.MAX_REVIEWED_BLOB_BYTES + 1))

    def test_cli_audits_current_tree_and_reachable_history(self):
        result = subprocess.run(
            [sys.executable, str(MODULE_PATH)],
            check=False,
            text=True,
            capture_output=True,
        )
        if result.returncode != 0:
            self.fail(
                "source-boundary verifier failed:\n"
                f"stdout:\n{result.stdout}\n"
                f"stderr:\n{result.stderr}"
            )
        report = json.loads(result.stdout)
        self.assertGreater(report["trackedFiles"], 0)
        self.assertGreater(report["historicalBlobs"], 0)
        self.assertGreater(report["reviewedBinaryFiles"], 0)


if __name__ == "__main__":
    unittest.main()
