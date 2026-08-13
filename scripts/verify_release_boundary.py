#!/usr/bin/env python3
"""Fail closed when a core release archive contains an unexpected file or JAR namespace."""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import re
import stat
import tarfile
import zipfile
from pathlib import Path, PurePosixPath


CORE_FILES = frozenset(
    {
        "preflight.jar",
        "preflight.jar.sha256",
        "README.txt",
        "LICENSE",
        "THIRD_PARTY_NOTICES.md",
        "PRIVACY.md",
        "KNOWN_LIMITATIONS.md",
        "DEPENDENCY_INVENTORY.md",
        "SBOM-SHA256SUMS.txt",
        "preflight-java.cdx.json",
        "preflight-desktop-web.cdx.json",
        "preflight-desktop-native-aarch64-apple-darwin.cdx.json",
        "preflight-desktop-native-x86_64-pc-windows-msvc.cdx.json",
        "preflight-desktop-native-x86_64-unknown-linux-gnu.cdx.json",
    }
)

DIST_FILES = CORE_FILES | {"preflight.tar.gz", "preflight.zip", "archives.sha256"}
SBOM_FILES = frozenset(name for name in CORE_FILES if name.endswith(".cdx.json"))

JAR_EXACT_FILES = frozenset(
    {
        "META-INF/MANIFEST.MF",
        "META-INF/services/javax.imageio.spi.ImageInputStreamSpi",
        "META-INF/services/javax.imageio.spi.ImageReaderSpi",
        "profiles/ClayRGB1998.icc",
        "dev/starsector/preflight/agent/graphicslib-texture-data-1.12.1.class.b64",
    }
)

JAR_CLASS_PREFIXES = (
    "dev/starsector/preflight/agent/",
    "dev/starsector/preflight/cli/",
    "dev/starsector/preflight/core/",
    "dev/starsector/preflight/internal/",
    "com/twelvemonkeys/",
    "io/airlift/compress/",
)

JAR_RESOURCE_FILES = frozenset(
    {
        "com/twelvemonkeys/imageio/color/icc_profiles_lnx.properties",
        "com/twelvemonkeys/imageio/color/icc_profiles_osx.properties",
        "com/twelvemonkeys/imageio/color/icc_profiles_win.properties",
        "com/twelvemonkeys/net/MIMEUtil.properties",
    }
)

MAVEN_COORDINATES = frozenset(
    {
        "com.twelvemonkeys.common/common-image",
        "com.twelvemonkeys.common/common-io",
        "com.twelvemonkeys.common/common-lang",
        "com.twelvemonkeys.imageio/imageio-core",
        "com.twelvemonkeys.imageio/imageio-metadata",
        "com.twelvemonkeys.imageio/imageio-webp",
        "dev.starsector.preflight/preflight-agent",
        "dev.starsector.preflight/preflight-cli",
        "dev.starsector.preflight/preflight-core",
        "io.airlift/aircompressor",
    }
)

FORBIDDEN_PATH_SEGMENTS = frozenset(
    {
        "activation",
        "mods",
        "saves",
        "screenshots",
        "starsector.app",
        "starsector-core",
        "starsector.exe",
    }
)

CHECKSUM_LINE = re.compile(r"^([0-9a-f]{64})  ([^\r\n]+)$")


class BoundaryError(ValueError):
    pass


def safe_member_name(name: str) -> str:
    if not name or "\x00" in name or "\\" in name:
        raise BoundaryError(f"unsafe archive path: {name!r}")
    path = PurePosixPath(name)
    if path.is_absolute() or any(part in {"", ".", ".."} for part in path.parts):
        raise BoundaryError(f"unsafe archive path: {name!r}")
    lowered = {part.lower() for part in path.parts}
    forbidden = sorted(lowered & FORBIDDEN_PATH_SEGMENTS)
    if forbidden:
        raise BoundaryError(f"forbidden archive path segment {forbidden[0]!r}: {name}")
    return path.as_posix()


def validate_jar_bytes(data: bytes) -> int:
    try:
        archive = zipfile.ZipFile(io.BytesIO(data))
    except zipfile.BadZipFile as exc:
        raise BoundaryError("preflight.jar isn't a readable ZIP archive") from exc
    with archive:
        seen: set[str] = set()
        directories: set[str] = set()
        files: set[str] = set()
        for info in archive.infolist():
            name = safe_member_name(info.filename.rstrip("/"))
            if name in seen:
                raise BoundaryError(f"duplicate JAR entry: {name}")
            seen.add(name)
            if info.flag_bits & 1:
                raise BoundaryError(f"encrypted JAR entry: {name}")
            if info.is_dir():
                directories.add(name)
                continue
            files.add(name)
            if not allowed_jar_file(name):
                raise BoundaryError(f"unexpected JAR entry: {name}")
        if "META-INF/MANIFEST.MF" not in files:
            raise BoundaryError("preflight.jar has no manifest")
        if not any(name.startswith("dev/starsector/preflight/cli/") for name in files):
            raise BoundaryError("preflight.jar has no CLI classes")
        if not any(name.startswith("dev/starsector/preflight/agent/") for name in files):
            raise BoundaryError("preflight.jar has no agent classes")
        for directory in directories:
            prefix = f"{directory}/"
            if not any(name.startswith(prefix) for name in files):
                raise BoundaryError(f"empty or unexpected JAR directory: {directory}")
        return len(seen)


