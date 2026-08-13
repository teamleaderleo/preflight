# The 20-second 0% plateau is vanilla SpecStore

- **Date:** 2026-08-02
- **Install:** Starsector 0.98a-RC8, current 83-mod profile, macOS 26
- **Mode:** direct launch, prepared-pixel adapter, prepared NPOT textures, no JFR
- **Status:** exact phase and per-loader boundaries measured; two plausible shortcuts measured and rejected

## Result

The loading screen is not idle at 0%. Starsector renders the bar at a fraction that rounds to zero,
then calls the first large vanilla `SpecStore` loader before it renders another useful progress
value. That call constructs the weapon, projectile, hull, fighter, campaign, rules, and related spec
registries for the enabled profile.

The first run with real progress milestones recorded:

| boundary | agent elapsed | since prior boundary |
| --- | ---: | ---: |
| `ResourceLoaderState.init` entered | 4.095s | — |
| loading screen ready | 4.138s | 0.043s |
| first progress render (0.0%) | 4.500s | 0.362s after screen ready |
| first 1% crossing | 25.201s | **20.701s** |
| 5% | 25.365s | 0.163s |
| 10% | 26.085s | 0.720s |
| 25% (actual render jumped to 32.1%) | 29.211s | 3.126s |
| 50% (actual render jumped to 52.8%) | 29.220s | 0.008s |
| 75% | 31.597s | 2.376s |
| 90% | 33.197s | 1.599s |
| 95% | 34.390s | 1.193s |
| 99% | 34.909s | 0.519s |
| resource loop complete | 34.941s | 0.031s |

The game's last rendered fraction was 99.7%; the existing `progress-100` marker names the exact
post-loop boundary, not a literal call with `1.0f`. The uneven jumps are the game's weighted work
units, not missing observations. The probe saw 2,614 calls to `renderProgress(float)`.

## Exact attribution of the plateau

The probe now marks the setup calls between `ResourceLoaderState.init` entry and its first progress
render. On the first run:

| setup block | duration |
| --- | ---: |
| loading sprites and drawing the screen | 43ms |
| resource-manifest construction | 9ms |
| core script discovery | 1ms |
| enabled-plugin class registration | <1ms |
| initial script compile/link step | 1ms |
| script-store prime | 13ms |
| remaining ship-name/string/title setup | 330ms |

None explains the reported plateau. The shipped bytecode calls
`SpecStore.ÓO0000(ResourceLoaderState)` immediately after the first zero-valued progress render and
does not render the next weighted unit until it returns.

Two later runs wrapped that exact call:

| run | SpecStore | first render to 1% |
| --- | ---: | ---: |
| vanilla two audio workers | **18.403s** | 19.623s |
| JSON-reader experiment, vanilla audio | **19.056s** | 20.261s |

The extra ~1.2s between `SpecStore` return and 1% is the next vanilla spec/sprite-queue step. The
large claim is stable: roughly eighteen to nineteen seconds are in `SpecStore`, and the visible
zero plateau is roughly twenty seconds.

An exact 83-mod run then timed all 41 top-level static loader calls inside the reviewed coordinator.
`SpecStore` took 19.818s wall-clock; the calls themselves accounted for 19.659s. The dominant work
was:

| top-level loader | duration | share of measured calls |
| --- | ---: | ---: |
| ship and fighter variants (`SpecStore.oO0000()`) | **4.145s** | 21.1% |
| weapon definitions (`WeaponSpecLoader.new()`) | **3.183s** | 16.2% |
| campaign rules (`Rules.super(...)`) | **3.031s** | 15.4% |
| projectile definitions (`WeaponSpecLoader.o00000()`) | **2.273s** | 11.6% |
| ship hull specs (`ShipHullSpecLoader.Ò00000()`) | **2.138s** | 10.9% |
| `SpecStore.oo0000(...)` | **1.087s** | 5.5% |

Those six calls account for 15.857s, or 80.7% of measured loader time. The first five are already
identifiable data domains and together account for 75.1%. This rules out a diffuse collection of
tiny loaders: variants, weapons, hulls, and rules are the useful preparation/cache targets.

The largest loader was then split around its exact repeated operations. In a second exact-profile
run, the 4.051s ship/fighter-variant loader contained:

| variant operation | calls | aggregate duration |
| --- | ---: | ---: |
| merged JSON lookup, overlay, and parse | 5,573 | **3.582s** |
| live `HullVariantSpec` construction | 5,550 | 221ms |
| file/directory enumeration | 229 | 108ms |
| module/default-variant post-pass | 1 | 58ms |
| registry insertion | 5,550 | 3ms |

