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
Mac and Linux do not match the Windows class identity and are unchanged. A no-probe run from the
promoted `main` completed 15,003/15,003 prepared hits with no original decode, fallback, error, or
live buffer and reached the menu in 73.926 seconds. Across the three healthy default-equivalent
runs, the interactive times are 66.741, 69.640, and 73.926 seconds (69.640-second median).

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

## Cross-platform semantic phase port and Fast Rendering-only check

Commit `28e4532f` added exact Linux and Windows alternatives for the existing semantic
`ResourceLoaderState` phase probe. The three distributions obfuscate several method and class names
differently, so the rewrite now recognizes the reviewed ordered call shape inside three separately
pinned class/archive identities rather than pretending their symbols match. Every phase also retains
its executing thread. Direct transformer tests passed against the installed macOS and Linux JARs and
the exact copied Windows JAR. Commit `ae0c7468` exposed that opt-in probe through the Windows cohort
runner.

One live Windows discovery launch from product JAR
`8f745626b90d30706246295be5cdfb882c7b23e96acc2aa8ccee385382529796` reached the interactive menu
in 75.831 seconds. The probe writes at semantic boundaries, so that duration is intrusive discovery
evidence, not a performance claim. It did prove a complete, all-main-thread timeline:

| Boundary | Elapsed | Previous interval |
| --- | ---: | ---: |
| resource init enter | 3.068 s | 3.068 s |
| SpecStore start | 3.400 s | 0.010 s |
| SpecStore complete | 11.009 s | **7.609 s** |
| first 1% progress | 18.729 s | **7.720 s** |
| 25% progress | 25.688 s | 5.869 s from 10% |
| 75% progress | 30.924 s | 5.202 s from 50% |
| 95% progress | 38.463 s | 3.478 s from 90% |
| progress 100 | 39.461 s | 0.051 s from 99% |
| audio workers complete | 39.504 s | 0.034 s |
| graphics finalize complete | 39.541 s | 0.036 s |
| script store complete | 39.590 s | 0.039 s |
| mod callbacks complete | 58.097 s | **18.224 s** |
| resource init complete | 58.190 s | 0.092 s |

Every retained row named thread `main` with thread id 1. Adapter health was clean: 30 transformations
from 31 exact matches, the one already-known combat-runtime-integrity decline, zero contained or
source-binding failures, 15,003 prepared prefetch hits, zero original decodes/fallbacks/errors, and
zero pending or active buffers. The packet is retained on Big Red under
`20260902-windows-startup-phase-probe`.

This splits the architectural target into at least three material serial islands, not just
SpecStore: 7.609 seconds in SpecStore, 7.720 seconds between SpecStore and first ordinary progress,
and 18.224 seconds in mod callbacks. The producer/main-thread-commit design must say which of these
it overlaps and preserve their required ordering; moving one named method alone cannot claim the
whole remaining opportunity.

The first Fast Rendering-only attempt also exposed a harness confound: without Preflight's quiet
Log4j setup, Fast Rendering synchronously copied per-resource logging into captured stdout. That
excluded attempt had already produced 20,494,761 stdout bytes when it was stopped. Commit
`8a4c377a` now gives every Windows cohort arm the same unbuffered file-only Log4j configuration.
With that correction, one Fast Rendering-only run reached the common graphics-preload marker in
67.017 seconds and shut down cleanly. It has no transformed semantic interactive-menu boundary, so
only the graphics clock is comparable:

| Condition | Graphics preload | Interpretation |
| --- | ---: | --- |
| standalone Preflight, default-equivalent observations | 51.834 / 55.517 / 59.537 s | 55.517 s median |
| Fast Rendering only | 67.017 s | one corrected exploratory observation |
| Preflight + Fast Rendering | 37.002 s | one exploratory observation |

These are not an interleaved release cohort. They do establish that the 37.002-second combined
result is not simply Fast Rendering's standalone floor: on this fixture Preflight removes useful
work even under Fast Rendering, while standalone Preflight is already faster than Fast Rendering
alone on the shared graphics clock. The combination remains fastest because the two systems attack
different serial work.

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
| Tuned Windows standalone Preflight worker | 55.517 s median | game log -> graphics preload |
| Tuned Windows Fast Rendering only | 67.017 s | game log -> graphics preload |
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

No, but it is already independently useful. The corrected Fast Rendering-only observation took
67.017 seconds to the graphics marker, standalone Preflight's three default-equivalent observations
had a 55.517-second median, and the combined route took 37.002 seconds. Fast Rendering replaces the
stock loader, so the combined run recorded zero Preflight prepared-pixel attempts or hits; it still
benefited from Preflight's other startup caches and rewrites. The exact values remain exploratory
until an interleaved cohort, and Fast Rendering alone still lacks the semantic interactive boundary.

### Can the Windows VM receive Big Red's real GPU?

Not by ordinary full passthrough without taking the host desktop: Big Red has one Intel Arc iGPU and
Linux owns it. The device exposes seven SR-IOV virtual functions, so a future reversible VF lane is
possible in principle. Intel's Windows-guest procedure requires a platform-matched SR-IOV/zero-copy
driver package; that package has not been identified or installed for this Arrow Lake fixture. No VF
was enabled and the host display was not disturbed.

### Can a bounded producer hide prepared-carrier work under early serial loading?

Not usefully in the reviewed form. The opt-in `texture-prepared-staging-v1` experiment starts one
daemon producer at the exact pre-SpecStore boundary, holds at most 64 MiB of ordinary heap-backed
carriers, never waits in the game's consumer, and clears unconsumed state at resource-load
completion. It has an exact Windows class/archive gate, original decode fallback, per-plan kill
switch, and causal/settlement telemetry.

