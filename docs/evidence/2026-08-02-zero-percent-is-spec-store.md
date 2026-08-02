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

Command shape:

```text
java -jar preflight-cli/target/preflight.jar run --direct --adapter \
  --texture-auto --texture-mode prepared-pixels --prepared-npot \
  --no-record --startup-phase-probe
```

The direct launcher used the saved 1440×932 windowed/fullscreen and sound preferences and required no
manual Play click.