Twenty-three parsed variants were skipped before construction by the game's duplicate/total-
conversion gates. The useful cache boundary is therefore now concrete: cache the ordered merged
JSON representation, then retain the game's cheap constructor, registry insertion, skip behavior,
and post-pass. Serializing mutable live `HullVariantSpec` instances would attack only 221ms while
assuming responsibility for transient hull links and later fixups.

The next exact-profile run split the 3.512s weapon-definition loader at the same boundary:

| weapon operation | calls | aggregate duration |
| --- | ---: | ---: |
| merged JSON lookup, overlay, and parse | 3,077 | **2.761s** |
| script registration | 987 | 14ms |
| file listing | 2 | 11ms |
| live registry insertion | 3,074 | 7ms |

Merged JSON therefore accounts for 78.6% of the weapon loader. The remaining roughly 719ms includes
vanilla object hydration and loop/logging overhead. As with variants, the safe shortcut is to cache
only the pure merged representation and leave duplicate gates, script registration, concrete weapon
construction, and registry mutation on the original thread in their original order.

A cold/warm exact-profile pair then consumed that boundary. The cold run captured 2,921 merged JSON
values after vanilla produced them and published one 2.2 MiB checksummed artifact only after the full
weapon loader returned normally. The warm run reconstructed a fresh `JSONObject` for every hit and
kept 156 paths on the original loader:

| boundary | cold learning | warm cache | change |
| --- | ---: | ---: | ---: |
| weapon-definition loader | 3.338s | **0.998s** | **-2.340s** |
| merged JSON operation | 2.516s | **0.365s** | **-2.151s** |
| script registration | 13ms | 19ms | noise |
| registry insertion | 8ms | 7ms | noise |

The narrow weapon dependency selector hashes the exact game JAR and every ordered `.wpn` provider
under `data/weapons/` and `data/shipsystems/wpn/`. It took 322ms on the warm run, making the measured
net launch improvement roughly 2.0 seconds. The identity names the artifact, so different mod
profiles coexist and any relevant content, provider-order, or game-version change selects a new
learning artifact instead of reusing stale data.

The adjacent projectile loader uses the same `WeaponSpecLoader` shape but a separate pair of methods
and data domains. A pinned probe split its 2.345s directional total as follows:

| projectile operation | calls | aggregate duration |
| --- | ---: | ---: |
| merged JSON lookup, overlay, and parse | 1,263 | **1.618s** |
| file listing | 2 | 9ms |
| live registry insertion | 1,260 | 9ms |
| script registration | 580 | 6ms |

Merged JSON accounts for 69.0% of the projectile loader, leaving about 703ms for object hydration,
branching, and loop/logging overhead. macOS reported no thermal or performance warning immediately
before and after this run, but the 31°C ambient and single sample make the absolute duration
directional rather than a benchmark claim. The subphase dominance is sufficient to justify the same
pure-representation cache boundary while retaining all live projectile construction and side effects.

A cold/warm exact-profile pair then validated that boundary in the shared class while the weapon
cache remained hot. The cold run captured 1,159 reusable values, kept 104 calls on vanilla, and
published an 853 KiB artifact only after the projectile loader returned normally. The warm run hit
all 1,159 prepared entries with the same 104 fallbacks:

| boundary | cold learning | warm cache | change |
| --- | ---: | ---: | ---: |
| projectile-definition loader | 2.349s | **1.004s** | **-1.345s** |
| merged JSON operation | 1.563s | **0.304s** | **-1.259s** |
| script registration | 9ms | 6ms | noise |
| registry insertion | 8ms | 8ms | noise |

The projectile dependency selector hashes the exact game JAR and every ordered `.proj` provider
under `data/weapons/proj/` and `data/shipsystems/proj/`. It took 219ms on the warm run, making the
directional net launch improvement roughly 1.1 seconds. Both warm telemetry blocks were active in
the same `WeaponSpecLoader` transformation: 2,921 weapon hits and 1,159 projectile hits, with zero
shadowed targets. macOS again reported no thermal or performance warning after both launches; the
31°C ambient still makes these single-run absolute totals directional.

The next exact probe found the same pure boundary inside the ship-hull loader:

| hull operation | calls | aggregate duration |
| --- | ---: | ---: |
| merged JSON lookup, overlay, and parse | 2,671 | **2.059s** |
| file listing | 1 | 9ms |
| live SpecStore dependency lookup | 17,200 | 12ms |
| live hull registry insertion | 2,670 | 4ms |

Merged JSON accounts for 81.0% of the 2.543s hull loader. The remaining roughly 459ms constructs
the concrete hull, sprite, engine, weapon-slot, shield, and related live objects. The 17,200
dependency lookups are numerous but collectively negligible, so replacing or indexing them would
add risk without meaningful startup value. A hull cache should therefore retain those lookups and
all live construction/registration while bypassing only the merged `.ship` JSON operation.

