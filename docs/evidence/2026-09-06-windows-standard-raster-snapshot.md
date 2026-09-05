# Windows coherent converter representation trials

Objective: reduce ordinary Recommended texture-loading time while retaining the original
large-image converter, GL policy, 1024 ceiling, main-thread ownership and late resources.
Owner: current Codex task. Phase: both candidates rejected; accepted executable restored.
Finish: retain only justified changes, merge main, restore normal Windows task and retire builds.

Baseline main `4831eae286369ac8eb15bf985fc4bbb2ff4dfeba`, installed JAR
`369d43b415829c5082c29d1762adb9d8fdb73f6cf73d6406087740bd38534cb9`.
Most recent ordinary interactive menus: 18.797 / 18.413 s. These are a reference, not a paired
control or evidence of a guaranteed sub-18 result.

An untouched coherent carrier currently materializes a standard raster, retains it, then copies
it for `BufferedImage.getData()`. The candidate constructs the independent snapshot directly from
immutable prepared pixels. It uses the same JDK raster/sample-model/data-buffer classes, with
no custom `getPixel` implementation. After any raster exposure or mutation, it continues through
the existing materialized image. Every call still receives an independent snapshot.

This differs from the rejected custom-raster experiment: the stock converter sees its ordinary
standard raster implementation. No converter, cleanup, handler, cache, scheduling or GL code changes.
RGB/RGBA tests cover independent snapshot mutation, subsequent carrier mutation, concrete raster
classes and absence of unnecessary retained materialization. No startup speedup is claimed yet.

Full `mvn verify` passed in 47.973 s with installed Windows common/core and Log4j fixtures.
The installed loader test now checks both RGB and RGBA above the ceiling using the actual stock
converter: upload bytes, GL calls, handler metadata and subimage reload match the stock baseline.
Installed bytecode confirms a single `BufferedImage.getData()` call before the converter pixel loops.

Candidate executable source `6cef88d6f17199c72d089fbc39928a5a4c8e2228`, JAR SHA-256
`aa1c328ad162025cd785000af4782ec2a87724cba2edd9bd985a0c2e8cd81679`.
Three-platform CI `33988324288` passed. Ordinary trial: three Recommended launches,
native graphics, 1024x720 and 20-second cooldowns. Stop on lifecycle failure; do not select an
isolated fast observation while omitting slower runs.

## Trial outcome

Ordinary cohort `20260906-035149`: the first two interactive menus were **18.545 / 18.538 s**;
graphics-ready 16.736 / 16.824 s. Both were accepted, adapter-healthy and shut down gracefully.
Both committed 15,002 resources, including 44 coherent fallbacks, with zero active/pending buffers.
Raster materializations decreased from 45 to 21; cumulative carrier raster bytes decreased from
387,656,658 to 264,437,760, and coherent carrier bytes decreased from 123,218,898 to zero.
These counters measure cumulative allocations, not peak or simultaneous live memory.

The third launch stalled inside native `GL11.nglTexImage2D` through the stock TextureLoader and
`preflight$commitPreparedResource`. The captured main stack had elapsed 143.81 seconds and used
5515.62 ms CPU. No menu was observed for that run. This does not establish that the snapshot
change caused the previously observed native stall. It also cannot establish reliable improvement.
The successful timings are close to the prior baseline and do not meet the sub-18 target.

Private evidence lives in `Diagnostics/raster-snapshot/third-thread-dump.txt`,
`third-jcmd-error.txt`, `third-retirement.json`, and the archived cohort. Stopped the cohort and
retired exact game PID 10432, launcher shell 13628 and wrapper 2500, checking creation timestamps
before each stop. Confirmed zero actors before restoring the accepted baseline JAR. The final
change removes the single-copy candidate code and candidate-specific tests; PR history retains it.

Baseline recovery `20260906-035912` accepted both ordinary runs: interactive menus 18.223 / 17.387 s;
graphics-ready 17.199 / 15.678 s. This is baseline variability, not a candidate improvement.

## Packed stock-converter input

