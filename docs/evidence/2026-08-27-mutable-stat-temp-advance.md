# Temporary-stat advance direct base path

**Status:** accepted with limit; exact structural, installed-class execution, Java 17, and live campaign checks passed

The settled unpaused campaign profile sampled
`MutableStatWithTempMods.advance` 28 times in 531 campaign stacks, or 5.27%. Every sample was leaf
work called immediately by `Market.advance`; the settled paused interval contained no sample from
the method. Starsector calls it once for market power and four times per commodity on each active
market pass.

The reviewed base implementation first reads its private `LinkedHashMap`, then calls its virtual
lazy getter twice and dispatches `isEmpty` and `values` through `Map`. The getter returns that same
field for the base class. This is repeated abstraction work around the actual temporary-mod
countdown.

## Exact boundary

The retained transform keeps the original method under a private adapter name and adds a replacement
with two paths:

- an exact base-class receiver reads the existing `LinkedHashMap` once, uses its concrete
  `isEmpty` and `values` methods, and performs the same iterator countdown, removal, and `unmodify`;
- a subclass receiver delegates to the byte-for-byte retained original, preserving an overridden
  `getMods()` implementation.

The base path preserves iteration order, float subtraction and comparison, iterator removal timing,
source IDs, `unmodify` calls, and concurrent-modification behavior. It adds no field, cache,
serialization data, or persistent mutation. It therefore cannot enter a save or change save format.

Admission requires the exact class SHA-256
`90690b6d4e6c4081990f3e545a9402c7120b659f0eb504218ec3c58da2c65a9e`, exact
`starfarer.api.jar` SHA-256
`6ac6c78c6116946d487376426340d019938f986ceae1391ae1fa599e890e3185`, Java 17 class version,
app loader and source, private map field, and every reviewed getter, map, iterator, field, and
`unmodify` use. Identity or shape drift, or a second rewrite, preserves the original bytecode. The
independent kill switch is
`PREFLIGHT_DISABLE_ADAPTER_PLANS=mutable-stat-temp-advance-v1`.

## Verification

The installed-archive test transforms Starsector's exact class and loads it with the real API JAR.
An empty stat remains unchanged. A two-day temporary flat mod remains present after one day,
expires after the deadline, is removed, calls the original unmodify behavior, and restores the
base modified value. A changed instruction shape declines. Structural assertions prove the base
path contains no `getMods` call, uses the one existing map field, and retains an original-method
fallback. Cache-replay tests also prove installed-target telemetry remains correct when transformed
bytecode is served from Preflight's transformation cache.

Final Java 17 `./mvnw verify` passed: 365 core tests, 54 CLI integration tests with three skipped,
22 synthetic tests with one skipped, and the complete agent suite. Focused installed-archive and
cache-replay tests also passed for Mnemonic Sensors, RAT, and the temporary-stat plan.

## Live result

One Preflight-only `campaign-sample-paused-unpaused.json` run completed every semantic step in one
owned Starsector process. It observed the initial paused save without toggling it, collected the
settled paused window, used Starsector's mapped pause control once, collected the settled unpaused
window, and stopped the exact process. Runtime telemetry reported 59 applied transforms, zero
contained failures, and one installed temporary-stat target.

The candidate run sampled `MutableStatWithTempMods.advance` 15 times in 676 unpaused campaign
stacks, or 2.22%, versus 28/531 (5.27%) in the earlier comparable trace. That is a 3.05 percentage
point and roughly 58% relative reduction in sampled share. Differing campaign evolution makes this
directional rather than a precise CPU delta. No candidate-window allocation sample appeared under
the method.

Frame pacing did not improve in this non-lockstep comparison. The candidate unpaused window
recorded 49.80 average FPS, 12.48 FPS 1% low, and 80.1 ms p99, versus 52.27, 14.18, and 70.5 ms in
the earlier run. The paused windows remained close. The retained claim is therefore limited to the
exact semantic simplification, successful live compatibility, and directional reduction of the
targeted CPU category; it does not claim an FPS uplift.

The bounded machine-readable record is
[`data/2026-08-27-mutable-stat-temp-advance.json`](data/2026-08-27-mutable-stat-temp-advance.json).
The raw JFR remains a disposable local artifact and is not committed.