The strict-profile hull cache then exercised that boundary in two real direct launches. The cold
run captured 2,471 reusable merged values into a 5.7 MiB artifact and retained 200 vanilla
fallbacks. Those fallbacks are the core-game hull inputs for which capture did not produce a
reusable serialized value; the cache deliberately leaves them on the original call rather than
inventing a second representation. The warm run hit all 2,471 prepared entries:

| boundary | cold learning | warm cache | change |
| --- | ---: | ---: | ---: |
| ship-hull loader | 2.653s | **0.754s** | **-1.899s** |
| merged JSON operation | 2.130s | **0.344s** | **-1.786s** |
| live SpecStore lookups | 16ms | retained | — |
| live registry insertion | 5ms | retained | — |

The hull dependency selector hashes the exact game JAR and every ordered `.ship` provider under
`data/hulls/`; `.skin` and unrelated resources are intentionally excluded. Selection took 203ms on
the warm run, making the directional net launch improvement about **1.7 seconds**. The warm loader
still performed the original live construction, all SpecStore lookups, and all registry mutation.
There were zero shadowed targets. macOS reported no thermal or performance warning, but the 31°C
ambient still makes the absolute single-run timings directional.

The next exact probe split the campaign-rules loader. This probe raises its bounded subphase-label
capacity from 16 to 32 because the four earlier loader probes legitimately consume the first 16
categories; a regression test now ensures later labels remain visible.

| rules operation | calls | aggregate duration |
| --- | ---: | ---: |
| expression/token parsing | 62,340 | **1.575s** |
| merged `rules.csv` read, overlay, and parse | 1 | **830ms** |
| duplicate-ID linear scan | 21,059 | **774ms** |
| string regex replacement/splitting | 205,686 | 296ms |
| script class registration | 23,339 | 6ms |
| trigger-list insertion | 21,059 | 4ms |
| option allocation | 16,155 | 3ms |

The measured loader took 3.989s with the high-frequency probe enabled; an immediately preceding
lighter probe measured 3.508s, so the absolute total includes visible instrumentation overhead.
The ranking is still actionable. In particular, vanilla scans every previously registered rule
under the same trigger only to reject a duplicate ID, then performs the actual insertion in 4ms.
An exact `(trigger, ruleId)` set can preserve the same rejection and ordered registry mutation while
removing that quadratic scan. CSV preparation is the next pure cache boundary. Parsed expression
objects remain live game objects and should not be serialized without a stronger equivalence proof.

An exact-build-gated runtime replacement then validated the indexed duplicate check in a real direct
launch. It created one temporary set for the rules-loader invocation, checked all 21,059 ordered
registrations, observed zero duplicates, and released the set after the original loader completed.
The original trigger-list insertion remained in place, while the attributed linear-scan subphase
disappeared entirely:

| rules boundary | attributed baseline | indexed run | change |
| --- | ---: | ---: | ---: |
| complete rules loader | 3.989s | **3.428s** | **-561ms** |
| duplicate-ID linear scan | 774ms | **removed** | **-774ms gross** |
| expression/token parsing | 1.575s | 1.778s | +203ms run variance |

The net result is directionally consistent with removing the scan despite a slower expression phase
in the indexed sample. The adapter reported one completed load, 21,059 checks, zero duplicates, and
zero shadowed targets. macOS again recorded no thermal, performance, or CPU-power warning; at 31°C
ambient this remains a directional single-run comparison rather than a stable benchmark.

The next cold/warm pair exercised a strict-profile cache around the single merged `rules.csv`
operation. Its identity hashes the exact game JAR and all 44 ordered providers of
`data/campaign/rules.csv`; unrelated campaign CSV files are excluded. The cold run selected that
identity in 116ms, executed vanilla, captured one 12.3 MiB serialized array, and published it only
after the complete rules loader returned normally. The warm run selected the same artifact in
113ms and reconstructed a fresh `JSONArray` before retaining all live rule parsing, construction,
script registration, and ordered trigger insertion:

| rules boundary | cold learning | warm cache | change |
| --- | ---: | ---: | ---: |
| complete indexed rules loader | 3.325s | **2.306s** | **-1.019s** |
| merged CSV operation | 959ms | **166ms** | **-793ms** |
| expression/token parsing | 1.451s | 1.479s | noise |

After the warm selector cost, the directional net launch improvement from this cache is about
**680ms**. Telemetry reported one hit, zero misses, captures, or writes, all 21,059 duplicate-index
checks, zero duplicates, and zero shadowed targets. macOS again recorded no thermal, performance,
or CPU-power warning; the 31°C ambient still limits these figures to directional evidence.

### Inside the expression phase: two levers, not one

