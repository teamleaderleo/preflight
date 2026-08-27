# Settled active-campaign OpenGL state reissues

**Date:** 2026-08-28

**Disposition:** retained intrusive discovery result; one narrow state-suppression candidate justified,
but no FPS optimization claim

**Parent:** [Gameplay FPS program #449](https://github.com/teamleaderleo/preflight/issues/449)

**Implementation:** `7186f7b3`

## Result

The exact settled active-campaign window retained 1,434 complete frames and 8,116,645 calls to 12
modeled state families. Of 7,943,140 calls for which the prior state was known, 3,289,692 repeated
the state already modeled: 41.42% of known comparisons and 40.53% of all modeled calls. That is
2,294 observed same-state reissues per retained frame.

The largest independently modeled families were:

| Family | Calls/frame | Same-state/frame | Same-state share of calls |
| --- | ---: | ---: | ---: |
| `glEnable` / `glDisable` by capability | 3,323.79 | 1,270.80 | 38.23% |
| `glBlendFunc` | 1,300.45 | 482.30 | 37.09% |
| `glBindTexture` by active unit and target | 1,005.90 | 539.96 | 53.68% |
| `glViewport` | 7.00 | 1.00 | 14.29% |
| `glMatrixMode` | 22.00 | 0 | 0% |

This is a materially larger opportunity than the recent allocation paper cuts. The modeled redundant
calls alone are about 16.89% of the 13,579 selected GL wrapper calls per frame in the accompanying
broad census. They cross Java, LWJGL/JNI, and Apple's legacy OpenGL translation boundary despite
asking the driver to preserve an already-active state.

It is not yet a performance result and it does not justify suppressing all 3.29 million calls at once.
The next candidate should cover one family, retain exact installed identities and original-call
fallback, model all relevant invalidation paths, and use thin shuffled A/B cohorts after correctness.
Texture binding is the leading first candidate because it has the highest observed redundant share
and a comparatively narrow state key. Enable/disable and blend state remain separate later candidates.
Matrix transforms remain higher risk and were not shown redundant by this probe.

## Hitch relationship

The 58 frames slower than 33.33 ms averaged 2,304.83 same-state reissues, versus 2,294.07 across all
frames. Their redundancy share was 40.54%, effectively the same as the all-frame 40.53%. Texture,
enable/disable, and blend redundancy shares also remained within 0.14 percentage points of their
all-frame shares.

The state reissues are therefore a baseline frame-tax lead, not the cause of particular hitches.
Removing them could improve ordinary CPU submission headroom and may reduce Rosetta/driver overhead,
but the >33 ms active tail still needs CPU/GPU/command-starvation attribution of its own.

## Instrument and claim boundary

The probe was explicitly enabled with `PREFLIGHT_FRAME_GL_STATE_REISSUES=1`. It composed 19 reviewed
hooks into the broad GL-count boundary: 17 exact GL11 wrappers and two exact GL13 wrappers. The hot
path used fixed primitive tables and counters with no per-command clock read or allocation. It did
not suppress or change any original LWJGL call.

This remains intrusive discovery instrumentation. The observed frame window averaged 53.77 FPS, but
that number is not an FPS claim because millions of injected Java calls performed the bookkeeping.
Performance claims must use a thin candidate with this census disabled.

The model conservatively forgot state across 62,405 retained display-list or attribute-pop
invalidations. Raw observation counted 120,763 display-list calls, 16,627 server attribute pops, and
2,868 client attribute pops across retained and discarded frames. There were zero unexpected-thread
calls, unknown methods, capability-table overflows, or texture-table overflows. Unobserved extension
state changes and display-list compilation semantics remain explicit correctness questions before
suppression.

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

The broad phase shape remained close to both immediately preceding routes. This run recorded 8,210
total boundaries, 5,471 paused campaign frames, 1,615 unpaused campaign frames, and five dropped state
transitions. The command-census route recorded 8,277 / 5,241 / 1,894 / five; the GPU route recorded
8,253 / 5,236 / 1,888 / five. The lower active frame count is compatible with discovery overhead,
which is why none of these FPS values are compared. The scenario still does not bind exact save bytes
or assert identical visual content every frame, so this is reproducible semantic shape rather than a
lockstep paired cohort.

## Correctness, fallback, and health

The installed `lwjgl.jar` SHA, class SHA, bytecode major version, complete method set, and exact
descriptors gate the composed probe. Installed-bytecode tests ran ASM verification against the real
GL11 and GL13 classes. Any mismatch keeps the original class bytes. The shared adapter carried its
normal process-level and per-plan kill switches.

The live adapter applied 67 exact transformations across 82 registered targets with zero source
binding rejection, transformation decline, contained failure, or kill switch. Runtime combat
integrity remained valid. The lifecycle scanner examined the game log and console, found no gameplay
fatal, and both launcher and exact game PID exited with code zero. Java 17 `./mvnw verify` passed all
five modules before the run: 2,256 tests, zero failures/errors, and nine expected skips.

## Next correctness gate

Before a texture-bind candidate can skip a call, audit and model display-list compile/execute state,
texture deletion, every active-texture entry point reachable in the installed corpus, and context
ownership. Start unknown and call the original function whenever the model is unknown. On any
unmodeled mutation, table overflow, wrong thread, or runtime fault, decline suppression for the
session and continue with original calls. Telemetry must distinguish observed, suppressed, declined,
and invalidated calls without performing clocks or allocations per bind.

If that gate holds, run ordinary campaign and combat correctness first, then repeated interleaved
thin A/B cohorts on the same settled campaign and 1,040-DP corpus. A reduction in native submissions
without a useful frame-time or CPU improvement is a valid rejection.

Machine-readable metrics and raw-file hashes are in
[`2026-08-28-opengl-state-reissues.json`](data/2026-08-28-opengl-state-reissues.json).

