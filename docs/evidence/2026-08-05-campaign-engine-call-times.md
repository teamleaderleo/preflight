# Attribute campaign frames inside the vanilla engine

Date: 2026-08-05

Status: market/fleet drill-down complete; all current exact maintenance shortcuts live-verified

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
owners. Full `mvn verify` passes.

## Market/fleet live result

`campaign-market-fleet-v1-20260805-082230` loaded the representative save, exercised campaign play,
and exited normally. Adapter health remained `ACTIVE`: 43 exact transformations, zero declines,
and zero contained failures. Campaign throughput was 49.80 average FPS, 58.14 median FPS, 12.77 FPS
1% low, 34.0ms p95, and 78.3ms p99 across 5,649 frames. The user-driven route differed from prior
runs, so these numbers validate the probe rather than form an A/B performance claim.

The unsampled enclosing timings retained the earlier result: 1,072,831 market advances consumed
9,924.5ms. The exact commodity memo served 120,810,713 hits and delegated 257,999 changed/first
states, with all entry-snapshot capabilities available. The drill-down counted 483,766,272
commodity-stat accesses and 120,941,568 event-mod accesses inside those market advances. Their
individual sampled means were about 43ns, near or below the cost of the timing operation itself;
therefore the reported extrapolated 8.14s and 3.71s are instrumentation-inflated and must not be
added to other totals. The call volumes are valid and explain why reducing even tiny validation
work at an outer exact boundary matters.

The reliable fleet totals were more directly actionable:

- `ModularFleetAI.advance`: 87,759 calls / **2,783.1ms**, maximum 83.19ms;
- inherited `BaseCampaignEntity.advance`: 132,549 calls / **1,461.8ms**, maximum 20.82ms;
- `CampaignFleetView.advance`: 37,589 calls / **772.3ms**, maximum 16.04ms; and
- fleet logistics: 132,549 calls / 273.7ms.

The sampled plugin rankings are leads, not speed claims. `niko_MPC_derelictEscort` contained a
20.52ms sampled condition call, Nexerelin's local-resource submarket averaged 13.5 microseconds,
`Boggled_Genelab` averaged 17.4 microseconds, and UAF's `SUSynchrotonFuelCalibrator` contained a
15.38ms sampled fleet callback. The sparse condition outlier in particular has only 82 samples and
must be reproduced before adapting mod behavior.

## First exact behavior optimization

Exact installed bytecode exposed two semantics-preserving redundancies below the fleet total:

- `BaseCampaignEntity.runScripts(float)` always constructs `new ArrayList(scripts)` even when the
  authoritative list is empty. The adapter returns before that allocation only when
  `List.isEmpty()` is true; non-empty lists enter a renamed byte-for-byte vanilla method, preserving
  its defensive snapshot and script behavior.
- `CampaignFleetView.advance(float)` obtains the same sorted-member snapshot twice in one method,
  with no intervening mutation; the second result is used only for `size()`. The adapter reuses the
  already-live first local snapshot.

Both owners are gated on the exact 0.98a-RC8 class and core-archive hashes, reviewed method shapes,
and Java 17 version. Changed classes decline. A runtime kill switch
`preflight.campaign.entityMaintenance.disabled=true` restores vanilla. Synthetic execution proves
empty lists return, non-empty lists still invoke the original script path, and a second transform
declines. The exact installed archive transforms both owners, and full `mvn verify` passes. A short
live campaign run applied the fleet-view shortcut and exited normally with ACTIVE health, but
telemetry caught the entity shortcut missing: the earlier entity-index target already transformed
`BaseCampaignEntity`, after which the transformer correctly returned. The production path now
composes the disjoint id-mutation and `runScripts` rewrites while the original exact source identity
is still available. An installed-archive test proves both hooks coexist, and full `mvn verify`
passes again.

