#!/usr/bin/env python3
"""Snapshot and compare campaign saves around a human-operated gameplay pilot."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import stat
import sys
from pathlib import Path


FORMAT = "preflight-campaign-save-state-v1"
MAX_SNAPSHOT_BYTES = 4 * 1024 * 1024


class GuardError(Exception):
    pass


def _selected_name(value: str) -> str:
    candidate = Path(value)
    if value in {"", ".", ".."} or candidate.name != value or len(candidate.parts) != 1:
        raise GuardError("selected save must be one directory name, not a path")
    if not value.startswith("save_"):
        raise GuardError("selected save must use Starsector's save_ directory prefix")
    return value


def _stable_file_digest(path: Path) -> tuple[int, str]:
    before = path.stat(follow_symlinks=False)
    if not stat.S_ISREG(before.st_mode):
        raise GuardError(f"save entry is not a regular file: {path}")
    if before.st_nlink != 1:
        raise GuardError(f"save entry is hard-linked and not an independent copy: {path}")
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    after = path.stat(follow_symlinks=False)
    identity_before = (
        before.st_dev, before.st_ino, before.st_nlink, before.st_size, before.st_mtime_ns
    )
    identity_after = (
        after.st_dev, after.st_ino, after.st_nlink, after.st_size, after.st_mtime_ns
    )
    if identity_before != identity_after:
        raise GuardError(f"save entry changed while it was being hashed: {path}")
    return after.st_size, digest.hexdigest()


def _campaign_digest(directory: Path) -> dict[str, object]:
    canonical = hashlib.sha256()
    files = 0
    total_bytes = 0

    def walk_error(error: OSError) -> None:
        raise GuardError(f"save tree could not be read: {error}")

    for root_text, directories, names in os.walk(
            directory, followlinks=False, onerror=walk_error
    ):
        root = Path(root_text)
        directories.sort()
        names.sort()
        for name in list(directories):
            child = root / name
            if child.is_symlink():
                raise GuardError(f"save tree contains a symbolic-link directory: {child}")
            mode = child.stat(follow_symlinks=False).st_mode
            if not stat.S_ISDIR(mode):
                raise GuardError(f"save tree contains a non-directory entry: {child}")
            relative = child.relative_to(directory).as_posix()
            canonical.update(f"directory\0{relative}\n".encode())
        for name in names:
            child = root / name
            if child.is_symlink():
                raise GuardError(f"save tree contains a symbolic-link file: {child}")
            relative = child.relative_to(directory).as_posix()
            size, digest = _stable_file_digest(child)
            canonical.update(f"file\0{relative}\0{size}\0{digest}\n".encode())
            files += 1
            total_bytes += size
    return {"sha256": canonical.hexdigest(), "bytes": total_bytes, "files": files}


def snapshot(
        saves_directory: Path, selected_save: str, *, require_selected: bool = True
) -> dict[str, object]:
    selected_save = _selected_name(selected_save)
    if saves_directory.is_symlink():
        raise GuardError("saves directory must not be a symbolic link")
    try:
        resolved = saves_directory.resolve(strict=True)
    except FileNotFoundError as error:
        raise GuardError(f"saves directory is unavailable: {saves_directory}") from error
    if not resolved.is_dir():
        raise GuardError(f"saves path is not a directory: {resolved}")

    campaigns: dict[str, object] = {}
    for child in sorted(resolved.iterdir(), key=lambda path: path.name):
        if not child.name.startswith("save_"):
            continue
        if child.is_symlink():
            raise GuardError(f"campaign save must not be a symbolic link: {child}")
        if not child.is_dir():
            raise GuardError(f"campaign save is not a directory: {child}")
        campaigns[child.name] = _campaign_digest(child)
    if require_selected and selected_save not in campaigns:
        raise GuardError(f"selected disposable save is unavailable: {resolved / selected_save}")
    return {
        "format": FORMAT,
        "scope": "campaign save_* content; global saves/common state is excluded",
        "savesDirectory": str(resolved),
        "selectedSave": selected_save,
        "campaignSaves": campaigns,
    }


def _write_json(path: Path, value: dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def _load_snapshot(path: Path) -> dict[str, object]:
    if path.stat().st_size > MAX_SNAPSHOT_BYTES:
        raise GuardError("save-state snapshot exceeds 4 MiB")
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict) or value.get("format") != FORMAT:
        raise GuardError("save-state snapshot has an unsupported format")
    if not isinstance(value.get("savesDirectory"), str):
        raise GuardError("save-state snapshot lacks its saves directory")
    if not isinstance(value.get("selectedSave"), str):
        raise GuardError("save-state snapshot lacks its selected save")
    if not isinstance(value.get("campaignSaves"), dict):
        raise GuardError("save-state snapshot lacks campaign identities")
    return value


def compare(before_path: Path, saves_directory: Path) -> dict[str, object]:
    before = _load_snapshot(before_path)
    expected_directory = Path(str(before["savesDirectory"])).resolve(strict=True)
    actual_directory = saves_directory.resolve(strict=True)
    if actual_directory != expected_directory:
        raise GuardError("comparison saves directory differs from the snapshot")
    selected = str(before["selectedSave"])
    after = snapshot(actual_directory, selected, require_selected=False)
    before_campaigns = before["campaignSaves"]
    after_campaigns = after["campaignSaves"]
    assert isinstance(before_campaigns, dict)
    assert isinstance(after_campaigns, dict)

    changed = sorted(
        name for name in set(before_campaigns) | set(after_campaigns)
        if before_campaigns.get(name) != after_campaigns.get(name)
    )
    unexpected = [name for name in changed if name != selected]
    selected_present = selected in before_campaigns and selected in after_campaigns
    selected_changed = selected_present and selected in changed
    reasons: list[str] = []
    if not selected_present:
        reasons.append("the selected disposable save was removed")
    elif not selected_changed:
        reasons.append("the selected disposable save did not change; a save write was not observed")
    if unexpected:
        reasons.append("campaign saves other than the selected disposable copy changed")
    accepted = selected_present and selected_changed and not unexpected
    return {
        "format": FORMAT,
        "accepted": accepted,
        "scope": after["scope"],
        "savesDirectory": after["savesDirectory"],
        "selectedSave": selected,
        "selectedSavePresent": selected_present,
        "selectedSaveChanged": selected_changed,
        "otherCampaignSavesUnchanged": not unexpected,
        "changedCampaignSaves": changed,
        "unexpectedChangedCampaignSaves": unexpected,
        "reasons": reasons,
        "before": before_campaigns,
        "after": after_campaigns,
    }


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)
    snapshot_parser = subparsers.add_parser("snapshot")
    snapshot_parser.add_argument("--saves-dir", type=Path, required=True)
    snapshot_parser.add_argument("--selected", required=True)
    snapshot_parser.add_argument("--output", type=Path, required=True)
    compare_parser = subparsers.add_parser("compare")
    compare_parser.add_argument("--before", type=Path, required=True)
    compare_parser.add_argument("--saves-dir", type=Path, required=True)
    compare_parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    try:
        if args.command == "snapshot":
            _write_json(args.output, snapshot(args.saves_dir, args.selected))
            return 0
        result = compare(args.before, args.saves_dir)
        _write_json(args.output, result)
        return 0 if result["accepted"] else 1
    except (GuardError, FileNotFoundError, json.JSONDecodeError, OSError) as error:
        print(f"Save-state guard: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
