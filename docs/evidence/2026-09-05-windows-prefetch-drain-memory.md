# Normal Windows worker shutdown and VM memory

Normal Recommended launches now give the exact finite Windows prefetch worker up to five
seconds to finish before the original interrupt and map cleanup. This closes the shutdown
race identified in [the ordinary-launch investigation](2026-09-05-windows-normal-launch-memory.md):
interrupting an active read can close the shared pack channel and disable later pack serving.

The VM was separately increased from 12 to 16, then 20 GiB. Its 14 active vCPUs were preserved.
The final padded-policy launch at 20 GiB reached the interactive menu in 24.236 seconds.
The native unpadded path still stalled with about 9 GiB free; no native stall fix is claimed.

## Implementation and contracts

Executable source: `5fcfd7bae6d5d9071ba3f7a1197e28b49c095956`.
JAR SHA-256: `470437a855aa58ccf0b2dff2be83e4b10f7482fb80154c21a428c3e928933135`.
Subsequent documentation and incomplete-archive handling do not change these executable bytes.

Installed common archive and worker contracts remain those recorded in
[the bytecode inspection](2026-09-05-windows-prepared-resource-contracts.md).
Fresh in-memory disassembly confirmed that `L$1.run()` consumes the byte queue, consumes the
image queue, then returns. `L.Ò00000()` interrupts its static Thread and clears the two maps.
The existing registered target binds the preloader class and entire common archive, including
the reviewed worker. The new weave requires the unique original Thread-field interrupt site;
it duplicates that exact receiver for a bounded join, then leaves the original interrupt intact.
The existing learned-result retention still owns image-map cleanup; byte-map cleanup remains stock.

No worker is added, no queue is reordered, and the join performs no GL or file I/O. Handler,
alias, registration, transformation, sampler/mipmap, reload and 1024-ceiling behavior are unchanged.
Timeout or caller interruption returns to original cancellation; caller interruption is restored.
Unknown Thread subclasses and self-join decline. The typed prepared-resource prototype retains
its existing drain without stacking another five-second wait. Normal single-worker learned
Kaleidoscope serving enables the new behavior; `preflight.texture.windowsPrefetchDrain=false`
opts out. `prefetchShutdown` reports bounded aggregate counters and wait time. Its `completed`
counter means the thread exited, not independent proof that every job succeeded.

## Fixed 12 GiB check and later RAM observations

All completed runs: ordinary `preflight`, Recommended, 1024x720, one worker, automatic 2 GiB
initial heap with the existing 6 GiB maximum, typed prepared resources disabled. The existing
`GALLIUM_DRIVER=llvmpipe` selection supplies padded coherent-direct uploads. As the prior
loaded-module check established, that environment value is not proof of a Mesa renderer.
The same JAR, launcher batch/wrapper, enabled-mod hash and CPU count matched across all four.

| Session | Configured RAM | Drain | Graphics preload | Interactive menu |
| --- | --- | --- | --- | --- |
| `20260905-140336` | 12 GiB | explicitly off | 47.384 s | 49.008 s |
| `20260905-140521` | 12 GiB | normal default | 42.796 s | 44.484 s |
| `20260905-141001` | 16 GiB, after shutdown/start | normal default | 29.272 s | 31.830 s |
| `20260905-141446` | 20 GiB, after shutdown/start | normal default | 22.487 s | 24.236 s |

Every completed run retained/consumed all 102 seeded late resources, with zero pack failures,
pack fallbacks, active upload buffers and pending upload buffers. Enabled runs called the new
hook once and completed without timeout/error; recorded wait was below one millisecond because
the worker had already exited. The control also finished before interruption. Thus the 12 GiB
pair does not isolate a timing benefit from draining: the race did not occur in this pair.
The correctness tests exercise delayed completion, timeout and caller cancellation directly.

