# Windows VM startup tuning — worker preparation closes much of the standalone gap

Date: 2026-09-01 (host) / 2026-09-02 (Windows guest)  
Status: accepted exploratory attribution and successor experiments; not a repeated release claim

## Answer first

The Windows fixture was not merely “a slow VM.” It exposed two separable effects.

First, its 16 guest vCPUs included Big Red's two 2.5 GHz low-power cores while QEMU's emulator and
disk I/O threads were pinned onto those same cores. Both host and guest also used balanced power
plans, and Windows Defender had no exclusions for the 89-mod game tree or Preflight cache. After
reserving those two low-power cores for QEMU, giving Windows the fourteen 4.4-5.1 GHz cores, setting
both systems to performance plans, and adding only those two Defender path exclusions, stock
Preflight reached the graphics-preload marker in 111.910 and 115.413 seconds. The earlier accepted
stock result was about 181.313 seconds. This is one tuned A-B-A observation, not attribution of the
gain to any one knob.

Second, Preflight plus Fast Rendering reached the same graphics marker in 38.010 seconds between
those stock runs. That marker was early: its exact semantic `main-menu-interactive` transition was
50.998 seconds after process start. Even on the stricter usable-menu clock, the combined route was
about 2.2 times faster than the stock route's 111-115 second ready interval.

The remaining large target is now specific. Stock Starsector logged 15,458 texture-loader operations
on the main thread across 110.087 seconds. Fast Rendering logged 15,573 texture loads across three
workers plus 542 main-thread operations across 49.467 seconds. Preflight's stock prepared-pixel
adapter served 460 exact hits and 229,314,384 bytes with zero prepared-pixel fallbacks; its own code
spent only 0.763-1.855 seconds inside the intercepted loads while 106.6-108.8 seconds elapsed between
them. Hashing and pixel conversion are no longer the dominant Windows startup cost in this fixture.

After the cohort runner was repaired and pushed to `main` at `1ba9286f`, a native confirmation made
both Preflight-backed conditions wait for the exact interactive title boundary. Stock Preflight took
112.455 seconds to graphics preload and 125.255 seconds to the interactive menu. Preflight plus Fast
Rendering took 37.002 and 49.551 seconds respectively. On the same time-to-play boundary, the
combined route was 60.4% faster, or 2.53 times quicker.

The first large standalone successor is now retained on `main`. Instead of removing Starsector's
Windows prefetch queue, `d4799fc1` keeps that exact queue and decoder fallback but lets its worker
resolve prepared pixels before the main thread needs them. Two healthy runs reached the interactive
menu in 69.640 and 66.741 seconds: a 68.191-second median, 45.6% faster than the 125.255-second
standalone reference. All 15,003 worker requests were prepared hits in both runs; zero used the
original decoder, zero fell back, and zero buffers remained live at shutdown. This is exploratory
rather than an interleaved release claim, and it still trails Preflight plus Fast Rendering's 49.551
seconds by 18.640 seconds.

That one-worker path is now the Prepared Pixels default on the exact pinned Windows class. The
rejected full bypass and the multi-worker rewrite remain opt-in diagnostics, and
`preflight.adapter.disablePlans=texture-prepared-prefetch-v1` remains the immediate kill switch.
Mac and Linux do not match the Windows class identity and are unchanged.

Adding workers to the stock queue did not close the remaining gap. The first live attempt correctly
declined because the real class has a public byte-result consumer and a private byte decoder with
the same descriptor; its 151.936-second menu time is therefore not a candidate measurement. After
the matcher and its synthetic fixture were corrected, three workers completed the same 15,003
prepared image jobs plus 4,454 byte jobs, reached a peak of three active workers, settled to zero,
and reported no failure, fallback, or original decode. It nevertheless reached the menu in 71.644
seconds, versus the one-worker 68.191-second median. The three-worker candidate is retained as a
healthy rejection rather than promoted.

Log alignment explains why this is not the same parallelism as Fast Rendering. In the 49.551-second
Fast Rendering run, its `FR-Resource-Loader` worker began common per-mod CSV loads at about 3.5
seconds while the main thread serviced render/upload work. With standalone Preflight, those same
loads remained on `main` around 41.9-48.4 seconds. Common later landmarks were consequently about
19.7 seconds behind: main-menu music at 54.425 versus 34.784 seconds, save-descriptor access at
55.441 versus 35.723 seconds, and GraphicsLib preload at 57.063 versus 37.302 seconds. Preflight's
workers already parallelize offline preparation, and the accepted Windows worker parallelizes
prepared image resolution; neither overlaps the live spec-store/CSV phase with main-thread GL
commit work. That live producer/consumer overlap—not another blind worker-count increase—is the
next architectural target.

