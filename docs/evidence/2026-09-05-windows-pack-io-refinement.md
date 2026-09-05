# Windows pack I/O refinement

Owner: Leo-authorized active Codex goal to find and verify a Windows startup win.
Start: main `d301613e713b6819e3322ad754619035d8d6efc2`.
Phase: validated opt-in Windows improvement; integration tracked in PR #1227.
Finish: measurable matched Windows startup improvement with resource/GL contracts retained,
verified code integrated into main. Matched results below establish an improvement on the tested fixture.

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

## Initial-heap candidate: executable identity and first diagnostic

Source `78e9110be9e77ec3e1ac4c5aad9f580bb11e7c5d`; JAR
`a9d06382e380161cad75b88f1b551e53b72473c029fe067934b48bcbe9769c45`.
Full Maven verify passed (47.087 s); focused policy tests 3 passed; operator tests 116 passed,
5 platform skips. Windows dry run admitted exact identities and emitted child `_JAVA_OPTIONS:
-Xms2048m`. The game's persistent batch/wrapper and 6144 MiB maximum were unchanged.

Diagnostic `20260905-121244`: graphics 40.589 s / interactive 42.454 s, with heap probe, NMT,
load attribution and periodic JFR dumps. This is not an uninstrumented matched comparison.
NMT observed Java heap commitments of 2048, 5446, 3178, 4382 MiB, proving growth and release
below the old fixed floor. Retained `startup-4.jfr` is a periodic snapshot (3,358,155 bytes).
Module inspection identified system OPENGL32.dll and Intel `igxelpgicd64.dll`; the driver is
native in this diagnostic. Raw data remains in `windows-pack-io/`.
Archive SHA-256 `31be9e2680edcb34353c45fde988d405e56cb37dae6cbe3c43168b2fbec84030`.

Matched verification now uses the same JAR, Recommended, one worker, typed prepared resources,
1024x720, native selection, explicit barrier/claims/entry-reader false. No attribution, NMT or
JFR probes. A explicitly disables the initial-heap probe; B explicitly enables it.
Planned order is A/B, B/A, A/B, checking exact admission, fixture identity and resource accounting
for each run. No default promotion has been made.

The first uninstrumented A attempt (`20260905-121438`, same candidate JAR with heap probe
explicitly false) stalled before graphics completion. At 260.8 seconds process elapsed, main
was inside native `GL11.nglTexImage2D` under the existing prepared-resource commit path; the
stock worker was no longer present. Available memory had recovered to 5724 MB at capture,
so ongoing paging alone cannot explain that stalled state. This resembles the earlier native
upload stall and does not establish a new root cause or a fix. Captured process, thread dumps,
modules and game log under `windows-pack-io/control-stall-*`. Requested window close, then
force-retired only the exact captured PID after ten seconds if still alive. This failed attempt
must remain in reliability accounting and is excluded from completed-startup timing pairs.
The pair will restart only after the cohort settles.

The stalled game's batch paused after forced retirement; its CLI remained alive. The cohort
wrongly counted that CLI as a game JVM, masking the exit. The host also threw on task failure
before copying the run's evidence. Monitoring now excludes the exact launched CLI PID from
the game-JVM predicate (cleanup still includes it). Host completion now archives the current
failed session, records task exit status, restores state, then returns failure; a creation-time
check refuses a stale session. Real Windows replay archived the failed run with taskExitCode=1
and accepted=false: ZIP SHA-256 `4faed23bbdea3b928b4ec470ed9d9108b3364da3d743e45e4da1a9c0df089889`.
These harness corrections do not change the candidate JAR or healthy game execution.

## Three matched completed pairs

All on JAR `a9d06382...`, executable source `78e9110b`, harness source `a2b9f4de`.
Every run passed the fixture/artifact, explicit gate, one-worker, 1024 ceiling, 15,002-commit,
102-late-resource, zero-pack-failure and zero-active-buffer checks. Order was A/B, B/A, A/B.