The first correctness run exposed an invalid input assumption rather than a performance result:
the general texture-access order caused 81 carriers to be staged but **0 hits / 15,003 misses**. All
15,003 prepared requests still succeeded with zero original decodes, fallbacks, or internal errors,
and every staged/direct buffer and producer settled. The run reached the interactive menu in 66.810
seconds, but the producer had done no useful causal work.

Commit `58088161` therefore split the learned game-prefetch sequence from general texture access.
The initial `.sptp` suffix collided with the existing preparation-receipt format; commit `ef787584`
gave the queue its own `.sptq` identity and cache-pruning rule. Commit `6bea6c35` publishes learned
order at the established semantic menu snapshot so Windows harness shutdown cannot lose it. The
resulting exact full-profile queue is 763,209 bytes.

With that valid queue, the candidate served **2,170 staged hits** out of 15,003 prepared requests
(14.5%). It retained 15,003 prepared hits, zero original decodes, zero fallbacks, zero internal
errors, zero active/pending buffers, zero queued bytes/entries, and an inactive producer at the
snapshot. Peak staged memory was 66,566,075 bytes under the 64 MiB bound; 8,996 late races and 3,837
already-consumed keys declined immediately to the existing path. Yet process start to interactive
menu was **65.239 seconds**. Recent thin standalone observations were **65.205, 66.937, and 71.556
seconds**. The candidate is therefore a useful rejected experiment: it proved safe overlap and
removed some consumer work, but produced no defensible player-visible improvement. It remains
opt-in and is not promoted; no interleaved cohort is justified without a stronger causal design.

### Can the accepted worker cover textures first requested by late mod callbacks?

Yes, for one exact and materially expensive family. The native queue contains 15,003 paths, while
the learned general access order contains 15,647. The 644-path difference is outside both the
native worker and the rejected `.sptq` staging order; this is not a dormant copy of either earlier
experiment. Kaleidoscope accounts for 102 of those late paths and 146,669,568 prepared pixel bytes.
Its reviewed `onApplicationLoad` callback reads configuration and calls `loadTexture` only after the
native worker's ordinary stop/clear boundary.

The accepted candidate promotes only learned `graphics/kaleidoscope/` paths into that exact worker,
retains only their completed results across the stop boundary, removes unfinished candidates before
the interrupted worker can strand a consumer, and restores the original empty-map invariant at the
semantic menu snapshot. It is bounded to 512 paths / 192 MiB, requires the exact reviewed one-worker
rewrite shape, retains original decode fallback, and has both the ordinary plan kill switch and the
explicit `preflight.texture.windowsKaleidoscopePrefetch=false` disable path.

An intrusive correctness/phase run served **102 / 102** candidate textures from prepared pixels with
zero fallback, decline, or internal error. All 102 completed results were retained at worker stop and
consumed before the menu snapshot; leftovers, active buffers, pending buffers, and active direct
bytes were all zero. Kaleidoscope's callback fell from **3.062 seconds to 0.685 seconds**, removing
about **2.377 seconds** from the directly targeted serial work. The probe run's 58.439-second graphics
and 73.636-second interactive clocks are not used as performance claims.

Thin consecutive three-run cohorts on the same current build, profile, cache, Java, display, VM,
and recommended preset produced:

| Condition | Graphics samples / median | Interactive samples / median |
| --- | --- | --- |
| candidate | 50.254, 52.366, 57.234 / **52.366 s** | 66.494, 67.125, 72.068 / **67.125 s** |
| explicit-off baseline | 56.289, 58.878, 49.573 / **56.289 s** | 71.155, 74.700, 64.342 / **71.155 s** |

The median deltas are **-3.923 seconds (-7.0%)** to the graphics marker and **-4.030 seconds
(-5.7%)** to the semantic interactive boundary. The ranges overlap and the cohorts were adjacent,
not interleaved, so those percentages are retained as bounded evidence rather than a universal
claim. The direct 2.377-second callback reduction, repeated clean lifecycle counters, and
multi-second thin signal are sufficient to accept the narrow candidate without spending more
launches on precision that will not close the remaining gap to 30 seconds. Recommended Windows
launches enable it by default; conservative/custom launches and an explicit false property remain
unchanged.

### What actually occupies the remaining SpecStore and early-progress plateaus?

Commit `4aa1ddb6` activated the already-written detailed SpecStore, weapon, hull, rules, and
rule-expression probes on the exact Linux and Windows distributions. Source binding was only half
the missing work: the exact installed JAR tests found different platform obfuscation for the
SpecStore coordinator, script-registration calls, weapon/hull registries, and rules loader. Each
rewrite now recognizes its reviewed platform shape under a separate exact class/archive identity;
all five rewrites pass against both installed Linux and Windows JARs.

One Windows intrusive discovery launch on product JAR
`a8aa7c34e74a7356ebf1d3be89409da092580ced9ec300743ee3f2d4df08c359` reached the graphics marker
in 52.999 seconds and the semantic menu in 67.436 seconds. Those clocks are probe-bearing and are
not performance claims. The run completed normally and populated the previously empty loader and
subphase tables:

| Serial region | Time | Strongest interior attribution |
| --- | ---: | --- |
| SpecStore | 8.342 s | faction loader 2.541 s; variant-related loader 2.181 s; hull post-load 0.960 s; rules 0.728 s |
| faction loader | 2.541 s | priority-table construction 1.643 s; 683,270 spec lookups 0.265 s |
| SpecStore complete to first 1% milestone | **8.649 s** | still not split by the current probe |
| mod callbacks | 10.884 s | GraphicsLib 3.489 s; Nexerelin 1.228 s; AshLib 1.188 s; MagicLib 0.822 s |

