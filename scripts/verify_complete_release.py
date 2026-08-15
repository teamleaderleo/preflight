#!/usr/bin/env python3
"""Fail closed when the final merged release differs from the reviewed artifact set."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import tempfile
from pathlib import Path
from urllib.parse import urlparse

import verify_release_boundary as core_boundary


DESKTOP_PACKAGES = frozenset(
    {
        "Preflight-Linux-x86_64.AppImage",
        "Preflight-Linux-x86_64.AppImage.sig",
        "Preflight-Linux-x86_64.deb",
        "Preflight-macOS-arm64.app.tar.gz",
        "Preflight-macOS-arm64.app.tar.gz.sig",
        "Preflight-macOS-arm64.dmg",
        "Preflight-Windows-x86_64.exe",
        "Preflight-Windows-x86_64.exe.sig",
    }
)

PLATFORM_CHECKSUMS = {
    "SHA256SUMS-darwin-arm64.txt": frozenset(
        {
            "Preflight-macOS-arm64.app.tar.gz",
            "Preflight-macOS-arm64.app.tar.gz.sig",
            "Preflight-macOS-arm64.dmg",
        }
    ),
    "SHA256SUMS-linux-x64.txt": frozenset(
        {
            "Preflight-Linux-x86_64.AppImage",
            "Preflight-Linux-x86_64.AppImage.sig",
            "Preflight-Linux-x86_64.deb",
        }
    ),
    "SHA256SUMS-win32-x64.txt": frozenset(
        {
            "Preflight-Windows-x86_64.exe",
            "Preflight-Windows-x86_64.exe.sig",
        }
    ),
}

CAPABILITY_RECEIPTS = {
    "CAPABILITIES-darwin-arm64.json": (
        "darwin",
        "arm64",
        "SHA256SUMS-darwin-arm64.txt",
    ),
    "CAPABILITIES-linux-x64.json": (
        "linux",
        "x64",
        "SHA256SUMS-linux-x64.txt",
    ),
    "CAPABILITIES-win32-x64.json": (
        "win32",
        "x64",
        "SHA256SUMS-win32-x64.txt",
    ),
}

UPDATER_PACKAGES = {
    "darwin-aarch64": "Preflight-macOS-arm64.app.tar.gz",
    "linux-x86_64": "Preflight-Linux-x86_64.AppImage",
    "windows-x86_64": "Preflight-Windows-x86_64.exe",
}
PUBLIC_UPDATER_HOST = "github.com"
PUBLIC_UPDATER_PREFIX = "/teamleaderleo/preflight/releases/download"
PRIVATE_CANDIDATE_HOST = "private-candidate.invalid"
PRIVATE_CANDIDATE_PATH = re.compile(r"^/run-[1-9][0-9]*/([^/]+)$")

COMPLETE_FILES = (
    core_boundary.DIST_FILES
    | DESKTOP_PACKAGES
    | frozenset(PLATFORM_CHECKSUMS)
    | frozenset(CAPABILITY_RECEIPTS)
    | {"latest.json", "latest.json.sha256"}
)
SEMVER = re.compile(r"^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$")


class CompleteReleaseError(ValueError):
    pass


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def validate_complete_release(directory: Path) -> dict[str, int | str]:
    if not directory.is_dir():
        raise CompleteReleaseError(f"release directory doesn't exist: {directory}")
    entries = list(directory.iterdir())
    unsafe = sorted(path.name for path in entries if not path.is_file() or path.is_symlink())
    if unsafe:
        raise CompleteReleaseError(f"release directory contains non-regular entries: {unsafe}")
    actual = {path.name for path in entries}
    if actual != set(COMPLETE_FILES):
        raise CompleteReleaseError(
            f"complete release files differ: expected {sorted(COMPLETE_FILES)}, got {sorted(actual)}"
        )

    core = validate_core_subset(directory)
    for manifest_name, package_names in PLATFORM_CHECKSUMS.items():
        checksums = core_boundary.parse_checksums(directory / manifest_name, package_names)
        for package_name, expected in checksums.items():
            if sha256(directory / package_name) != expected:
                raise CompleteReleaseError(
                    f"{manifest_name} checksum doesn't match: {package_name}"
                )

    capability_source_revision = validate_capability_receipts(directory)
    updater_platforms, updater_url_mode = validate_updater_manifest(directory)
    return {
        "releaseFiles": len(COMPLETE_FILES),
        "desktopPackages": len(DESKTOP_PACKAGES),
        "platformChecksumManifests": len(PLATFORM_CHECKSUMS),
        "capabilityReceipts": len(CAPABILITY_RECEIPTS),
        "capabilitySourceRevision": capability_source_revision,
        "updaterPlatforms": updater_platforms,
        "updaterUrlMode": updater_url_mode,
        "coreArchivePayloadFiles": core["archivePayloadFiles"],
    }


def validate_capability_receipts(directory: Path) -> str:
    common_receipt: dict | None = None
    for receipt_name, (platform, architecture, checksum_name) in CAPABILITY_RECEIPTS.items():
        try:
            receipt = json.loads((directory / receipt_name).read_text(encoding="utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise CompleteReleaseError(f"{receipt_name} isn't valid UTF-8 JSON") from exc
        expected_fields = {
            "format",
            "platform",
            "architecture",
            "capabilityReceiptSha256",
            "capabilityReceipt",
            "artifacts",
        }
        if not isinstance(receipt, dict) or set(receipt) != expected_fields:
            raise CompleteReleaseError(f"{receipt_name} fields differ from the reviewed schema")
        if (
            receipt["format"] != "preflight-release-artifact-capabilities-v1"
            or receipt["platform"] != platform
            or receipt["architecture"] != architecture
        ):
            raise CompleteReleaseError(f"{receipt_name} identifies another release target")
        capability = receipt["capabilityReceipt"]
        if not isinstance(capability, dict):
            raise CompleteReleaseError(f"{receipt_name} has no packaged capability statement")
        encoded_capability = (
            json.dumps(capability, indent=2, ensure_ascii=False, sort_keys=True) + "\n"
        ).encode("utf-8")
        if (
            not isinstance(receipt["capabilityReceiptSha256"], str)
            or receipt["capabilityReceiptSha256"]
            != hashlib.sha256(encoded_capability).hexdigest()
        ):
            raise CompleteReleaseError(f"{receipt_name} capability digest doesn't match")
        validate_packaged_capability(capability, receipt_name)
        if common_receipt is None:
            common_receipt = capability
        elif capability != common_receipt:
            raise CompleteReleaseError("platform packages carry different capability statements")

        expected_packages = PLATFORM_CHECKSUMS[checksum_name]
        artifacts = receipt["artifacts"]
        if (
            not isinstance(artifacts, list)
            or len(artifacts) != len(expected_packages)
            or {
                item.get("name") for item in artifacts if isinstance(item, dict)
            }
            != set(expected_packages)
        ):
            raise CompleteReleaseError(f"{receipt_name} artifact set differs from {checksum_name}")
        expected_digests = core_boundary.parse_checksums(
            directory / checksum_name, expected_packages
        )
        for artifact in artifacts:
            if not isinstance(artifact, dict) or set(artifact) != {"name", "bytes", "sha256"}:
                raise CompleteReleaseError(f"{receipt_name} has a malformed artifact identity")
            name = artifact["name"]
            if (
                not isinstance(artifact["bytes"], int)
                or artifact["bytes"] <= 0
                or artifact["bytes"] != (directory / name).stat().st_size
                or artifact["sha256"] != expected_digests[name]
                or artifact["sha256"] != sha256(directory / name)
            ):
                raise CompleteReleaseError(f"{receipt_name} artifact identity doesn't match: {name}")
    if common_receipt is None:
        raise CompleteReleaseError("complete release has no capability receipt")
    return common_receipt["sourceRevision"]


def validate_packaged_capability(capability: dict, receipt_name: str) -> None:
    required = {
        "format",
        "productVersion",
        "sourceRevision",
        "sourceDirty",
        "boundarySourceSha256",
        "engineJarSha256",
        "rendererBoundary",
        "filesystem",
        "processes",
        "arbitraryShellCommandsAccepted",
        "network",
    }
    if set(capability) != required or capability["format"] != "preflight-release-capabilities-v1":
        raise CompleteReleaseError(f"{receipt_name} packaged capability fields differ")
    if (
        not isinstance(capability["sourceRevision"], str)
        or not re.fullmatch(r"[a-f0-9]{40}", capability["sourceRevision"])
        or capability["sourceDirty"] is not False
        or not isinstance(capability["boundarySourceSha256"], str)
        or not re.fullmatch(r"[a-f0-9]{64}", capability["boundarySourceSha256"])
        or not isinstance(capability["engineJarSha256"], str)
        or not re.fullmatch(r"[a-f0-9]{64}", capability["engineJarSha256"])
        or capability["arbitraryShellCommandsAccepted"] is not False
        or capability.get("network", {}).get("automaticTelemetry") is not False
        or not capability.get("rendererBoundary", {}).get("nativeCommands")
    ):
        raise CompleteReleaseError(f"{receipt_name} packaged capability statement is unsafe")


def validate_core_subset(directory: Path) -> dict[str, int]:
    with tempfile.TemporaryDirectory(prefix="preflight-core-release-") as temp:
        staged = Path(temp)
        for name in core_boundary.DIST_FILES:
            shutil.copyfile(directory / name, staged / name)
        try:
            return core_boundary.validate_dist(staged)
        except core_boundary.BoundaryError as exc:
            raise CompleteReleaseError(f"core release boundary failed: {exc}") from exc


def validate_updater_manifest(directory: Path) -> tuple[int, str]:
    checksum = core_boundary.parse_checksums(
        directory / "latest.json.sha256", {"latest.json"}
    )
    if checksum["latest.json"] != sha256(directory / "latest.json"):
        raise CompleteReleaseError("latest.json.sha256 doesn't match latest.json")
    try:
        manifest = json.loads((directory / "latest.json").read_text(encoding="utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise CompleteReleaseError("latest.json isn't valid UTF-8 JSON") from exc
    if not isinstance(manifest, dict) or set(manifest) != {"version", "notes", "platforms"}:
        raise CompleteReleaseError("latest.json fields differ from the reviewed updater schema")
    if not isinstance(manifest["version"], str) or not SEMVER.fullmatch(manifest["version"]):
        raise CompleteReleaseError("latest.json has no semantic version")
    if not isinstance(manifest["notes"], str) or not 0 < len(manifest["notes"]) <= 16_384:
        raise CompleteReleaseError("latest.json has invalid release notes")
    platforms = manifest["platforms"]
    if not isinstance(platforms, dict) or set(platforms) != set(UPDATER_PACKAGES):
        raise CompleteReleaseError("latest.json updater platforms differ from the supported set")
    url_mode: str | None = None
    for platform, package_name in UPDATER_PACKAGES.items():
        entry = platforms[platform]
        if not isinstance(entry, dict) or set(entry) != {"signature", "url"}:
            raise CompleteReleaseError(f"latest.json has invalid entry for {platform}")
        signature = entry["signature"]
        if not isinstance(signature, str) or not signature or len(signature) > 16_384:
            raise CompleteReleaseError(f"latest.json has invalid signature for {platform}")
        try:
            packaged_signature = (directory / f"{package_name}.sig").read_text(
                encoding="utf-8"
            ).strip()
        except UnicodeDecodeError as exc:
            raise CompleteReleaseError(
                f"{package_name}.sig isn't valid UTF-8"
            ) from exc
        if signature != packaged_signature:
            raise CompleteReleaseError(
                f"latest.json signature differs from {package_name}.sig"
            )
        url = entry["url"]
        try:
            parsed = urlparse(url) if isinstance(url, str) else None
            username = parsed.username if parsed is not None else None
            password = parsed.password if parsed is not None else None
            port = parsed.port if parsed is not None else None
        except ValueError as exc:
            raise CompleteReleaseError(
                f"latest.json has unsafe package URL for {platform}"
            ) from exc
        if (
            parsed is None
            or parsed.scheme != "https"
            or not parsed.netloc
            or port is not None
            or username
            or password
            or parsed.query
            or parsed.fragment
            or Path(parsed.path).name != package_name
        ):
            raise CompleteReleaseError(f"latest.json has unsafe package URL for {platform}")
        mode = reviewed_updater_url_mode(parsed, manifest["version"], package_name)
        if mode is None:
            raise CompleteReleaseError(
                f"latest.json package URL is outside the reviewed release origins for {platform}"
            )
        if url_mode is None:
            url_mode = mode
        elif url_mode != mode:
            raise CompleteReleaseError("latest.json mixes public and private-candidate package URLs")
    if url_mode is None:
        raise CompleteReleaseError("latest.json has no reviewed updater URL mode")
    return len(platforms), url_mode


def reviewed_updater_url_mode(parsed, version: str, package_name: str) -> str | None:
    hostname = parsed.hostname.lower() if parsed.hostname is not None else ""
    public_path = f"{PUBLIC_UPDATER_PREFIX}/v{version}/{package_name}"
    if hostname == PUBLIC_UPDATER_HOST and parsed.path == public_path:
        return "public-release"
    private_match = (
        PRIVATE_CANDIDATE_PATH.fullmatch(parsed.path)
        if hostname == PRIVATE_CANDIDATE_HOST
        else None
    )
    if private_match is not None and private_match.group(1) == package_name:
        return "private-candidate"
    return None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--release", required=True, type=Path)
    args = parser.parse_args()
    try:
        report = validate_complete_release(args.release)
    except (CompleteReleaseError, OSError) as exc:
        parser.error(str(exc))
    print(json.dumps(report, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
