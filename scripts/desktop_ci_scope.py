#!/usr/bin/env python3
"""Decide which expensive desktop CI jobs a change needs."""

from __future__ import annotations

import sys
from collections.abc import Iterable

PACKAGE_EXACT = {
    ".github/workflows/desktop-ci.yml",
    "pom.xml",
    "preflight-desktop/package.json",
    "preflight-desktop/package-lock.json",
    "scripts/verify_linux_glibc_floor.py",
    "scripts/write_linux_builder_provenance.py",
}
PACKAGE_PREFIXES = (
    ".github/actions/setup-build-jdk/",
    "preflight-desktop/scripts/",
    "preflight-desktop/src-tauri/",
)

NATIVE_EXACT = {
    ".github/workflows/desktop-ci.yml",
}
NATIVE_PREFIXES = (
    "preflight-desktop/src-tauri/",
)


def needs_package_matrix(paths: Iterable[str]) -> bool:
    for raw_path in paths:
        path = raw_path.strip().replace("\\", "/")
        if not path:
            continue
        if path in PACKAGE_EXACT or path.startswith(PACKAGE_PREFIXES):
            return True
    return False


def needs_native_validation(paths: Iterable[str]) -> bool:
    for raw_path in paths:
        path = raw_path.strip().replace("\\", "/")
        if not path:
            continue
        if path in NATIVE_EXACT or path.startswith(NATIVE_PREFIXES):
            return True
    return False


def main() -> int:
    mode = sys.argv[1] if len(sys.argv) > 1 else "package"
    if mode == "package":
        result = needs_package_matrix(sys.stdin)
    elif mode == "native":
        result = needs_native_validation(sys.stdin)
    else:
        print(f"unknown desktop CI scope: {mode}", file=sys.stderr)
        return 2
    print("true" if result else "false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
