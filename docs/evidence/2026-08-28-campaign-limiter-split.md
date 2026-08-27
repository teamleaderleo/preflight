# Campaign hitch limiter split

**Date:** 2026-08-28

**Disposition:** retained diagnostic infrastructure; this is not an FPS optimization claim

**Parent:** [Gameplay FPS program #449](https://github.com/teamleaderleo/preflight/issues/449)

**Child:** [Hitch packet v1 #1150](https://github.com/teamleaderleo/preflight/issues/1150)

**Implementation:** `354d9006` (`Measure exact campaign frame limiter wait`)

## Result

The thin Preflight-only confirmation split every eligible paused-campaign frame at Starsector's
exact main-loop limiter sleep. Both captured frames above 50 ms remained game-work hitches after
the known sleep was removed:

| Trigger | Total | Limiter request / elapsed | Pre-swap excluding limiter | Native swap | Messages |
| --- | ---: | ---: | ---: | ---: | ---: |
| sequence 1,124 | 53.521 ms | 0 / 0.016 ms | 52.753 ms | 0.511 ms | 0.239 ms |
| sequence 2,991 | 56.003 ms | 8 / 9.069 ms | 45.981 ms | 0.458 ms | 0.438 ms |

This does not retroactively time the 50.003 ms frame in the first hitch-packet run, but it strongly
falsifies the hypothesis that the matching paused >50 ms fingerprint is merely the ordinary FPS
cap sleep. The exact broad campaign phase timers were deliberately disabled: their prior intrusive
discovery result identified this boundary, but their FPS numbers are not reused as a performance
claim.

The complete 89.852-second paused series contained 5,238 eligible frames. Its average was 58.30
FPS, its 1% low was 30.30 FPS, and 31 frames exceeded 33.33 ms. Those slow frames were not one
family:

- 23 were native-swap dominated;
- seven were pre-swap game-work dominated after limiter removal;
- one was limiter-oversleep dominated.

The one limiter-dominated frame lasted 43.269 ms. Starsector requested 11 ms, `Thread.sleep`
returned after 22.867 ms, and the remaining pre-swap work took 19.657 ms. Ordinary limiter sleep
averaged 9.509 ms with a 12.100 ms p99; remaining pre-swap wall time averaged 5.603 ms with a
13.100 ms p99; native swap averaged 1.768 ms but had a 17.600 ms p99. The familiar approximately
33 ms tail is therefore primarily presentation/swap quantization in this run, while the rarer
>50 ms hitches are game work. Both are real, and they need different next experiments.

## Exact boundary and semantics

Installed bytecode puts the campaign/main-state limiter in
`com/fs/starfarer/BaseGameState.traverse()Ljava/lang/String;`. The exact class has SHA-256
`cc5ef1d187dae1ca1017f6d40dae8576b88603a28a8d1c008e2d2aa2516c7c4d`, class-file major 61,
and comes from the 0.98a-RC8 core JAR with SHA-256
`a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149`.

The method has exactly two `Thread.sleep(long)` calls. The first is the existing literal 50 ms
inactive-window sleep. The second receives the integer-truncated result of the existing
`max(1 / fpsCap - elapsed, 0) * 1000` calculation. The transform duplicates that second call's
unchanged `long` argument, timestamps immediately before it, and timestamps immediately after it
returns. It changes neither the requested duration nor control flow.

`preSwapExcludingLimiter` is deliberately named as wall time, not CPU time. It may still include
native calls, OS descheduling, JIT/GC effects, or other waits before `Display.swapBuffers`.
`limiterOvershoot` is elapsed sleep minus the requested whole milliseconds; it is not automatically
an error because `Thread.sleep` is not a precise deadline API.

The combat loop owns a separate limiter in `CombatState.traverse`. This narrow campaign plan does
not claim combat coverage.

## Thin live confirmation

The checked `campaign-hitch-limiter-current-state` scenario used internal PID/start-bound Continue,
left the loaded pause state untouched, waited 30 seconds, then retained 60 seconds. It passed all
five semantic steps and shut down the exact owned process with code 0. No save action was issued.
The workload was stable paused campaign throughout, but this was one diagnostic confirmation—not a
paired or shuffled optimization cohort.

All 5,238 eligible campaign frames had complete presentation and limiter splits. The exact limiter
recorded 5,808 calls and 5,808 normal completions over the whole process. Two bounded hitch packets
completed their post-windows; no trigger was dropped. Broad campaign-phase production was off and
correctly reported unavailable rather than guessed.

Adapter health was `ACTIVE`: 64 exact transformations applied across 77 registered targets, with
zero source-binding rejections, unavailable plans, declines, contained failures, cache-rejection
signals, wrapper failures, or runtime-integrity failures. The limiter transform itself exact-matched
the installed class, source JAR, app loader, and app loader name. Unit tests also prove that a
disabled plan, wrong identity, changed two-sleep shape, missing limiter, or second transform declines
to original bytecode.

The inclusive display-boundary hook averaged 16.409 microseconds and had one 7.275 ms maximum. That
scope excludes the two limiter timestamps. These measurements establish diagnostic behavior and
coverage; they are not an FPS-overhead or uplift claim.

The reference identity was the same 83-mod ordered profile used by hitch packet v1:

- profile fingerprint `2995668308ac3d31d645ccac30fb1a7e644e64fce5609050a1488df4cadc5af6`;
- texture-profile fingerprint `59b01dc050f39a9f07053bd168cc8c1ecd55086b429b2d732456f87ca217a702`;
- 1440x932 windowed direct launch with sound, ordinary presentation policy, prepared pixels, and
  the Recommended optimization preset;
- native arm64 Oracle Java 17.0.12 Preflight wrapper and the installed Java 17 game runtime;
- frame telemetry on, broad campaign timers off, JFR recording off, smooth-frame-pacing experiment
  off.

Lifecycle inspection examined the bounded console and game log, found no gameplay fatal, and
classified the controller stop as clean. The known late OpenAL native call appeared only after the
PID-bound controller stop and did not become a lifecycle failure.

## Correctness and save boundary

The adapter adds static primitive counters and bounded primitive frame arrays. It does not transform
save/load or serialization classes, replace gameplay objects, change campaign time, or write a save.
The scenario performed no save action and remained paused, so it did not advance the campaign into
an autosave route. Exact identity mismatch, kill switch, disabled telemetry, or changed method shape
keeps the original class bytes.

Java 17 `./mvnw verify` passed all five modules: 2,240 tests, zero failures/errors, and nine expected
skips.

## Mutable conclusions and next experiment

**Observed:** campaign limiter measurement is complete for this installed identity. It converted
`preSwap` into known limiter wait versus remaining pre-swap wall time on every eligible frame.

**Observed:** two matching >50 ms paused hitches were not caused by the ordinary limiter wait.
Future CPU attribution should target the remaining game-work interval.

**Observed:** the 1% tail had a different dominant family. Native swap was largest on 23 of 31
frames above 33.33 ms and explains most of the 60-to-30-FPS quantization in this run.

**Explored, not exhausted:** one limiter oversleep was material. Limiter timing stays in the packet
and corpus, but this run does not justify changing the game's cap or sleep policy.

**Highest-information next slice:** preserve the thin packet, then add bounded presentation-path
identity and GPU/driver attribution around native-swap-dominated clusters. First establish the
actual swap-interval/context policy and enough OpenGL command/state context to distinguish GPU
backlog from compositor/vsync waiting. Only then consider an intrusive GPU timing experiment.

**Second branch:** for the rarer game-work >50 ms fingerprint, let a thin trigger arm a short CPU
capture for a subsequent matching hitch. Do not run permanent broad call timers merely to rediscover
that the pre-swap remainder is large.

The machine-readable record and raw-file hashes are in
[`2026-08-28-campaign-limiter-split.json`](data/2026-08-28-campaign-limiter-split.json). The raw run
directory is disposable after this bounded record is committed.
