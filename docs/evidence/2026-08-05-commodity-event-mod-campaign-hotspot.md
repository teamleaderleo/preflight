# Commodity event-mod campaign memo — 2026-08-05

## Observation

The state-separated analysis of
`retreat-ship-interpreted-v1-20260805-005857/startup.jfr` classified 781 campaign and 1,023 combat
main-thread execution samples. `CommodityOnMarket.reapplyEventMod` appeared in 84 campaign samples
(10.76%) and no combat samples. Its sampled leaf work was 47 quantity calculations, 32
`MutableStat.unmodifyFlat` calls, and five `MutableStat.getModifiedValue` calls. Every stack was
called by `Market.advance`.

The reusable analysis command is:

```bash
python3 scripts/starsector_gameplay_hotspots.py \
  ~/.starsector-preflight/runs/retreat-ship-interpreted-v1-20260805-005857/startup.jfr \
  --contains CommodityOnMarket.reapplyEventMod
```

## Exact vanilla behavior

The reviewed Starsector 0.98a-RC8 `CommodityOnMarket.class` SHA-256 is
`0d4157d29532ef969b0d61a52783a4fc3846c758d73409141915c2807e3c83e4`; its containing
`starfarer_obf.jar` SHA-256 is
`a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149`.

For every commodity on every market frame, vanilla advances three temporary trade stats and then
calls `reapplyEventMod`. That method computes their combined quantity, removes `available["eMod"]`,
derives a replacement from the available value and commodity econ unit, and writes the replacement.
`MutableStat.unmodifyFlat` and `modifyFlat` dirty the stat whenever the non-zero value changes, while
`getModifiedValue` recomputes and clears that dirty bit. Thus an unchanged market repeatedly removes,
recomputes, and restores the same value.

## Rewrite and boundaries

`CommodityEventModMemoPlan` is pinned to the exact class, archive, app loader, Java 17 class version,
five required methods, and the reviewed instruction shape. It renames and retains vanilla's method.
The wrapper fingerprints the only four values that determine the preserved method's result:

1. combined trade quantity;
2. current available modified value;
3. current `eMod` value; and
4. commodity econ unit when quantity is non-zero.

The first invocation always delegates. After vanilla returns, the wrapper stores the exact float bit
patterns in private transient fields on that commodity. It additionally guards the exact `eMod`
object and description references, so an external same-value replacement or relabel still delegates
and lets vanilla restore its object-level state. An exact match may return early; every changed bit
or reference delegates and replaces the post-vanilla fingerprint. Transient fields do not enter save
data, and a loaded save starts invalid. Class, method, archive, or loader drift declines the entire
transformation. The narrow runtime escape hatch is
`-Dpreflight.campaign.eventModMemo.disabled=true`.

## Offline validation

- Synthetic shape tests prove exact-hash and reviewed-body gating, one-time transformation, original
  retention, transient fields, explicit runtime gating, and telemetry.
- The installed-archive test transforms the real class, links it against Starsector's installed jars,
  and executes both zero- and non-zero-quantity paths against the game's real
  `MutableStatWithTempMods`. Consecutive unchanged calls hit; changes to `available` and trade
  quantity delegate once and then hit.
- The installed test substitutes only a minimal commodity-spec carrier because an unrelated vanilla
  dependency contains the illegal field name `new.super`, which a stock verifying JVM refuses to
  reflect over. The transformed class and real mutable-stat implementation remain the subjects of
  the execution test.

## Live validation

The mixed campaign/combat run
`~/.starsector-preflight/runs/ship-cast-sites-interpreted-v1-20260805-031646` completed normally with
adapter health `ACTIVE`, 28 transformations, and no decline or contained failure. The memo handled
16,167,426 calls: 15,970,331 exact unchanged-state hits (98.78%) and 197,095 delegations (1.22%).
Those delegations include first observations and real market-state changes; no failure was reported.

State-separated sampling still found `reapplyEventMod` in 41/580 campaign samples (7.07%), down from
84/781 (10.76%) in the earlier non-identical campaign recording. The workloads are not controlled
enough to claim the 3.69-point difference as a precise speedup, but the live hit/delegate split
proves the intended redundant work exists at very high volume and that changed states continue to
reach vanilla. A controlled identical-save A/B would be needed for an exact frame-time attribution;
live compatibility and the optimization boundary are now validated.
