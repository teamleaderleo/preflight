#!/usr/bin/env python3
"""Where the load's wall clock goes, as opposed to where its CPU goes.

Execution sampling answers "which Java frames were on a CPU". It cannot answer "what was the
thread waiting for", and after 2026-08-01 that is the question: deleting ~15s of sampled CPU
from the loading thread bought 2.68s of wall clock, so four fifths of that work was not on the
critical path.

This reads a SAMPLE-mode recording and puts the two side by side per thread: on-CPU sample
share, and total time parked, sleeping, or waiting on a monitor.
"""
import collections
import glob
import json
import os
import shutil
import re
import subprocess
import sys

def _jfr_binary():
    """`jfr` ships with the JDK but is not always on PATH, and the game's own JRE lacks it."""
    found = shutil.which("jfr")
    if found:
        return found
    for candidate in sorted(glob.glob("/Library/Java/JavaVirtualMachines/*/Contents/Home/bin/jfr")
                            + glob.glob("/opt/homebrew/Cellar/openjdk/*/libexec/openjdk.jdk/Contents/Home/bin/jfr"),
                            reverse=True):
        if os.access(candidate, os.X_OK):
            return candidate
    raise SystemExit("No `jfr` binary found. Install a JDK or put jfr on PATH.")


JFR = _jfr_binary()
BLOCKING = ["jdk.ThreadPark", "jdk.ThreadSleep", "jdk.JavaMonitorWait", "jdk.JavaMonitorEnter"]


def events(path, names, depth=1):
    out = subprocess.run(
        [JFR, "print", "--json", "--stack-depth", str(depth), "--events", ",".join(names), path],
        capture_output=True, text=True,
    )
    if out.returncode != 0:
        print(out.stderr[:400], file=sys.stderr)
        return []
    try:
        return json.loads(out.stdout)["recording"]["events"]
    except Exception as error:  # noqa: BLE001 - diagnostic tool, report and continue
        print(f"  (could not parse {names}: {error})", file=sys.stderr)
        return []


def seconds(value):
    """JFR JSON renders durations as ISO-8601 ("PT0.040969983S"), not as a number."""
    if isinstance(value, (int, float)):
        return value / 1e9
    if not isinstance(value, str):
        return 0.0
    match = re.fullmatch(r"PT(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?", value)
    if not match:
        return 0.0
    hours, minutes, secs = (float(g) if g else 0.0 for g in match.groups())
    return hours * 3600 + minutes * 60 + secs


def thread_of(event):
    values = event.get("values", {})
    for key in ("eventThread", "sampledThread", "thread"):
        who = values.get(key)
        if isinstance(who, dict) and who.get("javaName"):
            return who["javaName"]
    return "?"


def top_frame(event):
    trace = event.get("values", {}).get("stackTrace")
    if not trace or not trace.get("frames"):
        return "(no stack)"
    frame = trace["frames"][0]
    method = frame.get("method", {})
    kind = method.get("type", {})
    return f"{kind.get('name', '?')}.{method.get('name', '?')}"


def main(path):
    samples = events(path, ["jdk.ExecutionSample"], depth=1)
    print(f"execution samples: {len(samples)}")
    by_thread = collections.Counter(thread_of(e) for e in samples)
    total = sum(by_thread.values()) or 1
    print("\non-CPU share by thread:")
    for name, count in by_thread.most_common(8):
        print(f"  {name:<28} {count:>7}  {count / total * 100:5.1f}%")

    print("\nblocked time by thread (park / sleep / monitor-wait / monitor-enter):")
    blocked = collections.defaultdict(lambda: collections.Counter())
    for name in BLOCKING:
        for event in events(path, [name], depth=1):
            blocked[thread_of(event)][name] += seconds(
                event.get("values", {}).get("duration"))
    rows = sorted(blocked.items(), key=lambda kv: -sum(kv[1].values()))
    for name, kinds in rows[:8]:
        total_s = sum(kinds.values())
        detail = "  ".join(f"{k.split('.')[-1]}:{v:.1f}s" for k, v in kinds.most_common())
        print(f"  {name:<28} {total_s:7.1f}s   {detail}")

    native = events(path, ["jdk.NativeMethodSample"], depth=1)
    if native:
        print(f"\nnative-method samples: {len(native)} "
              f"({len(native) / (len(samples) + len(native)) * 100:.0f}% of all samples)")
        by_native = collections.Counter(thread_of(e) for e in native)
        for name, count in by_native.most_common(5):
            print(f"  {name:<28} {count:>7}")
        print("  top native frames:")
        for frame, count in collections.Counter(top_frame(e) for e in native).most_common(8):
            print(f"    {count:>6}  {frame}")

    hottest = by_thread.most_common(1)
    if hottest:
        name = hottest[0][0]
        print(f"\ntop on-CPU frames on {name}:")
        frames = collections.Counter(
            top_frame(e) for e in samples if thread_of(e) == name)
        for frame, count in frames.most_common(12):
            print(f"  {count:>6}  {count / hottest[0][1] * 100:5.1f}%  {frame}")


if __name__ == "__main__":
    main(sys.argv[1])
