# Stellar Networks refreshes one remote market on every paused campaign frame

## Finding

The campaign slice of `magiclib-notification-v1-20260804-230724` contains 1,500 main-thread
execution samples. Stellar Networks' `MarketUpdater.advance` and `MarketProvider.updateMarket` are
present on 92 of them (**6.13%**). `CargoData.sort` is present on 78 samples, primarily underneath
that refresh path.

This is not a redundant sort that can safely be removed. The sampled calls enter each remote
market's submarket plugin, rebuild cargo, synchronize mothballed fleets, and recompute ship stats.
Skipping the inner sort would skip observable game work and risk stale markets or ships.

The archived upstream source instead exposes a safe outer boundary. While campaign time is paused,
`MarketUpdater.advance` selects one random market from `EconomyAPI.getMarketsCopy()` on every frame,
skips the market currently open by the player, and otherwise calls `MarketProvider.updateMarket`.
Game time cannot advance while this happens, so a market refreshed earlier in the same paused
interval does not need another background refresh on the next frame.

Upstream source:
<https://github.com/jaghaimo/stelnet/blob/master/src/stelnet/board/query/MarketUpdater.java>

## Exact adapter

`StelnetMarketUpdaterPlan` supports the installed Stellar Networks 3.3.0 archive only:

- `stelnet.jar` SHA-256
  `3a0fcb88c9652de3f65e051d1eb0fb84020c566a2c18c0b03426c204e2003513`;
- `stelnet/board/query/MarketUpdater.class` SHA-256
  `1f7aafb86365e1d28ae61051d0df896b1283d8ef2a9eb0e29e29567fe5bf14e7`;
- Java 17 class version and exact `advance`, picker, listener, call, branch, and return shapes;
- the normal target-archive and class-loader gates in `AdapterTargetRegistry`.

The adapter preserves the original random picker under a private Preflight name. At the start of a
paused interval, the runtime snapshots and shuffles the current market list. It then serves each
market at most once. Later paused frames return before the picker and full market refresh once the
pass is exhausted. Unpausing discards the queue, so the next pause starts a fresh pass.

The queue is also invalidated on registration, player-market close, and player-market transaction.
It deliberately does **not** invalidate on `reportPlayerOpenedMarketAndCargoUpdated`, because
`MarketProvider.updateMarket` emits that callback after every background refresh; invalidating there
would restart the queue on every frame and defeat the boundary. A queue/reflection/runtime failure
delegates to the preserved original picker. Fatal VM errors are never swallowed.

Offline coverage proves each market is served once before exhaustion, invalidation and unpausing
start fresh passes, a snapshot failure selects the original fallback, wrong identities and second
transforms decline, and the exact installed archive contains the expected wrapper, preserved picker,
pause observation, exhaustion gate, and three invalidation sites. The opt-in installed-archive test
passed, followed by full `mvn verify` (core 195; CLI unit 371; integration 38 with one expected skip;
synthetic 22 with one expected skip).

## Live result

`stelnet-paused-refresh-v1-20260804-232052` loaded the representative campaign, crossed paused and
unpaused UI boundaries repeatedly, and exited normally. Adapter health was `ACTIVE`: 20 loaded exact
transformations, zero declines, and zero contained failures. Stellar Networks telemetry reported:

- 12 paused intervals;
- 2,232 markets queued and 1,320 markets served;
- 267 paused frames stopped after queue exhaustion;
- four reviewed invalidations;
- zero delegation and zero runtime failures.

The comparable campaign slice contains 1,449 main-thread execution samples. Stellar Networks is
present on 66 (**4.55%**), down from 92/1,500 (**6.13%**) in the preceding interactive recording.
That is a 25.8% reduction in sample share. The per-second timeline also contains repeated multi-second
stretches with zero Stellar Networks samples after earlier refresh bursts, matching the exhaustion
counters rather than moving the work to another stack.

This pilot was intentionally interaction-heavy: twelve short paused intervals repeatedly created
fresh passes, so it stresses reset correctness and is less favorable than leaving one screen open.
The two recordings have different UI activity and do not support a frame-time or universal speedup
claim. They do prove that the exact expensive call chain is bounded, becomes idle within a paused
interval, resets on the reviewed boundaries, and fails open.
