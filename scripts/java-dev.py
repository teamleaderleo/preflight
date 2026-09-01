#!/usr/bin/env python3
"""Run an explicit, memorable slice of Preflight's Java verification."""

from __future__ import annotations

import argparse
import fcntl
import hashlib
import json
import os
import platform
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from collections.abc import Callable, Sequence
from contextlib import ExitStack
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MODULES = {
    "core": "preflight-core",
    "agent": "preflight-agent",
    "cli": "preflight-cli",
    "synthetic": "preflight-synthetic-startup",
}
MAVEN_PREFIX = ["./mvnw", "--batch-mode", "--no-transfer-progress"]
REUSE_FORMAT = "preflight-java-dev-reuse-v1"
REUSE_EXTENSION_VERSION = "1.3.0"
REUSE_CONTROL_ENVIRONMENT = (
    "MAVEN_BASEDIR",
    "PREFLIGHT_JAVA_DEV_CACHE",
)
REUSE_SELECTOR = re.compile(
    r"(?:[A-Za-z_$][A-Za-z0-9_$]*\.)*[A-Za-z_$][A-Za-z0-9_$]*"
    r"(?:#[A-Za-z_$][A-Za-z0-9_$]*)?"
)
JAVA_IDENTITY_PROPERTIES = (
    "file.encoding",
    "java.home",
    "java.runtime.version",
    "java.vendor",
    "java.version",
    "java.vm.name",
    "java.vm.version",
    "native.encoding",
    "os.arch",
    "user.country",
    "user.language",
    "user.timezone",
)
REUSE_EXTENSIONS_XML = f"""<?xml version="1.0" encoding="UTF-8"?>
<extensions xmlns="http://maven.apache.org/EXTENSIONS/1.0.0"
            xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
            xsi:schemaLocation="http://maven.apache.org/EXTENSIONS/1.0.0 https://maven.apache.org/xsd/core-extensions-1.0.0.xsd">
  <extension>
    <groupId>org.apache.maven.extensions</groupId>
    <artifactId>maven-build-cache-extension</artifactId>
    <version>{REUSE_EXTENSION_VERSION}</version>
  </extension>
</extensions>
"""
REUSE_CACHE_CONFIG_XML = """<?xml version="1.0" encoding="UTF-8"?>
<cache xmlns="http://maven.apache.org/BUILD-CACHE-CONFIG/1.4.0"
       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
       xsi:schemaLocation="http://maven.apache.org/BUILD-CACHE-CONFIG/1.4.0 https://maven.apache.org/xsd/build-cache-config-1.4.0.xsd">
  <configuration>
    <enabled>true</enabled>
    <hashAlgorithm>SHA-256</hashAlgorithm>
    <mandatoryClean>true</mandatoryClean>
    <remote enabled="false" saveToRemote="false"/>
    <local>
      <maxBuildsCached>2</maxBuildsCached>
    </local>
    <attachedOutputs>
      <dirNames>
        <dirName>classes</dirName>
        <dirName>test-classes</dirName>
      </dirNames>
    </attachedOutputs>
  </configuration>
</cache>
"""


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
    exact.add_argument(
        "--reuse",
        action="store_true",
        help="opt in to selector/toolchain-partitioned exact-result reuse",
    )

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
        if getattr(args, "reuse", False):
            require_exact_reuse_selector(args.selector)
            command.append("clean")
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


def require_exact_reuse_selector(selector: str) -> None:
    if REUSE_SELECTOR.fullmatch(selector) is None:
        raise ValueError(
            "--reuse requires one literal Java class or Class#method; "
            "wildcards, lists, parameter patterns, and other Surefire expressions are refused"
        )


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    return sha256_bytes(path.read_bytes())


def reuse_maven_environment(
    environment: os._Environ[str] | dict[str, str] = os.environ,
) -> dict[str, str]:
    """Return the exact inherited environment Maven may observe on a reuse run."""
    result = dict(environment)
    for name in REUSE_CONTROL_ENVIRONMENT:
        result.pop(name, None)
    return result


def java_runtime_fingerprint(
    runner: Callable[..., subprocess.CompletedProcess[object]] = subprocess.run,
) -> dict[str, str]:
    configured_home = os.environ.get("JAVA_HOME")
    executable = (
        Path(configured_home) / "bin" / "java"
        if configured_home
        else Path(shutil.which("java") or "")
    )
    if not executable.is_file():
        raise RuntimeError("cannot identify the Java executable used by Maven")
    result = runner(
        [str(executable), "-XshowSettings:properties", "-version"],
        capture_output=True,
        text=True,
    )
    stdout = str(result.stdout or "")
    stderr = str(result.stderr or "")
    if result.returncode != 0:
        raise RuntimeError("the Java identity probe failed")
    observed = {}
    for line in (stdout + "\n" + stderr).splitlines():
        match = re.fullmatch(r"\s+([A-Za-z0-9._-]+) = (.*)", line)
        if match and match.group(1) in JAVA_IDENTITY_PROPERTIES:
            observed[match.group(1)] = match.group(2)
    for required in ("java.home", "java.vendor", "java.version", "os.arch"):
        if required not in observed:
            raise RuntimeError(f"the Java identity probe omitted {required}")
    return {"executable": str(executable.resolve()), **observed}


