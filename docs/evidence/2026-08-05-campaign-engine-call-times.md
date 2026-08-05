# Attribute campaign frames inside the vanilla engine

Date: 2026-08-05

Status: deeper location/economy pilot complete; market/fleet drill-down next

## Why another layer was necessary

The first live call-time pilot proved two real medium hitches: Nexerelin route spawn/despawn reached
35.155ms inside a 50.834ms frame, and diplomacy advance reached 36.253ms inside a 53.250ms frame.
The other four reviewed seams were smaller. Most frames over 100ms contained none of the six timed
calls, so optimizing those two isolated calls would not explain the general campaign tail.

The next exact owner is vanilla `CampaignEngine.advance(float, B)`. It directly invokes the major
managers, advances current and background locations, and owns both lists of engine-level
`EveryFrameScript` instances. Timing these call sites avoids the unsound practice of assigning a
silent frame interval to the nearest log message.

## Exact target and call shape

Installed `com/fs/starfarer/campaign/CampaignEngine.class` has SHA-256
`99cb7c6a7aa026ec3f2fe4439d66b7b8cd24e4068ca24ffae89eac7421cabf6d` in the reviewed
`starfarer_obf.jar`. Plan `campaign-engine-call-time-probe-v1` requires the exact class identity,
Java 17 class version, and method descriptor `(FLcom/fs/starfarer/util/super/B;)V`.

It also requires the complete reviewed invocation counts before transforming:

- one each for intel, campaign events, important people, persistent UI data, economy, factions,
  and campaign help;
- two memory advances;
- ten location/hyperspace advances across normal, paused, fast, current, and background paths; and
- two `EveryFrameScript.advance(float)` calls, one for persistent scripts and one for transient
  scripts.

Any changed hash, descriptor, count, or pre-existing runtime call declines the adapter instead of
guessing at a future patch.

## Runtime behavior

Every reviewed call stores its receiver and arguments in fresh locals, starts the timer, restores
the original operand stack, and invokes the original instruction. Normal and exceptional exits
both close the timer; exceptional exits rethrow the original `Throwable`. Runtime diagnostics
contain their own non-fatal failures and propagate VM-fatal errors.

Manager and location categories use fixed primitive counters. Engine-level scripts use a
session-scoped `ClassValue` to allocate one counter object per concrete class, then retain count,
total, average, maximum and threshold counts. The maximum call also retains its end epoch so it can
be joined to the frame probe's exact worst-frame timestamps. No class attribution or timing runs
unless `preflight.frameTimes=true` was explicitly requested.

## Verification

Runtime tests cover fixed-phase and concrete-script grouping. Shape tests pin all 21 reviewed call
sites and reject a missing call, disabled runtime, wrong identity, or a second transformation. The
exact installed `starfarer_obf.jar` transforms to 19 fixed-phase entries, two per-class script
entries, and exception-safe exits for every call. Full `mvn verify` passes.

## Live result

`campaign-engine-times-v1-20260805-073428` loaded the representative campaign, roamed through the
campaign and UI, entered combat, returned to the campaign, and exited normally. Adapter health was
`ACTIVE`: 40 exact transformations, zero declines, and zero contained failures. Memory pressure was
not a confounder: the macOS probe retained about 18GB / 74% available memory.

Campaign frame throughput was:

| slice | frames | average | median | 1% low | p95 | p99 | >100ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| all campaign | 5,474 | 52.76 FPS | 59.52 FPS | 15.06 FPS | 31.2ms | 66.4ms | 23 |
| first 30 seconds | 1,383 | 46.10 FPS | 58.48 FPS | 9.15 FPS | 44.5ms | 109.3ms | 17 |
| after 30 seconds | 4,091 | 55.47 FPS | 59.52 FPS | 20.45 FPS | 27.1ms | 48.9ms | 6 |

The user's repeated observation that campaign play is rough immediately after loading and then
smooths out is therefore measured directly, including throughput rather than only frame-time
percentiles.

The inclusive vanilla engine totals identify two dominant buckets:

- location and hyperspace advancement: 79,072 calls / **19,039.7ms**, maximum 163.94ms;
- economy advancement: 6,222 calls / **11,492.1ms**, maximum 50.95ms.

Those maxima are not log-gap guesses. The 163.94ms location call ends 70ms before the end of a
241.80ms retained campaign frame, and the 50.95ms economy call ends 10ms before the end of a
64.18ms frame.

The concrete engine-script list also exposed specific mod costs:

- Stellar Networks `stelnet.board.query.MarketUpdater`: **1,832.9ms** total and a 131.03ms maximum;
- vanilla `CoreScript`: 1,440.0ms total and a 52.46ms maximum;
- `data.plugins.qolp_clock`: 921.0ms total;
- Mnemonic Sensors: 432.1ms total;
- MagicLib paintjob runner: 383.5ms total;
- MagicLib bounty-board plugin: 264.6ms total and a 50.87ms maximum.