The smaller loader families are no longer plausible explanations for the whole plateau: weapon
file/JSON work was 29 ms, hull file/JSON work was 34 ms, and rules expression command lookup was
285 ms. Commit `4869fffd` then split the exact post-SpecStore path. A second clean intrusive run
showed that the progress render took 5 ms, `queueShipAndWeaponSprites` 31 ms, resource ordering 42
ms, and executor creation/setup 6 ms. The interval from `resource-batches-start` to the first 1%
milestone was **8.902 seconds**. The large target is therefore the first high-priority resource
loads themselves, not queue construction, UI rendering, or executor setup.

Commit `a935562a` added the bounded first/top resource attribution and passed direct transforms of
the exact installed macOS, Linux, and Windows JARs plus the full reactor. One normal-completion
Windows discovery launch retained only the first 64 and slowest 64 resources, aggregate type
totals, and no report writes in the resource loop. It identified the apparent first-resource block
precisely:

| Resource family/path | Calls | Measured time |
| --- | ---: | ---: |
| all textures | 15,003 | 27.006 s |
| first texture: `graphics/cursors/cursor_blue.png` | 1 | **8.897 s** |
| remaining 15,002 textures | 15,002 | about 18.109 s |
| fonts | 21 | 0.175 s |
| sounds | 2,099 | 0.026 s |
| alpha-adder texture | 1 | 0.017 s |

No other individual resource exceeded 34 ms. The cursor file is therefore not an 8.9-second asset;
it is the first consumer that waits for one-time prepared-prefetch/texture initialization. The
stock Windows coordinator starts the image-prefetch worker at bytecode offset 1857 and only then
moves 4,479 high-priority resources to the front at offsets 1944-1968. The worker consequently
produces the original enqueue order while the main thread consumes a different prioritized order.
That exact producer/consumer order mismatch is the next narrow candidate. It is materially
different from adding workers (already rejected) or staging an arbitrary prefix (also rejected):
the candidate should align existing prepared production with the exact consumption order while
leaving GL upload and original fallbacks unchanged.

This intrusive run reached graphics in 61.657 seconds and the semantic menu in 76.727 seconds;
neither clock is a performance claim. Adapter health was clean: 130 registry targets, 49 observed
and parsed classes, 29 exact matches, 28 applied transformations, one known decline, 23 shadowed
alternatives, and zero contained failures or source-binding rejections. The exact prepared workload
remained 15,003 worker enqueues, 15,492 prepared hits, zero original decodes/fallbacks/errors, and
zero live buffers at shutdown.

## Open questions / next experiment

1. Do not promote or retest prepared-carrier staging merely because it achieved 2,170 hits. Reopen
   only if a new design can avoid producer/consumer CPU contention or target a materially larger
   serial block.
2. Prepared-order alignment and independent byte/image lanes are now both retained rejections. The
   latter removes the first-texture wait but does not improve total thin startup against current
   main. The next separate SpecStore candidate is faction priority-table construction at 1.643
   seconds. GraphicsLib remains a 3.5-5.8-second callback target across the intrusive packets.
3. Require an exploratory causal signal before paying for an interleaved standalone cohort. Keep
   llvmpipe padded; the bounded NPOT result rejects capability-only gating.
4. Run the exact worker successor on a native-GPU Windows machine before promoting any
   renderer-specific behavior.
5. Investigate Intel SR-IOV only after obtaining the exact supported Windows guest driver and a
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

The prepared-carrier staging correctness, thin learning, and valid causal rejection are retained at:

```text
/home/leo/Windows-Share/Diagnostics/20260902-windows-prepared-staging-correctness
/home/leo/Windows-Share/Diagnostics/20260902-windows-prefetch-order-learning
/home/leo/Windows-Share/Diagnostics/20260902-windows-prefetch-order-learning2
/home/leo/Windows-Share/Diagnostics/20260902-windows-prefetch-order-learning3
/home/leo/Windows-Share/Diagnostics/20260902-windows-prepared-staging-valid
```

The accepted late-Kaleidoscope correctness, thin candidate, and matching current-main baseline are
retained at:

```text
/home/leo/Windows-Share/Diagnostics/20260902-windows-kaleidoscope-prefetch-correctness
/home/leo/Windows-Share/Diagnostics/20260902-windows-kaleidoscope-prefetch-thin
/home/leo/Windows-Share/Diagnostics/20260902-windows-kaleidoscope-prefetch-baseline
```

The exact Windows detailed SpecStore/early-progress discovery packet is retained at:

```text
/home/leo/Windows-Share/Diagnostics/20260902-windows-detailed-startup-phase-probe
/home/leo/Windows-Share/Diagnostics/20260902-windows-post-spec-phase-probe
/home/leo/Windows-Share/Diagnostics/20260902-windows-resource-load-attribution.zip
```

The final archive is 1,588,722 bytes with SHA-256
`23f6f7653da2f385d41551f7d6ae580bb1508637918753e3ed4eff3c225c485c`.

### Does aligning prepared-prefetch order remove the first-texture wait?

No. This is a useful rejected optimization, retained as an exact-gated,
off-by-default diagnostic seam.

The candidate moved the reviewed Windows `ResourceLoaderState` worker start
behind stock resource prioritization and captured that same prioritized order
for the existing prepared-pixel queue. The installed-game correctness run
confirmed one capture and one reorder pass over all 15,003 queued textures,
with all 15,003 entries matched, zero ordering errors, zero original decodes,
zero fallbacks, and zero leaked buffers. The queue's first desired, before, and
after path was `graphics/cursors/cursor_blue.png`; no entry needed to move
because delaying the worker start had already made producer and consumer order
agree.

That did not remove the stall. The cursor remained the first texture load and
took 8.342 seconds. The gap from `resource-batches-start` to the first one
percent progress event only fell from 9.128 seconds to 8.573 seconds. Total
texture consumer time increased from 27.006 seconds to 27.949 seconds. The
candidate run reached graphics preload at 53.785 seconds, but its mod-callback
window happened to be 10.510 seconds rather than the baseline probe's 16.630
seconds; that unrelated single-run variation cannot be credited to texture
queue ordering.