## Exact A-B-A

All three runs used the same Windows 11 installation, 89-mod profile, 1024x720 windowed display,
llvmpipe renderer, current prepared cache, Eclipse Temurin 17.0.10 game runtime, and Preflight JAR:

```text
9b3fedf64716b3a051dfd74d9efa8fc0dcf0efb3e0498a8fea9f4df6654e3e55
```

The prepared-cache check passed before every leg and preparation was not run.

| Order | Condition | Game-log start -> graphics preload | Process start -> semantic menu | Adapter result |
| ---: | --- | ---: | ---: | --- |
| 1 | Preflight | 111.910 s | 111.292 s to `main-menu-ready` | accepted; 23 applied / 24 exact; one bounded decline |
| 2 | Preflight + Fast Rendering | 38.010 s | 50.998 s to `main-menu-interactive` | accepted; 19/19; zero declines |
| 3 | Preflight | 115.413 s | 114.121 s to `main-menu-ready` | accepted; 23 applied / 24 exact; one bounded decline |

The stock decline was the exact combat-runtime-integrity plan declining `CombatState` and retaining
the original bytes. Both stock runs reported zero contained failures and zero source-binding
rejections. The combined run reported zero declines, contained failures, or source-binding
rejections. All three closed the game gracefully.

The stock runner stopped after the historical graphics marker and therefore did not wait long enough
for the title overlay's stricter interactive transition. The Windows cohort runner now waits for and
requires that exact boundary for Preflight-backed conditions and reports it separately from graphics
preload. This preserves the historical clock without presenting it as time-to-play.

## Same-semantics native confirmation

The repaired runner retained the same game/profile/JAR/display identity and automatically recorded
the High Performance power GUID, fourteen guest processors, Defender still enabled, and the two
exact path exclusions.

| Condition | Game-log start -> graphics preload | Process start -> interactive menu | Accepted |
| --- | ---: | ---: | --- |
| Preflight + Fast Rendering | 37.002 s | 49.551 s | yes |
| Preflight | 112.455 s | 125.255 s | yes |

This confirmation is still one run per condition. It establishes the boundary and direction; a
release-performance claim still needs the ordinary shuffled repeated cohort.

## Rejected Windows prefetch-bypass experiment

The Windows `com/fs/graphics/L` prefetch-bypass target had already been reviewed and unit-tested,
but remained outside the live prepared-pixel registry after an earlier full-profile run appeared to
stop at about 39 seconds. A live `jcmd Thread.print -l` on the reproduced run explained that stop:
the main thread was runnable in `GL11.nglTexImage2D`, reached from the exact stock
`TextureLoader` upload method. This was not a Java deadlock, cache wait, or failed bytecode match.

Enabling the exact target with Recommended true-size uploads made one large NPOT upload
pathologically slow under the fixture's Mesa llvmpipe renderer. A second run used Conservative's
padded coherent-direct carrier to test whether only that true-size allocation was at fault. It
passed the old `graphics/stations/rat_probe.png` stopping point, but did not produce a useful launch:

| Candidate | Graphics preload | Interactive menu | Texture cleanup observations | Outcome |
| --- | ---: | ---: | ---: | --- |
| Windows prefetch bypass + Conservative padded prepared pixels | 114.609 s | not reached after about 247 s | 21,795 from 0.760-235.518 s | rejected |

The run was intentionally retained as a rejection. Its prepared cache check passed, it used the
same installed profile and llvmpipe fixture, and it shut down cleanly when the cohort deadline was
reached. Because the menu was not reached, no completed adapter report was available; the result is
not evidence for an exact prepared-hit count. It is sufficient evidence that moving all of this
fixture's uploads onto stock synchronous `glTexImage2D` is a major regression, not a Windows port of
the native Mac/Linux win.

The live Windows bypass registration is therefore fail-closed again. The exact target and its unit
coverage remain available for a future bounded upload strategy or a native-GPU Windows cohort. The
current product must not infer renderer performance from GL2/NPOT correctness capability alone.

## Intrusive stock-upload attribution

