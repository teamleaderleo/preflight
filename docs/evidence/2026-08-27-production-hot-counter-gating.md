# Production hot-counter gating

The campaign memo and entity-maintenance optimizations previously updated diagnostic counters on
their hottest paths even when the opt-in campaign timers were disabled. Production runs do not need
those totals: the counters exist to explain profiling sessions, while installation and fail-closed
health remain available independently. The runtime now snapshots `preflight.campaignTimes` at
session start and only updates the high-frequency counters when that property is enabled.

Correctness behavior is unchanged. Both optimization plans installed and remained enabled in the
follow-up run. Commodity validation-unavailable events still count and disable the memo even without
profiling because they are rare correctness diagnostics. Stable snapshot state also remains live;
the follow-up ended with 450 cached owners even though its hit/rebuild counters stayed at zero.

## Preflight-only follow-up

One Preflight-owned sampled launch completed Continue, observed the initial paused state for three
seconds, verified it was already paused, held the settled paused window, used the mapped campaign
control to unpause, and completed the full unpaused window. All three semantic receipts executed,
the scenario passed, and the frame probe dropped zero inactive intervals. The JVM flushed one valid
5.5 MiB, 163-second JFR chunk. The outer launcher again reported exit 6 because an OpenAL shutdown
thread reached `AL10.nalBufferData` after the controlled scenario; this did not invalidate the
completed scenario or recording.

The settled paused distribution was effectively unchanged from the immediately preceding sampled
run: 58.25 versus 58.46 average FPS, 30.03 versus 30.21 FPS 1% low, and 33.3 versus 33.1 ms p99.
The unpaused distribution was worse (48.65 versus 52.27 average FPS and 13.53 versus 14.18 FPS 1%
low), but the profile shows that this run included battle/autoresolve work under
`BaseLocation.advanceEvenIfPaused`. It is not a clean frame-time attribution to the counter change.

Timer-free sampling is directionally consistent with removing hot-path bookkeeping:

- `CommodityOnMarket.reapplyEventMod` wrapper leaves fell from 85/937 campaign samples (9.07%) to
  63/1,097 (5.74%); and
- all commodity-wrapper stacks fell from 95/937 (10.14%) to 66/1,097 (6.02%).

This is not claimed as an FPS uplift. `MutableStatWithTempMods.advance`, which the change did not
touch, also moved from 43/937 samples (4.59%) to 7/1,097 (0.64%), demonstrating meaningful workload
variation between the two traversals. The supported conclusion is narrower: routine users no
longer pay shared counter writes whose results are not emitted for them, while `--campaign-times`
profiling retains the diagnostic totals.

The bounded record is
[`data/2026-08-27-production-hot-counter-gating.json`](data/2026-08-27-production-hot-counter-gating.json).
The raw JFR and launch directory remain disposable local artifacts and are not committed.