`rules-expression-parse` is the largest remaining rules subphase at 1.575s over 62,340 calls, and
until now it was one opaque number. The reviewed constructor does only two things that are not
field stores and list access: it calls `Misc.tokenize(String)` exactly once, and for a command
invocation it calls `getCommandClass`, which walks every declared rule-command package in order
with `Class.forName` and `newInstance` until one resolves, memoising the winner per name.

An offline census of the same 44 ordered `rules.csv` providers the cache identity already hashes
reproduces the game's own counts, which is what makes the rest of this section trustworthy:

| census | game |
| --- | --- |
| 44 rules.csv providers | 44 ordered providers |
| **21,059** merged rules | **21,059** duplicate-index checks and trigger insertions |
| 62,645 expression constructions | 62,340 measured constructions |

The 305-construction gap is the census's cruder handling of commented lines inside cells; it is
0.5% and does not move any conclusion below.

That corpus contains **31,816 distinct expression strings out of 62,645 — 49.2% are exact
repeats.** So a memo keyed by the expression string has a hard ceiling of about half the tokenizer,
and no more.

Replaying the exact corpus through the game's own `Misc.tokenize`, on the game's own JVM
(Zulu 17.0.10 x86_64 under Rosetta), sizes that ceiling directly:

| tokenizer over the 62,645-expression corpus | duration |
| --- | ---: |
| vanilla `Misc.tokenize` | **0.514s** |
| memoised, rebuilding fresh `Misc.Token` objects on every hit | **0.297s** |

Tokens carry public mutable fields, so no memo may ever hand out a shared `Token`; the measured
variant caches only the character scan's result and allocates fresh tokens per call, which is the
only shape that is safe without proving no mod writes to those fields.

**The tokenizer is therefore about a third of the expression phase, and memoising it is worth
roughly 0.2s.** That leaves roughly a second inside the constructor that is not tokenizing, and the
only other candidate is command-class resolution. The same profile declares **41 rule command
packages** across 28 `settings.json` providers and invokes **671 distinct command names**, so a
command whose package is declared late pays up to 40 failed `Class.forName` calls, each of which
scans the whole modded classpath. A standalone replay of exactly that walk resolves 665 of the 671
names in **7,022 `forName` attempts, against 665 when the winning package is already known** — a
10.5x reduction in classpath scans, and the shape a prepared `name -> winning package` map would
have.

That standalone replay took 5.7s versus 0.30s, but **those seconds are not a claim about the
game**: the harness builds its own `URLClassLoader` over 182 entries and pays cold jar-index
construction inside the timed region, and the game's mod loader is not that loader. The ratio is
the transferable part; the seconds are not.

So the split is measured on one side and only bounded on the other, and the next step is to measure
it in the game rather than argue about it. `RuleExpressionPhasePlan` pins the expression class
(`8f628d7f…`) and partitions its constructor into three exclusive subphases —
`rules-expression-tokenize`, `rules-expression-command-class`, and `rules-expression-residual`.
`StartupPhaseRuntime`'s subphase slot is flat rather than nested, so the plan switches labels
instead of nesting them: the residual opens at method entry, each reviewed call is bracketed, and
the residual reopens after. The three sum to the constructor.

One consequence has to be read carefully. The outer `rules-expression-parse` label is opened by
`RulesLoaderPhasePlan` at the `NEW` and is closed by this plan's first switch, so **with this probe
installed that label measures argument evaluation only, not construction.** The two probes are
readable together, not comparable, and the 1.575s baseline above is the number to compare the three
new labels against.

Applied offline to the installed class, the plan takes the constructor from 310 to 325
instructions, keeps the single `tokenize` call and both `getCommandClass` calls, emits the seven
label switches and one close in the expected order, and declines a second weave.

A real direct launch then measured the split, with all four earlier caches warm:

| expression subphase | calls | duration |
| --- | ---: | ---: |
| `rules-expression-tokenize` | 62,340 | **742ms** |
| `rules-expression-command-class` | 25,762 | **641ms** |
| `rules-expression-residual` | 150,442 | 48ms |
| `rules-expression-parse` (argument evaluation only, as predicted) | 62,340 | 23ms |

The constructor totals 1,431ms against the 1,575ms the single outer label used to report, and the
outer label collapsed to 23ms exactly as the design said it would. The complete rules loader took
2,293ms on this run, so **the expression constructor is 62% of the warm rules loader, and it is two
roughly equal halves.**

The census predicted 25,721 command invocations; the game performed 25,762, a 0.16% difference,
which is the third independent agreement between the offline model and the running game.

