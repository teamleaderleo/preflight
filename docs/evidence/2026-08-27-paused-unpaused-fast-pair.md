# Paused/unpaused fast-preset diagnostic pair

**Date:** 2026-08-27
**Status:** one paired diagnostic; not a release claim

The same Continue → paused → unpaused campaign route was run once with Preflight's measurement-only
boundary and once with the shipped `fast` preset. Both launches went through Preflight, selected the
current save through the internal title callback, foregrounded the exact recorded PID once, and used
the internal pause controls. Each run reported zero inactive intervals and retained more than 30
active seconds in both state buckets.

This pair validates the new state-separated comparison shape. It does not justify describing the
whole fast preset as an established campaign-FPS win: one live pair is sensitive to campaign events,
temperature, and mod activity.

## Result

| State and metric | Measurement only | Fast | Relative delta |
| --- | ---: | ---: | ---: |
| paused average FPS | 58.37 | 58.29 | -0.14% |
| paused 1% low FPS | 30.58 | 29.94 | -2.09% |
| paused 0.1% low FPS | 12.45 | 23.75 | **+90.76%** |
| paused stutter burden, ms/s | 5.41 | 1.42 | **-73.75%** |
| paused repeated slow frames | 0.11% | 0.00% | **-100%** |
| paused slow frames/minute | 21.07 | 30.74 | +45.89% |
| unpaused average FPS | 52.07 | 51.73 | -0.65% |
| unpaused 1% low FPS | 14.51 | 15.48 | +6.69% |
| unpaused 0.1% low FPS | 6.89 | 7.57 | +9.87% |
| unpaused stutter burden, ms/s | 61.42 | 56.16 | -8.56% |
| unpaused repeated slow frames | 2.97% | 4.01% | +35.02% |
| unpaused slow frames/minute | 160.76 | 188.32 | +17.14% |

The paused result is not contradictory. Fast produced more isolated threshold misses, but removed
every repeated slow-frame cluster and cut their excess duration sharply. That is why the 0.1% low
and stutter burden improved while the raw frequency and 1% low did not. This is exactly the class of
case that FPS percentile-only reporting concealed.

The unpaused result is genuinely mixed. Tail severity improved modestly (`p99` 68.9ms → 64.6ms;
frames over 50ms 44 → 36), but slow-frame frequency and repeated exposure regressed. The next
optimization pass should therefore target recurring unpaused campaign work rather than the limiter
or paused rendering throughput.

## Harness failures found and fixed

The measurements finished, but two post-measurement cleanup bugs prevented a normal paired result:

1. The measurement-only route tried to restore pause after the unpaused window. A legitimate
   campaign interaction made that action unavailable. The route now captures and stops from the
   unpaused state, matching the already-proven sampling scenario.
2. A successful explicit `quit` stopped the exact PID before finalization, after which the runner
   incorrectly demanded a second controller-stop receipt from the vanished process. Explicit
   successful quits now bypass that redundant receipt.

The macOS driver also now rejects a locked console before launching a foreground FPS route. Internal
game controls continue to work without focus, but inactive frames are deliberately excluded and
cannot support an active-game FPS claim.

The bounded record is
[`data/2026-08-27-paused-unpaused-fast-pair.json`](data/2026-08-27-paused-unpaused-fast-pair.json).
Raw runtime reports were hashed into the record and remain disposable local artifacts.