| Pair | A interactive (s) | B interactive (s) | Reduction (s) | Reduction |
|---|---:|---:|---:|---:|
| 1 | 59.068 | 43.013 | 16.055 | 27.2% |
| 2 | 53.856 | 43.798 | 10.058 | 18.7% |
| 3 | 56.571 | 44.574 | 11.997 | 21.2% |

Median interactive A 56.571 s / B 43.798 s, a 22.6% reduction.
This is repeated evidence on one fixed Windows fixture, not a cross-hardware guarantee.
The earlier failed A attempt is retained separately; no native-stall remediation is established.
Raw structured results: `windows-pack-io/heap-pairs-source78.json`.

- A `20260905-123029-windows-startup-2x2.zip`: graphics 56.691 s; SHA-256 `b8dd1f4b4e1b9376eb67750034d46b3a9e711e5d4254e243998b78fa1d7ab4c5`.
- B `20260905-123203-windows-startup-2x2.zip`: graphics 41.184 s; SHA-256 `8846c132ddc2ce318b08cfa06bec350da3f338a7660e367435c3001c7a13d6fd`.
- B `20260905-123314-windows-startup-2x2.zip`: graphics 42.844 s; SHA-256 `749a9d9918ca12290c170cd97345a0d08a14e4ee4e27811876fc90e8a554e23c`.
- A `20260905-123436-windows-startup-2x2.zip`: graphics 51.334 s; SHA-256 `b546727e4b1dc0c11ec2d6b6a5d6bb162a86ada26d70df195954071089b53df2`.
- A `20260905-123558-windows-startup-2x2.zip`: graphics 53.680 s; SHA-256 `078efd0a27695462c5288bf806df541353bada9e3132aaccd98603850c8f126b`.
- B `20260905-123731-windows-startup-2x2.zip`: graphics 42.521 s; SHA-256 `4e216ac3bbe06e27f73e0d721fe6d639bace1ea4125e00854a7da369a6cbe998`.

The final code also declines quoted explicit heap environment options. Its healthy fixture
behavior is unchanged; a freshly packaged final build will receive a separate same-JAR
confirmation pair rather than being conflated with these six observations.

## Final executable confirmation and disposition

Executable source `308d23b82a3586836095105f45bfb3ede149f219`; JAR SHA-256
`7eba6ad258af832316d8400aa74cbfe4a1a291de59a9bcf98386efde290dfbc8`.
Full local Maven verify passed in 44.850 s; all three Java platforms and both operator jobs
passed [CI 33944926098](https://github.com/teamleaderleo/preflight/actions/runs/33944926098).
The real Windows operator fixture passed its 10 shutdown cases and new PID classification
checks. Scope-excluded native desktop packaging is not claimed as tested.

Final same-JAR confirmation (B/A order, same fixture and explicit flags as above):

| Arm | Graphics (s) | Interactive (s) |
|---|---:|---:|
| B | 47.481 | 48.926 |
| A | 55.835 | 58.325 |

Final confirmation improvement: 9.399 s (16.1%). This is one pair on the final
artifact, kept separate from the earlier three-pair set. Both runs passed all resource and
lifecycle checks. The prototype remains opt-in; no heap maximum, texture policy or worker
change is made. The earlier native upload stall remains unresolved and is not hidden by
assigning it a successful-startup time.

- `20260905-124252-windows-startup-2x2.zip` SHA-256 `c0deaace0a5bf92123b455a332aa28e4bfc0dae3d0e940492d5091d5eb16f8df`.
- `20260905-124417-windows-startup-2x2.zip` SHA-256 `3e3baa7757e0bbf49f2c28ebcb1399b6c81f71ab0c7e01024b3a3d89a8cb910d`.

Raw structured confirmation: `windows-pack-io/heap-final-pair-source308.json`. The original
pack/hint/launcher files are retained; cohort runs performed no preparation. Normal scheduled
task arguments and host power policy are restored by the harness. The installed final JAR is
retained for use; temporary transfer JARs, rollback copies and local build output are disposable
and are removed during task closure.
