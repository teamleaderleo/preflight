#!/usr/bin/env python3
"""Where the load's wall clock goes, as opposed to where its CPU goes.

Execution sampling answers "which Java frames were on a CPU". It cannot answer "what was the
thread waiting for", and that is the question: deleting ~15s of sampled CPU from the loading
thread bought 2.68s of wall clock, so most of that work was not on the critical path.

This reads a SAMPLE-mode recording and puts the two side by side per thread: on-CPU sample share,
and total time parked, sleeping, or waiting on a monitor -- with the stack each thread was blocked
*at*, because "blocked" alone cannot tell an idle daemon parked on an empty queue from the loading
thread waiting on another thread's result.

Two calibration steps exist because both of them, missing, produced a wrong published finding:

  * Durations are rescaled against a known-period periodic event. Starsector launches with
    -XX:+UseFastUnorderedTimeStamps, under which JFR's tick-to-nanosecond conversion is wrong by a
    constant factor -- 2.49x on the reviewed install, where a Thread.sleep(10) records as 4.7ms.
    Every duration in a recording from that JVM is compressed by that factor.
  * The blocked ranking is never truncated above the loading thread. Ranked by raw blocked time,
    six permanently-idle daemons (Java2D Queue Flusher, Common-Cleaner, Finalizer, Timer-0 ...)
    outrank it, and a top-8 cut hid the one thread whose waiting is the load.
"""
import argparse
import collections
import glob
import json
import os
import shutil
import re
import subprocess
import sys

# The loading thread under the direct-launch protocol. Reported unconditionally: it is the thread
# whose wall clock is the load, and it is not the one that blocks longest.
LOADING_THREAD = "main"

# Periodic events whose period is fixed by the JDK's own profile.jfc, so the spacing of their
# recorded timestamps measures the recording's clock against real time.
CALIBRATION_EVENT = "jdk.CPULoad"
CALIBRATION_PERIOD_SECONDS = 1.0
# Below this the clock is close enough to real that rescaling would be noise, not correction.
CLOCK_REPORT_THRESHOLD = 1.05

BLOCKING = ["jdk.ThreadPark", "jdk.ThreadSleep", "jdk.JavaMonitorWait", "jdk.JavaMonitorEnter"]


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


