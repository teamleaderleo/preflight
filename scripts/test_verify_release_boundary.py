import hashlib
import importlib.util
import io
import json
import subprocess
import sys
import tarfile
import tempfile
import unittest
import zipfile
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("verify_release_boundary.py")
spec = importlib.util.spec_from_file_location("verify_release_boundary", MODULE_PATH)
assert spec and spec.loader
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def fake_jar(extra_name: str | None = None) -> bytes:
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as archive:
        archive.writestr("META-INF/MANIFEST.MF", "Main-Class: dev.starsector.preflight.cli.PreflightCli\n")
        archive.writestr("dev/starsector/preflight/cli/PreflightCli.class", b"cli")
        archive.writestr("dev/starsector/preflight/agent/PreflightAgent.class", b"agent")
        archive.writestr("dev/starsector/preflight/core/Hashing.class", b"core")
        if extra_name:
            archive.writestr(extra_name, b"unexpected")
    return output.getvalue()


def fake_jar_with_empty_directory() -> bytes:
    output = io.BytesIO(fake_jar())
    rewritten = io.BytesIO()
    with zipfile.ZipFile(output) as source, zipfile.ZipFile(rewritten, "w") as destination:
        for info in source.infolist():
            destination.writestr(info, source.read(info))
        destination.mkdir("com/fs/starfarer/")
    return rewritten.getvalue()


def populate(directory: Path, jar: bytes | None = None) -> None:
    jar = jar or fake_jar()
    files: dict[str, bytes] = {}
    for name in module.CORE_FILES:
        if name == "preflight.jar":
            files[name] = jar
        elif name == "preflight.jar.sha256":
            files[name] = f"{digest(jar)}  preflight.jar\n".encode()
        elif name.endswith(".cdx.json"):
            files[name] = json.dumps(
                {"bomFormat": "CycloneDX", "components": [{"name": name}]},
                sort_keys=True,
            ).encode()
        elif name != "SBOM-SHA256SUMS.txt":
            files[name] = f"fixture {name}\n".encode()
    files["SBOM-SHA256SUMS.txt"] = "".join(
        f"{digest(files[name])}  {name}\n" for name in sorted(module.SBOM_FILES)
    ).encode()
    for name, data in files.items():
        (directory / name).write_bytes(data)

    with zipfile.ZipFile(directory / "preflight.zip", "w") as archive:
        for name in sorted(module.CORE_FILES):
            archive.writestr(name, files[name])
    with tarfile.open(directory / "preflight.tar.gz", "w:gz") as archive:
        for name in sorted(module.CORE_FILES):
            info = tarfile.TarInfo(name)
            info.size = len(files[name])
            archive.addfile(info, io.BytesIO(files[name]))
    (directory / "archives.sha256").write_text(
        "".join(
            f"{digest((directory / name).read_bytes())}  {name}\n"
            for name in ("preflight.tar.gz", "preflight.zip")
        ),
        encoding="utf-8",
    )


class ReleaseBoundaryTest(unittest.TestCase):
    def test_accepts_exact_distribution(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            populate(directory)
            report = module.validate_dist(directory)
            self.assertEqual(len(module.CORE_FILES), report["archivePayloadFiles"])
            self.assertEqual(len(module.SBOM_FILES), report["sboms"])

    def test_rejects_extra_distribution_file(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            populate(directory)
            (directory / "starsector.log").write_text("private", encoding="utf-8")
            with self.assertRaisesRegex(module.BoundaryError, "distribution files differ"):
                module.validate_dist(directory)

    def test_rejects_proprietary_jar_namespace(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            populate(directory, fake_jar("com/fs/starfarer/Game.class"))
            with self.assertRaisesRegex(module.BoundaryError, "unexpected JAR entry"):
                module.validate_dist(directory)

    def test_rejects_empty_unreviewed_jar_namespace(self):
        with self.assertRaisesRegex(module.BoundaryError, "unexpected JAR directory"):
            module.validate_jar_bytes(fake_jar_with_empty_directory())

    def test_rejects_archive_path_traversal(self):
        with tempfile.TemporaryDirectory() as temp:
            archive_path = Path(temp) / "bad.zip"
            with zipfile.ZipFile(archive_path, "w") as archive:
                archive.writestr("../save/private", b"private")
            with self.assertRaisesRegex(module.BoundaryError, "unsafe archive path"):
                module.zip_members(archive_path)

    def test_rejects_archive_directory_entry(self):
        with tempfile.TemporaryDirectory() as temp:
            archive_path = Path(temp) / "bad.zip"
            with zipfile.ZipFile(archive_path, "w") as archive:
                archive.mkdir("extra/")
            with self.assertRaisesRegex(module.BoundaryError, "unexpected directory"):
                module.zip_members(archive_path)

    def test_rejects_tar_symbolic_link(self):
        with tempfile.TemporaryDirectory() as temp:
            archive_path = Path(temp) / "bad.tar.gz"
            with tarfile.open(archive_path, "w:gz") as archive:
                info = tarfile.TarInfo("preflight.jar")
                info.type = tarfile.SYMTYPE
                info.linkname = "/Applications/Starsector.app"
                archive.addfile(info)
            with self.assertRaisesRegex(module.BoundaryError, "non-regular tar entry"):
                module.tar_members(archive_path)

    def test_rejects_staged_and_archived_content_mismatch(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            populate(directory)
            (directory / "README.txt").write_text("changed after archive\n", encoding="utf-8")
            with self.assertRaisesRegex(module.BoundaryError, "entry differs"):
                module.validate_dist(directory)

    def test_cli_reports_summary(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            populate(directory)
            result = subprocess.run(
                [sys.executable, str(MODULE_PATH), "--dist", str(directory)],
                check=True,
                text=True,
                capture_output=True,
            )
            payload = json.loads(result.stdout)
            self.assertEqual(len(module.DIST_FILES), payload["distributionFiles"])


if __name__ == "__main__":
    unittest.main()