The corrected `campaign-maintenance-v2-20260805-084353` run exited normally with ACTIVE health, 33
transformations, zero declines, and zero contained failures. Both maintenance hooks installed.
Across the campaign it observed **15,402,921 empty** script lists and only **286,218 non-empty**
lists: the new path avoided the defensive snapshot on 98.176% of 15,689,139 calls. This proves a
large allocation-volume reduction, not an FPS delta; a user-driven run is not a controlled A/B.

With the heavy market/fleet targets omitted, campaign throughput was 51.03 average FPS, 59.17
median, and 14.03 FPS 1% low over 5,574 frames. Warm-up remained visible: the first 30 seconds were
45.11 average / 10.80 FPS 1% low, while later play was 53.28 average / 17.01 FPS 1% low. The run also
revealed that filtering the location/economy target did not disable its composition behind the
entity-index target. The diagnostic filter now resets all four campaign timing runtimes when their
plan IDs are omitted, so future clean FPS passes cannot accidentally retain a composed timer.

## Empty market snapshots

The exact `Market.advance(float)` bytecode exposed another allocation-only redundancy at the outer
boundary of the 483.77-million commodity-stat calls. Every market advance constructs two defensive
copies before iterating: `new ArrayList(getConditions())` and `new ArrayList(industries)`. The copy
is necessary when either list is non-empty because a condition or industry callback may mutate its
authoritative list. It has no semantic purpose when the authoritative list is empty.

The first candidate replaced both exact constructor/iterator sequences with a runtime helper.
Empty lists returned Java's shared empty iterator; non-empty lists still returned an iterator over
`new ArrayList(values)`. The shipped method uses these iterators only through `hasNext()` and
`next()`, never `remove()`, so the empty branch did not expose a different mutation contract.
Synthetic execution proved the non-empty iterator remained isolated if its source list changed
after creation.

This third shortcut is pinned to the same exact installed `Market` class and core archive as the
existing attribution probe. Production target ordering composes maintenance first and the opt-in
market timer second while retaining the original source identity. Changed hashes, bytecode shapes,
Java versions, and second transforms decline; the existing
`preflight.campaign.entityMaintenance.disabled=true` switch restores vanilla.

Full `mvn verify` passes: core 195, CLI unit 375, failsafe 38 with one expected skip, and synthetic
22 with one expected skip.

The clean `campaign-market-snapshots-v1-20260805-090403` live gate exited normally with ACTIVE
health, 34 transformations, zero declines, and zero contained failures. All four heavy campaign
timing plans were actually disabled. Across 1,368,227 market advances per list:

- conditions were empty only **416 times / 0.0304%**;
- industries were empty **205,888 times / 15.0478%**.

That evidence rejects an *empty-only* condition branch: checking 1.368 million calls merely to avoid
416 snapshots is the wrong trade. Inspecting `ArrayList(Collection)` exposed a stronger safe form,
however. Vanilla materializes a stable source array, wraps it in an `ArrayList`, and then allocates
the wrapper's iterator. Neither loop exposes the private wrapper or calls `Iterator.remove()`.
The final helper therefore snapshots with `values.toArray()` and traverses it with a tiny private
array iterator. Non-empty lists retain the same stable pre-callback contents with two objects instead
of three; empty lists use the shared empty iterator with no allocation.

The final adapter applies this compact snapshot to conditions and industries. On this route the
2,530,150 non-empty list traversals each avoid their unused `ArrayList`, while 206,304 empty
traversals avoid the array, wrapper, and iterator. That is about **3,149,062 avoided heap objects**.
The exact installed archive test proves both maintenance helpers compose with all eight ordinary and
three class-grouped timing entries. Cross-frame snapshot caching was explicitly rejected because
`Market.getIndustries()` exposes the mutable backing list directly to mods. This is a measured
call-volume-derived allocation reduction, not an FPS claim; the compact iterator itself remains
launch-free verified and the operator-driven route is not a controlled A/B.

## Disabled fleet-AI profiler labels

