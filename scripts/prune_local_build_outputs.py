#!/usr/bin/env python3
"""Bound generated binaries across this repository's local Git worktrees."""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path


GENERATED_PATHS = (
    "target",
    "preflight-agent/target",
    "preflight-cli/target",
    "preflight-core/target",
    "preflight-synthetic-startup/target",
    "preflight-desktop/dist",
    "preflight-desktop/.ui-matrix",
    "preflight-desktop/desktop-dist",
    "preflight-desktop/node_modules/.preflight-ui-layout",
    "preflight-desktop/scripts/__pycache__",
    "preflight-desktop/src-tauri/target",
    "report-intake/dist",
    "scripts/__pycache__",
)


@dataclass(frozen=True)
class BuildSet:
    root: Path
    outputs: tuple[Path, ...]
    newest_mtime: float
    current: bool = False
    dirty: bool = False
    total_bytes: int = 0


@dataclass(frozen=True)
class Decision:
    build: BuildSet
    action: str
    reason: str


def git(*args: str, cwd: Path | None = None) -> str:
    completed = subprocess.run(
        ("git", *args),
        cwd=cwd,
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    return completed.stdout


def repository_root() -> Path:
    return Path(git("rev-parse", "--show-toplevel").strip()).resolve()


def registered_worktrees() -> list[Path]:
    roots = []
    for line in git("worktree", "list", "--porcelain").splitlines():
        if line.startswith("worktree "):
            root = Path(line.removeprefix("worktree "))
            if root.is_dir():
                roots.append(root.resolve())
    return roots


def has_source_changes(root: Path) -> bool:
    return bool(git("status", "--porcelain", "--untracked-files=normal", cwd=root).strip())


def output_metrics(path: Path) -> tuple[int, float]:
    total = 0
    newest_mtime = path.lstat().st_mtime
    for directory, child_directories, filenames in os.walk(path, followlinks=False):
        directory_path = Path(directory)
        for name in (*child_directories, *filenames):
            stat = (directory_path / name).lstat()
            total += stat.st_size
            newest_mtime = max(newest_mtime, stat.st_mtime)
    return total, newest_mtime


def format_bytes(total: int) -> str:
    value = float(total)
    for unit in ("B", "KiB", "MiB", "GiB", "TiB"):
        if value < 1024 or unit == "TiB":
            return f"{value:.1f} {unit}"
        value /= 1024
    raise AssertionError("unreachable")


def discover_build_sets(current_root: Path) -> list[BuildSet]:
    builds = []
    for root in registered_worktrees():
        outputs = tuple(root / relative for relative in GENERATED_PATHS if (root / relative).exists())
        if not outputs:
            continue
        symlinks = [path for path in outputs if path.is_symlink()]
        if symlinks:
            joined = ", ".join(str(path) for path in symlinks)
            raise RuntimeError(f"refusing symlinked build output: {joined}")
        metrics = [output_metrics(path) for path in outputs]
        builds.append(BuildSet(
            root=root,
            outputs=outputs,
            newest_mtime=max(newest_mtime for _, newest_mtime in metrics),
            current=root == current_root,
            dirty=has_source_changes(root),
            total_bytes=sum(total_bytes for total_bytes, _ in metrics),
        ))
    return builds


def choose_build_sets(
    builds: list[BuildSet],
    *,
    now: float,
    keep_completed: int,
    minimum_age_hours: float,
    maximum_age_hours: float = 72,
    retire_current: bool = False,
) -> list[Decision]:
    if keep_completed < 0:
        raise ValueError("keep_completed must not be negative")
    if minimum_age_hours < 0:
        raise ValueError("minimum_age_hours must not be negative")
    if maximum_age_hours < minimum_age_hours:
        raise ValueError("maximum_age_hours must be at least minimum_age_hours")

    completed = sorted(
        (build for build in builds if not build.current and not build.dirty),
        key=lambda build: (build.newest_mtime, str(build.root)),
        reverse=True,
    )
    retained = {build.root for build in completed[:keep_completed]}
    minimum_age_seconds = minimum_age_hours * 60 * 60
    decisions = []
    for build in sorted(builds, key=lambda candidate: str(candidate.root)):
        age_hours = max(0.0, now - build.newest_mtime) / 3600
        if build.current:
            if retire_current and build.dirty:
                decisions.append(Decision(build, "keep", "current worktree has source changes"))
            elif retire_current:
                decisions.append(Decision(build, "remove", "explicitly retiring clean current worktree"))
            else:
                decisions.append(Decision(build, "keep", "current worktree"))
        elif now - build.newest_mtime >= maximum_age_hours * 60 * 60:
            detail = f"{age_hours:.1f} hours old; beyond {maximum_age_hours:g}-hour retention limit"
            if build.dirty:
                detail += "; source changes remain untouched"
            decisions.append(Decision(build, "remove", detail))
        elif build.root in retained:
            decisions.append(Decision(
                build,
                "keep",
                (
                    f"newest completed build set; {age_hours:.1f} hours old, "
                    f"expires at {maximum_age_hours:g} hours"
                ),
            ))
        elif now - build.newest_mtime < minimum_age_seconds:
            decisions.append(Decision(build, "keep", f"only {age_hours:.1f} hours old"))
        else:
            detail = f"{age_hours:.1f} hours old"
            if build.dirty:
                detail += "; source changes remain untouched"
            decisions.append(Decision(build, "remove", detail))
    return decisions


def remove_outputs(build: BuildSet) -> None:
    root = build.root.resolve()
    for output in build.outputs:
        resolved_parent = output.parent.resolve()
        if root != resolved_parent and root not in resolved_parent.parents:
            raise RuntimeError(f"refusing build output outside its worktree: {output}")
        if output.is_symlink():
            raise RuntimeError(f"refusing symlinked build output: {output}")
    for output in build.outputs:
        shutil.rmtree(output)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Remove rebuildable Maven, Rust, frontend, and package outputs from old registered "
            "worktrees. The current worktree is retained unless explicitly retired after its "
            "source is clean; source changes are never removed."
        )
    )
    parser.add_argument(
        "--apply",
        action="store_true",
        help="apply the displayed plan; without this flag the command is read-only",
    )
    parser.add_argument(
        "--keep-completed",
        type=int,
        default=0,
        help="number of newest clean, non-current build sets to retain after 24 hours (default: 0)",
    )
    parser.add_argument(
        "--minimum-age-hours",
        type=float,
        default=24,
        help="never remove build sets younger than this (default: 24)",
    )
    parser.add_argument(
        "--maximum-age-hours",
        type=float,
        default=72,
        help="remove non-current build sets at or beyond this age, even the newest (default: 72)",
    )
    parser.add_argument(
        "--retire-current",
        action="store_true",
        help=(
            "include generated output from the current worktree once its source tree is clean; "
            "this explicit retirement is not subject to the age or completed-set retention floor"
        ),
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        current_root = repository_root()
        decisions = choose_build_sets(
            discover_build_sets(current_root),
            now=time.time(),
            keep_completed=args.keep_completed,
            minimum_age_hours=args.minimum_age_hours,
            maximum_age_hours=args.maximum_age_hours,
            retire_current=args.retire_current,
        )
        removed_outputs = 0
        removed_bytes = 0
        for decision in decisions:
            paths = ", ".join(str(path.relative_to(decision.build.root)) for path in decision.build.outputs)
            if decision.action == "remove" and args.apply:
                remove_outputs(decision.build)
                verb = "REMOVED"
                removed_outputs += len(decision.build.outputs)
                removed_bytes += decision.build.total_bytes
            elif decision.action == "remove":
                verb = "WOULD REMOVE"
            else:
                verb = "KEEP"
            print(
                f"{verb}: {decision.build.root} ({decision.reason}; "
                f"{format_bytes(decision.build.total_bytes)}) [{paths}]"
            )
        if not args.apply and any(decision.action == "remove" for decision in decisions):
            print("Dry run only. Pass --apply to remove the listed rebuildable outputs.")
        elif args.apply:
            print(
                f"Removed {removed_outputs} generated output directories "
                f"({format_bytes(removed_bytes)} logical bytes)."
            )
        return 0
    except (OSError, RuntimeError, subprocess.CalledProcessError, ValueError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