The command-class number is the more interesting one because of how few calls actually do work:
only the **671 distinct names** miss the static map, and the other 25,091 calls are map hits worth
nanoseconds. So 641ms is carried by 671 package walks — roughly **0.9ms each**, spent on failed
`Class.forName` calls against the modded classpath. That is what a prepared `name -> winning
package` map removes, and it is the shape the offline replay predicted.

Both halves are now worth building and neither is a serialization problem:

- the tokenizer wants an in-process memo of the character scan only, never a shared `Token`, with a
  measured ceiling near 40% of 742ms;
- command-class resolution wants a prepared map keyed by the game JAR, the ordered mod archives,
  and the declared package list, replaying `Class.forName` plus `newInstance` on the winning package
  only — which keeps every side effect vanilla has, including the discarded instance's static
  initialisation, and falls back to the full walk on any miss.

The run reported 15 registry targets, 9 exact matches, 9 transformations applied, zero declined and
zero shadowed.

### The tokenizer memo, built and measured

The first of those two halves is now in the agent. `RuleTokenCachePlan` replaces the constructor's
single `Misc.tokenize` call with `RuleTokenCacheRuntime.tokenize`, pushing two constants ahead of it:
a `MethodHandle` constant for `Misc.tokenize(String)` and a class constant for `Misc$Token`. That
shape matters more than it looks. The handle is resolved by the JVM at that call site exactly as the
`invokestatic` it replaces was, so the runtime always has vanilla to fall back to and never guesses
at a class loader, and a build that no longer offers that method fails to link there rather than
silently losing its tokenizer. The rewrite adds two instructions and no branches, so the method
keeps its original stack map and needs no extra locals.

### How this differs from the prepared-artifact caches

Worth stating explicitly, because the two shapes are easy to confuse. The variant, weapon,
projectile, hull, and rules-CSV caches all share one technique: spill the call's arguments into
fresh locals, ask the runtime for a prepared value, branch past vanilla on a hit, and capture after
vanilla on a miss. They all rewrite with `COMPUTE_FRAMES`, which is safe here because
`SafeClassWriter` answers every supertype question with `java/lang/Object` without loading a class.

The tokenizer memo does not use that technique, and the reason is the shape of the work rather than
any safety limit:

| | prepared-artifact caches | tokenizer memo |
| --- | --- | --- |
| calls per launch | 1 to a few thousand | 62,340 |
| payload | one merged CSV, thousands of merged JSON documents | a handful of tokens |
| where the repeats are | across launches | **within one launch** |
| what removes the work | a content-keyed artifact on disk | a map in the process |
| invalidation surface | game JAR, ordered providers, schema version | none |

Half the tokenizer's calls repeat inside a single launch, so an in-process map captures that half
with no artifact, no identity key, and nothing that can go stale. Persisting it was considered and
not built: it would reach the other 51%, but it would also mean deserializing 31,614 token shapes
and hashing a provider set at startup to earn a saving of the same order, and it would put a
staleness surface in front of a function that currently cannot be wrong.

Nothing about `COMPUTE_FRAMES` or branching was avoided for safety. A branch-free rewrite is simply
smaller for a call of this shape, and it leaves the original frames as narrow as vanilla wrote them
rather than widening them to `Object`.

The equivalence argument is narrow and checkable: `tokenize` reads only its argument, and every
token it emits is `new Misc$Token(String, TokenType)` with no later field write anywhere in the
method. The list is therefore fully determined by the ordered `(string, type)` pairs. Since
`Misc$Token` carries four public non-final fields and the caller mutates the list it receives, a hit
allocates a fresh `ArrayList` and a fresh token per element; only the character scan is skipped.

Two real launches, against the same warm profile as the split above:

| run | tokenize | rules loader | command-class (control) |
| --- | ---: | ---: | ---: |
| probe only | 742ms | 2,293ms | 641ms |
| memo, generic `invoke` | 597ms | 2,149ms | 675ms |
| memo, erased `invokeExact` | **578ms** | **2,098ms** | 626ms |

The command-class label is untouched by this change and moved 641 -> 675 -> 626ms across the three
runs, so roughly ±4% is this machine's run-to-run noise. The tokenizer's 742 -> 578ms is well
outside it, but these are still single runs and the honest figure is **about 150ms**.

The telemetry was byte-identical on both memo runs and is the fourth independent agreement with the
offline census:

| | census | runtime |
| --- | ---: | ---: |
| calls | 62,645 | 62,340 |
| distinct expressions | 31,816 | 31,614 |
| repeat rate | 49.2% | 49.3% |

`declined` was zero: every token shape the game produced was capturable. The memo holds 31,614
entries for the process lifetime and has no artifact, no identity key, and nothing to invalidate.

