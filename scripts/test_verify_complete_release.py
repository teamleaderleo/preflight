import hashlib
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).parent
sys.path.insert(0, str(SCRIPTS))

CORE_TEST_PATH = SCRIPTS / "test_verify_release_boundary.py"
core_spec = importlib.util.spec_from_file_location("core_test_fixture", CORE_TEST_PATH)
assert core_spec and core_spec.loader
core_fixture = importlib.util.module_from_spec(core_spec)
core_spec.loader.exec_module(core_fixture)

MODULE_PATH = SCRIPTS / "verify_complete_release.py"
spec = importlib.util.spec_from_file_location("verify_complete_release", MODULE_PATH)
assert spec and spec.loader
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def populate(directory: Path) -> None:
    core_fixture.populate(directory)
    for name in module.DESKTOP_PACKAGES:
        (directory / name).write_text(f"fixture {name}\n", encoding="utf-8")
    for manifest_name, package_names in module.PLATFORM_CHECKSUMS.items():
        (directory / manifest_name).write_text(
            "".join(f"{digest(directory / name)}  {name}\n" for name in sorted(package_names)),
            encoding="utf-8",
        )
    capability = {
        "format": "preflight-release-capabilities-v1",
        "productVersion": "0.1.0",
        "sourceRevision": "a" * 40,
        "sourceDirty": False,
        "boundarySourceSha256": "b" * 64,
        "engineJarSha256": "c" * 64,
        "rendererBoundary": {"nativeCommands": ["start_game"], "tauriPermissions": ["core:default"]},
        "filesystem": {"preflightOwned": [], "gameOwnedWrites": [], "excluded": ["save files"]},
        "processes": [{"family": "bundled Java engine", "purpose": "fixed commands"}],
        "arbitraryShellCommandsAccepted": False,
        "network": {"automaticTelemetry": False},
    }
    capability_digest = hashlib.sha256(
        (
            json.dumps(capability, indent=2, ensure_ascii=False, sort_keys=True) + "\n"
        ).encode("utf-8")
    ).hexdigest()
    for receipt_name, (platform, architecture, checksum_name) in module.CAPABILITY_RECEIPTS.items():
        artifacts = [
            {
                "name": name,
                "bytes": (directory / name).stat().st_size,
                "sha256": digest(directory / name),
            }
            for name in sorted(module.PLATFORM_CHECKSUMS[checksum_name])
        ]
        (directory / receipt_name).write_text(
            json.dumps(
                {
                    "format": "preflight-release-artifact-capabilities-v1",
                    "platform": platform,
                    "architecture": architecture,
                    "capabilityReceiptSha256": capability_digest,
                    "capabilityReceipt": capability,
                    "artifacts": artifacts,
                },
                indent=2,
            ) + "\n",
            encoding="utf-8",
        )
    platforms = {}
    for platform, package_name in module.UPDATER_PACKAGES.items():
        platforms[platform] = {
            "signature": (directory / f"{package_name}.sig").read_text(encoding="utf-8").strip(),
            "url": f"https://private-candidate.invalid/run-123/{package_name}",
        }
    latest = json.dumps(
        {"version": "0.1.0", "notes": "Fixture release", "platforms": platforms},
        indent=2,
        sort_keys=True,
    ) + "\n"
    (directory / "latest.json").write_text(latest, encoding="utf-8")
    (directory / "latest.json.sha256").write_text(
        f"{digest(directory / 'latest.json')}  latest.json\n", encoding="utf-8"
    )


def rewrite_manifest(directory: Path, manifest: dict) -> None:
    (directory / "latest.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    (directory / "latest.json.sha256").write_text(
        f"{digest(directory / 'latest.json')}  latest.json\n", encoding="utf-8"
    )


