# Windows queued prepared-resource claims

This experiment builds on `580994f77c4d951b2e9e8b67e2662f978abc404e` and
[the installed resource contracts](2026-09-05-windows-prepared-resource-contracts.md).
The independent opt-in is `preflight.texture.windowsPreparedResourceClaims`.
Typed resources must also be enabled. No default promotion is implied.

## Ownership contract

The installed common archive still hashes to
`5a26d047baefc6dcd763121a17d170e3b864bfb19a83d11f645ba8be49f1641b` and
the core archive to
`5dd222b9e266d2ac2d63b3dad4983eb05caaf5a247d7dfb82aaeba47ea774cc8`.
Inspection used `javap` against archive entries without extracting obfuscated names.

`L$1.run()` drains the byte queue before entering its image loop. In that loop,
BCI 122 acquires the image-queue monitor, BCI 127 removes index zero, and BCI 143
puts the in-flight sentinel into the result map before releasing the monitor.
Decode/publication happens outside the monitor. The loop's BCI 216 `isEmpty()`
test is outside the monitor. The queue is a synchronized `LinkedList`; the result
map is a `ConcurrentHashMap`.

Consequently, main may remove a matching path only under the same queue monitor,
with no result/sentinel already present, and while at least two entries remain.
Keeping the final entry prevents main from invalidating the worker's preceding
nonempty test. Ready typed results take precedence. Main reads the prepared carrier
outside both runtime and queue locks, then uses the existing main-thread completion
commit. It never fabricates a stock result or sentinel. A failed prepared read retires
the removed job and leaves the original getter free to miss and decode. An existing
handler, transform, unknown obligation, changed key, or worker-owned job keeps its
original behavior. Existing aliases, caches, GL policy and 1024 ceiling are unchanged.

The revised candidate additionally requires the exact worker's first completed image
before admitting claims. This preserves the original byte-before-image phase. The
early-claim experiment below is retained as a rejection, not exposed as another mode.

## Rejected early claims

Source `c61aecb418aabb90cfe45a00a2053dc36750aa73`, JAR SHA-256
`7902a2278173d792fd8d45cfdd0cac6375fa28828c4fa7eac57f03c938c35438`.
The same Windows VM had 14 vCPUs, 12 GiB RAM, 1024x720, Recommended policy,
one stock worker, and the existing mod selection hash
`76227ce91333c202271e541774f3e86fd8711c2542d63a81cfd18a4dc0a6997f`.
Both observations below enabled startup phase and texture CPU probes, so their
clocks are diagnostic observations, not a performance campaign.

| Session | Claims | Graphics / interactive | Result |
| --- | --- | --- | --- |
| `20260905-090600` | early claims on | neither reached | rejected; native upload stall, forced cleanup |
| `20260905-091316` | explicit off | 58.211 / 60.523 s | accepted, graceful shutdown |

Early claiming reduced the cursor call to one millisecond. The batch reached its
first progress update in 149 ms. The last completed phase report held 327 other
texture calls totaling 888 ms; this is a partial report, not a completion count.
A thread dump at process elapsed 222.41 seconds found main RUNNABLE inside
`GL11.nglTexImage2D`, through the typed resource wrapper. No Java deadlock was
reported. Loaded process modules included system `OPENGL32.dll` and Intel
`igxelpgicd64.dll`; this was native Arc, not llvmpipe. Normal close did not finish
within the existing 45-second shutdown deadline, so the helper forced cleanup.
Subsequent checks found no game process. No final adapter report exists for this
failed run, so complete claim/retirement or upload accounting cannot be asserted.

The disabled observation completed all 15,002 commits (14,958 direct, 44 coherent),
plus one original consumer; it retained and consumed all 102 Kaleidoscope results.
There were no pack failures, pending completions, active upload buffers, or failed
commits. Polling accounted for 16.295 seconds; direct preparation took 1.768 seconds.
The cursor took 8.651 seconds. These observations motivate testing claims after
the byte phase, but do not prove why the earlier native upload stalled.

Evidence under `/home/leo/Windows-Share/Diagnostics/`:

| Artifact | SHA-256 |
| --- | --- |
| `20260905-prepared-claims-c61aecb4-rejected.zip` | `f9970da7dfea44a33efe0c41385463211972604214ac69a5ba9ab73cf12022e2` |
| `20260905-091316-windows-startup-2x2.zip` | `658836160d6274ee6f0eb74d5c2a6f1bbe42aadd2b319b2e72e602082a095a92` |

The native thread dump is `prepared-claims-c61aecb4-threads-13848.txt`; the separate
forced-stop report is `prepared-claims-c61aecb4-stop/shutdown.json`.

## Verification

The revised candidate passes 49 focused tests with the installed Windows archives
and shared game libraries. They exercise bytecode verification, real loader/handler/
repository behavior with fake GL, claim races, ready-result precedence, cache-miss
retirement, final-entry preservation, worker-phase admission, and the prior ceiling,
replacement and exceptional-release contracts. Full local verification, three-platform
CI, and native observations are recorded with the final candidate on
[PR #1225](https://github.com/teamleaderleo/preflight/pull/1225).

## Image-phase claims and post-publication wakeups

Source `13d47aac24c6efc1a7d11b366550a9ff43c3f825`, JAR SHA-256
`ee78017f6b1834ff567a4101a74c983c7bff9f0640256b82283862425618ab45`,
completed the instrumented `20260905-091836` observation at 61.256 seconds graphics
and 64.424 seconds interactive. All 15,002 commits, 44 ceiling fallbacks and 102 late
Kaleidoscope results completed with zero failures, pending results, or active buffers.
However, it made **zero queued claims**. The worker already owned every needed image
after its byte phase. Main accumulated 19.309 seconds across 1,811 polling waits.
This is a correct but ineffective claim-only variant on this fixture.

Its archive `20260905-091836-windows-startup-2x2.zip` has SHA-256
`df512819ca0925eaa44fcedfa7aa7aa08d06fd307ab992b8cfdb097b0330bde8`.

The next revision adds a wakeup immediately after the exact worker's completed-image
`Map.put` at BCI 167 and its following `POP`. The byte-queue loop, in-flight sentinel,
decode call, exception handling and stock map operations are unchanged. This worker
target is registered only for the opt-in successor and is bound to the same archive,
class hash and app classloader. The notification is deliberately after the stock map
insertion, not the earlier typed publication at decode return. Main checks the result
and enters `Object.wait(10)` under the notification lock, preventing lost wakeups while
retaining the original timeout when no signal arrives. Only the bound worker and the
currently awaited path may signal. No worker, queue order or GL owner changes.

Eight native Windows PowerShell 5.1.26100.9168 runner cases passed for enabled and
explicit-disabled forwarding and incompatible-option rejection.