It does not reach the 42% the offline benchmark set as a ceiling. That benchmark memoised into a
plain `HashMap` and called the constructor directly; the agent pays a `ConcurrentHashMap` lookup, a
`ClassValue` lookup, and a `MethodHandle` call per hit. Erasing the constructor handle once so the
hot path can use `invokeExact` recovered 19ms of that, which is inside the noise band and is
reported here as such rather than as a win.

**Command-class resolution is now the larger half of the expression phase and the next thing to
build.**

### The prepared command-class map, and why it recovered a quarter of what was predicted

`getCommandClass(name)` disassembles to a memo check followed by a walk over every declared
`ruleCommandPackages` entry, calling `Class.forName(pkg + "." + name, false, loader)` and
`newInstance()` until one resolves, catching `Exception` and continuing. `RuleCommandClassCachePlan`
inserts a prepared answer at the branch target of the memo miss -- so it cannot run on the 25,091
calls a launch that never leave the map -- and reports every caught failure and every winner so a
cold run learns the map. A fourth insertion publishes it from the rules loader's return.

Reading the bytecode changed the design that issue #290 recorded. `forName` passes
`initialize = false`, but `newInstance()` initialises, so a package ahead of the winner holding a
same-named class that loads and then fails to instantiate has **already run that class's static
initialiser**, and shortcutting past it would skip that. Rather than assume this never happens, the
learning run records the kind of every caught failure and admits a name only when all of them were
`ClassNotFoundException`. On this profile `uncleanNames` was 0, so the assumption happened to hold --
but it is now a checked fact rather than an assumption, and a profile where it does not hold degrades
name by name instead of resolving the wrong class.

The mechanism does exactly what it was built to do. Cold run: 671 misses, 671 captures, one 20 KB
artifact. Warm run: **671 prepared, 671 hits, 0 misses, 0 disagreements, 0 writes**, and the declared
package list matched. The 671 is the fifth independent agreement with the offline census, which
predicted 671 distinct names. The game declares **47** packages, not the 41 the census counted, and
the identity builder sees 74 `settings.json` providers against the census's 28; the census
under-counted providers and the count of 41 above should be read as a floor.

| `rules-expression-command-class` | duration |
| --- | ---: |
| no cache (three earlier runs) | 641ms, 675ms, 626ms |
| cold, learning hooks installed | 653ms |
| warm, prepared map | **515ms** |
| warm, prepared map (second run) | **454ms** |

The rules loader moved with it: 2,098ms on the tokenizer-memo run, then 1,983ms and 1,909ms warm.
The honest figure is **about 165ms**, and the learning hooks cost nothing measurable.

**That is roughly a quarter of what the model predicted, and the model was wrong in an instructive
way.** The reasoning in #290 was that 641ms is 671 package walks at ~0.9ms each, so replacing 41
lookups with 1 should remove most of it. Removing 40 of 41 lookups removed 25%. So the failed
lookups were never where the time was: a lookup that misses is answered cheaply, while the one that
hits has to find, read, define, verify, and **initialise** a real mod class. About 485ms of the
~649ms is that work, it is vanilla's own, and this cache deliberately still performs it.

The offline replay's 10.5x reduction in `forName` attempts therefore transferred as a count and not
as a duration, exactly as the caveat recorded beside it said it might. That caveat was right for a
reason worth keeping: the replay built its own `URLClassLoader` and paid cold jar-index construction
inside the timed region, which inflates failures relative to successes.

### The launcher is now spending 1.6 seconds hashing before the JVM starts

Measuring the above surfaced something larger than the thing being measured. Every prepared-artifact
cache asks the CLI to hash its dependency profile before the game is launched, and those costs are
serial and additive:

| profile | identity build |
| --- | ---: |
| variant JSON | 590.0ms |
| weapon JSON | 375.8ms |
| projectile JSON | 207.5ms |
| hull JSON | 190.8ms |
| rule command classes | 170.5ms |
| rules CSV | 125.4ms |
| **total, before the JVM starts** | **1,612.6ms** |

The caches these unlock are worth several seconds, so the net is still strongly positive. But 1.6
seconds of the win is being handed back in the launcher, on every single launch, and until now nobody
had added it up.

For this cache specifically the arithmetic is stark: **170ms of hashing to save 165ms of loading.**
In isolation it is a wash. It is still worth keeping -- the in-game work is genuinely gone and the
artifact is 20 KB -- but the honest statement is that it only pays once the identity is cheap.

The fix is not subtle and it is not specific to this cache. Every one of these digests re-reads and
re-hashes files that have not changed: this one alone hashes 64.3 MB across 104 jars on every launch.
A per-file SHA-256 memo keyed by path, size, and modification time would take all six to near zero on
any launch where the install did not change, which is every launch except the one after a mod update.
Tracked as its own issue, because it is worth more than any single one of the caches it gates.

### The memo was the wrong fix, and the measurement says so