An opt-in probe then timed the three exact reviewed stock `glTexImage2D`/`glTexSubImage2D` call
sites without replacing or deferring the original calls. The probe retains only aggregate counters
and the 32 slowest uploads. It is disabled at transformation time unless explicitly requested, so
ordinary launches have no added call-site hook.

The one discovery run was healthy: 25 transformations applied from 26 exact matches, one bounded
decline, zero process failure, exact interactive-menu detection, and graceful shutdown. Its
159.863-second process-to-interactive time is deliberately excluded from performance claims because
the probe is intrusive.

| Counter | Observation |
| --- | ---: |
| `glTexImage2D` calls | 15,483 |
| `glTexSubImage2D` calls | 0 |
| upload bytes | 3,064,515,568 |
| time inside the native calls | 27.768 s |
| maximum call | 82.291 ms |
| calls at least 50 ms | 2 |
| calls at least 100 ms | 0 |

This is a meaningful cumulative tax, not one giant-texture explanation. Even assigning the entire
27.768 seconds to removable upload work would not explain the full gap between standalone Preflight
and the 49.551-second Preflight-plus-Fast-Rendering result. The successor comparisons below measure
the prepared path without presenting this intrusive run's startup duration as a performance claim.

The first such bypass-plus-probe attempt improved the historical graphics marker to 85.984 seconds,
but still failed the actual product boundary: no exact ready or interactive signal appeared before
the harness ended the run cleanly after about 219 seconds. A live thread dump at 195.55 seconds
found the main thread in `GL11.nglPopMatrix` below `TitleScreenState.render`, not blocked in texture
upload. Log silence after 109.539 seconds was therefore not an upload stall. The run ended before
the normal adapter report boundary and exposed a diagnostics gap: the upload probe needed its own
bounded periodic/shutdown sidecar. That sidecar is now part of the opt-in probe; this incomplete run
is retained rather than interpreted as an upload total.

The repaired sidecar made the repeated rejection causal. That run reached graphics preload in
97.972 seconds but still had no exact ready/interactive transition when it ended cleanly after about
230 seconds. The last bounded sidecar checkpoint, at 20,480 uploads, reported 3,805,399,712 bytes
and 27.069 seconds inside native GL, with a 56.061 ms maximum, one call at least 50 ms, and none at
least 100 ms. The native GL total was already slightly below the safe run's 27.768 seconds; the
missing time is therefore outside `glTexImage2D`.

The resource identity explained the workload divergence:

| Log set | Cleanup observations | Unique paths | `cache/` paths |
| --- | ---: | ---: | ---: |
| safe Windows queue | 15,468 | 15,468 | 7 |
| prepared bypass | 21,748 | 21,726 | 6,206 |

The bypass set contained every safe path plus 6,258 more. Of those additions, 6,177 were generated
GraphicsLib `cache/..._normal.png` assets and 66 were `graphics/shaders/` assets. Only 22 bypass
observations repeated a path, so “duplicate uploads” was the wrong first interpretation: the bypass
made a materially larger generated-resource workload enter startup.

Generated `cache/` resources are mutable runtime output, not immutable game/mod input. The next
narrow candidate therefore leaves that namespace on Starsector's original prefetch path while
bypassing exact prepared immutable resources. It has an explicit telemetry counter and remains
behind the Windows diagnostic gate until a same-probe run reaches the semantic menu and restores a
comparable texture workload.

## Comparable padded bypass and worker successor

The apparent bypass workload explosion was partly a preset confound: the safe attribution used
Recommended while the rejected bypass used Conservative. Recommended keeps several additional
startup transforms active. The corrected Recommended-plus-padded bypass restored a comparable
texture workload:

- 15,493 uploads versus the stock probe's 15,483 (0.06% difference);
- 3,066,849,264 upload bytes versus 3,064,515,568 (0.08% difference);
- 19.085 seconds in native GL versus 27.768 seconds in the intrusive stock run;
- 15,489 prepared hits, three exact `entry-missing` fallbacks, zero contained failures, and no live
  buffers at shutdown.

That intrusive candidate reached the interactive menu in 70.986 seconds, but its timing remains
excluded. Two thin repetitions took 97.335 and 87.677 seconds, a 92.506-second median. The corrected
bypass was therefore a real improvement over the 125.255-second standalone reference, but moving
prepared work onto the main thread still left substantial serialization and variance.