class CompleteReleaseTest(unittest.TestCase):
    def test_accepts_exact_complete_release(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            populate(directory)
            report = module.validate_complete_release(directory)
            self.assertEqual(len(module.COMPLETE_FILES), report["releaseFiles"])
            self.assertEqual(3, report["updaterPlatforms"])
            self.assertEqual(3, report["capabilityReceipts"])
            self.assertEqual("a" * 40, report["capabilitySourceRevision"])
            self.assertEqual("private-candidate", report["updaterUrlMode"])

    def test_rejects_an_extra_file_at_the_final_publish_boundary(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            populate(directory)
            (directory / "starsector.log").write_text("private", encoding="utf-8")
            with self.assertRaisesRegex(module.CompleteReleaseError, "files differ"):
                module.validate_complete_release(directory)

    def test_rejects_a_changed_native_package(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            populate(directory)
            (directory / "Preflight-Linux-x86_64.deb").write_text("changed", encoding="utf-8")
            with self.assertRaisesRegex(module.CompleteReleaseError, "checksum doesn't match"):
                module.validate_complete_release(directory)

    def test_rejects_a_capability_receipt_that_hides_a_dirty_checkout(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            populate(directory)
            path = directory / "CAPABILITIES-linux-x64.json"
            receipt = json.loads(path.read_text(encoding="utf-8"))
            receipt["capabilityReceipt"]["sourceDirty"] = True
            encoded = json.dumps(
                receipt["capabilityReceipt"],
                indent=2,
                ensure_ascii=False,
                sort_keys=True,
            ) + "\n"
            receipt["capabilityReceiptSha256"] = hashlib.sha256(encoded.encode()).hexdigest()
            path.write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
            with self.assertRaisesRegex(module.CompleteReleaseError, "unsafe"):
                module.validate_complete_release(directory)

    def test_rejects_a_capability_receipt_with_another_artifact_digest(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            populate(directory)
            path = directory / "CAPABILITIES-win32-x64.json"
            receipt = json.loads(path.read_text(encoding="utf-8"))
            receipt["artifacts"][0]["sha256"] = "0" * 64
            path.write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
            with self.assertRaisesRegex(module.CompleteReleaseError, "doesn't match"):
                module.validate_complete_release(directory)

    def test_rejects_a_duplicate_capability_artifact(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            populate(directory)
            path = directory / "CAPABILITIES-darwin-arm64.json"
            receipt = json.loads(path.read_text(encoding="utf-8"))
            receipt["artifacts"].append(receipt["artifacts"][0])
            path.write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
            with self.assertRaisesRegex(module.CompleteReleaseError, "artifact set differs"):
                module.validate_complete_release(directory)

    def test_rejects_an_updater_signature_not_matching_the_published_file(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            populate(directory)
            (directory / "Preflight-Windows-x86_64.exe.sig").write_text("changed\n", encoding="utf-8")
            manifest_name = "SHA256SUMS-win32-x64.txt"
            package_names = module.PLATFORM_CHECKSUMS[manifest_name]
            (directory / manifest_name).write_text(
                "".join(
                    f"{digest(directory / name)}  {name}\n" for name in sorted(package_names)
                ),
                encoding="utf-8",
            )
            capability_path = directory / "CAPABILITIES-win32-x64.json"
            capability = json.loads(capability_path.read_text(encoding="utf-8"))
            for artifact in capability["artifacts"]:
                artifact["bytes"] = (directory / artifact["name"]).stat().st_size
                artifact["sha256"] = digest(directory / artifact["name"])
            capability_path.write_text(
                json.dumps(capability, indent=2, sort_keys=True) + "\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(module.CompleteReleaseError, "signature differs"):
                module.validate_complete_release(directory)

    def test_rejects_an_updater_url_for_another_asset(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            populate(directory)
            manifest = json.loads((directory / "latest.json").read_text(encoding="utf-8"))
            manifest["platforms"]["linux-x86_64"]["url"] = (
                "https://private-candidate.invalid/run-123/another.AppImage"
            )
            (directory / "latest.json").write_text(
                json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
            )
            (directory / "latest.json.sha256").write_text(
                f"{digest(directory / 'latest.json')}  latest.json\n", encoding="utf-8"
            )
            with self.assertRaisesRegex(module.CompleteReleaseError, "unsafe package URL"):
                module.validate_complete_release(directory)

    def test_rejects_an_arbitrary_https_updater_origin(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            populate(directory)
            manifest = json.loads((directory / "latest.json").read_text(encoding="utf-8"))
            for entry in manifest["platforms"].values():
                package_name = Path(entry["url"]).name
                entry["url"] = f"https://downloads.example.com/releases/v0.1.0/{package_name}"
            rewrite_manifest(directory, manifest)
            with self.assertRaisesRegex(module.CompleteReleaseError, "reviewed release origins"):
                module.validate_complete_release(directory)

    def test_rejects_an_explicit_port_on_a_reviewed_updater_origin(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            populate(directory)
            manifest = json.loads((directory / "latest.json").read_text(encoding="utf-8"))
            package_name = module.UPDATER_PACKAGES["windows-x86_64"]
            manifest["platforms"]["windows-x86_64"]["url"] = (
                "https://private-candidate.invalid:443/run-123/" + package_name
            )
            rewrite_manifest(directory, manifest)
            with self.assertRaisesRegex(module.CompleteReleaseError, "unsafe package URL"):
                module.validate_complete_release(directory)

    def test_accepts_the_exact_public_release_tag_and_rejects_another_tag(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            populate(directory)
            manifest = json.loads((directory / "latest.json").read_text(encoding="utf-8"))
            for entry in manifest["platforms"].values():
                package_name = Path(entry["url"]).name
                entry["url"] = (
                    "https://github.com/teamleaderleo/preflight/releases/download/"
                    f"v0.1.0/{package_name}"
                )
            rewrite_manifest(directory, manifest)
            report = module.validate_complete_release(directory)
            self.assertEqual("public-release", report["updaterUrlMode"])

            manifest["platforms"]["windows-x86_64"]["url"] = manifest["platforms"][
                "windows-x86_64"
            ]["url"].replace("/v0.1.0/", "/v0.1.1/")
            rewrite_manifest(directory, manifest)
            with self.assertRaisesRegex(module.CompleteReleaseError, "reviewed release origins"):
                module.validate_complete_release(directory)

    def test_rejects_mixed_public_and_private_candidate_urls(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            populate(directory)
            manifest = json.loads((directory / "latest.json").read_text(encoding="utf-8"))
            package_name = module.UPDATER_PACKAGES["linux-x86_64"]
            manifest["platforms"]["linux-x86_64"]["url"] = (
                "https://github.com/teamleaderleo/preflight/releases/download/"
                f"v0.1.0/{package_name}"
            )
            rewrite_manifest(directory, manifest)
            with self.assertRaisesRegex(module.CompleteReleaseError, "mixes public and private"):
                module.validate_complete_release(directory)

    def test_cli_reports_the_final_release_summary(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            populate(directory)
            result = subprocess.run(
                [sys.executable, str(MODULE_PATH), "--release", str(directory)],
                check=True,
                text=True,
                capture_output=True,
            )
            report = json.loads(result.stdout)
            self.assertEqual(len(module.COMPLETE_FILES), report["releaseFiles"])


if __name__ == "__main__":
    unittest.main()