The direct causal counters therefore reject the proposed mechanism: resource
calls remained 17,124, all 15,003 textures still ran, no queue entries moved,
and the target first-texture wait remained. A repeated thin cohort would only
measure unrelated startup variance, so none was run.

The stronger successor hypothesis is cold first-image work on Windows: decoder
or Java2D/ImageIO initialization, first prepared-pixel conversion, or another
one-time dependency reached by the first cursor load. The next slice should
split that 8.342-second call before attempting another optimization.

Preserved evidence:

```text
/home/leo/Windows-Share/Diagnostics/20260902-windows-prepared-priority-order-correctness.zip
```

The archive is 1,589,978 bytes with SHA-256
`f0496c3de0a24fbef89d51e8b5447eb4f0ba92b92a06d33a7aef84c1c4373c90`.

### Where does the eight-second cursor wait actually live?

The bounded cold-path split exonerates the prepared pack, LZ4 read, lookup,
layout, and carrier construction. With the exact prioritized-order diagnostic
making the cursor the first reviewed worker-queue entry, the main resource
consumer began waiting for `graphics/cursors/cursor_blue.png` at 11.955
seconds and completed at 20.494 seconds: an 8.539-second call.

The worker did not enter the cursor's prepared lookup until 20.482 seconds.
That lookup and carrier construction completed at 20.485 seconds, and the
main consumer returned nine milliseconds later. The retained cursor sample
itself took only 729 microseconds total:

- packed-store read: 585 microseconds;
- complete compatibility lookup: 631 microseconds;
- carrier construction: 77 microseconds.

Thus at least 8.527 of the 8.539 seconds is before the prepared lookup. The
2.1 GiB prepared pack and its decompression are not the big startup seam. The
worker thread is either scheduled late or blocked before its first queue
removal. Exact bytecode confirms that `com/fs/graphics/L.o00000()` constructs
`L$1`, creates a plain `Thread`, and calls `Thread.start()`, while `L$1.run()`
begins by acquiring the shared image-queue monitor before removing index zero.
That scheduler-versus-monitor distinction is the next narrow question.

The intrusive run preserved exact workload and adapter health: the cursor
remained the first prioritized path, all 15,003 prepared entries matched,
zero entries needed a reorder, and there were zero ordering errors, original
decodes, prepared fallbacks, or leaked buffers. Its 51.921-second graphics and
66.366-second interactive clocks are discovery clocks, not performance claims.

Preserved evidence:

```text
/home/leo/Windows-Share/Diagnostics/20260902-windows-cursor-worker-delay-attribution.zip
```

The archive is 1,593,956 bytes with SHA-256
`80a5aa112d935a99d9152263afffc997b8fd2f03d51761699d0093d359af26bb`.

### Does overlapping the byte and image queues turn the cursor result into a startup win?

No. It proves the causal explanation and removes the local wait, but a thin interleaved comparison
does not show a useful total-startup improvement. The exact-gated implementation and reusable
comparison runner remain on `main` as off-by-default diagnostic prior art.

External early thread dumps corrected the preceding scheduler-versus-monitor hypothesis. The
reviewed `L$1.run()` worker was RUNNABLE in byte-resource reads. Exact installed bytecode shows two
serial phases: it drains the byte queue and byte-result map first, then begins the prepared-image
queue. The existing rejected multi-worker implementation did not test overlap because every worker
also preferred bytes before images.

The focused successor used exactly two lanes: one stock decoder and writer for the byte-result map,
and one stock decoder and writer for the image-result map. This preserves the original one-writer
behavior for each independent map while allowing the cursor carrier to start immediately. The
installed-game correctness run confirmed the mechanism:

- cursor carrier: 793 microseconds on `Preflight-Windows-Prefetch-Image`;
- images: 15,003 claims and 15,003 completions;
- bytes: 4,454 claims and 4,454 completions;
- peak two workers, both stopped;
- zero pool failures, original decodes, prepared or dimension fallbacks, internal errors, pending
  buffers, active direct bytes, or failure samples.

That intrusive run reached graphics in 46.374 seconds and the interactive menu in 60.336 seconds,
but those probe-bearing clocks are discovery evidence only. A thin A-B-B-A comparison then tested
current Recommended main, including its promoted Kaleidoscope optimization, against the split
candidate with that intentionally non-composable neighbor disabled:

| condition | graphics preload | semantic ready | semantic interactive |
| --- | ---: | ---: | ---: |
| current main A1 | 50.979 s | 48.413 s | 65.969 s |
| split queues B1 | 56.704 s | 55.789 s | 70.933 s |
| split queues B2 | 51.886 s | 52.004 s | 66.191 s |
| current main A2 | 52.753 s | 48.464 s | 67.165 s |
| current-main median | 51.866 s | 48.439 s | 66.567 s |
| split-queue median | 54.295 s | 53.897 s | 68.562 s |

The candidate is about 2.0 seconds slower at the interactive boundary and about 5.5 seconds slower
at semantic ready in these two interleaved observations. Every candidate run completed the same
15,003 image and 4,454 byte jobs with zero failures or live state. The local cursor stall was real,
but it was not an isolated critical-path saving: overlap introduces competing CPU/I/O work and also
forgoes the current Kaleidoscope path. This is therefore a rejected startup optimization, not a
reason to undo the causal instrumentation or the safe opt-in seam.

Preserved evidence:

```text
/home/leo/Windows-Share/Diagnostics/20260902-windows-split-queue-fallback-and-correctness.zip
/home/leo/Windows-Share/Diagnostics/20260902-windows-split-queue-thin-interleaved.zip
```

