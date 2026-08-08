#!/usr/bin/env python3
"""Audit tracked files and reachable Git history for accidental private or game content."""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
from collections import defaultdict
from pathlib import Path, PurePosixPath


MAX_REVIEWED_BLOB_BYTES = 512 * 1024
REVIEWED_OVERSIZED_BLOBS = {
    "preflight-desktop/src-tauri/icons/icon.icns": frozenset(
        {
            (1_746_720, "05ca5e3efb0c65533cdb45e944ae3e941651cd0da45a9a8e30e72b8ca76c0a7f"),
            (1_201_813, "9f600da93bdd51b2e4a94bda5a111fc53559fe478781e29b2e27f44b13ac8e7d"),
        }
    )
}
FORBIDDEN_SEGMENTS = frozenset({"activation", "mods", "saves", "screenshots"})
FORBIDDEN_BASENAMES = frozenset(
    {
        "campaign.xml",
        "fs.common_obf.jar",
        "starfarer.api.jar",
        "starfarer_obf.jar",
        "starsector-core",
        "starsector.exe",
        "starsector.log",
    }
)
FORBIDDEN_SUFFIXES = (
    ".7z",
    ".crash",
    ".dmp",
    ".dmg",
    ".hprof",
    ".jar",
    ".jfr",
    ".faction",
    ".proj",
    ".rar",
    ".sav",
    ".save",
    ".ship",
    ".skin",
    ".variant",
    ".wpn",
    ".zip",
)
ALLOWED_BINARY_PREFIXES = (
    "preflight-desktop/src/assets/",
    "preflight-desktop/src-tauri/icons/",
)
ALLOWED_BINARY_SUFFIXES = frozenset({".icns", ".ico", ".png"})
LICENSED_FIXTURE_PREFIXES = (
    "preflight-core/src/test/resources/audio/ogg-v1/",
    "preflight-agent/src/main/resources/dev/starsector/preflight/agent/graphicslib-texture-data-",
)


class SourceBoundaryError(ValueError):
    pass


def validate_path(name: str) -> None:
    if not name or "\x00" in name or "\\" in name:
        raise SourceBoundaryError(f"unsafe repository path: {name!r}")
    path = PurePosixPath(name)
    if path.is_absolute() or any(part in {"", ".", ".."} for part in path.parts):
        raise SourceBoundaryError(f"unsafe repository path: {name!r}")
    lowered = [part.lower() for part in path.parts]
    forbidden = sorted(set(lowered) & FORBIDDEN_SEGMENTS)
    if forbidden:
        raise SourceBoundaryError(f"forbidden repository path segment {forbidden[0]!r}: {name}")
    basename = lowered[-1]
    if basename in FORBIDDEN_BASENAMES or basename.endswith(FORBIDDEN_SUFFIXES):
        raise SourceBoundaryError(f"forbidden repository file type: {name}")


def validate_blob(name: str, data: bytes) -> None:
    validate_path(name)
    if len(data) > MAX_REVIEWED_BLOB_BYTES and not reviewed_oversized_blob(name, data):
        raise SourceBoundaryError(
            f"repository blob exceeds {MAX_REVIEWED_BLOB_BYTES} reviewed bytes: {name} ({len(data)})"
        )
    binary = b"\x00" in data[:8192]
    if not binary:
        try:
            data.decode("utf-8")
        except UnicodeDecodeError:
            binary = True
    if binary and not allowed_binary(name):
        raise SourceBoundaryError(f"unexpected binary repository blob: {name}")


def reviewed_oversized_blob(name: str, data: bytes) -> bool:
    fingerprints = REVIEWED_OVERSIZED_BLOBS.get(name, frozenset())
    if not fingerprints:
        return False
    return (len(data), hashlib.sha256(data).hexdigest()) in fingerprints


def allowed_binary(name: str) -> bool:
    path = PurePosixPath(name)
    return name.startswith(ALLOWED_BINARY_PREFIXES) and path.suffix.lower() in ALLOWED_BINARY_SUFFIXES


def git(repository: Path, *args: str, input_data: bytes | None = None) -> bytes:
    result = subprocess.run(
        ["git", *args],
        cwd=repository,
        input=input_data,
        capture_output=True,
        check=False,
    )
    if result.returncode != 0:
        message = result.stderr.decode("utf-8", errors="replace").strip()
        raise SourceBoundaryError(f"git {' '.join(args)} failed: {message}")
    return result.stdout


