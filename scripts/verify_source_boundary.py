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
            (2_092_359, "77c2712d281195fc1a1be3db12a580e4840da5ee07ac1a34883e025f6c7da160"),
            (2_171_363, "e013913f5072f38405bb0d664b3837c8c5c483144c2924094355095e06d55a08"),
            (2_306_590, "7e3be710fe97bb6cc566fccd62408db5734c8932282611f46fbe53cbf84b1b53"),
            (2_853_963, "3e6c91921e576fc00a7cb937ff437acc0615099bf29c774d8521c1fe78f08a5e"),
            (1_746_720, "05ca5e3efb0c65533cdb45e944ae3e941651cd0da45a9a8e30e72b8ca76c0a7f"),
            (1_201_813, "9f600da93bdd51b2e4a94bda5a111fc53559fe478781e29b2e27f44b13ac8e7d"),
            (1_638_831, "376cef6ce98eff08e963582ae39657884f3d86ef3de69d4522e77c9c9bef6af7"),
            (1_972_176, "37e3592a2376d7bd4dc3cc32bfc18a967c4d2114a6972afafe1c0b85835dedbf"),
            (2_046_260, "294ea99d507c5c30707e4c67fbb2de843b6e237e18fe7b4ff8cd3a3c7e2547b6"),
            (2_119_411, "af22e6391350c15a0b9067d685c34891e3ce5ea63c416bee47a94db9200c0a45"),
        }
    )
}
# Documentation screenshots are reviewed as exact Git blobs. Keeping the byte size
# alongside the blob id makes accidental truncation or substitution obvious and
# lets the history audit accept only the reviewed image bytes.
REVIEWED_DOCUMENTATION_IMAGES = {
    "docs/images/desktop-home-dark.png": frozenset(
        {
            (61_109, "6b8308d1b71cb8e85a350bfb6d31956c51556ee5"),
            (56_247, "ca01e532a00e804e5cca00c6f85028ab81b0fbdb"),
            (311_930, "cc60150e4eab29122b717b3dc2c3cbcbdd239111"),
            (276_824, "f1d0360b221694c0fc233c31fe5b1e2fa28d2800"),
            (330_112, "be8130c24b2552eff4525e11df2ae395f65e2aa6"),
        }
    ),
    "docs/images/desktop-home-light.png": frozenset(
        {
            (64_418, "1c4517a2161186f34a79ea9fffa66db79f28f167"),
            (62_880, "a042bb65181efe99c41e2f7132a96f6ec8ca9c4e"),
            (342_496, "fea75bae343a0352cc5ee7d11c60218289645f0e"),
            (225_729, "8e2cd80e328c2a6d00f11ea4df7df6b42017a405"),
            (271_531, "b37ce69cc922ebf9d056589a111517f4b2a63b9f"),
        }
    ),
    "docs/images/desktop-profiles-light.png": frozenset(
        {
            (59_424, "b206041df4ee61123b3a6a47b492270bbcae4ac6"),
            (56_409, "4402dd545b806b5667f442e7370bfa3b3f3f8cf9"),
            (226_973, "056ce6773da636d6d98b261f9b347a7b2a7099e0"),
            (145_567, "7c01323ab0b7f55d6b9387ca674458353f54f225"),
            (166_216, "16cec8402f5c9ac3d284cf8e7779d7e03fa1f7ac"),
        }
    ),
    "docs/images/walkthrough-benchmark.png": frozenset(
        {
            (56_692, "69fb4cf05512f189151cf1e0f58085a6be6162fe"),
            (56_508, "b36aa4e523f1f850179093388f5ea38a18deb066"),
            (231_566, "304629870fc89b19b0a913efa70df4beb131c7be"),
            (135_908, "3cc5d7a2a7fcf7d6054040dc64e067a6380f710c"),
            (152_308, "8745d6436fbfce9432ee3f2a2c37bc2a35480f84"),
        }
    ),
    "docs/images/walkthrough-ready.png": frozenset(
        {
            (61_105, "7e413e9e2e55678192dd4c9f5a04673fc20f4cfa"),
            (56_016, "e8676673464db9e897c831e69813bb08d7bb83c4"),
            (213_139, "57e61befeba4174c4ef2dd5a454623248200cbfa"),
            (125_297, "7d87378f447f9e9532ef214d1d636280199e0922"),
            (126_779, "0448bd1d2daad5dd90496dfe80a406c59dd434e4"),
            (178_803, "252e5c87318f8966de92b994b7ba955074263f4c"),
        }
    ),
    "docs/images/walkthrough-setup.png": frozenset(
        {
            (50_674, "f41cfd715b7129a3439e3d3deb9e8886d52e90fd"),
            (49_295, "2922b4b63d94ac7a3f1eaca5adc886914199deb1"),
            (188_622, "c606b45a65933bb5ff35dc792bbd19ccc80936d3"),
            (124_928, "44c2f93da0cf6846cb6325b41c402fcbc565df92"),
            (128_919, "0fff54c587604aea528cad0d1c61248fac5fc8cd"),
        }
    ),
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
REVIEWED_FIXTURE_PREFIXES = (
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
    if binary and not (allowed_binary(name) or reviewed_documentation_image(name, data)):
        raise SourceBoundaryError(f"unexpected binary repository blob: {name}")


def reviewed_oversized_blob(name: str, data: bytes) -> bool:
    fingerprints = REVIEWED_OVERSIZED_BLOBS.get(name, frozenset())
    if not fingerprints:
        return False
    return (len(data), hashlib.sha256(data).hexdigest()) in fingerprints


def allowed_binary(name: str) -> bool:
    path = PurePosixPath(name)
    return name.startswith(ALLOWED_BINARY_PREFIXES) and path.suffix.lower() in ALLOWED_BINARY_SUFFIXES


def git_blob_sha1(data: bytes) -> str:
    header = f"blob {len(data)}\0".encode("ascii")
    return hashlib.sha1(header + data).hexdigest()


def reviewed_documentation_image(name: str, data: bytes) -> bool:
    fingerprints = REVIEWED_DOCUMENTATION_IMAGES.get(name, frozenset())
    if not fingerprints:
        return False
    return (len(data), git_blob_sha1(data)) in fingerprints


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
    # The PR checkout fetches sibling refs to provide complete ancestry. Those refs are independent
    # review surfaces: audit only objects reachable from the checked-out merge HEAD and its parents.
    objects = git(repository, "rev-list", "--objects", "HEAD").decode("utf-8").splitlines()
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
    result: dict[str, bytes] = {}
    with subprocess.Popen(
        ["git", "cat-file", "--batch"],
        cwd=repository,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    ) as process:
        assert process.stdin is not None and process.stdout is not None
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
    reviewed_fixtures = 0
    binary_files = 0
    for name in tracked:
        path = repository / name
        if not path.is_file() or path.is_symlink():
            raise SourceBoundaryError(f"tracked entry isn't a regular file: {name}")
        data = path.read_bytes()
        validate_blob(name, data)
        binary_files += int(allowed_binary(name) or reviewed_documentation_image(name, data))
        reviewed_fixtures += int(name.startswith(REVIEWED_FIXTURE_PREFIXES))

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
        "reviewedFixtureFiles": reviewed_fixtures,
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
