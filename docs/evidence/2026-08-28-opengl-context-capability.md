# Live OpenGL timing-capability inventory

**Date:** 2026-08-28

**Disposition:** retained diagnostic prerequisite; this is not an FPS optimization claim

**Parent:** [Gameplay FPS program #449](https://github.com/teamleaderleo/preflight/issues/449)

**Implementation:** `3db5b1b1` (`Inventory live OpenGL timing capabilities`)

## Result

The exact live Starsector context on the reference Apple M5 exposes the pieces required for a
bounded, asynchronous GPU-time experiment:

- vendor `Apple`;
- renderer `Apple M5`;
- version `2.1 Metal - 90.5`;
- OpenGL 1.5 query objects available;
- `GL_EXT_timer_query` available;
- `GL_ARB_sync` available;
- `GL_ARB_timer_query` unavailable and OpenGL 3.3 unavailable.

The next probe can therefore use the LWJGL 2 EXT timer-query path, with a small fixed query ring and
`GL_QUERY_RESULT_AVAILABLE` polling. It must never wait for a result on the render thread. The ARB
timer-query entry points cannot be assumed merely because sync objects exist.

This resolves capability only. It does not yet distinguish GPU execution from driver,
compositor/VSync, or OS-scheduling wait, and it is not evidence that a rendering change is useful.

## Measurement boundary

The retained inventory runs once on the first explicit frame-telemetry `beforeSwap()` callback,
after the game has created and made its OpenGL context current. It reads capability booleans from
LWJGL's live `ContextCapabilities` and reads `GL_VENDOR`, `GL_RENDERER`, and `GL_VERSION`. It creates
no query, fence, buffer, texture, or rendering state. Strings and failure text are bounded.

The inventory completed in 1,213 microseconds. It runs before the native-swap wall bracket, so it
cannot inflate the recorded native-swap duration. It may perturb the first early frame, which is
outside any settled performance claim. After the one-time attempt, the hot path adds only a volatile
attempted-state check to this specific inventory feature.

Reflection, linkage, context, or driver failure records the inventory as unavailable and leaves the
existing frame recorder active. No capability is inferred on failure.

## Live route and health

The short `campaign-continue-proof` route launched only through Preflight, observed the exact
interactive main menu, executed the internal PID/start-bound Continue action, reached
`campaign-ready`, retained the process for ten seconds, and stopped only the owned process. All four
semantic steps passed, exit code was zero, lifecycle scanning found no fatal, and no save action was
issued.

The run retained the 83-mod profile fingerprint
`2995668308ac3d31d645ccac30fb1a7e644e64fce5609050a1488df4cadc5af6`, prepared texture profile
`59b01dc050f39a9f07053bd168cc8c1ecd55086b429b2d732456f87ca217a702`, 1440x932 windowed display,
sound, Recommended preset, ordinary 60-FPS/VSync policy, and Java 17. Because this is a capability
check rather than a cohort, no FPS or workload-comparability claim is made.

Adapter health was `ACTIVE`: 62 exact transforms across 77 registered targets, with zero source
binding rejections, unavailable plans, declines, contained failures, cache rejection signals,
wrapper failures, or runtime-integrity failures. The lower applied count than the longer paused run
reflects classes not loaded by this short route, not a decline.

Java 17 `./mvnw verify` passed all five modules: 2,242 tests, zero failures/errors, and nine expected
skips.

## Decision and next experiment

**Observed:** the reference context supports the EXT elapsed-time query route and nonblocking query
availability checks.

**Rejected assumption:** the implementation must not require OpenGL 3.3 or
`GL_ARB_timer_query`; neither is present here.

**Still unresolved:** whether GPU execution grows on the approximately 17 ms native-swap tail, or
whether that tail remains after GPU work is already complete.

**Next narrow slice:** add an exact-gated, opt-in or telemetry-scoped fixed-size EXT timer-query
ring around the rendered frame preceding swap. Poll only old slots whose result is already
available, record skipped/unavailable/overrun counts, delete owned queries at shutdown when safe,
and retain an immediate disable/fail-open path. First validate semantic behavior and adapter health;
then compare GPU time, native-swap off-CPU time, and actual swap interval within one thin paused
route. Do not make an FPS uplift claim from the instrumented run.

The machine-readable record and raw-file hashes are in
[`2026-08-28-opengl-context-capability.json`](data/2026-08-28-opengl-context-capability.json).