The fallback/correctness archive is 3,229,039 bytes with SHA-256
`164a5e5754008a1c4e16e6795ae2ca2dfb12a27aac1d2a09d1f1232e99077ca3`. The thin interleaved
archive is 6,402,330 bytes with SHA-256
`5bbb734cafb1c9342b9ab295d1b1a725451d867192e52e9c7b460cb0a7824cb2`.

### Can a second shared GL context remove the 27.8-second upload island?

Not by keeping Starsector's Display context current on the main thread while a
second context uploads concurrently on the exact Windows llvmpipe stack. This
is a useful capability rejection, not evidence against GL ownership handoff or
Fast Rendering's broader thread inversion.

The reason to test this seam was direct rather than speculative. The stock
upload probe observed 15,483 `glTexImage2D` calls, 3,064,515,568 submitted
bytes, and 27.768 seconds inside native GL calls. That one cumulative owner is
approximately the gap between the standalone result and a launch in the
thirties.

The opt-in #1214 probe was exact-gated to the installed LWJGL 2.9.3 artifact
(jar SHA-256
`527d509f60132e5b2653c7fc0f8cf299d6f698f4a8013342bef47705dc57ed3f`).
It created deterministic 4x4 and 1024x1024 RGBA inputs and was prepared to
validate every byte from the live Display context. It never intercepted or
replaced a normal game texture.

Four interactive-machine runs corrected two instrumentation confounders and
then repeated the capability result:

- `SharedDrawable` construction succeeded in 183 milliseconds, but the worker
  could not make its context current within the bounded 15-second window;
- a separate 1x1 Pbuffer shared with Display was reported supported
  (`Pbuffer.getCapabilities() == 3`) and constructed in 220--241 milliseconds,
  but its worker also could not progress to the first texture upload while the
  Display context remained current;
- moving the probe from the nested `Display.create(...)` implementation to a
  post-create `Display.update` boundary removed LWJGL's outer global-lock
  confounder and reproduced the same bounded result twice;
- every retained run reported zero uploaded bytes, no attributable GL error,
  no normal texture replacement, and a responsive main game JVM that continued
  to the ordinary graphics-preload/cleanup phase.

The candidate therefore fails its acceptance contract: object sharing was
advertised and the auxiliary drawable could be created, but concurrent context
ownership did not become usable and the timed-out daemon could not be cleanly
destroyed before process shutdown. It must remain opt-in diagnostic code and
must not graduate into the startup path.

The next architectural question is narrower and different: explicitly release
the Display context, hand GL ownership to one renderer/upload thread while the
main thread performs CPU/spec production, then return ownership and verify the
shared objects. That is consistent with the exact Fast Rendering prior art. It
is not equivalent to adding another loader worker, and it needs its own
correctness-first contract because a failed ownership handoff cannot safely
fall back mid-call. Until that passes, explicit Preflight + Fast Rendering
integration remains the demonstrated route closest to the thirties.

Preserved evidence:

```text
/home/leo/Windows-Share/Diagnostics/20260902-windows-shared-context-upload-capability.zip
```

The archive is 5,633,128 bytes with SHA-256
`50ccbe31b53355d42363cbfb13e3dfeb0028edbec94640e833c3573de44963ef`.

### Can explicit Display ownership handoff make the shared context usable?

No, not at a reviewed `Display.update()` boundary on the exact Windows llvmpipe fixture. The #1215
successor released the live Display context before starting one shared-Pbuffer worker, performed no
main-thread GL work while ownership was released, and was prepared to restore Display, validate
every uploaded byte, delete the synthetic objects, and destroy the Pbuffer. Three physical-machine
runs rejected that sequence before any startup prototype was attempted.

The state machine and exact installed LWJGL 2.9.3 bytecode separated two possible causes. The first
run started inside the nested `Display.update(boolean)` boundary and could still have been blocked
by LWJGL's `GlobalLock`. The successor instead ran after the no-argument `Display.update()` returned
from `Display.update(boolean)`, whose installed bytecode had already exited the global monitor. A
cold transformation-cache run still behaved the same way. Finally, the worker bound was doubled to
test whether this was merely slow first-context construction:

| reviewed boundary | worker bound | worker `makeCurrent` | result |
| --- | ---: | ---: | --- |
| nested update | 15 s | worker became current at about 17.35 s elapsed | timeout; Display not restored |
| after no-arg update | 15 s | 17.011 s inside acquisition | timeout; Display not restored |
| after no-arg update | 30 s | 32.011 s inside acquisition | timeout; Display not restored |

The doubled bound moved acquisition completion by the same 15 seconds. That rules out a fixed
roughly-17-second context startup cost: on this stack, the worker does not acquire the shared
context during the bounded ownership window. The exact native synchronization cause remains
unassigned, but it is not necessary to decide this candidate. Waiting longer only moves the same
failure.

Late telemetry is still useful. In the two post-update runs, after the main probe abandoned its
bounded wait, the worker eventually became current, uploaded the deterministic tiny and 1024-square
textures (two objects / 4,194,368 bytes), called `glFinish`, reported GL error zero, released its
context, and terminated. The main thread correctly refused to race that late native call by
reacquiring or destroying either drawable. Consequently it could not restore Display, validate the
bytes, or clean up safely, and ordinary rendering later failed with `No OpenGL context found in the
current thread`. The external fixture dismissed the native alert, retained shutdown telemetry, and
stopped only the exact owned process tree.

This fails the correctness contract before an ordinary-startup sanity check, much less a timing
comparison. The probe remains exact-gated, off by default, and bounded as rejected diagnostic prior
art. No standalone texture-upload pipeline should be built on this handoff model. Explicit Preflight
plus Fast Rendering remains the demonstrated route closest to startup in the thirties; any attempt
to absorb that architecture should start from its renderer-thread ownership model rather than try
to transfer ownership after Starsector has begun driving Display.