def events(path, names, depth=1, jfr=None):
    out = subprocess.run(
        [jfr or _jfr_binary(), "print", "--json", "--stack-depth", str(depth),
         "--events", ",".join(names), path],
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


def instant(value):
    """Event timestamps are ISO-8601 instants; returns epoch seconds, or None when unparseable."""
    if not isinstance(value, str):
        return None
    # Python's fromisoformat rejects the nanosecond precision JFR emits, so trim to microseconds.
    trimmed = re.sub(r"(\.\d{6})\d+", r"\1", value)
    try:
        import datetime
        return datetime.datetime.fromisoformat(trimmed).timestamp()
    except ValueError:
        return None


def clock_factor(calibration_events):
    """How much a recorded duration must be multiplied by to become wall-clock seconds.

    The calibration event fires on a period the JDK configuration fixes, and the JVM schedules it
    against real time, so the spacing of its *recorded* timestamps is the recording clock's error.
    Returns 1.0 when there is not enough to measure, which is the safe direction: it reports the
    recording as-is rather than inventing a correction.
    """
    stamps = sorted(
        filter(None, (instant(e.get("values", {}).get("startTime")) for e in calibration_events)))
    if len(stamps) < 3:
        return 1.0
    span = stamps[-1] - stamps[0]
    if span <= 0:
        return 1.0
    observed_period = span / (len(stamps) - 1)
    return CALIBRATION_PERIOD_SECONDS / observed_period


def thread_of(event):
    values = event.get("values", {})
    for key in ("eventThread", "sampledThread", "thread"):
        who = values.get(key)
        if isinstance(who, dict) and who.get("javaName"):
            return who["javaName"]
    return "?"


def frames_of(event, count=1):
    trace = event.get("values", {}).get("stackTrace")
    if not trace or not trace.get("frames"):
        return ["(no stack)"]
    rendered = []
    for frame in trace["frames"][:count]:
        method = frame.get("method", {})
        kind = method.get("type", {})
        rendered.append(f"{kind.get('name', '?')}.{method.get('name', '?')}")
    return rendered


def top_frame(event):
    return frames_of(event, 1)[0]


def blocked_by_thread(path, jfr=None, depth=4):
    """Blocked seconds per thread, with the stack the thread was blocked at."""
    totals = collections.defaultdict(collections.Counter)
    sites = collections.defaultdict(collections.Counter)
    for name in BLOCKING:
        for event in events(path, [name], depth=depth, jfr=jfr):
            who = thread_of(event)
            elapsed = seconds(event.get("values", {}).get("duration"))
            totals[who][name] += elapsed
            sites[who][" <- ".join(frames_of(event, depth))] += elapsed
    return totals, sites


def report(path, top=8, jfr=None):
    jfr = jfr or _jfr_binary()
    factor = clock_factor(events(path, [CALIBRATION_EVENT], depth=1, jfr=jfr))
    if factor >= CLOCK_REPORT_THRESHOLD or factor <= 1 / CLOCK_REPORT_THRESHOLD:
        print(f"!! This recording's clock runs at {1 / factor:.3f}x real time.")
        print(f"   Every duration below is the recorded value x{factor:.3f}. Starsector launches")
        print("   with -XX:+UseFastUnorderedTimeStamps, which does exactly this.")
    else:
        print(f"clock calibration: {factor:.3f}x (recording clock agrees with real time)")

    samples = events(path, ["jdk.ExecutionSample"], depth=1, jfr=jfr)
    print(f"\nexecution samples: {len(samples)}")
    by_thread = collections.Counter(thread_of(e) for e in samples)
    total = sum(by_thread.values()) or 1
    print("\non-CPU share by thread:")
    for name, count in by_thread.most_common(top):
        print(f"  {name:<28} {count:>7}  {count / total * 100:5.1f}%")

    totals, sites = blocked_by_thread(path, jfr=jfr)
    print("\nblocked time by thread (park / sleep / monitor-wait / monitor-enter), wall seconds:")
    print("  a thread parked on its own empty work queue is idle, not delayed -- read the site.")
    ranked = sorted(totals.items(), key=lambda kv: -sum(kv[1].values()))
    shown = ranked[:top]
    # The loading thread is the point of the whole report and it does not rank near the top:
    # idle daemons block for the entire run, it blocks only when something is late.
    if LOADING_THREAD in totals and all(name != LOADING_THREAD for name, _ in shown):
        shown = shown + [(LOADING_THREAD, totals[LOADING_THREAD])]
    for name, kinds in shown:
        marker = "<-- loading thread" if name == LOADING_THREAD else ""
        detail = "  ".join(
            f"{k.split('.')[-1]}:{v * factor:.1f}s" for k, v in kinds.most_common())
        print(f"  {name:<28} {sum(kinds.values()) * factor:7.1f}s   {detail} {marker}")
        site, elapsed = sites[name].most_common(1)[0]
        print(f"      at {site}  ({elapsed * factor:.1f}s)")

    native = events(path, ["jdk.NativeMethodSample"], depth=1, jfr=jfr)
    if native:
        print(f"\nnative-method samples: {len(native)} "
              f"({len(native) / (len(samples) + len(native)) * 100:.0f}% of all samples)")
        by_native = collections.Counter(thread_of(e) for e in native)
        for name, count in by_native.most_common(5):
            print(f"  {name:<28} {count:>7}")
        print("  top native frames:")
        for frame, count in collections.Counter(top_frame(e) for e in native).most_common(8):
            print(f"    {count:>6}  {frame}")

    if by_thread:
        name = LOADING_THREAD if LOADING_THREAD in by_thread else by_thread.most_common(1)[0][0]
        count = by_thread[name]
        print(f"\ntop on-CPU frames on {name}:")
        frames = collections.Counter(top_frame(e) for e in samples if thread_of(e) == name)
        for frame, hits in frames.most_common(12):
            print(f"  {hits:>6}  {hits / count * 100:5.1f}%  {frame}")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("recording", help="a SAMPLE-mode startup.jfr")
    parser.add_argument("--top", type=int, default=8, help="rows per ranking (default 8)")
    args = parser.parse_args(argv)
    report(args.recording, top=args.top)


if __name__ == "__main__":
    main()
