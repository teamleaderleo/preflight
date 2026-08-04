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

## Clean-stat fast path

That live recording also showed why the first memo did not remove the entire stack. Even a 98.78%
hit called `getCombinedTradeModQuantity`, which in turn called three `MutableStat.getModifiedValue`
methods; the wrapper also called it once for `available`. The quantity method alone remained the
leaf in 30/580 campaign samples (5.17%), split between memo validation and the much rarer but more
expensive vanilla delegations.

The refined hit path exact-gates a companion transformation of the shipped `MutableStat` from
`starfarer.api.jar`. It adds one public final synthetic accessor that only reads the existing private
transient `needsRecompute` flag. A valid commodity entry now requires the same four backing stat
objects, all four flags clean, and exact float-bit matches against their public authoritative
`modified` values. It still checks the `eMod` object, value, description reference, and the relevant
econ unit. Hits therefore skip all four getters and the combined-quantity arithmetic. Dirty stats,
direct public aggregate writes, backing-object replacement, event-mod relabels, and econ-unit drift
all delegate and refresh the complete post-vanilla fingerprint.

Both classes, both containing archives, method shapes, Java version, source kind, path, and app
loader are pinned. If the accessor is absent despite those gates, the commodity wrapper catches the
linkage failure, disables the memo for the session, and continues through vanilla. Synthetic tests
cover both exact shapes and the linkage fail-open. The installed-class execution test uses the
game's real `MutableStatWithTempMods` for dirty mutations, direct `modified` writes, whole-stat
replacement, zero/non-zero quantities, and same-value description changes. Full `mvn verify` is
green. The following campaign pilot measures the residual stack directly.

### Fast-path live result

`~/.starsector-preflight/runs/commodity-clean-stat-v2-20260805-033607` completed normally with
adapter health `ACTIVE`, 28 transformations, no decline or contained failure, and no fatal log or
native JVM crash evidence. The refined memo handled 129,026,515 calls: 128,803,184 hits (99.8269%)
and 223,331 delegations (0.1731%). `fastValidationUnavailable` remained zero, proving the exact
`MutableStat` seam was linked and used throughout the session.

The state-separated recording contains 1,677 campaign main-thread samples.
`getCombinedTradeModQuantity` appears in zero of them, versus 31/580 in the v1 recording. The
targeted four-getter/quantity stack is therefore removed rather than merely diluted by the longer
run. The next exposed layer is the exact `eMod` identity check: `MutableStat.getFlatStatMod` is now
the leaf in 212/1,677 campaign samples (12.64%). That lookup cannot simply be omitted because
`getFlatMods()` exposes the mutable backing map; a safe next step must retain detection of direct
same-key replacement, removal/reinsertion, and description/value mutation.

## Exact map-entry fast path (v3, offline-green)

The next refinement retains the exact flat-mod backing map, its `eMod` entry node, and
`HashMap.modCount` after each vanilla delegation. On a prospective hit it requires the same map,
the same structural generation, and the same entry value identity before reading the cached
`StatMod`'s public value and description fields. This covers the mutation surface that makes a
blindly cached lookup unsafe:

- a same-key `put` updates the retained node and is caught by value identity even though Java does
  not increment `modCount`;
- removal/reinsertion and other structural edits change `modCount`;
- whole-map replacement is caught by map identity; and
- direct `StatMod.value` or `StatMod.desc` edits remain covered by the existing exact field checks.

The `HashMap.modCount` handle is capability-gated through `MethodHandles.privateLookupIn`.
Starsector's reviewed launcher already supplies `--add-opens java.base/java.util=ALL-UNNAMED`; a
launcher that does not simply receives a null snapshot and retains the exact `getFlatStatMod`
lookup. No weaker validation mode exists. The exact `MutableStat` rewrite adds a second read-only
synthetic accessor for the current private `flatMods` reference. Missing accessor linkage is caught
after vanilla has completed, disables the memo, and returns safely.

Focused tests pass in both closed-module fallback mode and the launcher's open-module mode. The
positive tests cover same-key replacement, direct entry replacement, removal/reinsertion, map
replacement, absent-entry insertion, and stable entries. The installed-class suite executes the
real Starsector classes in both modes, including all v2 mutation cases. Full `mvn verify` with the
installed core jar is green.

### Map-entry live result

`~/.starsector-preflight/runs/commodity-event-entry-v3-20260805-035020` completed normally with
adapter health `ACTIVE`, all 28 transformations applied, and no decline or contained failure. The
memo served 24,241,238 unchanged calls and delegated 223,219 calls. Snapshot capability was active:
all 223,219 post-vanilla states captured an entry snapshot, with zero unavailable captures and zero
accessor fallback. No snapshot invalidation was needed in this workload because the earlier dirty,
identity, float-bit, or description checks caught every observed change first; the map-generation
check remained armed for direct edits through the exposed map.

The state-separated recording contains 983 campaign and 1,010 combat main-thread samples.
`MutableStat.getFlatStatMod` fell from 212/1,677 campaign samples in v2 (12.64%) to 5/983 (0.51%).
All five remaining samples are under the preserved vanilla method during legitimate delegations;
the 24.2-million-call hit path no longer performs the lookup. `CommodityOnMarket.reapplyEventMod`
itself is now a compiled leaf in 67/983 samples (6.82%), so the next cost is the exact hit-path
validation rather than another hidden vanilla call. Campaign frame time for this mixed interactive
pilot was p50 16.8ms, p95 26.5ms, and p99 57.3ms; it is not an identical-workload frame-time A/B.
