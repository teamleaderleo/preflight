# Deterministic paused/unpaused campaign cycle

The `campaign-profile-paused-unpaused` scenario completed a single Preflight-owned Starsector
process without GUI focus automation. The runtime action protocol accepted Continue, observed that
the loaded save was already paused, injected Starsector's mapped pause control to unpause, held the
active campaign, injected the same mapped control to restore pause, and then stopped the exact
process it launched. All four action receipts executed. The pause receipts verified `true -> true`,
`true -> false`, and `false -> true` state transitions respectively.

This is the first clean live proof of the v3 campaign state/control transform and the reusable
mixed-state harness. Adapter health was ACTIVE: 64 exact transforms applied, zero declined, zero
unavailable, and zero contained failures. No inactive frame interval was observed. Two pause-edge
intervals were classified and excluded rather than leaking into either state bucket.

## Frame-pacing result

After the shared 30-second campaign warmup, the paused segment recorded 2,625 frames at 58.49
average FPS, 58.48 median FPS, 35.09 FPS 1% low, and 31.45 FPS 0.1% low. Its p95 and p99 frame times
were 20.2ms and 28.5ms; only two frames exceeded 33.33ms and the worst was 40.818ms.

The unpaused state recorded 2,672 frames at 53.60 average FPS, 59.52 median FPS, 16.75 FPS 1% low,
and 7.16 FPS 0.1% low. Its p95 and p99 were 30.9ms and 59.7ms; 113 frames exceeded 33.33ms and the
worst was 155.078ms. This state bucket includes the scenario's explicit five-second transition wait
and subsequent 45-second hold. The median stayed near 60 FPS, but the tail became much worse. That
supports pursuing intermittent campaign work rather than the frame limiter or a broad rendering
throughput ceiling.

## Larger remaining category

The unsampled enclosing probes attribute 6,890.8ms to economy advancement and 10,341.0ms to
location advancement over the run. Market advancement accounts for 6,268.2ms of the economy total.
The sampled subphase probe counted 224,410,368 commodity-stat accesses and 56,102,592 event-mod
accesses. Its extrapolated subphase timings are instrumentation-inflated and are leads, not additive
wall-clock claims.

The existing exact commodity memo was enabled and served 55,933,399 unchanged event-mod calls while
delegating 217,884 changed or first states. The raw call volume therefore does not mean that prior
work disappeared: it demonstrates that the memo is heavily exercised, while its validation and
the enclosing market traversal still sit inside a reliable 6.27-second market total. The next
investigation should separate the residual validation cost from market traversal and location/fleet
work before changing behavior.

The bounded machine-readable record is
[`data/2026-08-27-paused-unpaused-cycle-live.json`](data/2026-08-27-paused-unpaused-cycle-live.json).
The disposable raw run directory is intentionally not committed.
