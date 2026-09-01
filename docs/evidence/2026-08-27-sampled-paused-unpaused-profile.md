# Timer-free sampled paused/unpaused campaign profile

The deterministic semantic-control harness launched one Preflight-owned game with a single-chunk
JFR and without deep campaign call timers. Continue executed, the loaded campaign verified already
paused, the full paused window completed, the mapped pause control unpaused the campaign, and the
full unpaused window completed. There were zero inactive frame intervals.

At the end of measurement a campaign interaction was active. The requested cleanup pause correctly
failed closed instead of sending a pause key into a modal. The harness stopped its exact process and
the JVM flushed one valid 4.9 MiB, 163-second recording. The scenario status is therefore failed
after measurement, while the completed windows and profile are usable. The checked-in sampling
scenario now stops directly after its unpaused window; restoring pause has no value when the process
is neither retained nor saved, and a late interaction can legitimately make that action unavailable.

## Frame-time confirmation

The settled paused bucket recorded 3,420 frames at 58.46 average FPS, 30.21 FPS 1% low, 20.83 FPS
0.1% low, and 33.1ms p99. The unpaused bucket recorded 1,901 frames at 52.27 average FPS, 14.18 FPS
1% low, 8.38 FPS 0.1% low, and 70.5ms p99. This independently reproduces the earlier result: median
throughput remains near 60, while active campaign work creates the damaging tail.

## Timer-free campaign ranking

The recording contains 937 campaign main-thread execution samples. The largest coherent leaves are:

- `CommodityOnMarket.reapplyEventMod`: 85 samples / **9.07%**;
- `MutableStatWithTempMods.advance`: 43 / **4.59%**;
- the remaining body of `Market.advance`: 33 / **3.52%**;
- `BaseLocation.advanceEvenIfPaused`: 31 / **3.31%**;
- `BaseLocation.advance`: 22 / **2.35%**; and
- `Economy.advanceMarketConditionsWhenPaused`: 19 / **2.03%**.

Filtering the commodity memo finds 95 stacks. Eighty-five stop in the compiled exact memo wrapper;
the other ten are legitimate delegated `MutableStat.recompute` work. Filtering temporary-stat
advancement finds 43 stacks, all leaf work called immediately by `Market.advance`. These samples
were collected without the high-frequency market timer, so they confirm that the residual
per-commodity machinery is a real target rather than timer overhead.

The safe boundary remains narrower than an entire market. `MutableStatWithTempMods.getMods()`
exposes a mutable map, `MutableStat.modified` is public, and mods may replace backing stat objects.
The existing memo intentionally detects those changes. A next candidate may reduce virtual calls,
telemetry writes, and empty temporary-stat advancement, but it must retain exact object, dirty-bit,
float-bit, map-entry, description, and econ-unit validation.

The bounded record is
[`data/2026-08-27-sampled-paused-unpaused-profile.json`](data/2026-08-27-sampled-paused-unpaused-profile.json).
The raw JFR remains a disposable local artifact and is not committed.
