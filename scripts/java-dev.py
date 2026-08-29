#!/usr/bin/env python3
"""Run an explicit, memorable slice of Preflight's Java verification."""

from __future__ import annotations

import argparse
import shlex
import subprocess
import sys
from collections.abc import Callable, Sequence
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MODULES = {
    "core": "preflight-core",
    "agent": "preflight-agent",
    "cli": "preflight-cli",
    "synthetic": "preflight-synthetic-startup",
}
MAVEN_PREFIX = ["./mvnw", "--batch-mode", "--no-transfer-progress"]


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(
        description="Run a declared Java feedback scope; focused modes are not full acceptance."
    )
    result.add_argument(
        "--dry-run",
        action="store_true",
        help="print the selected scope and command without invoking Maven",
    )
    subparsers = result.add_subparsers(dest="mode", required=True)

    exact = subparsers.add_parser("test", help="run one exact JUnit class or Class#method")
    exact.add_argument("module", choices=MODULES)
    exact.add_argument("selector", help="JUnit class or Class#method")

    integration = subparsers.add_parser(
        "it", help="run one exact packaged child-JVM test from preflight-cli"
    )
    integration.add_argument("selector", help="integration-test class or Class#method")

    module = subparsers.add_parser("module", help="verify only one reactor module")
    module.add_argument("module", choices=MODULES)

    dependencies = subparsers.add_parser(
        "deps", help="verify one module plus every required reactor parent"
    )
    dependencies.add_argument("module", choices=MODULES)

    full = subparsers.add_parser("full", help="run the full Java integration oracle")
    full.add_argument(
        "--threads",
        type=positive_int,
        help="Maven reactor threads; omitted means Maven's serial default",
    )
    full.add_argument(
        "--forks",
        type=positive_int,
        help="test JVM forks; omitted means the repository default",
    )
    return result


def positive_int(raw: str) -> int:
    value = int(raw)
    if value < 1:
        raise argparse.ArgumentTypeError("must be at least 1")
    return value


def command_for(args: argparse.Namespace) -> tuple[list[str], str, bool]:
    command = list(MAVEN_PREFIX)
    if args.mode in {"test", "it"}:
        integration = args.mode == "it"
        module = "preflight-cli" if integration else MODULES[args.module]
        selector = "it.test" if integration else "test"
        command.extend(
            [
                "-pl",
                module,
                "-am",
                f"-D{selector}={args.selector}",
                "-Dsurefire.failIfNoSpecifiedTests=false",
            ]
        )
        if integration:
            command.append("-Dtest=__PreflightNoUnitTestMatches__")
        command.append("verify")
        kind = "packaged child-JVM" if integration else "JUnit"
        scope = f"exact {kind} {args.selector} in {module}, plus required parents"
        return command, scope, False

    if args.mode in {"module", "deps"}:
        module = MODULES[args.module]
        command.extend(["-pl", module])
        if args.mode == "deps":
            command.append("-am")
            scope = f"{module} plus required parents"
        else:
            scope = f"{module} only (requires its dependencies to be available already)"
        command.append("verify")
        return command, scope, False

    if args.threads:
        command.extend(["-T", str(args.threads)])
    if args.forks:
        command.extend([f"-DforkCount={args.forks}", "-DreuseForks=true"])
    command.append("verify")
    parallel = []
    if args.threads:
        parallel.append(f"{args.threads} reactor threads")
    if args.forks:
        parallel.append(f"{args.forks} test forks")
    suffix = f" ({', '.join(parallel)})" if parallel else " (repository defaults)"
    return command, "full Java reactor" + suffix, True


def main(
    argv: Sequence[str] | None = None,
    runner: Callable[..., subprocess.CompletedProcess[object]] = subprocess.run,
) -> int:
    args = parser().parse_args(argv)
    command, scope, full = command_for(args)
    label = "FULL INTEGRATION" if full else "FOCUSED FEEDBACK"
    print(f"Scope: {label} — {scope}")
    if not full:
        print("This does not certify unrelated modules.")
        print("Integration oracle: ./scripts/java-dev.py full")
    print(f"Command: {shlex.join(command)}")
    sys.stdout.flush()
    if args.dry_run:
        return 0
    return runner(command, cwd=ROOT).returncode


if __name__ == "__main__":
    sys.exit(main())