The market/fleet drill-down measured 87,759 calls and 2,783.1ms in
`ModularFleetAI.advance(float)`. Exact installed bytecode exposes an unconditional dynamic label
inside that path: every ability advance builds `"Ability [" + ability.getId() + "]"` with a new
`StringBuilder`, then passes it to `com.fs.profiler.Profiler.new(String)`. The shipped profiler is
disabled by default, and its begin method returns immediately while disabled, so the dynamic string
and builder have no observable consumer in ordinary play.

Plan `vanilla-fleet-ai-profiler-label-v1` replaces only that exact allocation expression with the
interned constant `"Ability"` while the profiler is off. A second exact transform publishes the
state written by the profiler's real `o00000(boolean)` toggle. When profiling is enabled, the
original bytecode still obtains the ability id and constructs the full label. The shortcut also
requires both owners to have installed successfully; a partial install, changed class or archive,
wrong Java version, second transform, or runtime kill switch delegates to the vanilla expression.
The kill switch is `preflight.campaign.fleetAiProfiler.disabled=true`.

The two reviewed class identities are SHA-256
`71117478e53743a6950b0062409d51b2194f6cb8a2588d7e1388c365752fdb13` for
`ModularFleetAI` in `starfarer_obf.jar` and
`1aa03bca3fc5f39fe3bd7e8c1070be7bf7823336e1e6090916c3c5fcd44a04cf` for `Profiler` in
`fs.common_obf.jar`; their archive hashes are pinned separately. Executable synthetic coverage
proves disabled, enabled, disabled-again, partial-install, kill-switch, and second-transform
behavior. Both real installed classes transform under their exact archives, and full `mvn verify`
passes. The first live attempt matched both exact targets but correctly retained vanilla because the
separate plan-availability registry entry was missing. That entry and a regression test covering
both production targets are now present. The clean combined gate below installed both owners and
avoided 100,354 dynamic labels with zero delegation while the profiler remained disabled.

The same attempted gate did complete the compact market iterator's live verification. It exited
normally with zero contained failures and exercised 2,840,164 condition snapshots plus 2,840,164
industry snapshots. Conditions were empty 860 times; industries were empty 441,318 times. The three
market-maintenance hooks all installed. The run's PARTIAL health was caused solely by the two
fleet-profiler targets retained through the missing availability entry, not by the market adapter.

## Empty memory maintenance

The state-separated allocation samples also expose a high-frequency empty-collection cost in
`com.fs.starfarer.campaign.rules.Memory.advance(float)`. In the pre-maintenance
`commodity-event-entry-v3-20260805-035020` recording it was the allocation leaf in 50 campaign
samples with about 114.3MB of sampled allocation weight: 106.9MB attributed to `ArrayList$Itr`,
6.3MB to `LinkedHashMap$LinkedKeyIterator`, and 1.0MB to the map's values view. Sampling weight is
statistical evidence, not a byte-for-byte heap census. The array-list iterators and values view are
the targeted unconditional allocations. The linked-key iterator occurs inside an active nested
requirement-set scan and is deliberately retained.

Exact installed bytecode always asks the private expiry list for an iterator and the private
requirement map for `values().iterator()`. It does this even when either collection is empty. The
new guards run after vanilla's restoration, pause, clock, and day-conversion work. An empty expiry
list jumps directly to the requirement gate; an empty requirement map returns. Non-empty lists and
maps enter their unchanged original loops. In particular, expiry decrement, `unset`, and
`Iterator.remove` behavior remain untouched, as does the complete requirement scan/removal path.

This fourth maintenance target pins `Memory.class` SHA-256
`48811db41f31f2bafdeaf73e2f98f864a055efa69dfd8442400042ab967b77d3` and the reviewed core archive,
method, Java version, source, and loader. Changed or second-transformed code declines, and
`preflight.campaign.entityMaintenance.disabled=true` restores vanilla together with the other
maintenance shortcuts. Synthetic execution covers empty and non-empty collections; the real
installed class transforms at both exact iterator sites. Full `mvn verify` passes. The clean
combined gate below exercised both branches at campaign scale; no FPS delta or byte-exact total
allocation claim is made.