Preserved evidence:

```text
/home/leo/Windows-Share/Diagnostics/20260902-windows-gl-ownership-handoff-timeout.zip
/home/leo/Windows-Share/Diagnostics/20260902-windows-gl-ownership-handoff-post-update-timeout.zip
/home/leo/Windows-Share/Diagnostics/20260902-windows-gl-ownership-handoff-30s-coupled-timeout.zip
```

The archives are respectively 811,333, 61,613, and 54,376 bytes, with SHA-256 values
`8f86d4bc80bc28af9786f0110d2d410c2f8764fc762859b1d5fca45bfe3492d1`,
`629575828c8562196f411d6f8279fe2f20297a5cf1cde03b144ec363d4d8bb80`, and
`d6ae56029c49b0582cf41e3d0116ebc193c5a3cebf973eb19581b241d7e9fe7e`.

### Does feeding prepared texture bytes into Fast Rendering remove its startup decode tax?

No. The exact Fast Rendering 0.8.4 bridge moved a very large amount of image work, but a physical
Windows A/B/A/B cohort rejected it as a startup optimization.

The candidate transformed only the reviewed `TextureLoader` class from the installed `fr.jar`
(class SHA-256 `a426f8a33473713b4e43293483dfe4596a517527b92be7e92dcc1701a64b6feb`;
archive SHA-256 `dea3ea3d0fd7437d4a7945fee65f741d9b72d3fec565b9c4807aea479ce56144`).
After Fast Rendering's DDS miss and immediately before its ImageIO path, the bridge offered an exact
prepared RGBA carrier. Unsupported resource layouts and alpha-adder requests declined to the
original implementation. Errors also failed open, and a bounded circuit breaker protected repeated
failures. The independent `preflight.texture.fastRenderingPrepared` switch remained off by default.

All four runs used main at `3246e37d9fc05a3cfbb7b4a92c54a877fd313a10`, preflight JAR SHA-256
`b2d4b0db23d74d522da71abecd4fe038213bf9d14179abef71233fff0319fc36`, enabled-mods SHA-256
`76227ce91333c202271e541774f3e86fd8711c2542d63a81cfd18a4dc0a6997f`, the same game/profile,
1024x720 display state, 14-vCPU/12-GiB Windows VM, and llvmpipe adapter. Each accepted run reached
the independently observed interactive main-menu boundary and then shut down automatically.

| order | condition | graphics marker | interactive menu | prepared hits / attempts | hit bytes |
| ---: | --- | ---: | ---: | ---: | ---: |
| A1 | prepared bridge | 56.893 s | 93.409 s | 15,524 / 15,547 | 2,158,836,331 |
| B1 | bridge forced off | 55.735 s | 69.642 s | 0 / 0 | 0 |
| A2 | prepared bridge | 60.476 s | 73.842 s | 15,524 / 15,569 | 2,158,836,331 |
| B2 | bridge forced off | 56.736 s | 70.449 s | 0 / 0 | 0 |

The baseline medians were 56.236 seconds to the graphics marker and 70.046 seconds to the
interactive menu. Candidate medians were 58.685 and 83.626 seconds: respectively 2.449 seconds
(4.35%) and 13.580 seconds (19.39%) slower. Both candidate graphics measurements were slower than
their baseline partners, and neither candidate improved actual readiness.

The bridge itself was healthy: both candidate runs installed exactly once and reported no lookup,
layout, internal, or contained failures. The first run declined 22 resources and one texture type;
the second declined 44 resources and one texture type. Work was observed on the main thread and
three Fast Rendering workers. The summed bridge clock was 28.503 and 30.862 seconds, but that is
cross-thread seam time and is not a wall-clock performance claim.

This is a useful rejection. Displacing 15,524 decodes and about 2.159 GB of source texture reads per
run did not produce a player-visible startup win. The exact cause is not yet assigned. A plausible
lead is that Fast Rendering's original ImageIO work already overlaps on its loader workers, whereas
prepared-pack lookup/decompression and the required BufferPool snapshot add memory traffic or alter
worker readiness. The large first-candidate interactive outlier may include cold-cache, JIT, or
memory-pressure effects; the tight baseline pair makes heat alone an insufficient explanation.
Those are hypotheses, not findings.

The graphics marker also hid much of the first candidate's readiness delay, reinforcing the
interactive boundary as the deciding startup metric. Do not revive this bridge merely because its
causal counter is large. A successor would first need to show, with thin per-phase evidence, that it
can remove the duplicate decompression/copy or other worker-readiness tax. Until then the exact
bridge remains off-by-default diagnostic prior art, not a recommended optimization.

Preserved evidence:

```text
/home/leo/Windows-Share/Diagnostics/20260903-windows-fast-rendering-prepared-abab.zip
```

The archive is 6,665,810 bytes with SHA-256
`57e3edb841f56fbf8546eb85545d39205b97b36b86b2b550b1197a01e5b44f97`.

### Why did the same Fast Rendering condition move from 49.551 seconds to about 70 seconds?

The broad multiplier was the Big Red host power profile. The earlier tuned evidence explicitly used
the host performance profile, but the host had later returned to `balanced`; the Windows guest
remained on its high-performance scheme throughout. Restoring the host performance profile for one
otherwise identical combined run reduced graphics preload from 54.076 to 35.650 seconds and the
interactive boundary from 67.819 to 48.355 seconds. That is 34.1% and 28.7% faster respectively,
and reproduces the earlier 37.002/49.551 result rather than merely approaching it.

The earlier 14-vCPU combined run reached the graphics marker in 37.002 seconds and the interactive
menu in 49.551 seconds. The current forced-off baselines around the prepared-texture experiment
were 55.735/69.642 and 56.736/70.449 seconds. Exact identity comparison retained the same game,
enabled mods, Fast Rendering archive and agent, Java runtime, prepared manifest and index, display,
llvmpipe renderer, VM memory, and 14-vCPU count. The current and earlier adapter reports also both
applied the same 19 exact transformations. The one newly registered Fast Rendering bridge target
was not installed while its property was false.

