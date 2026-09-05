# Windows standard raster snapshot trial

Objective: reduce ordinary Recommended texture-loading time while retaining the original
large-image converter, GL policy, 1024 ceiling, main-thread ownership and late resources.
Owner: current Codex task. Phase: single-copy candidate rejected; packed converter image in verification.
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