## Restored memory-ID traversal

The later `commodity-direct-key-v4-20260805-111253` allocation profile exposed a separate,
one-shot cost in `Memory.replaceIdsWithEntities(LinkedHashMap)` while loading a campaign. It was a
pure execution leaf in 24/1,887 campaign samples (1.27%) and carried 23.49MB of sampled allocation
weight across ten allocation events. The largest classified pieces were about 8.0MB each for the
stable key-snapshot object array and a primitive array, plus about 2.0MB each for `ArrayList$Itr`
and regex compilation and about 1.75MB each for the `LinkedHashMap` key view and `ArrayList`.
Sampling weight is not a literal heap census.

Exact installed bytecode makes `new ArrayList(map.keySet()).iterator()` because the restoration loop
can remove and replace entries while traversing. It also checks `startsWith("enRef_")` before
`replaceFirst("enRef_", "")`, and likewise checks `startsWith("mRef_")` before
`replaceFirst("mRef_", "")`. The stable snapshot is semantically required, but its `ArrayList`
wrapper is not: an object-array snapshot plus a private iterator preserves the same pre-mutation key
sequence. After the proven prefix tests, the two literal regex operations are exactly `substring(6)`
and `substring(5)`.

The exact `Memory` adapter now performs those three substitutions. It retains the source snapshot,
entry order, all entity/market lookups, every map mutation, and vanilla behavior for empty and
non-empty inputs. It additionally reports empty and non-empty restoration traversals. The rewrite
requires the current class hash, exact method descriptor, one exact snapshot-construction shape,
and exactly the two reviewed literal replacement sites; any drift retains vanilla. Synthetic
transformed execution and mutation-during-traversal coverage, the exact installed-class transform,
and full `mvn verify` pass. This targets campaign-load latency and transient allocation, not
steady-state FPS.

## Compact paused-condition snapshots

`Economy.advanceMarketConditionsWhenPaused(float)` is another measured allocation leaf. The same
campaign recording contains 30 samples / about 70.2MB of sampled weight in that method. Bytecode
indices separate the sites: the per-market `new ArrayList(market.getConditions())` wrapper accounts
for nine samples / 25.2MB, its necessary stable source array accounts for 21 / 45.0MB, and the outer
market-list snapshot accounts for one further 2.1MB sample outside those 30 grouped events.

The paused-condition loop never exposes the wrapper and uses its iterator only through `next()` and
`hasNext()`. It now shares the already-live compact snapshot implementation: `toArray()` retains the
stable pre-callback condition contents, while the private array iterator omits the unused
`ArrayList`. The source array remains by design because a paused condition callback can mutate the
market's authoritative list. The outer `getMarketsCopy()` also remains untouched because it is a
public virtual method and bypassing it would change subclass dispatch semantics.

The adapter pins `Economy.class` SHA-256
`e3c66eca6a70cdfb17b298f45b020994146a1c29cd64422401dbdf11107cb529`, the core archive, Java version,
method, exact constructor/producer/iterator sequence, source, and loader. It composes with the
opt-in economy attribution rewrite on the same owner while the original identity is available.
Synthetic shape/stable-snapshot coverage, exact installed transformation, exact composition, and
full `mvn verify` pass. Telemetry separates paused condition snapshots from ordinary market
snapshots.

## Combined maintenance live gate

`campaign-maintenance-v3-20260805-102924` loaded the representative save, exercised ordinary
campaign and paused UI state, and exited normally. Adapter health was `ACTIVE`: all 40 reviewed
transformations applied, with zero declines, unavailable plans, or contained failures. The exact
new owners all installed and produced the following counters:

