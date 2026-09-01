# Market stability memo candidate: rejected

**Status:** exact-gated implementation and live probe completed; no optimization retained

The candidate skipped one vanilla market stability refresh block when the exact market class,
stability-stat class, stat identity, dirty state, modified value, and previous stability all remained
unchanged. It failed open on every mismatch, kept its fields transient, had a dedicated kill switch,
and passed synthetic and exact-installed structural tests. That safety work did not make it a good
optimization.

The guard itself required class checks, an added dirty accessor, identity comparisons, float-bit
comparisons, and five transient fields around a small existing calculation. The focused live run did
not establish user-facing improvement. Against the nearest prior Preflight campaign route, paused
average FPS was effectively unchanged (58.49 versus 58.57) and paused 1% low changed from 35.09 to
36.10 FPS, while 0.1% low changed from 31.45 to 24.21 FPS. Slow frames over 33.33ms changed from 2
to 7, and frames over 50ms from 0 to 3.

The unpaused route was likewise not favorable: average FPS changed from 53.60 to 51.33, 1% low from
16.75 to 13.30, and 0.1% low from 7.16 to 6.54. The candidate recorded 98 frames over 33.33ms, 48
over 50ms, 7 over 100ms, 25 repeated slow-frame clusters, and 69.67ms of stutter burden per active
second. The prior report predates cluster telemetry, so this is not a lockstep causal regression
claim. Temperature, battle evolution, and runtime noise remain confounders.

The correct conclusion is narrower: the candidate supplied no positive evidence strong enough to
pay for its hot-path guard and semantic surface. It was removed. Future FPS candidates should rank
repeated clusters, excess slow-frame time, slow-frame thresholds, and evidence completeness ahead
of a favorable isolated percentile. Average, median, 1% low, and 0.1% low remain supporting views.
