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
[issue #263](https://github.com/teamleaderleo/starsector-preflight/issues/263) is now narrowed to
preparing the dominant pure representations and deterministically rehydrating the live objects. The
reversible, version-aware optimized-mod manager is tracked separately in
[issue #262](https://github.com/teamleaderleo/starsector-preflight/issues/262).

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

The expression-phase investigation is offline and needs no run directory. Its three sources are
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
