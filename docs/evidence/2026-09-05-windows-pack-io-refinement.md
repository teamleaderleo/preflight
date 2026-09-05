# Windows pack I/O refinement

Owner: Leo-authorized active Codex goal to find and verify a Windows startup win.
Start: main `d301613e713b6819e3322ad754619035d8d6efc2`.
Phase: isolate the large idle-versus-in-game pack-read gap before choosing another runtime change.
Finish: measurable matched Windows startup improvement with resource/GL contracts retained,
verified code integrated into main. No improvement is established yet.

[Prior experiments](2026-09-05-windows-prepared-byte-barrier.md) own rejected barrier, scratch,
and physical-order results. Existing installed JAR remains d3de6398 executable code,
SHA-256 `97660b4e3e801af527da3d96ddd3834c6256a408d276d50ba9c1091f7fd5313d`.
No game, pack or launcher mutation has occurred in this refinement.

## Isolated measurements

Private source, Java-17 helper classes and raw output: shared Diagnostics `windows-pack-io/`.
The helper validates SPFP index SHA, exact ranges, SPFO SHA/profile and per-entry CRC32C.
It selects the 14,769 previously accepted paths against the original 2,259,086,856-byte pack.
Whole-pack SHA is checked immediately before running: **this warms the file cache**. These are
idle-JVM warm-cache diagnostic floors, not cold I/O or game startup comparisons.

JDK 21.0.12.1, same installed CLI JAR, parser heap cap 6 GiB. Raw reads/copies plus CRC cover
1,086,167,784 bytes. Heap: 342/251 ms; direct: 311/256 ms; mapped: 479/112 ms.
Mappings exist only in the isolated short-lived process, not in the production reader.
The real `PreparedTexturePack.readTrusted` parser decodes 2,073,882,309 pixel bytes:
ordinary 1436/1013 ms; exact-entry scratch 1085/1014 ms. Scratch file reads take 294 ms,
CRC 49 ms in both passes. All selected entries pass their existing CRC checks.

The prior in-game scratch diagnostic reported file-read time 13.185 s and pack time 18.408 s.
The large gap is not explained by idle warm-cache parser throughput. Memory pressure/cache
residency, scheduling, JVM differences and instrumentation interactions remain hypotheses.
Next: confirm actual game Java/flags and record file I/O, GC and execution samples during startup.

## Recovery

Task helpers: canonical `target/windows-pack-io/`; QGA wrapper records guest PID before waiting
(up to 600 s). Guest helper directory: `C:\Projects\starsector-preflight\target\windows-pack-io`.
Initial isolated processes exited normally; no game process was started yet. The active cache
still has the original pack and original SPFO restored by the parent experiment.

## Actual JVM and parser follow-up

The game process uses bundled OpenJDK 17.0.10+7-LTS, not the CLI's Java 21. Bundled java.exe
SHA-256 `9a2697956034fa9667c97644c0d2f8a3f7ab5e4cd3866951d56a73d9de4d2a9f`.
Copied the exact batch prefix into isolated helper commands (game batch unchanged). It sets
6 GiB fixed/pre-touched heap, Shenandoah `iu`/`compact`, and many compiler flags.
Java 17 default ordinary parser: 3260 ms first pass, 1056 ms warm. Exact game flags: ordinary
1614/1227 ms, scratch 1242 ms (file reads 296 ms). Changing only the isolated helper's
heuristic to adaptive: ordinary 1306/1000 ms, scratch 1018 ms. These do not reproduce the
in-game gap and do not justify changing the user's GC policy. Raw outputs and copied helper
commands are `java17-{default,game,adaptive}.{log,cmd}` in private evidence.

Native diagnostic `20260905-114540-windows-startup-2x2.zip`: accepted, graphics 59.817 s /
interactive 62.857 s; actual load attribution pack 23.759 s, lookup 24.072 s across 15,473 loads.
The initial JFR attach had incorrectly split native settings arguments. It did not start a
recording; the corrected attach arrived after exit. No JFR evidence is claimed from that run.
The watcher exited and the game/cohort settled. A corrected watcher validates the explicit
started-recording response and also captures bounded Windows memory/page-in snapshots.

## Memory pressure and capture refinement

The corrected recording started during native session `20260905-114937`, but Windows exit
left a zero-byte JFR file; no JFR event claims are made from it. The bounded memory samples
(`memory-2.jsonl`) survived: available physical memory fell to 434 MB; process private commit
rose from 6.87 GB to 26.76 GB; working set dropped from 7.19 GB to 2.76 GB while page-ins
continued. This is evidence of memory pressure, not yet attribution to a particular allocator.
The next watcher periodically dumps JFR while alive, samples GPU-process memory and requests
NMT summaries. The opt-in operator switch adds NMT only to the existing temporary environment
for Preflight arms; no game launcher file or persistent JVM setting changes.

The reusable isolated diagnostic is now `scripts/diagnostics/PackReadBenchmark.java`; compile
with `javac --release 17`. Arguments are pack path, accepted SPFO path, comma-separated modes
`heap,direct,mapped,parse,parse-ahead`; parser modes need the tested CLI JAR on the classpath.
It validates selected entry CRCs in every mode. Its process owns temporary mappings until exit.

## In-game allocation attribution

Session `20260905-115734` used NMT summary, load attribution and periodic JFR dumps. Graphics
60.134 s / interactive 62.947 s are diagnostic clocks only. The retained 4,268,813-byte
`startup-3.jfr` is a periodic snapshot, not a complete exit recording. It contains 1,362 slow
SPFP read events totaling 18.536 s (maximum 187.9 ms), chiefly on Thread-5; 24 GC pauses total
34 ms. Direct buffer samples peak at 459 MB then fall to about 26 MB. NMT committed total
stays about 6.4–7.0 GiB while Windows process private commit approaches 27 GB. GPU shared
usage grows substantially. NMT cannot attribute allocations made independently by native
libraries; this is not proof that all excess private commit belongs to the graphics driver.

The next independent candidate lowers the initial game heap from 6144 to 2048 MiB while
preserving the 6144 MiB maximum, collector, resources, GL and scheduling. Property
`preflight.windows.initialHeapProbe` is opt-in. The child-only override requires exact reviewed
batch/java.exe/wrapper hashes and Windows Recommended. Explicit heap options in any of
`_JAVA_OPTIONS`, `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS` decline. Unknown identities retain
original behavior. CLI-JVM heap and persistent launcher files are unchanged.
Focused policy tests cover admission, preservation of other options/files, default/platform/
preset declines, environment precedence, changed identities and missing/unreadable files.
The runtime parser and upload code are unchanged in this candidate.