The game log shows a real broad phase shift rather than one slow marker. In the earlier run the last
Fast Rendering CSV group, Core framebuffer, and GraphicsLib preload landmarks appeared at about
9.85, 22.50, and 37.30 seconds. In two current quiesced baselines they appeared around
15.70-16.12, 35.29-37.29, and 53.72-56.22 seconds. Interactive readiness also has independent tail
variance: one immediate repeat reached graphics in 53.328 seconds but did not become interactive
until 86.657 seconds, with late texture work continuing after the preload landmark.

The controlled host-profile pass retained the same normalized Windows cohort identity: game and
content, enabled-mod hash, Fast Rendering JAR and agent, Preflight JAR, Java runtime, prepared
cache, 1024x720 display, llvmpipe renderer, 14-vCPU/12-GiB guest, guest power scheme, SysMain state,
and Defender exclusions. The normalized `identity.json` SHA-256 was
`3f903c0f2c29f7bc53ced0c7d36f77e8877a4e813038f02899a1f4dc7f479e6b` in both arms. The
workload landmarks moved back with the host profile:

| Landmark | balanced host | performance host | earlier performance evidence |
| --- | ---: | ---: | ---: |
| Fast Rendering resource-loader span | 4.445-16.491 s | 3.160-9.531 s | 3.465-9.852 s |
| Resource-loader `LoadingUtils` records | 8,168 | 8,168 | 8,168 |
| Core framebuffer | 35.429 s | 22.044 s | 22.504 s |
| save descriptor | 53.706 s | 35.254 s | 36.342 s |
| GraphicsLib preload | 54.374 s | 35.901 s | 37.302 s |

Both new arms reached the exact interactive menu and shut down cleanly, with no adapter decline,
contained failure, or source-binding rejection. The balanced run loaded two AI Tweaks gameplay
classes that the performance run did not, producing 19 versus 17 applied transformations; those
two unrelated startup-menu class-load observations do not account for the restored resource-loader
and common phase slopes. This is a causal infrastructure A-to-B plus an independent reproduction,
not a shuffled product-speed claim.

Windows SysMain was active during the first audit and briefly consumed measurable guest CPU. It was
stopped, the guest was allowed to settle, no other guest process registered measurable CPU, and the
host was cool at about 48-50 C. The next accepted run was still 55.881/69.144 seconds. An immediate
same-condition repeat was 53.328/86.657 seconds. Heat and active SysMain therefore do not explain
the broad regression, and source-page-cache warmth did not recover the earlier boundary.

The host is a heterogeneous Intel Core Ultra 7 255H. The normal VM's 14 vCPUs map onto six
performance cores and eight efficiency cores; Windows sees a homogeneous virtual topology. A
correctness-preserving infrastructure experiment reduced the guest to the six P-cores only. That
was decisively worse at 83.477 seconds to graphics and 99.380 seconds to interactive. Fast
Rendering's worker pipeline and the broader game need the additional parallel capacity more than
this fixture benefits from restricting main-thread placement. The VM was restored to 14 vCPUs.

The unattended host wrapper now applies `performance` only during a cohort, records the prior and
active host profiles plus bounded QEMU/frequency/temperature samples, and restores the prior profile
on success or failure. This prevents benchmark drift without leaving Big Red permanently hot. The
performance run observed 48.355 seconds interactive while QEMU used up to 1,393% host CPU; P-core
samples reached about 4.68 GHz and package temperature peaked at 88 C without a thermal warning.
The host was restored to `balanced` immediately afterward.

Do not use 48.355 or 49.551 seconds as a universal Windows claim: this is still a single physical
llvmpipe fixture, and the independent late interactive-tail variance remains real. It is no longer
necessary to instrument Fast Rendering's outstanding queues merely to explain the broad 49-to-70
split. Future accepted Windows cohorts must retain the host profile as performance-relevant
identity.

Preserved evidence:

```text
/home/leo/Windows-Share/Diagnostics/20260903-windows-fast-rendering-quiesced-baseline.zip
/home/leo/Windows-Share/Diagnostics/20260903-windows-fast-rendering-immediate-repeat.zip
/home/leo/Windows-Share/Diagnostics/20260903-windows-fast-rendering-six-pcore-rejection.zip
/home/leo/Windows-Share/Diagnostics/20260903-012243-windows-startup-2x2.zip
/home/leo/Windows-Share/Diagnostics/20260903-012243-windows-startup-2x2-host.json
/home/leo/Windows-Share/Diagnostics/20260903-012904-windows-startup-2x2.zip
/home/leo/Windows-Share/Diagnostics/20260903-012904-windows-startup-2x2-host.json
```

The archives are respectively 1,887,268, 1,892,371, and 1,875,215 bytes, with SHA-256 values
`b6a9864c16ac2b72af5f8cfd43e713d445bd817e5a35da6a1171930a371d8d15`,
`5a3e2f7c8972ce3f6f5920d3fef5ebf9b2d13f5fa4286ae8ac26f66587cd28e0`, and
`afde0bc8fc19e48c6a8c5470ed06ef4160c20053bb4d3070bc5b2580521f647d`.
The balanced cohort/archive and host packet are 1,889,731 and 12,144 bytes with SHA-256 values
`3c42ebd82d7c90e4750baeb574628a2c08cbc0164df18da754e2527b680be8f8` and
`bf42232ca766d4a7f55be651d1b9fa8af5ebf58b8eb2ce5e6cd432ca66522a97`. The performance
cohort/archive and host packet are 1,856,679 and 11,351 bytes with SHA-256 values
`9adeba4aef6b7274624f23bf5b298e308b864ec657fc73d664ff4150d831400a` and
`bdce87064fbee54b9df70aad25a9f893d28e502721436ca4123fdbb1787f2b0e`.

