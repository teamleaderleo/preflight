#!/usr/bin/env python3
"""Run bounded desktop feedback without replaying the full verification inventory."""

from __future__ import annotations

import argparse
import os
import shutil
import stat
import subprocess
import sys
from collections.abc import Callable, Sequence
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DESKTOP = ROOT / "preflight-desktop"
SOURCE_ROOT = DESKTOP / "src"
TEST_SUFFIXES = (".test.ts", ".test.tsx")


class SelectionError(ValueError):
    """The requested focused inventory could not be established exactly."""


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(
        description="Run explicit owner-focused desktop feedback; never implies full verification."
    )
    commands = result.add_subparsers(dest="command", required=True)
    frontend = commands.add_parser(
        "frontend",
        help="run adjacent Vitest files for one or more edited frontend paths",
    )
    frontend.add_argument(
        "--list",
        action="store_true",
        help="print the selected test inventory without running it",
    )
    frontend.add_argument(
        "paths",
        nargs="+",
        help="paths below preflight-desktop/src, written from the repository or desktop root",
    )
    return result


def is_test_name(name: str) -> bool:
    return name.endswith(TEST_SUFFIXES)


def lexical_source_path(raw: str, root: Path, desktop: Path) -> Path:
    requested = Path(raw)
    if requested.is_absolute():
        candidate = requested
    elif requested.parts and requested.parts[0] == desktop.name:
        candidate = root / requested
    else:
        candidate = desktop / requested
    return Path(os.path.abspath(candidate))


def require_regular_unlinked(path: Path, source_root: Path) -> None:
    try:
        relative = path.relative_to(source_root)
    except ValueError as error:
        raise SelectionError(f"frontend path escapes {source_root}: {path}") from error
    current = source_root
    for part in relative.parts:
        current /= part
        try:
            attributes = os.lstat(current)
        except FileNotFoundError as error:
            raise SelectionError(f"frontend path does not exist: {path}") from error
        if stat.S_ISLNK(attributes.st_mode):
            raise SelectionError(f"frontend path uses a symbolic link: {path}")
    if not stat.S_ISREG(os.lstat(path).st_mode):
        raise SelectionError(f"frontend path is not a regular file: {path}")


def adjacent_tests(path: Path) -> list[Path]:
    if is_test_name(path.name):
        return [path]
    if path.suffix not in {".ts", ".tsx"}:
        raise SelectionError(f"frontend path is not TypeScript: {path}")
    prefix = f"{path.stem}."
    return sorted(
        candidate
        for candidate in path.parent.iterdir()
        if candidate.is_file()
        and not candidate.is_symlink()
        and candidate.name.startswith(prefix)
        and is_test_name(candidate.name)
    )


def select_frontend_tests(
    raw_paths: Sequence[str],
    *,
    root: Path = ROOT,
    desktop: Path = DESKTOP,
    source_root: Path = SOURCE_ROOT,
) -> list[Path]:
    lexical_source_root = Path(os.path.abspath(source_root))
    selected: set[Path] = set()
    missing: list[str] = []
    for raw in raw_paths:
        path = lexical_source_path(raw, root, desktop)
        require_regular_unlinked(path, lexical_source_root)
        nearby = adjacent_tests(path)
        if not nearby:
            missing.append(str(path.relative_to(Path(os.path.abspath(root)))))
            continue
        selected.update(nearby)
    if missing:
        joined = ", ".join(sorted(missing))
        raise SelectionError(
            "no adjacent test file for: "
            f"{joined}; pass an exact *.test.ts or *.test.tsx path instead"
        )
    return sorted(selected)


def print_inventory(tests: Sequence[Path], desktop: Path = DESKTOP) -> None:
    print(f"Focused frontend inventory ({len(tests)} files):")
    for test in tests:
        print(f"  {test.relative_to(desktop).as_posix()}")
    print("Scope: adjacent tests only; this is not transitive or full frontend verification.")


def run_frontend_tests(
    tests: Sequence[Path],
    *,
    desktop: Path = DESKTOP,
    runner: Callable[..., subprocess.CompletedProcess[object]] = subprocess.run,
    which: Callable[[str], str | None] = shutil.which,
) -> int:
    vitest_package = desktop / "node_modules" / "vitest" / "package.json"
    if not vitest_package.is_file() or vitest_package.is_symlink():
        raise SelectionError(
            f"desktop dependencies are absent or untrusted; run `npm ci` in {desktop}"
        )
    executable = "npm.cmd" if os.name == "nt" else "npm"
    npm = which(executable)
    if npm is None:
        raise SelectionError(f"{executable} is not available on PATH")
    relative_tests = [test.relative_to(desktop).as_posix() for test in tests]
    completed = runner([npm, "test", "--", *relative_tests], cwd=desktop)
    return completed.returncode


def main(argv: Sequence[str] | None = None) -> int:
    args = parser().parse_args(argv)
    try:
        tests = select_frontend_tests(args.paths)
        print_inventory(tests)
        if args.list:
            return 0
        sys.stdout.flush()
        return run_frontend_tests(tests)
    except (OSError, SelectionError, ValueError) as error:
        print(f"desktop-dev refused: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
