# Paused campaign frame pacing: presentation is a large part of the 30-FPS tail

**Status:** two accepted candidate observations plus one longer control; useful for the product
decision, not a release-wide FPS claim

**System:** Starsector 0.98a-RC8, current 83-mod development profile, M5 MacBook Air, bundled
x86-64 game runtime under Rosetta, 1440×932 windowed

**Condition held constant:** Preflight Recommended, prepared assets active, runtime fixes active,
the same campaign save, Continue-only internal control, campaign left paused, and the first 30
seconds of campaign frames excluded. The candidate changed only the display presentation request.

## Result

The phase probe found that most frames beyond 33.33 milliseconds in the control were waiting in
the native buffer swap. Starsector's own main loop still caps the game at 60 FPS after the swap, so
disabling vsync removes a second timing gate without uncapping the game.

| Paused campaign slice | Frames | Average FPS | 1% low | 0.1% low | p99 frame | Frames >33.33 ms | Native-swap p99 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Vsync on, control | 7,045 | 57.40 | 30.77 | 25.97 | 32.5 ms | 41 | 17.7 ms |
| Vsync off, candidate 1 | 2,144 | 58.95 | 43.67 | 26.25 | 22.9 ms | 4 | 6.6 ms |
| Vsync off, candidate 2 | 2,151 | 59.09 | 43.86 | 39.68 | 22.8 ms | 1 | 6.7 ms |

The two candidate 1-percent lows were 41.9 and 42.5 percent above the control. The control had 5.82
frames over 33.33 milliseconds per thousand measured frames; the candidates had 1.87 and 0.46.
Of the control's 41 slow frames, native swap was the largest measured phase in 33. Native swap was
the largest phase in none of the five candidate slow frames. The remaining candidate stalls were
already present before swap, so this switch does not replace ordinary campaign hotspot work.

The 0.1-percent tail is not stable across the two short candidate observations and is not used as a
claim. The control also ran longer than either candidate. Temperature and background load were not
held as a formal cohort. A packaged release claim still wants equal-duration, interleaved runs on
more than this one Mac.

## Product boundary

`Smooth frame pacing` is therefore an explicit experimental opt-in, separate from frame recording.
It:

- changes only `Display.setVSyncEnabled(true)` to `false` on the exact reviewed LWJGL 2 class;
- leaves an already-disabled request disabled;
- leaves Starsector's configured FPS cap and main-loop limiter unchanged;
- warns that tearing may be visible;
- performs no per-frame timestamp work unless frame recording is separately enabled;
- declines to transform on class hash, bytecode version, method-shape, loader, or source drift; and
- is unavailable when runtime optimizations are Off.

The transform does not open, parse, copy, or write campaign saves. Operator-side hashes of the
campaign and descriptor remained unchanged across both accepted candidate runs:

- campaign: `f716de34cf38b717134bee0d6233824ce76c0374624b0f644c82d61beaad07d1`
- descriptor: `45388feaf1a3fc279b3a4e3b56fa88ad51a06e8b6e5a2b0f04d29e6db760a2cc`

One earlier orchestration attempt never attached the Continue controller and stayed at the title
screen. It has no campaign-state receipt, was excluded immediately, and is not retained as data.
The two accepted candidate runs each recorded `continue fired` followed by
`campaign observed paused=true` from an agent attached to that launch's exact PID.

## Retained reports

- [Vsync-on control](data/2026-08-26-campaign-frame-pacing/paused-vsync-on.json)
- [Vsync-off candidate 1](data/2026-08-26-campaign-frame-pacing/paused-vsync-off-1.json)
- [Vsync-off candidate 2](data/2026-08-26-campaign-frame-pacing/paused-vsync-off-2.json)

The reports contain the bounded frame distributions, phase attribution, exact presentation-request
counters, runtime PID, and timestamps. They contain no save contents or local filesystem paths.
