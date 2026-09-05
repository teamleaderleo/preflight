# Windows prepared byte-barrier experiment

Owner: current Codex task, authorized by Leo to pursue the five proposed performance avenues.
Starting main: `5fe43a80e0715a2957398e787c7c6e7ea3087861`.
Phase: local implementation verified; Windows diagnostic next. No speed claim yet.

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

## Remaining authorized work

1. Run barrier diagnostic on native Windows, capture/settle any stall, compare same package with
   explicit barrier false without intrusive probes if healthy.
2. Investigate bounded upload-buffer reuse/read-directly options. Installed TextureLoader cleanup
   explicitly attempts reflective cleaner invocation: recycling a buffer after existing cleanup
   risks reuse of freed memory. Any pool must own allocation and intercept cleanup for its exact
   buffers; ordinary buffers retain original cleanup. Do not implement naive post-cleanup reuse.
3. Inspect raw-byte/audio consumers before removing any encoded reads; current prepared-audio
   path hook is downstream of InputStream acquisition.
4. Inspect existing deferred handle first-bind/metadata/reload contracts before lazy GPU upload.
5. Integrate coherent results to main, preserve evidence, restore Windows task, stop game, clean builds.

Validation so far: full local Maven verify passed; operator tests 119, 114 passed and 5 skipped
(PowerShell/platform); focused installed barrier/runtime tests passed before adding wait coverage.

## First native observations

Executable `d746cd5f`, packaged JAR SHA-256
`86a46618917913492c7d8010b65a2949772a3cc40c1c0779d15257f10fcd4511`.
Recommended, one worker, native driver selection, 1024x720, same fixture and mod selection.

- `20260905-100940-windows-startup-2x2.zip` SHA-256 `a3e3fca1759a515d04307c84e95be02016544ff85c8c0ff61e0e94dfa305c2a7`
  Resource accounting: {"active": false, "admissionDecline": "none", "admitted": 15003, "barrierPending": 0, "barrierRemoved": 15003, "barrierTaken": 15002, "barrierUnused": 1, "byteBarrierRequested": true, "bytePhaseComplete": true, "ceilingDeclines": 44, "claimAbandoned": 0, "claimErrors": 0, "claimFallbacks": 0, "claimReadMillis": 18370, "coherent": 44, "committed": 15002, "declines": 0, "direct": 14958, "directDimensionCeiling": 1024, "discarded": 0, "failures": 0, "imagePhaseDeferrals": 0, "inFlight": 0, "lastEntryDeclines": 0, "originalConsumed": 0, "pending": 0, "published": 15002, "queuedClaims": 0, "queuedClaimsRequested": false, "requested": true, "resourceRecords": 55359, "resultSignals": 0, "waitMillis": 9826, "waitPolls": 950, "workerDrainMillis": 0, "workerDrainTimeouts": 0, "workerImagePhaseObserved": true}
  Kaleidoscope retained/consumed: 102/102; active buffers 0; pack failures 0.
- `20260905-101246-windows-startup-2x2.zip` SHA-256 `3409182dc27c45589e900714f7b907c1f85e7eb565201dbe31bf51bb5839af59`
  Resource accounting: {"active": false, "admissionDecline": "none", "admitted": 15003, "barrierPending": 0, "barrierRemoved": 15003, "barrierTaken": 15002, "barrierUnused": 1, "byteBarrierRequested": true, "bytePhaseComplete": true, "ceilingDeclines": 44, "claimAbandoned": 0, "claimErrors": 0, "claimFallbacks": 0, "claimReadMillis": 17763, "coherent": 44, "committed": 15002, "declines": 0, "direct": 14958, "directDimensionCeiling": 1024, "discarded": 0, "failures": 0, "imagePhaseDeferrals": 0, "inFlight": 0, "lastEntryDeclines": 0, "originalConsumed": 0, "pending": 0, "published": 15002, "queuedClaims": 0, "queuedClaimsRequested": false, "requested": true, "resourceRecords": 55359, "resultSignals": 0, "waitMillis": 9650, "waitPolls": 934, "workerDrainMillis": 0, "workerDrainTimeouts": 0, "workerImagePhaseObserved": true}
  Kaleidoscope retained/consumed: 102/102; active buffers 0; pack failures 0.
- `20260905-101501-windows-startup-2x2.zip` SHA-256 `10fe88b00d0751d2d0904f3886654567b582d60acd829124bf2091156d773b81`
  Resource accounting: {"active": false, "admissionDecline": "none", "admitted": 15003, "barrierPending": 0, "barrierRemoved": 0, "barrierTaken": 0, "barrierUnused": 0, "byteBarrierRequested": false, "bytePhaseComplete": false, "ceilingDeclines": 44, "claimAbandoned": 0, "claimErrors": 0, "claimFallbacks": 0, "claimReadMillis": 0, "coherent": 44, "committed": 15002, "declines": 0, "direct": 14958, "directDimensionCeiling": 1024, "discarded": 0, "failures": 0, "imagePhaseDeferrals": 0, "inFlight": 0, "lastEntryDeclines": 0, "originalConsumed": 1, "pending": 0, "published": 15003, "queuedClaims": 0, "queuedClaimsRequested": false, "requested": true, "resourceRecords": 55359, "resultSignals": 0, "waitMillis": 18790, "waitPolls": 1752, "workerDrainMillis": 1859, "workerDrainTimeouts": 0, "workerImagePhaseObserved": true}
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
`test_launcher_marker_then_quiet` (expected 2100, observed 2000); retrying that failed job once.

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
