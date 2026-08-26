#!/usr/bin/env python3
"""Validate the currently tracked source tree without replaying Git history."""

from __future__ import annotations

import json
from pathlib import Path

import verify_source_boundary as boundary

ROOT = Path(__file__).resolve().parent.parent


def validate_current_repository(repository: Path) -> dict[str, int]:
    repository = repository.resolve()
    tracked = boundary.current_files(repository)
    reviewed_fixtures = 0
    binary_files = 0
    for name in tracked:
        path = repository / name
        if not path.is_file() or path.is_symlink():
            raise boundary.SourceBoundaryError(f"tracked entry isn't a regular file: {name}")
        data = path.read_bytes()
        boundary.validate_blob(name, data)
        binary_files += int(
            boundary.allowed_binary(name) or boundary.reviewed_documentation_image(name, data)
        )
        reviewed_fixtures += int(name.startswith(boundary.REVIEWED_FIXTURE_PREFIXES))
    return {
        "trackedFiles": len(tracked),
        "reviewedBinaryFiles": binary_files,
        "reviewedFixtureFiles": reviewed_fixtures,
        "maxBlobBytes": boundary.MAX_REVIEWED_BLOB_BYTES,
    }


def main() -> int:
    print(json.dumps(validate_current_repository(ROOT), sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
