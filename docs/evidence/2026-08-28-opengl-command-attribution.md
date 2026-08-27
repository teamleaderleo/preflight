# Settled active-campaign OpenGL command attribution

**Date:** 2026-08-28

**Disposition:** retained intrusive discovery result; large baseline state/matrix traffic identified,
but no FPS optimization claim

**Parent:** [Gameplay FPS program #449](https://github.com/teamleaderleo/preflight/issues/449)

**Implementation:** `e74b8342`

## Result

The exact settled active-campaign window retained 1,702 complete frames and 22,647,644 selected
LWJGL OpenGL wrapper calls: 13,306.49 calls per frame. Matrix state contributed 6,346.44 calls per
frame, fixed-function state 4,513.84, immediate-mode `glBegin` batches 1,454.53, and texture binds
980.37. Matrix plus fixed-function state represented 81.62% of selected calls; adding texture binds
raised the share to 88.98%. At the retained frame rate this is roughly 700,000 selected legacy-GL
calls per second.

This is a large baseline optimization lead, especially on the reference x86-64 JVM under Rosetta
and Apple's OpenGL-over-Metal implementation. It is not yet evidence that those calls are redundant.
Matrix transforms may encode required per-object work, and state can legitimately change between
draws. The next probe should therefore split exact methods and count consecutive same-argument
reissues for independently tracked state before suppressing anything.

The command volume did not explain the hitch tail by itself. The 70 frames slower than 33.33 ms
averaged 13,377.93 selected calls, only 0.54% above the all-frame mean. Across the 64 retained worst
frames, Pearson correlation between duration and selected command count was 0.116. One 99.290 ms
frame carried only 10,908 selected calls, well below the mean, while the maximum observed command
count was 15,221. This leaves the prior active-campaign hitch branch open: shader/pass cost,
content-specific GPU work, command-stream starvation, or CPU work outside these wrappers may still
produce the 50–100 ms frames.

No selected texture upload, shader/uniform, texture-unit, or framebuffer call occurred in the
retained window. Array/pixel draw, buffer bind/upload, and readback-or-explicit-flush traffic was
small and nearly constant. The observed route is overwhelmingly the legacy fixed-function and
immediate-mode path rather than a modern shader/FBO pipeline.

## Instrument and claim boundary

The probe was explicitly enabled with `PREFLIGHT_FRAME_GL_COUNTS=1`; the asynchronous GPU timer was
not requested. It injected one Java counter call at the entry of selected public static LWJGL 2
wrappers. The hot call performs one thread-ID read, a bounds check, and a primitive increment, with
no per-command clock read or allocation. Frame-boundary aggregation averaged 2.251 microseconds and
reached 375.875 microseconds. The injected per-command and JNI/driver perturbation cost is not
measured, so none of this run's FPS, percentile, or stutter values is an optimization comparison.

Counting began at the internal `campaign.begin-frame-window` action after the five-second unpause
transition. The action preserved the observed unpaused state. Its partial frame was deliberately
dropped, which accounts for the frame recorder's 1,703 frames versus the command recorder's 1,702.
Another 777 nonmatching frames after the window opened were excluded rather than mixed into the
campaign-unpaused aggregate. There were zero calls from an unexpected thread and zero unknown
category calls.

The bounded coverage counts selected wrapper families, not all OpenGL calls. Immediate rendering is
counted by `glBegin` batch rather than vertex. The result therefore supports relative family and
redundancy work, not a claim that 13,306 is the complete driver-call count.

## Workload comparability

The route used Preflight only and exactly one game process. All 14 semantic steps passed: internal
Continue, three seconds of untouched pause observation, exact-PID activation, already-paused
verification, 27 seconds of paused warm-up, 45 seconds paused, explicit unpause, five seconds of
transition, exact window start, 45 seconds of settled wall time, capture, and exact-PID shutdown.
It issued no save action and exited cleanly.

The profile fingerprint remained
`2995668308ac3d31d645ccac30fb1a7e644e64fce5609050a1488df4cadc5af6` with 83 mods; the prepared
texture fingerprint remained
`59b01dc050f39a9f07053bd168cc8c1ecd55086b429b2d732456f87ca217a702`. Display and runtime identity
remained Starsector 0.98a-RC8 at 1440x932 windowed, ordinary 60-FPS/VSync policy, and the shipped
x86-64 Zulu Java 17.0.10 runtime under Rosetta.

The broad phase shape closely reproduced the immediately preceding GPU-timer route: paused campaign
frames were 5,241 versus 5,236, active unpaused frames 1,894 versus 1,888, total boundaries 8,277
versus 8,253, and both runs dropped five state transitions with no inactive or invalid intervals.
That consistency supports workload attribution. The scenario still does not bind exact save bytes
or assert visual content every frame, so it is not a lockstep paired performance cohort.

## Correctness, fallback, and health

The exact installed `lwjgl.jar` SHA, class SHA, bytecode major version, required method, and complete
selected-method counts gate each target. Original bytecode remains the fallback on every mismatch.
Four loaded classes installed 127 reviewed wrapper hooks: GL11 (77), GL13 (7), GL15 (12), and GL20
(31). `EXTFramebufferObject` did not load during this route, so its exact adapter remained available
but unapplied; the zero framebuffer-call count is consistent with that observation.

The live adapter applied 66 exact transformations across 82 registered targets with zero source
binding rejection, transform decline, contained failure, or kill switch. Runtime combat integrity
remained valid. The lifecycle scanner examined the game log and console, found no gameplay fatal,
and both wrapper and exact game PID exited with code zero. Java 17 `./mvnw verify` passed all five
modules: 2,256 tests, zero failures/errors, and nine expected skips.

## Rosetta boundary and next experiment

Rosetta cannot be assigned a percentage from this run because an ARM JVM cannot load the shipped
x86 native libraries, so there is no same-machine native control. The command census does show a
credible amplification boundary: hundreds of thousands of small legacy-GL wrapper/JNI calls per
second enter Apple's translation stack. Rosetta may tax CPU-side Java/JIT and dispatch, while the
OpenGL-over-Metal layer may tax state validation and command construction. Neither explains the
near-zero correlation between selected call count and the worst durations.

The highest-information next slice is a narrower discovery adapter that records per-method volume
and consecutive same-argument reissues for state whose ownership can be modeled exactly: texture
binding per active unit, enable/disable capabilities, blend/alpha/depth/cull state, matrix mode,
viewport, and scissor. It must observe push/pop or other invalidation boundaries, remain bounded and
explicit, and make no FPS claim. If a large redundant fraction survives those gates, suppress one
low-risk family behind its own kill switch and compare repeated interleaved thin cohorts. Matrix
transform batching is a later, higher-risk branch; the current totals alone do not justify it.

Machine-readable metrics and raw-file hashes are in
[`2026-08-28-opengl-command-attribution.json`](data/2026-08-28-opengl-command-attribution.json).
Raw logs and launch artifacts are disposable after this bounded record is committed.
