#!/usr/bin/env python3
"""Identify and content-hash the campaign save loaded by the latest game log."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import stat
import sys
from pathlib import Path
from typing import Any


FORMAT = "starsector-preflight-loaded-save-identity-v1"
MAX_LOG_BYTES = 128 * 1024 * 1024
SAVE_MARKERS = (
    re.compile(
        r"Reading save data from \[(?:[^\]\r\n]*/)?saves/"
        r"(save_[^/\]\r\n]+)/descriptor\.xml\]"
    ),
    re.compile(r"Loading (?:[^\r\n]*/)?saves/(save_[^\r\n]+?)\.\.\."),
)


class IdentityError(Exception):
    pass


def read_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise IdentityError(f"expected JSON object: {path}")
    return value


def selected_save_from_log(log_path: Path) -> str:
    before = log_path.stat(follow_symlinks=False)
    if not stat.S_ISREG(before.st_mode):
        raise IdentityError(f"game log is not a regular file: {log_path}")
    if before.st_size > MAX_LOG_BYTES:
        raise IdentityError(f"game log exceeds {MAX_LOG_BYTES} bytes: {log_path}")
    data = log_path.read_text(encoding="utf-8", errors="replace")
    after = log_path.stat(follow_symlinks=False)
    if (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns) != (
        after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns
    ):
        raise IdentityError(f"game log changed while it was being read: {log_path}")
    matches = [
        (match.start(), match.group(1))
        for pattern in SAVE_MARKERS
        for match in pattern.finditer(data)
    ]
    if not matches:
        raise IdentityError(f"game log has no loaded campaign-save marker: {log_path}")
    selected = max(matches, key=lambda item: item[0])[1]
    candidate = Path(selected)
    if (
        candidate.name != selected
        or len(candidate.parts) != 1
        or selected in {"", ".", ".."}
        or not selected.startswith("save_")
        or len(selected.encode("utf-8")) > 255
    ):
        raise IdentityError("loaded save marker is not a valid save_ directory name")
    return selected


def stable_digest(path: Path) -> tuple[int, int, str]:
    before = path.stat(follow_symlinks=False)
    if not stat.S_ISREG(before.st_mode):
        raise IdentityError(f"save entry is not a regular file: {path}")
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        opened_before = os.fstat(stream.fileno())
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
        opened_after = os.fstat(stream.fileno())
    after = path.stat(follow_symlinks=False)
    identities = {
        (entry.st_dev, entry.st_ino, entry.st_size, entry.st_mtime_ns)
        for entry in (before, opened_before, opened_after, after)
    }
    if len(identities) != 1:
        raise IdentityError(f"save entry changed while it was being hashed: {path}")
    return after.st_size, after.st_mtime_ns, digest.hexdigest()


def save_tree_identity(save_directory: Path) -> dict[str, Any]:
    canonical = hashlib.sha256()
    entries: dict[str, dict[str, Any]] = {}
    total_bytes = 0

    def walk_error(error: OSError) -> None:
        raise IdentityError(f"loaded save could not be read: {error}")

    for root_text, directories, names in os.walk(
        save_directory, followlinks=False, onerror=walk_error
    ):
        root = Path(root_text)
        directories.sort()
        names.sort()
        for name in directories:
            child = root / name
            if child.is_symlink() or not stat.S_ISDIR(child.stat(follow_symlinks=False).st_mode):
                raise IdentityError(f"loaded save contains an unsafe directory: {child}")
            relative = child.relative_to(save_directory).as_posix()
            canonical.update(f"directory\0{relative}\n".encode())
        for name in names:
            child = root / name
            if child.is_symlink():
                raise IdentityError(f"loaded save contains a symbolic-link file: {child}")
            relative = child.relative_to(save_directory).as_posix()
            size, mtime_ns, digest = stable_digest(child)
            canonical.update(f"file\0{relative}\0{size}\0{digest}\n".encode())
            entries[relative] = {
                "bytes": size,
                "mtimeNs": mtime_ns,
                "sha256": digest,
            }
            total_bytes += size
    if not entries:
        raise IdentityError(f"loaded save contains no files: {save_directory}")
    return {
        "treeSha256": canonical.hexdigest(),
        "bytes": total_bytes,
        "files": len(entries),
        "entries": entries,
    }


def capture(game: Path, log_path: Path, before_path: Path | None = None) -> dict[str, Any]:
    game = game.resolve(strict=True)
    saves = (game / "saves").resolve(strict=True)
    expected_log_root = (game / "logs").resolve(strict=True)
    log_path = log_path.resolve(strict=True)
    if log_path.parent != expected_log_root:
        raise IdentityError("game log must be a direct child of the selected install's logs directory")
    selected = selected_save_from_log(log_path)
    selected_path = saves / selected
    if selected_path.is_symlink():
        raise IdentityError("loaded save directory must not be a symbolic link")
    save_directory = selected_path.resolve(strict=True)
    if (
        save_directory.parent != saves
        or save_directory.name != selected
        or not save_directory.is_dir()
    ):
        raise IdentityError("loaded save resolved outside the selected install's saves directory")
    tree = save_tree_identity(save_directory)
    result: dict[str, Any] = {
        "format": FORMAT,
        "installRoot": str(game),
        "selectedSave": selected,
        "tree": tree,
        "comparison": {
            "beforeAvailable": False,
            "sameSelectedSave": None,
            "contentUnchanged": None,
        },
    }
    if before_path is not None:
        before = read_json(before_path)
        if before.get("format") != FORMAT or not isinstance(before.get("tree"), dict):
            raise IdentityError(f"pre-run save identity has an unsupported format: {before_path}")
        same_save = before.get("selectedSave") == selected
        unchanged = same_save and before["tree"].get("treeSha256") == tree["treeSha256"]
        result["comparison"] = {
            "beforeAvailable": True,
            "sameSelectedSave": same_save,
            "contentUnchanged": unchanged,
            "beforeTreeSha256": before["tree"].get("treeSha256"),
        }
    return result


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--game", required=True, type=Path)
    parser.add_argument("--log", type=Path)
    parser.add_argument("--before", type=Path)
    args = parser.parse_args(argv)
    log_path = args.log if args.log is not None else args.game / "logs" / "starsector.log"
    try:
        result = capture(args.game, log_path, args.before)
    except (OSError, IdentityError, json.JSONDecodeError) as failure:
        print(f"loaded save identity: {failure}", file=sys.stderr)
        return 1
    json.dump(result, sys.stdout, indent=2, sort_keys=True)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
