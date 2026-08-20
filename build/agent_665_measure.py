import hashlib
import json
import math
import os
import pathlib
import platform
import shutil
import statistics
import subprocess
import sys
import time

label = sys.argv[1]
destination = pathlib.Path(sys.argv[2])
jar = pathlib.Path("preflight-cli/target/preflight.jar").resolve()
fixture = pathlib.Path("/tmp/preflight-cli-665-fixture")
shutil.rmtree(fixture, ignore_errors=True)
home = fixture / "home"
work = fixture / "work"
game = home / "Games" / "Starsector"
core = game / "starsector-core"
mods = game / "mods"
demo = mods / "demo"
work.mkdir(parents=True)
(core / "data" / "benchmark").mkdir(parents=True)
demo.mkdir(parents=True)
for index in range(1200):
    bucket = core / "data" / "benchmark" / f"b{index // 100:02d}"
    bucket.mkdir(exist_ok=True)
    (bucket / f"resource-{index:04d}.txt").write_text(
        f"synthetic resource {index:04d}\n", encoding="utf-8")
(demo / "mod_info.json").write_text(
    '{"id":"demo","name":"CLI benchmark demo"}\n', encoding="utf-8")
(demo / "data.txt").write_text("demo resource\n", encoding="utf-8")
(mods / "enabled_mods.json").write_text(
    '{"enabledMods":["demo"]}\n', encoding="utf-8")
launcher = game / "starsector.sh"
launcher.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
launcher.chmod(0o755)

environment = os.environ.copy()
environment.pop("NO_COLOR", None)
environment.pop("STARSECTOR_HOME", None)
environment.pop("STARSECTOR_DIR", None)
environment["TERM"] = "xterm-256color"
environment["COLUMNS"] = "88"
base = [
    "java",
    f"-Duser.home={home}",
    f"-Duser.dir={work}",
    "-jar",
    str(jar),
]


def invoke(arguments, *, columns="88"):
    env = environment.copy()
    env["COLUMNS"] = columns
    return subprocess.run(
        base + list(arguments),
        cwd=work,
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )


setup = invoke(["profile", "save", "solo", "--game", str(game)])
if setup.returncode != 0:
    raise SystemExit(
        "profile setup failed\nstdout=" + setup.stdout.decode(errors="replace")
        + "\nstderr=" + setup.stderr.decode(errors="replace"))

# Prime one-time history/profile recovery writes before any timed sample.
prime = invoke(["evidence", "--json"])
if prime.returncode != 0:
    raise SystemExit("evidence prime failed")

commands = {
    "help": ["--help"],
    "doctor": ["doctor"],
    "profile-list": ["profile", "list"],
    "cache-summary-human": ["cache"],
    "cache-summary-json": ["cache", "--json"],
    "profile-update-preview": ["profile", "update", "solo"],
}

profile_files = sorted((home / ".starsector-preflight" / "profiles").glob("*.json"))
if len(profile_files) != 1:
    raise SystemExit(f"expected one saved profile, found {profile_files}")
profile_file = profile_files[0]
before_profile_hash = hashlib.sha256(profile_file.read_bytes()).hexdigest()

# One untimed fresh-JVM warmup per command; every timed observation is a separate JVM.
for name, arguments in commands.items():
    warm = invoke(arguments)
    if warm.returncode != 0:
        raise SystemExit(
            f"warmup {name} failed with {warm.returncode}\n"
            + warm.stdout.decode(errors="replace") + "\n"
            + warm.stderr.decode(errors="replace"))

results = {}
for name, arguments in commands.items():
    samples = []
    stdout_values = []
    stderr_values = []
    exit_codes = []
    for _ in range(9):
        started = time.perf_counter_ns()
        completed = invoke(arguments)
        elapsed_ms = (time.perf_counter_ns() - started) / 1_000_000
        samples.append(elapsed_ms)
        stdout_values.append(completed.stdout)
        stderr_values.append(completed.stderr)
        exit_codes.append(completed.returncode)
    ordered = sorted(samples)
    if any(code != 0 for code in exit_codes):
        raise SystemExit(f"timed {name} returned {exit_codes}")
    if name.endswith("json"):
        for value in stdout_values:
            json.loads(value.decode("utf-8"))
    results[name] = {
        "samplesMs": [round(value, 3) for value in samples],
        "p50Ms": round(statistics.median(samples), 3),
        "p95Ms": round(ordered[math.ceil(0.95 * len(ordered)) - 1], 3),
        "minMs": round(min(samples), 3),
        "maxMs": round(max(samples), 3),
        "exitCode": exit_codes[0],
        "stdoutBytes": len(stdout_values[0]),
        "stderrBytes": len(stderr_values[0]),
        "stdoutSha256": hashlib.sha256(stdout_values[0]).hexdigest(),
        "stderrSha256": hashlib.sha256(stderr_values[0]).hexdigest(),
        "deterministicStdout": len(set(stdout_values)) == 1,
        "deterministicStderr": len(set(stderr_values)) == 1,
        "containsAnsi": any(b"\x1b[" in value for value in stdout_values + stderr_values),
    }

after_profile_hash = hashlib.sha256(profile_file.read_bytes()).hexdigest()
if before_profile_hash != after_profile_hash:
    raise SystemExit("preview-only profile update changed its saved profile")

narrow = invoke(["--help"], columns="44")
if narrow.returncode != 0:
    raise SystemExit("narrow help failed")
narrow_text = narrow.stdout.decode("utf-8")
narrow_lines = narrow_text.splitlines()

destination.write_text(json.dumps({
    "label": label,
    "platform": platform.platform(),
    "processors": os.cpu_count(),
    "java": subprocess.check_output(
        ["java", "-version"], stderr=subprocess.STDOUT, text=True).splitlines()[0],
    "fixture": {
        "discovery": "standard $user.home/Games/Starsector location; no --game on measured commands",
        "enabledMods": 1,
        "resourceFiles": 1201,
        "savedProfiles": 1,
    },
    "repetitions": 9,
    "results": results,
    "previewProfileUnchanged": before_profile_hash == after_profile_hash,
    "narrowHelp": {
        "columns": 44,
        "maxLineLength": max((len(line) for line in narrow_lines), default=0),
        "desktopDiscovered": any(line.strip().startswith("desktop") for line in narrow_lines),
        "containsAnsi": b"\x1b[" in narrow.stdout or b"\x1b[" in narrow.stderr,
    },
}, indent=2, sort_keys=True) + "\n", encoding="utf-8")