def reuse_identity(
    module: str,
    selector: str,
    command: Sequence[str],
    java_fingerprint: dict[str, str],
    environment: os._Environ[str] | dict[str, str] = os.environ,
    root: Path = ROOT,
) -> tuple[str, dict[str, object]]:
    environment_fingerprints = {
        name: sha256_bytes(value.encode("utf-8"))
        for name, value in sorted(environment.items())
    }
    repository_configuration = {}
    for relative in (
        Path("mvnw"),
        Path(".mvn/maven.config"),
        Path(".mvn/jvm.config"),
        Path(".mvn/wrapper/maven-wrapper.properties"),
        Path("scripts/java-dev.py"),
    ):
        path = root / relative
        repository_configuration[str(relative)] = sha256_file(path) if path.is_file() else "absent"
    payload: dict[str, object] = {
        "command": list(command),
        "environmentSha256": environment_fingerprints,
        "extension": {
            "artifact": (
                "org.apache.maven.extensions:"
                f"maven-build-cache-extension:{REUSE_EXTENSION_VERSION}"
            ),
            "cacheConfigSha256": sha256_bytes(REUSE_CACHE_CONFIG_XML.encode("utf-8")),
            "extensionsXmlSha256": sha256_bytes(REUSE_EXTENSIONS_XML.encode("utf-8")),
        },
        "format": REUSE_FORMAT,
        "java": java_fingerprint,
        "module": module,
        "platform": {
            "machine": platform.machine(),
            "release": platform.release(),
            "system": platform.system(),
        },
        "repositoryConfiguration": repository_configuration,
        "selector": selector,
    }
    canonical = json.dumps(payload, separators=(",", ":"), sort_keys=True).encode("utf-8")
    return sha256_bytes(canonical), payload


def cache_root(environment: os._Environ[str] | dict[str, str] = os.environ) -> Path:
    override = environment.get("PREFLIGHT_JAVA_DEV_CACHE")
    if override:
        root = Path(override)
    elif sys.platform == "darwin":
        root = Path.home() / "Library" / "Caches" / "dev.starsector.preflight" / "java-dev"
    elif environment.get("XDG_CACHE_HOME"):
        root = Path(environment["XDG_CACHE_HOME"]) / "preflight" / "java-dev"
    else:
        root = Path.home() / ".cache" / "preflight" / "java-dev"
    if not root.is_absolute():
        raise RuntimeError("the Java reuse cache root must be absolute")
    return root


def write_private(path: Path, content: str) -> None:
    path.write_text(content, encoding="utf-8")
    path.chmod(0o600)


def prepare_extension_base(directory: Path, root: Path = ROOT) -> Path:
    configuration = directory / ".mvn"
    configuration.mkdir(mode=0o700)
    write_private(configuration / "extensions.xml", REUSE_EXTENSIONS_XML)
    write_private(configuration / "maven-build-cache-config.xml", REUSE_CACHE_CONFIG_XML)
    for name in ("maven.config", "jvm.config"):
        source = root / ".mvn" / name
        if source.is_file():
            write_private(configuration / name, source.read_text(encoding="utf-8"))
    if (root / ".mvn/extensions.xml").exists():
        raise RuntimeError(
            "--reuse refuses to replace or compose a repository-owned .mvn/extensions.xml"
        )
    return configuration


def matching_surefire_reports(module: str, selector: str, root: Path = ROOT) -> list[str]:
    class_selector, separator, method_selector = selector.partition("#")
    reports = root / module / "target" / "surefire-reports"
    matches = []
    for report in sorted(reports.glob("TEST-*.xml")) if reports.is_dir() else []:
        try:
            suite = ET.parse(report).getroot()
        except (ET.ParseError, OSError):
            continue
        suite_name = suite.attrib.get("name", "")
        class_matches = suite_name == class_selector or suite_name.endswith("." + class_selector)
        if not class_matches:
            continue
        if separator:
            method_names = [case.attrib.get("name", "") for case in suite.findall("testcase")]
            if not any(
                name == method_selector
                or name.startswith(method_selector + "(")
                or name.startswith(method_selector + "[")
                for name in method_names
            ):
                continue
        matches.append(str(report.relative_to(root)))
    return matches