A bounded local Java pixel-loop experiment on 2048x1024 standard rasters (10 warmup, 20 measured
walks, isolated JVM per representation) measured 8.409 ms for ByteInterleavedRaster and 5.678 ms
for IntegerInterleavedRaster, with matching checksum. It omits allocation, conversion and GL;
it is a hypothesis generator, not game timing evidence.

The second candidate changes only the prepared completion's input to the exact Windows original
converter when a texture exceeds 1024. It constructs a standard TYPE_INT_RGB/ARGB image from
untouched immutable prepared pixels. The stock converter, padding/layout, derived colors, GL,
cleanup and real handler construction still execute. The earlier image getter still exposes the
original carrier. Non-prepared, small, exposed/mutated and unknown images retain their input.
`preflight.texture.packedConverterImage=false` opts out; bounded counters record image count and
cumulative packed array bytes. No new thread or GL operation is introduced.

Focused tests pass for exact installed RGB/RGBA converter bytes, varying alpha, metadata and
reload. Runtime tests also cover untouched representation, independent mutation, exposed-image
decline and opt-out. Full verification and ordinary native trial remain required.

Packed candidate full verification passed in 45.653 s. An initial new type assertion assumed
RGBA for an RGB fixture; corrected the assertion to require the original alpha policy, then
reran full verification. Executable source `10162d70ed8be727430a4b2f94a5f48b927776e6`, JAR
`a278a5960a4693f22ccfbce5dbe7aac18d5f04c5b76d7ae42e54458d47085879`.
Three-platform CI `33988998673` and a fresh ordinary three-run native cohort were requested.

Packed cohort `20260906-040604` accepted all three ordinary runs: interactive menus
**18.113 / 17.537 / 16.690 s** (median 17.537 s); graphics-ready 17.041 / 15.818 / 15.819 s.
The first run used 44 packed converter images, 464,154,156 cumulative packed-array bytes,
15,002 resource commits and 44 coherent fallbacks. Carrier raster materializations were 1,
196,608 bytes; active/pending upload buffers were zero. Packed arrays and the converter's own
snapshot still allocate memory; these counters are not evidence of total allocation or peak savings.
A second identical three-run cohort is required to assess repeatability. The 18.113 s observation
already prevents claiming that all observations meet the strict sub-18 target.
All three runs retained and consumed all 102 late Kaleidoscope resources. Three-platform CI
`33988998673` passed. Those three runs used source `10162d70…` and artifact `a278a596…`.

## Repeat failure and final selection

Packed repeat cohort `20260906-040956` stalled on its first launch, before menu readiness. The
main-thread dump captured at 04:13:34 again shows native `GL11.nglTexImage2D`, with 204.32 seconds
elapsed and 8937.50 ms main CPU time. Stopped the cohort; its two remaining launches did not run.
Retired exact game PID 10232, launcher shell 1772 and wrapper 11560 after checking their creation
timestamps. Private evidence: `packed-repeat-thread-dump.txt`, `packed-repeat-jcmd-error.txt` and
`packed-repeat-retirement.json` under `Diagnostics/raster-snapshot`, plus the archived cohort.

The 16.690-second successful launch remains an observation of a rejected candidate. Three successes
followed by a stalled repeat do not establish consistent sub-18 startup or native reliability.
The stall's texture identity is unknown because intrusive per-upload checkpointing was disabled.
No claim is made that the representation change caused the recurring native failure.

Both production candidates and their candidate-specific tests are removed from the final diff.
The accepted `369d43b4…` executable is restored. The packed-raster loop remains an experimental
lead in PR history; it is not enabled or shipped by this change. Next investigation must identify
the native upload that stops returning with low-overhead pending-call telemetry, then validate its
buffer/layout/driver lifecycle before reselecting a representation optimization. Further ordinary
timing samples alone cannot identify that failure.

Final guest check confirmed accepted JAR identity, clean checkout, ordinary task Ready and zero
Java/game/launcher-shell actors. Windows reports Intel Arc 140T GPU (10GB), driver 32.0.101.8991,
status OK. This inventory is not evidence that the driver caused the stall; no driver, RAM,
worker-count or scheduling change was made.