The RAM observations include fresh guest boots, different cache state, and bounded memory
sampling. They are useful current-configuration observations, not a randomized RAM-effect
campaign or evidence that the shutdown code alone produced the speedup.

## RAM change and pressure

The user authorized more VM memory if needed. The 30 GiB host had about 28 GiB available after
each graceful guest shutdown. Inactive XML comparison verified that only `memory` and
`currentMemory` changed: 12,582,912 to 16,777,216, then 20,971,520 KiB. The configured CPU maximum
remained 16 with 14 active CPUs. Windows confirmed 17,109,008,384 physical bytes at 16 GiB and
21,403,975,680 at 20 GiB. Launcher files, game/mod assets, pack and order hint were not prepared
or rewritten for the memory change. The VM is left running with 20 GiB configured.

Nine bounded 16 GiB observations sampled as low as 93.5 MiB free, with process private bytes
peaking at 24.34 GiB. Three more widely spaced 20 GiB samples observed at least 4,742.7 MiB free.
These sparse samples have different cadence and do not establish exhaustive memory extrema.
Private bytes are not attributed entirely to either Java heap or the graphics driver.
Private XML snapshots and guest/host memory receipts are retained under `windows-prefetch-drain/`.

## Native-selection failure at 20 GiB

Session `20260905-141642` removed the Gallium environment setting and selected the existing
unpadded policy. It did not reach graphics/menu completion. Two snapshots at 178.07 and
188.35 seconds showed main inside `GL11.nglTexImage2D` through the stock TextureLoader/resource
path, with unchanged 7,000 ms main CPU. Loaded modules were system `OPENGL32.dll` and Intel
`igxelpgicd64.dll`. The stalled-state observation had 9,587,092 KiB physical memory free.
More RAM did not resolve this stall; the observation does not identify its underlying cause.

Window-close returned false; the exact verified game process was forcibly retired. The runner
then exited with task result 1 without publishing its top-level summary. Its shutdown record
stopped in the wrapper-close phase, and the CLI run record remained RUNNING. These are incomplete
records, not success. The host previously refused to archive this case. Its completion path now
retains the raw archive with `accepted=false`, `incomplete=true` and an explicit missing-summary
reason, while preserving the stale-session guard and failure exit. Replaying that exact updated
completion block against this real failed session produced the 762,799-byte archive below.
No successful timing or complete terminal CLI record is fabricated. Supplemental thread,
process, memory and current game-log captures are retained as `native-stall-*` in private evidence.

## Validation and artifacts

Full local Maven verification passed in 46.796 s. Ten focused plan/runtime tests passed; an
additional test against the exact installed preloader and worker hashes verified ASM dataflow,
one unchanged Thread.start, join-before-interrupt order, both cleanup owners and duplicate refusal.
The 121 Python operator checks passed with 116 successes and five platform skips. Windows,
macOS and Linux Java verification and both operator jobs passed in workflow `33948733973`.
Final PR checks cover the later host archival change; no game-runtime retest is implied by a
documentation or archive-only commit.

Private archives are in Windows-Share Diagnostics; structured successful-run identities are in
`windows-prefetch-drain/results.json`. The fifth archive is explicitly incomplete.

| Archive (`-windows-startup-2x2.zip`) | SHA-256 |
| --- | --- |
| `20260905-140336` | `6ea18a882ca1cc3694fd2001c8386738f8ef07198cadbd96ae54cf46050da92b` |
| `20260905-140521` | `776b720ad06f2f65370fab77769549adf2b0a4cf9412f3c88d1f624a6551c6f6` |
| `20260905-141001` | `4c29113e863691ce03d1f9b972c2b5f01dbf2e241b649aae15e9c20c447cb4d1` |
| `20260905-141446` | `a5f96b890948349712bb4cdfb696e60f8a255a80ab708989a017a4159cb9a27d` |
| `20260905-141642` | `d7c8711e328a1d6795d86f8957d966e4c80b3b263c811bc2769136bab28393bb` |
