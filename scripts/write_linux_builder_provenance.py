#!/usr/bin/env python3
"""Write bounded provenance for the pinned Linux compatibility builder."""

from __future__ import annotations

import argparse
import json
import platform
import subprocess
from pathlib import Path

FORMAT = "preflight-linux-builder-provenance-v1"


def command_output(command: list[str]) -> str:
    result = subprocess.run(
        command,
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )
    if result.returncode != 0:
        return f"unavailable (exit {result.returncode})"
    return result.stdout.strip()


def os_release() -> dict[str, str]:
    path = Path("/etc/os-release")
    if not path.is_file():
        return {}
    values: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if "=" not in line or line.startswith("#"):
            continue
        key, value = line.split("=", 1)
        values[key] = value.strip().strip('"')
    return values


def build_report(image: str, maximum_glibc: str, glibc_report: dict) -> dict[str, object]:
    return {
        "format": FORMAT,
        "builderImage": image,
        "maximumAllowedGlibc": maximum_glibc,
        "maximumObservedGlibc": glibc_report.get("maximumObservedGlibc"),
        "packageCompatibility": glibc_report,
        "system": {
            "machine": platform.machine(),
            "osRelease": os_release(),
            "libcPackage": command_output(["dpkg-query", "-W", "-f=${Version}", "libc6"]),
            "ldd": command_output(["ldd", "--version"]).splitlines()[0],
        },
        "toolchain": {
            "java": command_output(["java", "-version"]),
            "node": command_output(["node", "--version"]),
            "npm": command_output(["npm", "--version"]),
            "rustc": command_output(["rustc", "-Vv"]),
            "cargo": command_output(["cargo", "--version"]),
        },
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--image", required=True)
    parser.add_argument("--max-glibc", required=True)
    parser.add_argument("--glibc-report", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    glibc_report = json.loads(args.glibc_report.read_text(encoding="utf-8"))
    report = build_report(args.image, args.max_glibc, glibc_report)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(args.output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
