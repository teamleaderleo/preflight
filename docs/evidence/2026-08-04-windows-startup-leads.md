# Windows-specific startup leads

**Date:** 2026-08-04

**Status:** researched; heap pre-touch experiment implemented, no Windows timing yet

This pass looked for costs that can differ materially on Windows rather than assuming the macOS
Rosetta profile transfers unchanged. The ranking below separates measured facts from hypotheses.

## 1. Shipped heap pre-touch: ready to measure

The Windows launcher explicitly requests a fixed 2 GiB heap and `-XX:+AlwaysPreTouch`. Oracle's
Java command reference says that this flag touches every heap page before the application starts;
the default is disabled. Windows commits virtual memory without assigning physical pages until the
addresses are first accessed, so pre-touch moves that work into JVM startup.

- Oracle Java command reference:
  <https://docs.oracle.com/en/java/javase/24/docs/specs/man/java.html>
- Microsoft `VirtualAlloc` contract:
  <https://learn.microsoft.com/en-us/windows/win32/api/memoryapi/nf-memoryapi-virtualalloc>

The reviewed macOS installation already established the shape of this cost under Rosetta: at a
6 GiB fixed heap, disabling pre-touch reduced the bundled JVM's pre-main startup from about 949 ms
to 70 ms. That number is not a Windows estimate; it only proves the launcher flag can dominate the
same pre-main interval we are trying to reduce.

`JAVA_TOOL_OPTIONS` cannot negate the batch file's flag. Oracle documents that launcher environment
options are prepended, and a bounded local precedence probe confirmed the later explicit
`-XX:+AlwaysPreTouch` wins. OpenJDK 17 deliberately parses `_JAVA_OPTIONS` after command-line flags,
however, and the same probe confirmed a final `-XX:-AlwaysPreTouch` wins:

- Oracle environment-option ordering:
  <https://docs.oracle.com/en/java/javase/16/docs/specs/man/java.html#using-the-jdk_java_options-launcher-environment-variable>
- OpenJDK 17 argument parser, lines 2140-2155:
  <https://github.com/openjdk/jdk17u/blob/master/src/hotspot/share/runtime/arguments.cpp#L2140-L2155>

Branch `codex/windows-pretouch-experiment` adds `preflight run --no-heap-pretouch`. It sets only the
child process's `_JAVA_OPTIONS`, preserves existing content, appends the disabling flag last, records
`heapPretouchDisabled` in `run.json`, and leaves the installed launcher untouched. It is deliberately
not part of `--fast`: pre-touch trades startup cost for possible page-fault stalls later, so the gate
is a Windows launch A/B plus a short gameplay hitch check.

## 2. Defender attribution: measure before exclusions

The current launch profile contains 61,693 resource providers. Building its index resolves and reads
attributes for every file, and the game then opens a large subset. Real-time antivirus can therefore
sit on both the 1.19 s Preflight preparation interval and the game's loading interval.

Microsoft ships a Defender Performance Analyzer specifically for this question. Its recording and
report commands attribute scan time to processes, paths, files and extensions. Microsoft explicitly
says the analyzer does not prescribe exclusions, and warns that broad exclusions reduce protection.
That makes the safe workflow:

1. record one representative launch;
2. inspect exact top processes and paths;
3. optimize our I/O pattern if Preflight is hot;
4. consider a narrow operator-controlled exclusion only with measured evidence and an explicit
   security warning.

- Defender performance tuning and analyzer workflow:
  <https://learn.microsoft.com/en-us/defender-endpoint/tune-performance-defender-antivirus>
- Analyzer command reference:
  <https://learn.microsoft.com/en-us/defender-endpoint/performance-analyzer-reference>
- Microsoft's exclusion mistakes and safety guidance:
  <https://learn.microsoft.com/en-us/defender-endpoint/defender-endpoint-exclusions-common-mistakes>

Windows Performance Recorder is the independent second instrument: its built-in profiles can capture
CPU, disk I/O, file I/O, hard faults and process/thread activity. Use it to distinguish Defender
blocking, NTFS metadata latency, pre-touch page faults and ordinary game work rather than inferring
from gaps between log lines.

- WPR built-in recording profiles:
  <https://learn.microsoft.com/en-us/windows-hardware/test/wpt/built-in-recording-profiles>

## 3. NTFS USN journal: plausible, but only after profiling

NTFS maintains a persistent change journal with records for file additions, deletions and
modifications. In principle, Preflight could store a volume journal identity and cursor beside the
resource index, then avoid the 61,693-file repeat walk when no relevant root changed.

- `fsutil usn` reference:
  <https://learn.microsoft.com/en-us/windows-server/administration/windows-commands/fsutil-usn>
- Change-journal record contract:
  <https://learn.microsoft.com/en-us/windows/win32/fileio/change-journal-records>

This is not the next implementation. Journal records can be truncated, the journal can be deleted or
recreated, roots can live on different volumes, and the Java runtime has no standard USN API. A safe
consumer needs an authenticated baseline and must fall back to the current full walk for every
missing, stale, wrapped or ambiguous cursor. Defender/WPR evidence should first show that metadata
validation remains worth enough time on Windows to justify native code and that correctness surface.

## Other candidates to price

- `--quiet-logs` already saves 0.403 s in the installed log4j replay by removing a duplicate console
  appender and buffering the file appender. Windows console behavior may change the absolute win,
  but that needs a Windows A/B rather than folklore.
- AppCDS launch integration remains unfinished. The exact-JVM capability detector is already merged,
  but nothing creates or consumes a Starsector application archive. This is cross-platform and may
  reduce the pre-first-log class-loading interval.
- The Windows game JVM is native x86-64 rather than x86 under Rosetta, so the measured 10x Rosetta
  SHA-256 penalty does not transfer. Keep hashing in the CLI for now, but re-price preparation and
  scheduling independently on Windows.

## Recommended Windows measurement order

1. Five alternating direct launches with and without `--no-heap-pretouch`; compare process spawn to
   first game log and total menu readiness, then play briefly for deferred-fault hitches.
2. One Defender analyzer recording of a representative warm launch, followed by a WPR CPU/file-I/O/
   hard-fault trace if Defender does not explain the filesystem cost.
3. Repeat the existing `--dry-run` preparation timing on Windows. Only investigate USN-based
   invalidation if the full index walk is still a material part of the user-visible wait.
4. Price `--quiet-logs` on Windows and inspect the pre-first-log class-load interval before deciding
   whether AppCDS or another OS-specific path comes next.
