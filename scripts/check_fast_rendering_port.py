#!/usr/bin/env python3
"""Compare local optional-renderer release archives with the reviewed identity; never execute them."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import zipfile

LOCK = Path(__file__).resolve().parents[1] / "docs" / "fast-rendering-port-lock.json"
MAX_ARCHIVE_BYTES = 8 * 1024 * 1024


def verify_archive(path, expected):
    if path.stat().st_size > MAX_ARCHIVE_BYTES:
        raise ValueError(f"{path.name}: archive exceeds reviewed size limit")
    with path.open("rb") as stream:
        data = stream.read(MAX_ARCHIVE_BYTES + 1)
    if len(data) > MAX_ARCHIVE_BYTES:
        raise ValueError(f"{path.name}: archive grew past size limit")
    if hashlib.sha256(data).hexdigest() != expected["sha256"]:
        raise ValueError(f"{path.name}: release bytes changed; review before updating the lock")
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        names = archive.namelist()
        for name, digest in expected["entries"].items():
            if names.count(name) != 1:
                raise ValueError(f"{path.name}: missing or duplicate entry {name}")
            entry = archive.getinfo(name)
            if entry.file_size > MAX_ARCHIVE_BYTES:
                raise ValueError(f"{path.name}: oversized entry {name}")
            if hashlib.sha256(archive.read(entry)).hexdigest() != digest:
                raise ValueError(f"{path.name}: entry changed: {name}")
    return {"asset": path.name, "identity": "matched", "entries": len(expected["entries"])}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive_directory", type=Path)
    args = parser.parse_args()
    lock = json.loads(LOCK.read_text())
    try:
        results = [verify_archive(args.archive_directory / name, expected)
                   for name, expected in lock["assets"].items()]
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        parser.exit(1, f"{error}\n")
    print(json.dumps({"release": lock["release"], "assets": results,
                      "runtimeCompatibility": "not-established-by-identity-check",
                      "preparedTextureBridge": lock["preparedTextureBridge"]}, indent=2))


if __name__ == "__main__":
    main()
