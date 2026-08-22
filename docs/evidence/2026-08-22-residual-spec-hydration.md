# Residual spec loading is now conversion and construction work

**Date:** 2026-08-22

**Runtime:** Starsector's bundled Zulu 17.0.10 x86_64 JRE under Rosetta 2

**Profile:** reviewed 83-mod installation, exact fingerprint
`2995668308ac3d31d645ccac30fb1a7e644e64fce5609050a1488df4cadc5af6`

**Status:** diagnostic attribution only; the desktop was intentionally not a clean benchmark

## The old reading problem is no longer the large residual

The current v3 prepared-pack probe reconstructed 8,825 merged reads in 193ms. The four dedicated
spec caches and the general merged-read cache are doing their jobs. The remaining `SpecStore`
interval was 4.51s, followed by 4.22s of mod callbacks. Repeating the text-cache design at another
reader cannot recover those seconds because the reads themselves no longer own them.

The current loader breakdown instead leaves about 1.7 to 2.0 seconds concentrated in serial
data-to-object work. The most visible current rows were projectile definitions, weapon definitions,
the weapon and hull spreadsheets, and two otherwise opaque `SpecStore` calls. The prepared caches
return fresh JSON trees. Starsector then repeatedly converts their strings, resolves enums and
colors, creates fresh spec objects, invokes setters, calculates derived values, and registers the
results in the original order.

## JFR points at repeated numeric conversion, without supplying wall time

The auto-stopped SAMPLE recording is:

`~/.starsector-preflight/runs/residual-profile-noisy-20260822-184752/startup.jfr`

It recorded 358 main-thread execution samples. Twenty-five stacks included `FloatingDecimal`; 24
of those passed through `Double.parseDouble`, and 23 passed through `JSONObject.optDouble`. Twelve
of the sampled numeric stacks belonged to `WeaponSpecLoader.o00000`. This is sample composition,
not elapsed time. It cannot be multiplied by the launch duration.

The recording also contained 144 main-thread stacks under `SpecStore`. Its visible leaf work was
dominated by `FloatingDecimal`, `HashMap.getNode`, JSON access, and the loaders themselves. That is
consistent with repeated hydration rather than another hidden disk read.

## Exact-call sampler

The profile-only adapter now recognizes the reviewed `WeaponSpecLoader` identity and exact 24
weapon plus 23 projectile numeric JSON call sites. It counts every `optDouble`/`getDouble` call and
times one in sixteen. Normal launches do not install this sampler. Changed class identity or call
shape leaves the class untouched.

The first noisy diagnostic is:

`~/.starsector-preflight/runs/weapon-hydration-sampler-noisy-20260822-190351`

| loader | definitions | loader wall | numeric calls | sampled mean | extrapolated numeric wall |
| --- | ---: | ---: | ---: | ---: | ---: |
| projectile | 1,263 | 810ms | 13,454 | 11.20us | about 150ms |
| weapon | 3,077 | 319ms | 32,583 | 1.02us | about 33ms |

The extrapolation is a diagnostic estimate from sampled calls. It is not a retained startup claim.
The projectile path runs first and pays cold class/JIT work; a pause inside a sampled call can skew
the estimate. A later probe records the sampled maximum explicitly so that this can be separated
from ordinary conversion cost.

The useful facts are stable enough to guide the next experiment:

- Starsector performs 46,037 numeric JSON accesses in these two exact loaders.
- Numeric conversion alone is not the whole unexplained interval.
- The smaller projectile corpus costs much more than the later, larger weapon corpus, so first-use
  initialization and other projectile construction work need their own ownership.

## What not to repeat

A generic string-to-double memo already produced 224,406 hits and 2,357 misses, then regressed the
projectile and weapon loaders. A schema-scoped projectile pretyping pass also regressed an offline
replay because it reflectively walked the tree after decode. Neither design should return.

## Bounded next candidate

The remaining step-change candidate is a prepared, schema-specific intermediate at the decode and
construction boundary. It must avoid a second reflective walk, retain fresh game objects, preserve
the original JSON values available to mods, run setters and registrations in the original order,
and decline on any unknown class, archive, schema, or prepared identity. The first experiment should
attribute projectile JSON access, enum/color/vector decoding, object construction, setter work, and
first-use initialization separately. Only then is it worth deciding whether a typed sidecar or a
smaller call-site shortcut can credibly remove at least 100ms.

The other large current lane is mod startup. GraphicsLib, Nexerelin, MagicLib, and AshLib together
owned about 2.7 seconds in the adjacent probe. They already have exact subphase attribution, so any
second large win is more likely to come from one of those owned paths than from another generic
resource cache.