The worker successor in `d4799fc1` keeps Starsector's exact `com/fs/graphics/L` image queue. It
deduplicates prepared enqueues, resolves prepared carriers on that worker, and falls directly
through to the original decoder on every miss. The exact class/source/method identities are pinned;
the probe is opt-in and the original path remains the default.

| Run | Graphics preload | Interactive menu | Prepared hits | Worker prepared hits | Original decodes | Fallbacks/errors |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| worker 1 | 55.517 s | 69.640 s | 15,470 | 15,003 | 0 | 0 / 0 |
| worker 2 | 51.834 s | 66.741 s | 15,485 | 15,003 | 0 | 0 / 0 |

Both runs recorded 35,877 duplicate enqueue declines, no original enqueues, no pending or active
buffers at shutdown, and graceful exact-menu completion. Their prepared-store work was also stable:
15.130 and 14.000 seconds inside load calls, and 1.546 and 1.499 seconds inside buffer preparation.
The 15-hit difference is about 0.1% and does not indicate materially different startup evolution.

This is the retained standalone direction. It removes a large portion of the Windows gap without
borrowing Fast Rendering, but it is not enabled as an ordinary preset until visual/menu sanity and
an interleaved cohort settle the exact seam.

## Rejected bounded true-size NPOT follow-up

The worker runs still supplied about 3.066 billion bytes to GL, including about 950 million bytes of
power-of-two padding. An earlier all-size true-dimension run appeared to stall on a 1735x1014 texture, so
`4c686cd6` added an opt-in dimension ceiling: true-size NPOT uploads at or below the ceiling, original
padded uploads above it. This is a diagnostic property, not a preset.

The 1024-pixel ceiling completed correctly but was a decisive performance rejection:

| Metric | Padded worker reference | 1024px true-size candidate |
| --- | ---: | ---: |
| interactive menu | 68.191 s median | 248.119 s |
| upload bytes supplied | about 3.066 billion | 2,222,874,685 bytes |
| padding avoided | 0 | 842,971,059 bytes |
| time inside prepared loads | 14.000-15.130 s | 94.653 s |
| worker prepared hits | 15,003 | 15,003 |
| original decodes / fallbacks / errors | 0 / 0 / 0 | 0 / 0 / 0 |

The candidate served 11,453 textures unpadded and retained padding for 24 larger textures. It
reached the exact menu, shut down gracefully, applied 27 transformations from 28 exact matches with
one bounded decline, and left no buffers live. In other words, the regression is not a workload or
correctness failure: llvmpipe's true-size NPOT path consumes enough CPU to starve the preparation
worker even though it uploads 843 MiB fewer bytes. Renderer capability is not a performance signal.
Keep llvmpipe on padded uploads.

## Tuned VM identity

- Big Red: Intel Core Ultra 7 255H, 30 GiB RAM, NVMe storage.
- Guest: 12 GiB RAM; about 9.2 GiB free after boot, so RAM was not the bottleneck.
- Guest vCPUs: 14 current / 16 maximum, pinned one-to-one to host CPUs 0-13.
- Host CPUs 0-5: 5.1 GHz performance cores; 6-13: 4.4 GHz efficiency cores.
- Host CPUs 14-15: 2.5 GHz low-power cores reserved for QEMU emulator and I/O work.
- Host and guest power plans: performance / High Performance.
- Defender: real-time monitoring remains enabled; exact exclusions are only
  `C:\Games\Starsector` and `C:\Users\Leo\AppData\Local\Starsector Preflight\cache`.
- Disk: virtio-blk on sparse qcow2 over NVMe, `cache=none`, native I/O, discard enabled.
- Display: QXL plus Mesa llvmpipe. These results are startup compatibility evidence, not gameplay
  FPS evidence.

The original libvirt XML is retained on Big Red at
`/home/leo/win11-starsector-before-cpu-partition-20260902-031520.xml`.

## Best and worst retained anchors

These anchors use different sittings and, in some cases, different readiness boundaries. They are a
map of observed behavior, not one controlled leaderboard.

