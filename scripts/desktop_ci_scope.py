#!/usr/bin/env python3
"""Decide whether a pull request needs the three-platform desktop package matrix."""

from __future__ import annotations

import sys
from collections.abc import Iterable

PACKAGE_EXACT = {
    ".github/workflows/desktop-ci.yml",
    "pom.xml",
}
PACKAGE_PREFIXES = (
    ".github/actions/setup-build-jdk/",
    "preflight-desktop/",
)


def needs_package_matrix(paths: Iterable[str]) -> bool:
    for raw_path in paths:
        path = raw_path.strip().replace("\\", "/")
        if not path:
            continue
        if path in PACKAGE_EXACT or path.startswith(PACKAGE_PREFIXES):
            return True
    return False


def main() -> int:
    print("true" if needs_package_matrix(sys.stdin) else "false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
