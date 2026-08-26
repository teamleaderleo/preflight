#!/usr/bin/env python3
"""Decide which repository-wide CI jobs a change needs."""

from __future__ import annotations

import sys
from collections.abc import Iterable

JAVA_EXACT = {
    ".github/workflows/ci.yml",
    "mvnw",
    "mvnw.cmd",
    "pom.xml",
}
JAVA_PREFIXES = (
    ".github/actions/setup-build-jdk/",
    ".mvn/",
    "preflight-agent/",
    "preflight-cli/",
    "preflight-core/",
    "preflight-synthetic-startup/",
)

OPERATOR_EXACT = {
    ".github/workflows/ci.yml",
}
OPERATOR_PREFIXES = (
    "build/ci/",
    "scripts/",
)


def _matches(paths: Iterable[str], exact: set[str], prefixes: tuple[str, ...]) -> bool:
    for raw_path in paths:
        path = raw_path.strip().replace("\\", "/")
        if path and (path in exact or path.startswith(prefixes)):
            return True
    return False


def needs_java_verify(paths: Iterable[str]) -> bool:
    return _matches(paths, JAVA_EXACT, JAVA_PREFIXES)


def needs_operator_checks(paths: Iterable[str]) -> bool:
    return _matches(paths, OPERATOR_EXACT, OPERATOR_PREFIXES)


def main() -> int:
    mode = sys.argv[1] if len(sys.argv) > 1 else "java"
    if mode == "java":
        result = needs_java_verify(sys.stdin)
    elif mode == "operator":
        result = needs_operator_checks(sys.stdin)
    else:
        print(f"unknown repository CI scope: {mode}", file=sys.stderr)
        return 2
    print("true" if result else "false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