| Platform / condition | Retained observation | Boundary |
| --- | ---: | --- |
| Linux Preflight, best warm learned pack | 18.27 s | game log -> graphics preload |
| Linux Preflight, current-main confirmation | 23.206 s | game log -> main menu |
| Tuned Windows Preflight + Fast Rendering | 37.002 s | game log -> graphics preload |
| Tuned Windows Preflight + Fast Rendering | 49.551 s | process start -> interactive menu |
| Tuned Windows Preflight worker successor | 68.191 s median | process start -> interactive menu |
| Tuned Windows Preflight | 112.455 s | game log -> graphics preload |
| Tuned Windows Preflight | 125.255 s | process start -> interactive menu |
| Earlier Windows Preflight | about 181.313 s | accepted startup route |
| Earlier Windows vanilla | about 369.326 s | accepted startup route |

On the historical anchors, tuned stock Preflight is roughly 3.3 times faster than the retained
Windows vanilla run on the graphics clock. Tuned Preflight plus Fast Rendering is the fastest
Windows configuration seen, but it remains about 2.7 times slower than the best Linux result on the
stricter interactive-menu clock versus Linux's graphics marker.

## Explored questions

### Did the second run merely inherit warm filesystem cache?

No as a complete explanation. The stock A legs bracketed the combined B leg at 111.910 and 115.413
seconds. Stock did not collapse toward 38 seconds after the combined run warmed the same game tree.

### Is Preflight already a full startup superset of Fast Rendering?

It is a healthy additive wrapper: 19 exact Preflight transforms applied under Fast Rendering with no
declines, and the combined route was far faster than the earlier 323.423-second Fast Rendering-only
observation. It is not yet a full texture-path superset. Fast Rendering replaces the stock loader,
so this combined run recorded zero Preflight prepared-pixel attempts or hits. A dedicated exact
Fast Rendering texture seam could potentially combine its parallel scheduling with Preflight's
prepared bytes.

### Can the Windows VM receive Big Red's real GPU?

Not by ordinary full passthrough without taking the host desktop: Big Red has one Intel Arc iGPU and
Linux owns it. The device exposes seven SR-IOV virtual functions, so a future reversible VF lane is
possible in principle. Intel's Windows-guest procedure requires a platform-matched SR-IOV/zero-copy
driver package; that package has not been identified or installed for this Arrow Lake fixture. No VF
was enabled and the host display was not disturbed.

## Open questions / next experiment

1. Inspect Fast Rendering's exact installed concurrency and GL-ownership seam. The remaining
   worker-successor gap is 18.640 seconds; do not assume it is pixel decoding or padding.
2. Run the exact worker successor on a native-GPU Windows machine before promoting any
   renderer-specific behavior. Keep llvmpipe padded; the bounded NPOT result rejects capability-only
   gating.
3. If Fast Rendering's additional concurrency cannot be reproduced safely, retain it as the
   supported parallel texture owner rather than deferring unsettled GL work behind an early menu.
4. Investigate Intel SR-IOV only after obtaining the exact supported Windows guest driver and a
   recovery plan; do not turn an exposed sysfs capability into a product-performance claim.

## Preserved evidence

The complete 85-file exploratory and confirmation bundle is on Big Red at:

```text
/home/leo/Windows-Share/Diagnostics/20260902-windows-startup-tuned
```

It contains the identity, schedule, cache check, per-run JSONL result, adapter/runtime reports, and
full logs for:

- `20260902-031639-windows-startup-2x2`
- `20260902-032225-windows-startup-2x2`
- `20260902-032437-windows-startup-2x2`
- `20260902-034011-windows-startup-2x2`
- `20260902-034211-windows-startup-2x2`

The rejected live Windows prefetch-bypass cohort is retained separately at:

```text
/home/leo/Windows-Share/Diagnostics/20260902-windows-prefetch-bypass-rejection/20260902-041450-windows-startup-2x2
```

The intrusive stock-upload attribution cohort is retained at:

```text
/home/leo/Windows-Share/Diagnostics/20260902-windows-texture-upload-attribution/20260902-043303-windows-startup-2x2
```

The incomplete bypass-plus-probe attempt and live thread dump are retained under:

```text
/home/leo/Windows-Share/Diagnostics/20260902-windows-prefetch-bypass-upload-attribution
```

The comparable padded bypass, worker successor, and bounded-NPOT rejection are retained at:

```text
/home/leo/Windows-Share/Diagnostics/20260902-windows-recommended-padded-bypass-candidate
/home/leo/Windows-Share/Diagnostics/20260902-windows-prepared-prefetch-worker
/home/leo/Windows-Share/Diagnostics/20260902-windows-bounded-unpadded
```