def current_files(repository: Path) -> list[str]:
    names = git(repository, "ls-files", "-z").decode("utf-8").split("\x00")
    return [name for name in names if name]


def historical_blobs(repository: Path) -> dict[str, set[str]]:
    objects = git(repository, "rev-list", "--objects", "--all").decode("utf-8").splitlines()
    names_by_oid: dict[str, set[str]] = defaultdict(set)
    object_ids: list[str] = []
    for line in objects:
        oid, separator, name = line.partition(" ")
        object_ids.append(oid)
        if separator:
            names_by_oid[oid].add(name)
    if not object_ids:
        return {}
    checks = git(
        repository,
        "cat-file",
        "--batch-check=%(objectname) %(objecttype) %(objectsize)",
        input_data=("\n".join(object_ids) + "\n").encode(),
    ).decode("utf-8").splitlines()
    result: dict[str, set[str]] = {}
    for line in checks:
        oid, object_type, _size = line.split(" ", 2)
        if object_type == "blob" and names_by_oid.get(oid):
            result[oid] = names_by_oid[oid]
    return result


def read_blobs(repository: Path, object_ids: list[str]) -> dict[str, bytes]:
    if not object_ids:
        return {}
    process = subprocess.Popen(
        ["git", "cat-file", "--batch"],
        cwd=repository,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    assert process.stdin is not None and process.stdout is not None
    result: dict[str, bytes] = {}
    try:
        for oid in object_ids:
            process.stdin.write(f"{oid}\n".encode())
            process.stdin.flush()
            header = process.stdout.readline().decode("ascii").strip()
            actual_oid, object_type, size_text = header.split(" ", 2)
            if object_type != "blob":
                raise SourceBoundaryError(f"historical object isn't a blob: {oid}")
            size = int(size_text)
            data = process.stdout.read(size)
            terminator = process.stdout.read(1)
            if len(data) != size or terminator != b"\n":
                raise SourceBoundaryError(f"truncated historical blob: {oid}")
            result[actual_oid] = data
    finally:
        process.stdin.close()
        stderr = process.stderr.read() if process.stderr is not None else b""
        return_code = process.wait()
        if return_code != 0:
            raise SourceBoundaryError(
                f"git cat-file --batch failed: {stderr.decode('utf-8', errors='replace').strip()}"
            )
    return result


def validate_repository(repository: Path) -> dict[str, int]:
    repository = repository.resolve()
    if not (repository / ".git").exists():
        raise SourceBoundaryError(f"repository has no .git directory: {repository}")
    shallow = git(repository, "rev-parse", "--is-shallow-repository").decode("ascii").strip()
    if shallow != "false":
        raise SourceBoundaryError("source-history audit requires a complete, non-shallow Git checkout")

    tracked = current_files(repository)
    licensed_fixtures = 0
    binary_files = 0
    for name in tracked:
        path = repository / name
        if not path.is_file() or path.is_symlink():
            raise SourceBoundaryError(f"tracked entry isn't a regular file: {name}")
        data = path.read_bytes()
        validate_blob(name, data)
        binary_files += int(allowed_binary(name))
        licensed_fixtures += int(name.startswith(LICENSED_FIXTURE_PREFIXES))

    history = historical_blobs(repository)
    blobs = read_blobs(repository, sorted(history))
    historical_paths = 0
    for oid, names in history.items():
        data = blobs[oid]
        for name in names:
            validate_blob(name, data)
            historical_paths += 1

    return {
        "trackedFiles": len(tracked),
        "reviewedBinaryFiles": binary_files,
        "licensedFixtureFiles": licensed_fixtures,
        "historicalBlobs": len(history),
        "historicalPaths": historical_paths,
        "maxBlobBytes": MAX_REVIEWED_BLOB_BYTES,
        "reviewedOversizedBlobFingerprints": sum(
            len(fingerprints) for fingerprints in REVIEWED_OVERSIZED_BLOBS.values()
        ),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", type=Path, default=Path(__file__).resolve().parent.parent)
    args = parser.parse_args()
    try:
        report = validate_repository(args.repository)
    except (OSError, SourceBoundaryError) as exc:
        parser.error(str(exc))
    print(json.dumps(report, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
