# Smoothness reporting follows the retained stutter model

**Date:** 2026-08-27
**Status:** implementation and focused verification complete

## Gap found

The frame recorder already emitted bounded stutter profiles, and the retained telemetry policy
already said not to rank gameplay by one percentile alone. The desktop bridge and controlled
benchmark comparison did not carry those fields through, however. The visible result still reduced
a session to average FPS, one-percent low, and p95/p99 frame time. That made the product disagree
with its own measurement policy.

No new game hook, per-frame clock, allocation, or retained timeline was added in this change. It
only exposes values already derived by the bounded runtime accumulator.

## Ranking used

The controlled comparison now emits an explicit `smoothnessPriority` and orders available metrics
as follows:

1. excess slow-frame time per active second (`stutterBurdenMillisPerSecond`);
2. exposure to consecutive slow frames (`repeatedSlowFramesPercent`);
3. slow-frame frequency per active minute;
4. longest recurring cluster and the 33.33/50/100ms severity counts;
5. p99 frame time, 0.1-percent low, and one-percent low;
6. average and median FPS as throughput context.

The latest-session card uses the first three measures as its leading values, followed by one-percent
low, p99, and average FPS. When the report contains complete settled paused and unpaused buckets,
the card presents those disjoint active-state windows instead of blending them. Focus changes and
the frame crossing a pause transition remain excluded by the recorder. Older reports without a
stutter profile retain the four-metric legacy display.

The controlled off→on page now makes the leading settled-campaign measures visible instead of
showing only startup time. Raw threshold counts remain in the sealed result for diagnosis; their
active-duration-normalized counterparts rank ahead of them.

## Verification

- `DesktopBridgeCommandTest` verifies bounded projection of the pause-specific stutter and severity
  fields without exposing raw worst-frame details.
- `DesktopBenchmarkLaunchTest` verifies sealing of the existing stutter profile and the comparison
  priority.
- `FramePacingCard.test.tsx` verifies the paused/unpaused split, metric ordering, active duration,
  and legacy fallback.
- `BenchmarkPage.test.tsx` verifies that a controlled comparison presents recurring-stutter metrics
  and labels improvement direction correctly.
- Real browser renders at 1040×700 and 720×560 showed no document, workspace, row, or metric-grid
  horizontal overflow. The narrow view retained a readable two-column metric layout, and the
  startup comparison uses a full-width row rather than leaving three unused grid columns.

This is a reporting correction, not a new FPS uplift claim.