def allowed_jar_file(name: str) -> bool:
    if name in JAR_EXACT_FILES or name in JAR_RESOURCE_FILES:
        return True
    if name.startswith("audio/ogg-v1/"):
        filename = name.removeprefix("audio/ogg-v1/")
        return "/" not in filename and (
            filename.endswith(".b64")
            or ".b64.part" in filename
            or filename == "encoded-ogg-parts.sha256"
        )
    if name.endswith(".class") and name.startswith(JAR_CLASS_PREFIXES):
        return True
    if name.startswith("META-INF/maven/"):
        relative = name.removeprefix("META-INF/maven/")
        coordinate, separator, filename = relative.rpartition("/")
        return separator == "/" and coordinate in MAVEN_COORDINATES and filename in {
            "pom.xml",
            "pom.properties",
        }
    return False


def zip_members(path: Path) -> dict[str, bytes]:
    try:
        archive = zipfile.ZipFile(path)
    except zipfile.BadZipFile as exc:
        raise BoundaryError(f"unreadable ZIP archive: {path}") from exc
    with archive:
        result: dict[str, bytes] = {}
        for info in archive.infolist():
            name = safe_member_name(info.filename.rstrip("/"))
            if info.is_dir():
                raise BoundaryError(f"unexpected directory in ZIP archive: {name}")
            if name in result:
                raise BoundaryError(f"duplicate ZIP entry: {name}")
            if info.flag_bits & 1:
                raise BoundaryError(f"encrypted ZIP entry: {name}")
            mode = info.external_attr >> 16
            if stat.S_ISLNK(mode):
                raise BoundaryError(f"symbolic link in ZIP archive: {name}")
            result[name] = archive.read(info)
        return result


def tar_members(path: Path) -> dict[str, bytes]:
    try:
        archive = tarfile.open(path, mode="r:gz")
    except (tarfile.TarError, OSError) as exc:
        raise BoundaryError(f"unreadable tar archive: {path}") from exc
    with archive:
        result: dict[str, bytes] = {}
        for info in archive.getmembers():
            name = safe_member_name(info.name.rstrip("/"))
            if info.isdir():
                raise BoundaryError(f"unexpected directory in tar archive: {name}")
            if name in result:
                raise BoundaryError(f"duplicate tar entry: {name}")
            if not info.isfile():
                raise BoundaryError(f"non-regular tar entry: {name}")
            extracted = archive.extractfile(info)
            if extracted is None:
                raise BoundaryError(f"unreadable tar entry: {name}")
            result[name] = extracted.read()
        return result


def parse_checksums(path: Path, expected_names: set[str] | frozenset[str]) -> dict[str, str]:
    entries: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        match = CHECKSUM_LINE.fullmatch(line)
        if not match:
            raise BoundaryError(f"malformed checksum line in {path.name}: {line!r}")
        digest, name = match.groups()
        safe_member_name(name)
        if name in entries:
            raise BoundaryError(f"duplicate checksum entry in {path.name}: {name}")
        entries[name] = digest
    if set(entries) != set(expected_names):
        raise BoundaryError(
            f"{path.name} entries differ: expected {sorted(expected_names)}, got {sorted(entries)}"
        )
    return entries


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def validate_dist(directory: Path) -> dict[str, int]:
    if not directory.is_dir():
        raise BoundaryError(f"distribution directory doesn't exist: {directory}")
    entries = list(directory.iterdir())
    unsafe = sorted(path.name for path in entries if not path.is_file() or path.is_symlink())
    if unsafe:
        raise BoundaryError(f"distribution directory contains non-regular entries: {unsafe}")
    actual = {path.name for path in entries}
    if actual != set(DIST_FILES):
        raise BoundaryError(
            f"distribution files differ: expected {sorted(DIST_FILES)}, got {sorted(actual)}"
        )
    loose = {name: (directory / name).read_bytes() for name in CORE_FILES}
    jar_entries = validate_jar_bytes(loose["preflight.jar"])

    jar_checksums = parse_checksums(directory / "preflight.jar.sha256", {"preflight.jar"})
    if jar_checksums["preflight.jar"] != sha256(loose["preflight.jar"]):
        raise BoundaryError("preflight.jar.sha256 doesn't match preflight.jar")

    sbom_checksums = parse_checksums(directory / "SBOM-SHA256SUMS.txt", SBOM_FILES)
    for name, digest in sbom_checksums.items():
        if digest != sha256(loose[name]):
            raise BoundaryError(f"SBOM checksum doesn't match: {name}")
        try:
            payload = json.loads(loose[name])
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise BoundaryError(f"SBOM isn't valid JSON: {name}") from exc
        if payload.get("bomFormat") != "CycloneDX" or not payload.get("components"):
            raise BoundaryError(f"SBOM has no CycloneDX component inventory: {name}")

    archived = {
        "preflight.zip": zip_members(directory / "preflight.zip"),
        "preflight.tar.gz": tar_members(directory / "preflight.tar.gz"),
    }
    for archive_name, members in archived.items():
        if set(members) != set(CORE_FILES):
            raise BoundaryError(
                f"{archive_name} entries differ: expected {sorted(CORE_FILES)}, got {sorted(members)}"
            )
        for name, data in members.items():
            if data != loose[name]:
                raise BoundaryError(f"{archive_name} entry differs from staged file: {name}")
        validate_jar_bytes(members["preflight.jar"])

    archive_checksums = parse_checksums(
        directory / "archives.sha256", {"preflight.tar.gz", "preflight.zip"}
    )
    for name, digest in archive_checksums.items():
        if digest != sha256((directory / name).read_bytes()):
            raise BoundaryError(f"archive checksum doesn't match: {name}")

    return {
        "distributionFiles": len(DIST_FILES),
        "archivePayloadFiles": len(CORE_FILES),
        "jarEntries": jar_entries,
        "sboms": len(SBOM_FILES),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dist", required=True, type=Path)
    args = parser.parse_args()
    try:
        report = validate_dist(args.dist)
    except (BoundaryError, OSError) as exc:
        parser.error(str(exc))
    print(json.dumps(report, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
