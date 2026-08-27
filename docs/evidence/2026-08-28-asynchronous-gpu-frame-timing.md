# Asynchronous whole-frame GPU timing

**Date:** 2026-08-28

**Disposition:** retained intrusive discovery instrument and attribution result; no FPS optimization
claim

**Parent:** [Gameplay FPS program #449](https://github.com/teamleaderleo/preflight/issues/449)

**Implementation:** `80bf7635`, teardown hardening `8d818072`, ownership verification `5e465911`

## Result

The live Apple OpenGL-over-Metal path contains two materially different bad-frame families.

In the focused paused campaign bucket, whole-frame GPU work was normally far below the missed-frame
tail: GPU p99 was 4.4 ms while frame-time p99 was 33.0 ms and native-swap inferred off-CPU p99 was
16.4 ms. Most of the recurring approximately 33 ms paused frames therefore reached presentation
with little GPU work outstanding. Representative paired rows were 33.5–33.7 ms frames with only
1.4–2.4 ms of GPU work and 16.2–16.7 ms off CPU inside native swap.

The unpaused bucket was different. Whole-frame GPU time measured 5.8 ms at p50, 25.4 ms at p95,
and 51.4 ms at p99; frame-time p95/p99 were 34.6/64.6 ms. The worst paired frame measured 221.1 ms
with 177.9 ms of GPU work and only 0.148 ms inferred off CPU inside swap. Other 87–147 ms frames
carried 75–136 ms of GPU work with less than 0.3 ms of inferred swap wait.

The unpaused aggregate includes the scenario's deliberate five-second post-unpause transition, so
it is not labelled a pure settled distribution. An exact receipt-time join of the 64 retained worst
unpaused pairs found 19 inside that transition and 45 after `unpaused-settled` began. The settled
subset still contained the 221.1/177.9 ms frame and several 87–122 ms frames with 75–111 ms of GPU
work. GPU-heavy active-campaign tails therefore persist beyond the transition, while their complete
settled distribution still needs a phase-owned aggregate.

This is directional attribution, not proof that any particular draw/state call is responsible.
It advances #449's CPU/GPU/presentation split enough to justify counting OpenGL command and state
volume in a focused active-campaign run. It also says not to use an unpaused rendering optimization
as the explanation for the paused 33 ms quantization; that branch remains presentation/VSync/
compositor work.

## Instrument boundary and overhead

The probe is explicit opt-in through `PREFLIGHT_FRAME_GPU_TIMER=1` or
`-Dpreflight.framePacing.gpuTimer=true`. It uses `GL_EXT_timer_query` with a fixed ring of 16 query
objects. A query begins immediately after one swap and ends immediately before the next, so it
measures GPU commands submitted between presentation boundaries. CPU and GPU can overlap; the GPU,
frame, and swap tracks are reported side by side and must not be added as if serial.

At most two old slots are polled per frame, and a result is read only after
`GL_QUERY_RESULT_AVAILABLE`. The hot path never waits for a query, allocates another query object,
calls `glFinish`, or grows the ring. A non-free ring slot drops a sample instead of blocking.

The focused run issued 8,314 queries, ended 8,313, read 8,312 results, and paired 8,313 frame
identities. There were zero ring overruns, existing-query-owner conflicts, contained failures, or
invalid/inactive frame drops. The sole active query was ended during cleanup, all 16 query objects
were deleted while the context was current, and the sealed report ended with zero pending slots.
The one-time post-begin ownership check passed.

The probe is materially intrusive: its 16,628 begin/end hook samples averaged 428.361 microseconds
and reached 10.136 ms. Two hooks occur per measured frame, so its observed average instrumentation
wall cost was roughly 0.857 ms per frame, exclusive of any GPU/driver perturbation caused by timer
queries themselves. None of this run's FPS, percentile, or stutter values may be presented as a
candidate performance improvement or regression.

## Workload and semantic control

The final run used only Preflight and exactly one game process. The
`campaign-paused-unpaused-optimized` scenario:

- reached the interactive main menu through the exact internal Continue action;
- observed the loaded campaign for three seconds without input;
- foregrounded the exact PID before measurement;
- verified the save was already paused and did not toggle it;
- retained 27 seconds of paused warm-up and 45 seconds of paused measurement;
- deliberately unpaused, retained a five-second transition, then 45 seconds labelled settled;
- captured evidence and stopped only the exact owned PID.

All 12 semantic steps passed. No save action was issued. The route retained 3,557 paired paused and
1,888 paired unpaused campaign frames, with zero inactive or invalid intervals and five deliberately
excluded state-transition intervals. All 7,184 comparable pairs observed swap interval one.

Identity was Starsector 0.98a-RC8, the same 83-mod profile fingerprint
`2995668308ac3d31d645ccac30fb1a7e644e64fce5609050a1488df4cadc5af6`, prepared-texture profile
`59b01dc050f39a9f07053bd168cc8c1ecd55086b429b2d732456f87ca217a702`, 1440x932 windowed display,
sound, Recommended preset, ordinary 60-FPS/VSync policy, and the shipped x86-64 Zulu Java 17.0.10
runtime under Rosetta. The scenario does not bind exact save bytes, so this remains within-run
attribution rather than a paired workload claim.

## Correctness, fallback, and health

The exact `org/lwjgl/opengl/Display` adapter is identity- and method-shape-gated and retains
`ORIGINAL_BYTECODE` fallback. The timer is off unless explicitly requested. Unsupported
capabilities, reflection/linkage trouble, driver errors, another `GL_TIME_ELAPSED` owner, or runtime
query failure disable only this diagnostic and retain the existing frame recorder and original
render/swap path.

Before implementation, a static scan of the exact installed game/mod corpus found no competing
`glBeginQuery`, `GL_TIME_ELAPSED`, `EXTTimerQuery`, or `ARBTimerQuery` references outside LWJGL.
The runtime still checks ownership before each begin and verifies ownership after the first begin.

The final live adapter applied 62 exact transforms across 77 registered targets with zero source
binding rejections, transform declines, or contained failures. The Display v5 plan applied once,
runtime combat integrity remained valid, lifecycle scanning found no gameplay fatal, and both the
wrapper and game exited cleanly. Java 17 `./mvnw verify` passed all five modules: 2,247 tests, zero
failures/errors, and nine expected skips.

Two earlier runs are deliberately retained as control lessons. A short pre-hardening correctness
route proved asynchronous query progress and clean gameplay, but preceded explicit query deletion
and ownership telemetry. A longer paused route dropped 6,039 inactive intervals and all 6,133 GPU
results because Starsector was not frontmost; it is rejected as workload evidence rather than
silently recycled. The final focused route corrected both limitations.

## Reconciliation with #449 and current main

The complete parent issue and its three existing comments were re-read after this run. Public
`origin/main` remained `afb00803002156be0c54f509f82d8bba34982eea`; it does not contain this
working branch's recent gameplay instrumentation. The working branch now implements or partially
implements the hitch recorder, semantic campaign/combat routes, CPU/swap split, whole-frame GPU
track, exact-step/JFR correlation, allocation analysis, and experiment rejection discipline.

The result closes #449's immediate whole-frame GPU capability question on this reference machine,
not the parent. Exact settled-phase GPU aggregates, GL command/state attribution, fresh first-30-
second decomposition, generalized mod frame/hitch tax, automated allocation/hot-pattern ranking,
broader workload fingerprints, adaptive escalation, and #251's thin entity-index cohort remain
missing or partial. Existing Stellar Networks, MagicLib, economy, fleet, and cold-campaign rankings
remain leads that need fresh verification when a current packet or relevant code/content change
points back to them.

The completed AI Tweaks `WeaponHandle.getLocation` experiment remains a canonical rejection within
the same program: it removed roughly 99% of its reviewed allocation family, passed ordinary and
1,040-DP correctness routes, but added a global getter tax and produced no useful player-visible
win. The GPU result does not revive that candidate.

## Rosetta boundary

The shipped game JVM and LWJGL path are x86-64 under Rosetta. Translation can tax Java/JIT work and
CPU-side legacy-GL dispatch, but it cannot be used as a unified explanation for this result. The
paused quantized tail was predominantly off CPU inside swap, and the unpaused tail included long
GPU elapsed intervals. A defensible translation estimate needs a cross-runtime or cross-machine
control; an ARM JVM cannot simply load the shipped x86 native libraries. Treat Rosetta as an open
CPU/submission attribution axis, not a substitute for measuring the GL workload.

## Decision and next experiment

**Observed:** paused recurring 33 ms tails normally combine low GPU time with roughly one refresh
interval of off-CPU native-swap wait.

**Observed:** active unpaused tails often contain high whole-frame GPU time, including after the
five-second transition.

**Falsified as a unified diagnosis:** one CPU, GPU, or presentation mechanism does not explain both
paused and unpaused bad frames.

**Highest-information next slice:** add counting-only, exact-gated whole-frame OpenGL command/state
attribution for the active-campaign path. Start with draw calls, texture binds, blend/state changes,
buffer/texture uploads, synchronous `glGet*` calls, and unchanged-state reissues where the exact
LWJGL seams can observe them. Keep it discovery-only, bounded, and phase-owned; do not suppress or
deduplicate calls in the first experiment. The report must distinguish the five-second unpause
transition from the exact settled step.

In parallel only when the paused branch becomes the target, test presentation policy/compositor
behavior with a thin controlled run. Do not infer a rendering optimization from the paused timer
result.

The machine-readable result and raw-file hashes are in
[`2026-08-28-asynchronous-gpu-frame-timing.json`](data/2026-08-28-asynchronous-gpu-frame-timing.json).
Raw logs and launch directories are disposable after this bounded record is committed.