The paragraph above proposed a per-file digest memo. Measuring before building it showed that
hashing was never the dominant term, so the memo would have bought very little in exchange for the
one guarantee these identities exist to provide.

Attribution of the 1,612.6ms:

| | ms |
| --- | ---: |
| five redundant reads of the same 8 MB resource index | ~540 |
| hashing 12,797 files, serially | ~293 |
| 12,797 `toRealPath` containment calls | ~87 |
| per-provider digest work, plus JIT warm-up in a JVM that runs once and exits | remainder |

The duplication was the cost. Six caches each read the same index, each hashed the same game jar, and
each resolved every provider independently.

Three measurements decide the memo:

| | ms |
| --- | ---: |
| hash all 12,797 files, serially | 293 |
| the same hashing across 8 workers | 90 |
| bare `stat` on all 12,797 files -- the memo's floor | 25 |

So the memo is worth about **65ms** over parallel hashing. What it costs is the ability to notice a
mod update that changes a file's bytes without changing its length or timestamp -- which for the rule
command class cache is the exact failure mode its identity was built to catch, written down in that
builder's own class comment. 65ms does not buy that, and the memo was not built.

What was built instead is duplication removal only, with nothing weakened: read the index once, hash
the game jar once, memoise `toRealPath` per parent directory (12,797 providers live in 694
directories), and hash in parallel while returning results in request order.

| profile | before | after |
| --- | ---: | ---: |
| index read | ×6, inside the rows below | 124.3ms, once |
| variant JSON | 588.7ms | 144.6ms |
| weapon JSON | 330.2ms | 69.8ms |
| projectile JSON | 207.4ms | 31.7ms |
| hull JSON | 199.6ms | 62.0ms |
| rules CSV | 116.2ms | 3.0ms |
| rule command classes | 170.5ms | 16.2ms |
| **total, before the JVM starts** | **1,612.6ms** | **451.6ms** |

All six identities are byte-identical to the values recorded beforehand, checked against the real
install, and the launch that produced the "after" column reported `hit` for all six. The rule command
class arithmetic that read as a wash above now reads **16ms of hashing to save 165ms of loading**.

Order is the whole safety argument for hashing in parallel. Callers feed these digests into a
`MessageDigest` one after another, so a permuted result would silently change every identity and
orphan every artifact already on disk. The test checks the parallel result against a serial
reference rather than against a recorded constant, because a recorded constant would only prove the
two agreed on the day it was written.

The largest remaining item is the 124.3ms index read itself: 8 MB holding 61,691 providers, where
the time is object construction rather than I/O.

## The rest of this load

The first complete milestone run decomposed `ResourceLoaderState.init` as:

| block | duration |
| --- | ---: |
| first 0.0% render to first 1% | **20.701s** |
| 1% through resource-loop completion | 9.740s |
| wait for concurrent audio workers | 4.075s |
| graphics/script/final pre-callback setup | 0.132s |
| all serial mod `onApplicationLoad` callbacks | **10.517s** |
| **entry to return** | **45.603s** |

The callback total is still concentrated in the two libraries already patched:

| callback | duration |
| --- | ---: |
| GraphicsLib `ShaderModPlugin` | 5.900s |
| AshLib `AshLibPlugin` | 2.133s |
| Nexerelin `ExerelinModPlugin` | 0.559s |
| MagicLib | 0.413s |
| Kaleidoscope | 0.188s |
| Second in Command | 0.187s |
| Console Commands | 0.135s |

Everything other than GraphicsLib and AshLib is about 2.5 seconds in aggregate, and every remaining
individual callback is under 0.6 seconds on this run. That puts a hard ceiling on more callback-only
work. It does not mean those libraries are perfect; it means the next multi-second target is earlier.

## Two shortcuts that did not work

### More audio workers

Vanilla already overlaps sound decoding with the resource loop through a fixed two-thread executor.
An exact-build-gated experiment widened it to four. The post-loop audio wait changed from 4.075s to
4.450s, while total resource initialization changed only within run variance. The limit is not a
shortage of executor slots, so the experiment was removed. Prepared decoded audio remains the useful
route because it removes work instead of scheduling more copies of it.

### Replacing synchronized StringReader

The installed open-source `json.jar` is old enough that `JSONTokener(String)` reads every character
through `StringReader.read()`. A semantic drop-in reader removed that per-character monitor while
preserving read, bulk read, skip, mark/reset, close, NUL, EOF, and error behavior. It was pinned to:

- `json.jar`: `63c3541f323f3dfdd595da9257a2099b6a6c39f35a6b3909d86c48a8aa456911`
- `JSONTokener.class`: `375747a019a38b55124a582639f87092ba486bf8bda2b74504345e60b96a7c8d`