- memory expiration lists: **4,526,048 empty** / 343,913 non-empty;
- memory requirement maps: **4,604,109 empty** / 265,852 non-empty;
- paused market-condition snapshots: **270,072 non-empty**;
- fleet-AI profiler labels: **100,354 avoided** / zero delegated, with profiling disabled.

The previously live-gated maintenance paths also remained active: 4,215,903 empty entity-script
lists skipped their defensive copy, while ordinary market traversal recorded 329,336 condition and
329,336 industry snapshots. The run validates exact installation, substantial use, fail-closed
plumbing, and normal campaign exit. It is not a controlled A/B, so its FPS distribution is retained
as diagnostic telemetry rather than attributed to these allocation removals.

## Compact active and paused location snapshots

Exact installed `BaseLocation.advanceEvenIfPaused(float, B)` bytecode creates two defensive
`ArrayList` copies on every call. The first snapshots the location's campaign entities once and
then obtains two iterators from the same copy: the first updates indicators and the second performs
light-source and paused advancement work. The second snapshots the location-script list for one
pass. Callbacks can mutate both authoritative collections, so both source arrays and the separation
between the entity and script snapshots must remain.

The exact adapter now stores each `List.toArray()` result directly. It creates two private cursors
over the one entity array and one cursor over the script array, preserving the original snapshot
moment, order, null handling, two-pass reuse, and callback-mutation isolation while omitting both
unused `ArrayList` wrappers. Empty sources use a shared empty array and iterator. The three vanilla
loops use only `next()` and `hasNext()`; none exposes the old wrapper or calls `remove()`.

The same exact class's ordinary `advance(float, B)` method contains three more copies with the same
safe boundary. It snapshots campaign entities for the main advancement loop, location tokens for
orbit updates, and—inside eligible-fleet encounter processing—the base-entity list for a proximity
scan. Each copy is traversed exactly once through `next()` and `hasNext()`. The adapter likewise
keeps all three source arrays and callback boundaries while omitting their private wrappers. The
conditional encounter snapshot remains conditional; no collection is cached across calls or
frames.

This shares the already-pinned `BaseLocation.class` SHA-256
`ab16080b8c40d8f61d522089f3c3696fe3b7c8d8f8b287f9c12a47fa449bae24` and core archive identity.
Production composition applies the entity index, this maintenance rewrite, and then the optional
location timer while the original exact identity is still available. Changed owners, methods,
constructor/producer/local/iterator shapes, Java versions, or second transforms decline; the common
`preflight.campaign.entityMaintenance.disabled=true` switch restores vanilla.

Synthetic execution proves one stable paused-entity array supports two independent passes after the
source list changes and that the empty script path is allocation-free. The exact installed class
transforms to two paused snapshot captures with three cursors and three active captures with three
cursors, with zero remaining collection-copy constructors in either method. The entity-index wrapper
and all three optional location timers coexist in the transformed class. The focused installed-class
test and full `mvn verify` pass. Telemetry separately measures empty/non-empty active entities,
location tokens, engagement entities, paused entities, and paused scripts.

`location-snapshots-v2-20260805-104410` then loaded the representative campaign, exercised active
and paused campaign state, and exited normally. Adapter health was `ACTIVE`: 37 reviewed transforms
loaded and applied, with zero unavailable plans, declines, or contained failures. Both location
snapshot hooks installed. The five capture kinds reported:

- paused entities: 0 empty / **25,529 non-empty**;
- paused scripts: **16,766 empty** / 8,763 non-empty;
- active entities: 0 empty / **17,820 non-empty**;
- active location tokens: **12,495 empty** / 5,325 non-empty; and
- conditional engagement entities: 0 empty / **902 non-empty**.

Every one of the 87,600 captures avoids its private wrapper. Under the shipped `ArrayList`
implementation, the 29,261 empty script/token captures also avoid a source array and iterator, for
about **146,122 avoided heap objects** on this short route. This is a call-volume-derived allocation
count, not an FPS claim; the operator-driven run is not a controlled A/B.
