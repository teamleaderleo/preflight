# Windows prepared byte-barrier experiment

Owner: current Codex task, authorized by Leo to pursue the five proposed performance avenues.
Starting main: `5fe43a80e0715a2957398e787c7c6e7ea3087861`.
Phase: executable prototypes verified locally, in three-platform CI, and on native Windows.
No measured speedup; all new experiments remain opt-in.

## Contracts and implementation

[Parent contracts](2026-09-05-windows-prepared-resource-contracts.md) own installed hashes,
handle/cache/reload semantics, GL policy, and the 1024 ceiling. [Earlier claims](2026-09-05-windows-prepared-resource-claims.md)
own rejected early claims and the no-win wakeup experiment.

Re-inspected installed common `L$1.run`: raw-byte loop tests `õ00000.isEmpty()` at BCI 101–109;
fallthrough at 112 is the sole normal image-phase entry. Images are first dequeued at 127,
with the loop test at 213–221. The hook runs at fallthrough, outside both queue monitors.
It removes admitted prepared image jobs before image ownership begins. It does not alter byte
jobs, add workers, move GL, or depend on a fabricated sentinel. If the hook is absent, it removes
nothing. The resource list is admitted immediately before worker start (core BCI 1857), after
stock enqueues (1829/1844); this prototype skips image-worker admission, not the enqueue itself.

Exact prepared identities, stock result conflicts, worker identity, session lifetime, typed
main scopes and the independent property all gate consumption. Existing transform and handler
cases still decline typed take. Prepared read failure retires the removed job and permits original
decode fallback. Remaining unknown and late jobs preserve their order. Counters distinguish
removed jobs, taken identities and discarded unused identities. No default promotion.

The optional upload checkpoint records the last attempted native call and buffer bounds in one
bounded overwritten file before GL entry. No GL query or additional worker is used. Its I/O is
intrusive and requires separate runs from startup comparisons. An adjacent probe defect was fixed:
subimage dimensions occupy different argument slots from image dimensions; timing telemetry now
uses actual width and height rather than y-offset and width. Native calls are unchanged.

## Outcome and scope

The byte barrier safely bypasses 15,003 stock image jobs and commits 15,002 prepared resources
on main, preserving all 102 learned Kaleidoscope late resources. One unused identity is retired
at session end. The same-package uninstrumented pair was slower with the barrier enabled;
there is no case for default promotion. Pack lookup, rather than layout classification or upload
carrier construction, dominates the measured prepared CPU path.

All five avenues were investigated. The barrier, bounded upload breadcrumb, load attribution,
exact-entry reader and pack-order snapshot are implemented behind independent switches.
Buffer pooling, Windows PCM bypass and deferred GL realization are not implemented: their
installed ownership/consumer contracts require separate work, detailed below. Existing worker
count, scheduling, GL ownership, handler behavior and the 1024 ceiling remain in force.