def classify_reuse(
    module: str,
    selector: str,
    output: str,
    root: Path = ROOT,
) -> tuple[str, list[str]]:
    reports = matching_surefire_reports(module, selector, root)
    if reports:
        return "executed", reports
    module_header = f"< dev.starsector.preflight:{module} >"
    module_start = output.find(module_header)
    if module_start < 0:
        return "not_established", []
    module_output = output[module_start:]
    reactor_summary = module_output.find("[INFO] Reactor Summary")
    if reactor_summary >= 0:
        module_output = module_output[:reactor_summary]
    restored = (
        "Found cached build, restoring "
        f"dev.starsector.preflight:{module} from cache by checksum "
    ) in module_output
    skipped = "Skipping plugin execution (cached): surefire:test" in module_output
    if restored and skipped:
        return "reused", []
    return "not_established", []


def run_with_reuse(
    args: argparse.Namespace,
    base_command: list[str],
    runner: Callable[..., subprocess.CompletedProcess[object]],
    probe_runner: Callable[..., subprocess.CompletedProcess[object]],
) -> int:
    module = MODULES[args.module]
    java_fingerprint = java_runtime_fingerprint(probe_runner)
    maven_environment = reuse_maven_environment()
    identity, _ = reuse_identity(
        module,
        args.selector,
        base_command,
        java_fingerprint,
        maven_environment,
    )
    reuse_root = cache_root() / REUSE_FORMAT
    namespace = reuse_root / "entries" / identity
    namespace.mkdir(parents=True, exist_ok=True)
    locks = reuse_root / "locks"
    locks.mkdir(parents=True, exist_ok=True)
    worktree_identity = sha256_bytes((str(ROOT.resolve()) + "\0" + module).encode("utf-8"))
    lock_paths = sorted(
        [
            locks / f"cache-{identity}.lock",
            locks / f"worktree-{worktree_identity}.lock",
        ]
    )
    with ExitStack() as held_locks:
        for lock_path in lock_paths:
            lock_file = held_locks.enter_context(lock_path.open("a+", encoding="utf-8"))
            fcntl.flock(lock_file.fileno(), fcntl.LOCK_EX)
        with tempfile.TemporaryDirectory(prefix="preflight-java-dev-reuse-") as temporary:
            extension_base = Path(temporary)
            configuration = prepare_extension_base(extension_base)
            command = [
                *MAVEN_PREFIX,
                "-f",
                str(ROOT / "pom.xml"),
                f"-Dmaven.build.cache.configPath={configuration / 'maven-build-cache-config.xml'}",
                f"-Dmaven.build.cache.location={namespace}",
                *base_command[len(MAVEN_PREFIX) :],
            ]
            environment = maven_environment.copy()
            environment["MAVEN_BASEDIR"] = str(extension_base)
            print(f"Reuse identity: {identity}")
            print(f"Command: {shlex.join(command)}")
            sys.stdout.flush()
            result = runner(
                command,
                cwd=ROOT,
                env=environment,
                capture_output=True,
                text=True,
            )
        stdout = str(result.stdout or "")
        stderr = str(result.stderr or "")
        disposition, reports = classify_reuse(module, args.selector, stdout + "\n" + stderr)
    if stdout:
        print(stdout, end="" if stdout.endswith("\n") else "\n")
    if stderr:
        print(stderr, file=sys.stderr, end="" if stderr.endswith("\n") else "\n")
    receipt = {
        "disposition": disposition,
        "format": REUSE_FORMAT,
        "identity": identity,
        "mavenExit": result.returncode,
        "module": module,
        "requestedReports": reports,
        "selector": args.selector,
    }
    print("Reuse receipt: " + json.dumps(receipt, separators=(",", ":"), sort_keys=True))
    if result.returncode == 0 and disposition == "not_established":
        print(
            "Maven succeeded without either the requested Surefire report or an exact cache-restore "
            "marker; refusing to call this feedback complete.",
            file=sys.stderr,
        )
        return 2
    return result.returncode


def main(
    argv: Sequence[str] | None = None,
    runner: Callable[..., subprocess.CompletedProcess[object]] = subprocess.run,
    probe_runner: Callable[..., subprocess.CompletedProcess[object]] = subprocess.run,
) -> int:
    args = parser().parse_args(argv)
    try:
        command, scope, full = command_for(args)
    except ValueError as error:
        print(f"Refused: {error}", file=sys.stderr)
        return 2
    label = "FULL INTEGRATION" if full else "FOCUSED FEEDBACK"
    print(f"Scope: {label} — {scope}")
    if not full:
        print("This does not certify unrelated modules.")
        print("Integration oracle: ./scripts/java-dev.py full")
    reuse = args.mode == "test" and args.reuse
    if reuse:
        print("Reuse is opt-in. A hit is reported as reused, never as current test execution.")
    else:
        print(f"Command: {shlex.join(command)}")
    sys.stdout.flush()
    if args.dry_run:
        if reuse:
            print(f"Command shape: {shlex.join(command)}")
        return 0
    if reuse:
        try:
            return run_with_reuse(args, command, runner, probe_runner)
        except (OSError, RuntimeError, ValueError) as error:
            print(f"Reuse setup refused: {error}", file=sys.stderr)
            return 2
    return runner(command, cwd=ROOT).returncode


if __name__ == "__main__":
    sys.exit(main())
