#!/usr/bin/env python3
"""Verify that packaged Linux ELF files stay within the reviewed GLIBC symbol floor."""

from __future__ import annotations

import argparse
import json
import os
import re
import stat
import subprocess
import tempfile
from pathlib import Path

REPORT_FORMAT = "preflight-linux-glibc-floor-v1"
GLIBC = re.compile(r"\bGLIBC_(\d+)\.(\d+)\b")


class CompatibilityError(RuntimeError):
    pass


def version_tuple(value: str) -> tuple[int, int]:
    match = re.fullmatch(r"(\d+)\.(\d+)", value.strip())
    if not match:
        raise CompatibilityError(f"invalid GLIBC version: {value!r}")
    return int(match.group(1)), int(match.group(2))


def format_version(value: tuple[int, int]) -> str:
    return f"{value[0]}.{value[1]}"


def versions_from_readelf(text: str) -> set[tuple[int, int]]:
    return {(int(major), int(minor)) for major, minor in GLIBC.findall(text)}


def is_elf(path: Path) -> bool:
    try:
        with path.open("rb") as handle:
            return handle.read(4) == b"\x7fELF"
    except OSError:
        return False


def readelf_versions(path: Path) -> set[tuple[int, int]]:
    result = subprocess.run(
        ["readelf", "--version-info", "--wide", str(path)],
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    if result.returncode != 0:
        raise CompatibilityError(
            f"readelf failed for {path}: {(result.stderr or result.stdout).strip()}"
        )
    return versions_from_readelf(result.stdout)


def scan_elf_files(root: Path, extra_files: list[Path] | None = None) -> dict[str, set[tuple[int, int]]]:
    root = root.resolve()
    if not root.is_dir():
        raise CompatibilityError(f"package root is not a directory: {root}")
    discovered: dict[str, set[tuple[int, int]]] = {}
    for path in sorted(root.rglob("*")):
        if path.is_symlink() or not path.is_file() or not is_elf(path):
            continue
        discovered[path.relative_to(root).as_posix()] = readelf_versions(path)
    for path in extra_files or []:
        path = path.resolve()
        if path.is_file() and is_elf(path):
            discovered[f"@package/{path.name}"] = readelf_versions(path)
    return discovered


def assert_floor(
    files: dict[str, set[tuple[int, int]]], maximum: tuple[int, int]
) -> dict[str, object]:
    observed = max((version for versions in files.values() for version in versions), default=None)
    offenders = {
        name: sorted(format_version(version) for version in versions if version > maximum)
        for name, versions in files.items()
        if any(version > maximum for version in versions)
    }
    if offenders:
        details = ", ".join(f"{name}: {versions}" for name, versions in sorted(offenders.items()))
        raise CompatibilityError(
            f"packaged ELF files require GLIBC newer than {format_version(maximum)}: {details}"
        )
    return {
        "elfFilesChecked": len(files),
        "maximumAllowedGlibc": format_version(maximum),
        "maximumObservedGlibc": format_version(observed) if observed else None,
    }


def one_file(directory: Path, suffix: str) -> Path:
    matches = sorted(
        path for path in directory.rglob("*")
        if path.is_file() and path.name.lower().endswith(suffix.lower())
    )
    if len(matches) != 1:
        raise CompatibilityError(
            f"expected exactly one {suffix} package under {directory}, found {len(matches)}"
        )
    return matches[0]


def run_checked(command: list[str], *, cwd: Path | None = None, env: dict[str, str] | None = None) -> None:
    result = subprocess.run(
        command,
        cwd=cwd,
        env=env,
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    if result.returncode != 0:
        output = (result.stderr or result.stdout).strip()
        raise CompatibilityError(f"command failed ({' '.join(command)}): {output}")


def verify_bundle(bundle_directory: Path, maximum: tuple[int, int]) -> dict[str, object]:
    bundle_directory = bundle_directory.resolve()
    if not bundle_directory.is_dir():
        raise CompatibilityError(f"bundle directory does not exist: {bundle_directory}")
    deb = one_file(bundle_directory, ".deb")
    appimage = one_file(bundle_directory, ".appimage")

    package_reports = []
    with tempfile.TemporaryDirectory(prefix="preflight-glibc-") as temporary:
        temporary_root = Path(temporary)

        deb_root = temporary_root / "deb"
        deb_root.mkdir()
        run_checked(["dpkg-deb", "--extract", str(deb), str(deb_root)])
        deb_files = scan_elf_files(deb_root)
        package_reports.append({"package": deb.name, **assert_floor(deb_files, maximum)})

        app_root = temporary_root / "appimage"
        app_root.mkdir()
        mode = appimage.stat().st_mode
        appimage.chmod(mode | stat.S_IXUSR)
        env = os.environ.copy()
        env.setdefault("APPIMAGE_EXTRACT_AND_RUN", "1")
        run_checked([str(appimage), "--appimage-extract"], cwd=app_root, env=env)
        extracted = app_root / "squashfs-root"
        app_files = scan_elf_files(extracted, [appimage])
        package_reports.append({"package": appimage.name, **assert_floor(app_files, maximum)})

    observed = [
        version_tuple(report["maximumObservedGlibc"])
        for report in package_reports
        if report["maximumObservedGlibc"] is not None
    ]
    return {
        "format": REPORT_FORMAT,
        "maximumAllowedGlibc": format_version(maximum),
        "maximumObservedGlibc": format_version(max(observed)) if observed else None,
        "packages": package_reports,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle", type=Path, required=True)
    parser.add_argument("--max-glibc", default="2.35")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    try:
        report = verify_bundle(args.bundle, version_tuple(args.max_glibc))
    except CompatibilityError as error:
        parser.error(str(error))
    rendered = json.dumps(report, indent=2, sort_keys=True) + "\n"
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(rendered, encoding="utf-8")
    print(rendered, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