Full local `mvn verify` passed for the final executable d3de6398. Three-platform CI
[33940991134](https://github.com/teamleaderleo/preflight/actions/runs/33940991134) passed all
Java and operator jobs; desktop packaging jobs were excluded by scope. Operator verification
passed 115 of 120 tests, with five PowerShell/platform skips on Linux. Installed bytecode and
runtime tests cover boundary placement, accounting, wakeup, native argument preservation,
pack corruption/close/interruption and independent-session order acceptance.

## First native observations

Executable `d746cd5f`, packaged JAR SHA-256
`86a46618917913492c7d8010b65a2949772a3cc40c1c0779d15257f10fcd4511`.
Recommended, one worker, native driver selection, 1024x720, same fixture and mod selection.

- `20260905-100940-windows-startup-2x2.zip` SHA-256 `a3e3fca1759a515d04307c84e95be02016544ff85c8c0ff61e0e94dfa305c2a7`
  Resource accounting: barrierRemoved=15003, barrierTaken=15002, barrierUnused=1, committed=15002, direct=14958, coherent=44, failures=0, inFlight=0, pending=0.
  Kaleidoscope retained/consumed: 102/102; active buffers 0; pack failures 0.
- `20260905-101246-windows-startup-2x2.zip` SHA-256 `3409182dc27c45589e900714f7b907c1f85e7eb565201dbe31bf51bb5839af59`
  Resource accounting: barrierRemoved=15003, barrierTaken=15002, barrierUnused=1, committed=15002, direct=14958, coherent=44, failures=0, inFlight=0, pending=0.
  Kaleidoscope retained/consumed: 102/102; active buffers 0; pack failures 0.
- `20260905-101501-windows-startup-2x2.zip` SHA-256 `10fe88b00d0751d2d0904f3886654567b582d60acd829124bf2091156d773b81`
  Resource accounting: barrierRemoved=0, barrierTaken=0, barrierUnused=0, committed=15002, direct=14958, coherent=44, failures=0, inFlight=0, pending=0.
  Kaleidoscope retained/consumed: 102/102; active buffers 0; pack failures 0.

Diagnostic 100940: 71.406 s graphics / 74.136 s interactive. This included phase, CPU and
per-upload file-write probes and is not timing evidence. All 15,003 admitted image jobs were
removed at the boundary, 15,002 committed, one unused identity retired. Direct 14,958, coherent
44; all 102 late resources retained/consumed; no pack failures, active buffers or failed commits.
Main carrier reads took 18.370 s; byte-boundary wait 9.826 s; direct preparation 1.686 s.
The first breadcrumb revision mislabeled Windows path uploads as buffered-image because its
path descriptor had five integer arguments while Windows has four. Its argument/buffer capture
was valid, but no exact path attribution is claimed for that revision. The descriptor is corrected
in the next revision. Periodic upload reports are not assumed to contain every final call.

Uninstrumented B 101246: 66.180 s graphics / 69.552 s interactive.
Uninstrumented A 101501 (same JAR, explicit barrier and claims false):
60.072 s graphics / 62.903 s interactive. One pair only, but it supplies no positive performance
case: the bypass worked and was slower in this observation. Keep it opt-in. The next diagnostic
splits aggregate prepared lookup (including pack), pack read/decode, layout and carrier construction.

Windows sound archive inspection: SHA-256
`d70e2760c9785770818607edd7be502ac75f7b87f8af5770c178a8d723c96dab`;
store `sound.C`, decoder `sound.O0oO.super(InputStream):sound.G`. The Windows report confirms
preparedAudio.enabled=false. Current prepared PCM target/producer expect sound.J/sound.F and a
different archive. Encoded byte reads cannot be called redundant on this fixture. A Windows PCM
port would need matching producer, exact decoder-policy identity, result fields, source admission
and runtime targets before any byte-work removal.

Deferred handle inspection: exact Object.Ø00000 simply binds stored target and ID; it does not
inspect the deferred flag or invoke a loader. Flipping the existing flag is not a first-use upload
implementation. A genuine deferred path needs metadata-ready handles and coverage of direct ID
consumers, binding, replacement and reload; no fake/blank texture substitution was introduced.

All three Java CI jobs passed in workflow 33938272307. macOS operator checks failed in unchanged
`test_launcher_marker_then_quiet` (expected 2100, observed 2000); that failed job passed on one retry.

## Aggregate attribution and bounded read-ahead

Attribution executable `0391355e246a005e4d83221e5cc4db4dd331d720`, JAR
`9778bc50a971a943c3230437f1d369768831fdbab2949c3a29e414da05c7e115`.
Native session `20260905-102135` reached graphics 57.344 s / interactive 60.333 s.
This is a diagnostic observation, not a cross-build speed comparison. All 15,002 typed commits,
44 coherent fallbacks, and 102 late resources completed; zero pack failures or active buffers.
Across 15,473 loads: lookup 17.813 s, including pack 17.558 s; layout 7 ms; carrier construction
124 ms. Direct preparation was 1.373 s. Main claim-read time was 16.480 s and byte wait 7.476 s.
Totals cover multiple callers and must not be added to wall startup time.

The next independent candidate is `preflight.texture.packReadAhead`, false by default.
It maintains one synchronized 4 MiB heap window per immutable open pack, serves positional reads
by copying from that window, and bypasses the window for larger read requests. It preserves the
existing entry parser and per-entry CRC32C verification; no checksum is skipped. Prepared pixels
retain their own arrays and cannot alias the reusable window. The window is session data from the
open pack, never a cross-pack cache; closing the pack clears it and reload creates a fresh reader.
Closed/interrupted source channels remain failures even for a cache hit. The candidate records
file-read calls/bytes/time, fills/hits/bypasses and CRC time. Unordered access can amplify I/O;
measure it before acceptance. No worker count, GL operation or direct upload allocation changes.

Focused core tests: 14 passed, including original raw/LZ4 corruption checks with read-ahead enabled,
byte-exact reads across window boundaries, large-read bypass, source close and interruption.
Full Maven verify passed (48.217 s). Operator tests: 120, 115 passed and 5 platform skips.
Attribution revision three-platform CI 33938844554 passed every Java and operator job.

Attribution archive SHA-256: `de287bdd31aa1d1960ec1e12db5cb37ac23db5fac4d2fd26643b5ac9e1e9c754`.

## Rejected speculative window; exact-entry successor

Executable `1155a17448ab3f73eee39227265531ba935f3b4d`, JAR
`c1dee122d485e642706965c760b681e94ac7c9bfa9137aa3c543a7abc5def011`.
Native diagnostic `20260905-103353`: graphics 93.335 s / interactive 95.686 s.
All 15,002 commits and 102 late resources completed, with zero pack failures/active buffers.
The speculative window filled 8,794 times and made 8,812 file reads, reading 37,022,388,123 bytes
for 2,116,618,727 bytes of served prepared pixels. File-read time 38.773 s; CRC 244 ms;
pack total 48.194 s. This variant is rejected: random/scattered access defeats neighboring-entry
speculation. It must not be promoted or described as a performance improvement.

The successor retains the independent property but resets scratch to the current exact entry on
every readTrusted call. Entries up to 4 MiB are fetched once, including their embedded checksum;
larger entries use the original positioned reads. A monitor spans the parse and CRC so readers
cannot overwrite each other's range. No neighboring entries are fetched or retained. Per-entry
CRC and parser bounds remain unchanged, and a repeated entry read observes fresh file bytes.
Tests assert exact physical bytes read, one syscall for a small range, and fresh rereads after
same-length mutation, in addition to the earlier corruption/close/interruption cases.

The read-ahead CI run's Java jobs passed, but macOS operator checks again hit an existing real-time
fixture race, this time first-observed-line versus game-start timing (35.153 ms vs >40 ms).
The two observed flaky detector tests now use scheduled monotonic clock ticks while preserving
real log-file I/O and original assertions. Production log-detector code is unchanged.

## Exact-entry result and pack-order lifecycle investigation

Executable `8f6a303193df38c6749b0e898e4685286486ddbf`, JAR
`55c766f70c56871e207dc49c99e416239db976debd6c8f522397662f3b88e740`.
Diagnostic 104834 (barrier, attribution and corrected upload checkpoint): graphics 65.997 s /
interactive 68.658 s. Pack time 18.408 s; actual file reads 13.185 s; CRC 229 ms. The exact-entry
reader made 15,493 file reads totaling 1,092,827,943 bytes, eliminating speculative amplification.
15,002 commits, all 102 late resources, zero pack failures/active buffers. The corrected last-attempt
breadcrumb identifies `graphics/fx/rat_seraph_lensflare.png`, with 512x16 RGBA, 32,768 buffer bytes,
main thread; this is the last attempted call, not a stalled call. No native stall occurred.

Same-JAR uninstrumented comparison, barrier and queued claims explicitly false in both legs:
B 105347 (entry reader on) graphics 61.654 s / interactive 64.778 s;
A 105614 (entry reader explicitly off) graphics 57.047 s / interactive 59.838 s.
One pair, no positive performance case and no promotion. Entry coalescing alone is insufficient.
Three-platform Java/operator CI 33940038873 passed all jobs. The cancellation-order regression test
also verifies the underlying channel closes while another thread owns the scratch monitor.

The active Windows pack remained the same 2,259,086,856-byte file throughout these runs. Its
`.spfo` learning file was only 54,955 bytes and had not changed since before this experiment,
despite accepted menu snapshots. Code inspection found pack order only persisted at shutdown,
reconfiguration or session reset. The existing menu publisher already flushes other learned
orders specifically to survive Windows exits that miss shutdown hooks, but omitted pack order.
The cohort's cache check reuses a valid cache and does not run optional physical reordering.

The final opt-in `preflight.texture.packOrderSnapshot` saves at the semantic menu snapshot, with
one successful observation per configured session. A new test proves repeated menu saves plus
session end cannot promote a candidate; the second independent configured session can. Existing
acceptance still requires two equal orders. The active pack is never rewritten by the runtime;
any later physical reorder is performed and validated by the existing preparation owner.

`20260905-103353-windows-startup-2x2.zip` SHA-256 `661478e4af6e4940ceb7f823810f58cee014edad75847025cdad4dac259be194`.

`20260905-104834-windows-startup-2x2.zip` SHA-256 `d03556d027966335388008c22402c89371221c2d4c5d935497d28f7b1f509701`.

`20260905-105347-windows-startup-2x2.zip` SHA-256 `40e66bb695d5cf1cbf942b2cfcf6d1442c1d92ab9d4bf281f31981ff3f9fd7ed`.

`20260905-105614-windows-startup-2x2.zip` SHA-256 `15e3fe06c4e3427db806eb4d5517b01e0b610abc7fe084b4845fda6e0187c616`.

## Menu snapshot validation

Executable `d3de639818f0e41ec2aed2a2668cd2f9d488478f`, JAR
`97660b4e3e801af527da3d96ddd3834c6256a408d276d50ba9c1091f7fd5313d`.
Both launches used Recommended, one worker, native selection, 1024x720, typed resources,
pack-order snapshot on and barrier/queued claims/entry reader explicitly off. The fixture
mod-selection hash remained `76227ce91333c202271e541774f3e86fd8711c2542d63a81cfd18a4dc0a6997f`.
These learning launches did not change the active pack and are not a pack-order comparison.

- `20260905-111106-windows-startup-2x2.zip`: graphics 55.496 s / interactive 58.511 s; accepted, packOrderPersisted=true. SHA-256 `15a4d6b7811b449b730be22f77a1fbe3914ca7769d39c402fbd6432f1ea0613a`.
- `20260905-112022-windows-startup-2x2.zip`: graphics 63.968 s / interactive 66.846 s; accepted, packOrderPersisted=true. SHA-256 `a5df2e3fe2d4feef223a8008355cddfb7330ba88399a1e1ab20be2fb48445d5a`.

The first independent session saved a 14,769-entry candidate; the second accepted the exact same
order. Accepted order digest (UTF-8 paths joined by LF, no trailing LF)
`3d32121feb13aaebaaaeb2d6c940eddb3ad338487a368855cf62b601ed12eea4`.
The original hint contained only 589 candidate entries and no accepted order. This establishes
the lifecycle defect and validates the existing two-session rule without weakening it.
Private original/first/second SPFO copies are retained under shared Diagnostics as
`byte-barrier-original.spfo`, `byte-barrier-order-1.spfo`, `byte-barrier-order-2.spfo`.

## Physical pack-order experiment

After independent acceptance, backed up the active pack on the guest, then ran the tested JAR's
`prepare --game C:\Games\Starsector --cache-dir <active-cache> --deep --verify-lookups`.
The first operator invocation was interrupted by PowerShell treating native progress stderr as
a terminating error. The retry restored normal native stderr handling, recovered the interrupted
preparation owner with zero incomplete temporary files, and completed with exit 0.
Lookup verification passed. Existing `PreparedTexturePackIO.reorder` copied each entry through
`copyVerifiedEntry`, preserving the entry CRC/structural checks and atomic publication.

Pack size remained 2,259,086,856 bytes. Original pack SHA-256:
`a97335bda8c44c9c18e5f8f5969071872ac47f67dc81364c988959b946f73a4d`;
reordered pack SHA-256:
`4a58d9bded7f23434f5e01ab0b81b35f8608c0fa4e2ac71a0bc7af3d92b663b1`.
The original was restored and hash-verified before the control leg. Both legs use d3de6398's
same JAR and the menu-snapshot flags above; the cohort must report preparationPerformed=false
so it cannot silently reapply the accepted order.

B (reordered), `20260905-112451-windows-startup-2x2.zip`: graphics 59.300 s / interactive 62.176 s; accepted, no preparation during cohort. SHA-256 `01ccba2b876cef8fe34997309fa0fd814740d525d9320efe50f57869f352f21a`.

A (original restored), `20260905-112748-windows-startup-2x2.zip`: graphics 53.853 s / interactive 57.083 s; accepted, no preparation during cohort. SHA-256 `02ac84e3587013064aab0b3fa18a908e085fc0166893a27f1429d7640cf698f0`.

Both completed 15,002 prepared commits and consumed all 102 late resources, with zero pack
failures or pending/in-flight typed resources. This single same-JAR pair provides no positive
performance case; no physical reorder or new switch is promoted. The original pack and original
learning hint are restored after the experiment. Raw reorder output and pack identity JSON remain
in shared Diagnostics as `byte-barrier-pack-reorder.log` and `byte-barrier-pack-reorder.json`.