### Can the faction priority-table walk be reused on an exact Windows profile?

Yes at the measured seam, with a smaller and noisier end-to-end effect. The accepted candidate
learns only IDs emitted by Starsector's original callback, binds each result to the exact profile,
faction JSON hash, callback class, table names, and fallback flag, and replays those IDs through the
same callback interface on a later launch. The artifact is bounded, checksummed, transactional, and
format-versioned. A miss, damaged artifact, profile mismatch, JSON fingerprint failure, class-shape
drift, or disabled plan executes the original method. The candidate remains opt-in under
`preflight.startup.windowsFactionPriorityCache` while the thin whole-launch magnitude is noisy.

The first implementation at `efa76741` was rejected before a second launch. Its key omitted faction
identity, so the first live report exposed 944 attempts but only eight misses followed by 936
same-launch hits and an eight-entry artifact. That meant results from the first faction could be
reused for later factions. No timing claim was made. `75634679` added the faction JSON identity,
raised the bounded artifact cardinality, prohibited same-launch replay, and bumped the format so the
bad artifact was rejected automatically. Its next learning launch executed all 944 original calls,
captured 35,765 callback IDs into 944 entries, reported zero hits/declines/failures, reached the
interactive menu, and wrote the corrected artifact.

`b6089f25` then removed reflective callback dispatch. The woven exact Windows method receives the
profile-validated ID array and calls its own reviewed interface directly. Three candidate and three
explicit-off thin runs were executed in B/A/B/A/B/A order with the Big Red host pinned to
`performance` for each run:

| order | condition | graphics marker | interactive menu |
| ---: | --- | ---: | ---: |
| B1 | faction priority replay | 36.853 s | 50.351 s |
| A1 | explicit off | 41.715 s | 55.079 s |
| B2 | faction priority replay | 37.374 s | 50.301 s |
| A2 | explicit off | 36.347 s | 49.166 s |
| B3 | faction priority replay | 41.297 s | 54.488 s |
| A3 | explicit off | 46.995 s | 60.763 s |

Candidate medians were 37.374 and 50.351 seconds; control medians were 41.715 and 55.079 seconds.
Those differences are directionally favorable, but the 10.648-second control range is larger than
the target and prevents a defensible 10% whole-launch claim. Two paired comparisons favored the
candidate and one favored control. Every candidate run retained 944 hits / 35,765 replayed IDs with
zero misses, fingerprint failures, capture declines, replay failures, or writes.

The same-build intrusive phase pair supplies the causal magnitude without using its whole-launch
numbers as a product claim:

| phase-probe condition | calls | faction priority-table wall | neighboring spec lookups |
| --- | ---: | ---: | ---: |
| explicit off | 944 | 723 ms | 683,270 calls / 103 ms |
| replay | 944 | 26 ms | 683,270 calls / 105 ms |

The exact seam therefore fell by 697 ms, or 96.4%, while the independent lookup workload stayed
stable. The replay run again served all 35,765 IDs without fallback. This is a real serial CPU-work
removal and a useful accepted candidate, but it is not the large standalone Windows renderer win.
Keep the end-to-end claim to “sub-second-class seam removal” until a less variable thin cohort or a
native Windows fixture resolves the whole-launch effect.

All direct-dispatch thin and phase runs used Preflight JAR SHA-256
`50eeb44f923a38cbd480b5c4819532b97663654acaf5f255053d9a965282a452`, enabled-mods SHA-256
`76227ce91333c202271e541774f3e86fd8711c2542d63a81cfd18a4dc0a6997f`, Java SHA-256
`82051fdab26319d77d20cc0065045d05ec00b3e3d05f44935d7c06b96b621d55`, 1024x720 windowed state,
14 vCPUs, 12 GiB guest memory, llvmpipe, and the Recommended preset. Preserved archives:

```text
/home/leo/Windows-Share/Diagnostics/20260903-024949-windows-startup-2x2.zip
/home/leo/Windows-Share/Diagnostics/20260903-025118-windows-startup-2x2.zip
/home/leo/Windows-Share/Diagnostics/20260903-025259-windows-startup-2x2.zip
/home/leo/Windows-Share/Diagnostics/20260903-025427-windows-startup-2x2.zip
/home/leo/Windows-Share/Diagnostics/20260903-025600-windows-startup-2x2.zip
/home/leo/Windows-Share/Diagnostics/20260903-025729-windows-startup-2x2.zip
/home/leo/Windows-Share/Diagnostics/20260903-030109-windows-startup-2x2.zip
/home/leo/Windows-Share/Diagnostics/20260903-030312-windows-startup-2x2.zip
```

Their SHA-256 values in the same order are
`1c4fce434ba0141288ab2f3eb7b174d0f584a220c5914f6b414f43d575e13ee2`,
`0d9f45045dab61e3b52335cdc0da9b6c9876dbcf36234ac6c68cfd4e7968a4e5`,
`2291a01b353bac4c5f847307490b1e4469b4b3d8966858609859c27f6a28f4c2`,
`7d8e64584cad8c1bcba75ac3343a06c952197a1f7370b93bfaf1994c1738dfb2`,
`6a8b9a9a621736d90522189fc912933e1a75916f66ff7cab30ee8b89b3aeba61`,
`4018b0cdff52888421d9f242283f94a5bbd46c1ef72c131629969796fa17866f`,
`5b52cb4a1ff6c120a81d967e410e7568ebc9cc4556733426c1f80b35c5624869`, and
`ca7562abb43b2ad9f3e61ebfae5a346f5dfea0f39c12ef58ff43005a7b66d2cf`.