The Stellar Networks maximum ends 7ms before the end of a 143.26ms frame. The MagicLib bounty
maximum likewise ends 7ms before the end of a 66.43ms frame. During this run the operator also
observed a bounty notification/UI temporarily capturing left clicks outside its visible dialog.
The current probes never intercept input, and there was no adapter failure. The log repeatedly
rebuilt MagicLib bounty state and emitted LunaLib `CaptainsLog`-missing errors while that UI was
active, so this is retained as a mod UI defect/performance lead rather than attributed to Preflight.

## Deeper location/economy probe

The first live result makes optimizing one of the smaller isolated scripts premature: locations
and economy account for the largest known inclusive work. Probe
`campaign-location-economy-call-time-probe-v1` therefore adds no behavior change and only runs when
the existing frame-time option is explicit.

It exact-pins the installed `BaseLocation` and `Economy` classes. `BaseLocation` groups active
entity advancement, paused entity advancement, and location scripts by concrete runtime class.
`Economy` separately times `ReachEconomy.updateLocationMap`, `ReachEconomyStepper.nextFrame`, and
the complete per-market advance call site. Every normal and exceptional exit records or fails inertly
and rethrows the original exception. The `BaseLocation` instrumentation composes after the existing
entity-index rewrite because both exact targets share the class and touch disjoint methods.

Synthetic shape/runtime coverage and an exact installed-archive test pass, including composition
with the entity index.

## Deeper live result

`campaign-location-economy-v1-20260805-080636` loaded the same campaign, exercised ordinary
campaign play, and exited normally. Adapter health remained `ACTIVE`: 40 exact transformations,
zero declines, and zero contained failures.

The economy result is unusually decisive. During 11,352 economy calls:

- location-map maintenance used **342.0ms**;
- the reach-economy stepper used **1,471.5ms**; and
- 2,120,837 market advances used **15,109.8ms**.

Market advancement is therefore about 89% of the measured 16,994.1ms economy total. A market call
averaged only 7.12 microseconds, but each economy tick advanced about 187 markets. This is an
aggregate-throughput target, not one pathological long call: the largest market advance was only
12.20ms.

Active location entities were led by:

- vanilla `CampaignFleet`: 232,195 calls / **10,564.1ms**, maximum 101.84ms;
- vanilla `CampaignTerrain`: 659,766 calls / **4,494.5ms**;
- vanilla `CampaignPlanet`: 492,811 calls / **2,378.7ms**; and
- `CustomCampaignEntity`: 3,491,990 calls / **1,325.2ms**.

The many individually tiny asteroid, jump-point, and gravity-well calls made this attribution run
deliberately intrusive, so its FPS is not a regression result. The next probe must sample those
high-frequency paths and drill into only `Market.advance` and `CampaignFleet.advance`.

Location scripts also found two material spikes: vanilla `Battle.advance` reached 97.47ms, while
`data.campaign.procgen.GestaltSeededFleetManager` reached 39.71ms. They remain secondary to the
fleet and market totals.

The launch log contains 28 caught `NullPointerException` traces from Industrial Evolution while
vanilla `CodexDataV2` asks industries for demand/supply on a synthetic market. `MilitaryRelay`
assumes `getContainingLocation()` is non-null and `ArtilleryStation` assumes `getStarSystem()` is
non-null. These occur during title-screen Codex initialization, not inside the measured campaign
loop. They are retained as a separate exact compatibility-guard candidate rather than folded into
the performance work or hidden by a broad exception handler.

## Sampled market/fleet drill-down

Probe `campaign-market-fleet-call-time-probe-v1` exact-pins the installed vanilla `Market` and
`CampaignFleet` classes and refuses any changed owner hash, Java version, method descriptor, call
count, or second transformation. It preserves receivers and every argument in fresh locals around
the original calls; normal and exceptional exits close the timer and rethrow the original failure.

Market attribution separates condition plugins, submarket plugins, industries, memory, people,
commodity temporary stats, commodity event mods, and the market power stat. The high-frequency
market/plugin seams use deterministic 1-in-32 sampling and report both measured and estimated
totals. Fleet attribution separates AI by concrete class, commander/officers, base-entity work,
fleet stats, accidents, logistics, count updates, movement, member buffs, fleet view, and both
hullmod campaign callbacks. Fleet AI and ordinary fleet phases are measured on every call; only the
potentially much larger hullmod/member loops are sampled.

The preceding broad `BaseLocation` entity-class probe now also samples active and paused entity
calls 1-in-64 while retaining unsampled location-script timing. This removes the tens of millions
of `nanoTime` pairs that made the first attribution run intentionally non-representative.

Synthetic shape/runtime tests and exact installed-archive transformations pass for both new
owners. Full `mvn verify` passes. One short campaign run is the remaining live-linkage and
attribution gate before selecting an actual behavior optimization.