`SpecStore` still took 19.056s, inside baseline variance. HotSpot and/or downstream parsing/object
construction makes this wrapper irrelevant. The experiment was removed rather than shipping dead
complexity.

## Concurrency and the useful analogy

Mod callbacks and vanilla spec loaders are ordered mutations of shared registries. They register
scripts and resources, merge mod overlays, populate global maps/lists, and may touch audio or graphics
state. Running arbitrary callbacks simultaneously would trade deterministic load order for races and
hard-to-reproduce corrupt state.

The safe concurrency pattern is narrower:

1. prepare dependency-free data concurrently or before launch;
2. key it by the exact ordered profile and every consumed content hash;
3. cross one barrier;
4. apply/rehydrate live registry mutations on the original thread in original order;
5. run vanilla on every miss or mismatch.

Preflight now materializes the safety key for that cache as a separate preparation stage. It hashes
the exact game JAR, every ordered provider below `data/`, and every ordered mod classpath archive;
the final identity names the profile artifact, so multiple mod profiles coexist instead of evicting
one another. On the measured 83-mod profile it covered 17,839 data providers (55,945,461 bytes) and
85 archives (49,075,713 bytes). A second real preparation selected the identical
`9d44e2704857856b8c7e22acddd422c32b3cbebb766bc0c25200d3bf6538b827` profile with
`artifactHit: true`; warm validation took 498ms. No runtime shortcut consumes this key yet, so this
change is deliberately inert until a loader-specific cache proves equivalent and faster.

That is closer to SWC's ahead-of-time transform cache or a persistent query cache than React's
`useRef`, which only avoids repeat work inside one process. The follow-up in
[issue #263](https://github.com/teamleaderleo/preflight/issues/263) is now narrowed to
preparing the dominant pure representations and deterministically rehydrating the live objects. The
reversible, version-aware optimized-mod manager is tracked separately in
[issue #262](https://github.com/teamleaderleo/preflight/issues/262).

## Reproduction

The three run directories were:

- `20260802-021753-397-09966e53` — real progress milestones, vanilla audio
- `20260802-022231-406-5ceaed08` — exact `SpecStore`, four-audio-worker negative
- `20260802-022644-286-6d60db3b` — exact `SpecStore`, JSON-reader negative
- `specstore-attribution-20260802` — 41 per-loader timings, vanilla audio
- `variant-attribution-2-20260802` — aggregate variant merge/construct/register/post-pass timings
- `20260802-040845-699-516fb794` — aggregate weapon merge/script/register timings
- `20260802-042118-653-6bcc3fae` — cold weapon JSON learning run
- `20260802-042235-465-fe4d1ef1` — warm weapon JSON cache run
- `20260802-102845-271-bd5752d6` — aggregate projectile merge/script/register timings
- `20260802-104416-023-60a40044` — cold projectile JSON learning run
- `20260802-104547-020-19e420a3` — warm projectile JSON cache run
- `20260802-105227-395-1366d474` — aggregate ship-hull merge/lookup/register timings
- `20260802-110641-398-e9514ce2` — cold hull JSON learning run
- `20260802-110845-534-fb8e9274` — warm hull JSON cache run
- `20260802-112438-211-4b39a214` — first rules-loader inner attribution
- `20260802-112637-599-df8c2752` — rules regex and duplicate-scan attribution
- `20260802-113646-682-c1b1a287` — indexed rules duplicate check, real direct launch
- `20260802-115136-046-268dc24b` — cold merged rules CSV learning run
- `20260802-115233-425-55898e14` — warm merged rules CSV cache hit

- `20260802-122951-010-635c22d6` — rule-expression tokenize/command-class split
- `20260802-125919-474-c0f105ea` — tokenizer memo, generic invoke
- `20260802-130134-146-3dfa544f` — tokenizer memo, erased invokeExact
- `20260802-134008-426-7cd6b30b` — cold rule command class learning run
- `20260802-135231-185-e9b37a7d` — warm prepared command class map
- `20260802-135428-568-26345296` — warm prepared command class map, second run

The offline half of the expression investigation needs no run directory. Its three sources are
archived beside this document:

- `2026-08-02-rules-expression-census.py.txt` — merged-profile expression census
- `2026-08-02-rules-tokenize-benchmark.java.txt` — `Misc.tokenize` on the game's JVM
- `2026-08-02-rule-command-class-benchmark.java.txt` — the rule-command package walk

Command shape:

```text
java -jar preflight-cli/target/preflight.jar run --direct --adapter \
  --texture-auto --texture-mode prepared-pixels --prepared-npot \
  --no-record --startup-phase-probe
```

The direct launcher used the saved 1440×932 windowed/fullscreen and sound preferences and required no
manual Play click.
