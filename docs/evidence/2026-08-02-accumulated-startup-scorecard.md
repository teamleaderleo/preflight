# Accumulated startup scorecard

**Date:** 2026-08-02  
**Baseline:** 88.13 seconds, accepted unattended campaign  
**Current stacked result:** 47.634–48.003 seconds removed  
**Theoretical current floor:** 40.127–40.496 seconds

This page adds the measured component results that landed after the accepted texture campaign. It is the current running scorecard for the development machine and mod profile while the fully composed campaign is still being assembled.

The arithmetic is executable:

```bash
python3 scripts/startup_scorecard.py
```

## Accumulated time removed

| Change | Measured saving | Source |
| --- | ---: | --- |
| Prepared textures and prefetch bypass | **25.530s** | [29% campaign](2026-08-01-twenty-nine-percent-when-they-compose.md) |
| AshLib repeated ship JSON | **7.066–7.435s** | [AshLib report](2026-08-02-ashlib-startup-json-cache.md) |
| GraphicsLib compact auto-generation replay | **4.821s** | [GraphicsLib report](2026-08-02-graphicslib-compact-autogen-replay.md) |
| Merged variant JSON | **~2.700s net** | [PR #275](https://github.com/teamleaderleo/starsector-preflight/pull/275) |
| Merged weapon JSON | **~2.000s net** | [PR #278](https://github.com/teamleaderleo/starsector-preflight/pull/278) |
| Merged projectile JSON | **~1.100s net** | [PR #281](https://github.com/teamleaderleo/starsector-preflight/pull/281) |
| Merged ship-hull JSON | **~1.700s net** | [PR #284](https://github.com/teamleaderleo/starsector-preflight/pull/284) |
| Merged campaign-rules CSV | **~0.680s net** | [PR #288](https://github.com/teamleaderleo/starsector-preflight/pull/288) |
| Campaign-rule duplicate index | **0.561s observed** | [PR #286](https://github.com/teamleaderleo/starsector-preflight/pull/286) |
| Rule-token memo | **~0.150s** | [PR #291](https://github.com/teamleaderleo/starsector-preflight/pull/291) |
| Prepared rule-command package map | **~0.165s** | [PR #298](https://github.com/teamleaderleo/starsector-preflight/pull/298) |
| Shared cache-profile identity pass | **1.161s** | [PR #300](https://github.com/teamleaderleo/starsector-preflight/pull/300) |
| **Total** | **47.634–48.003s** | [scorecard script](../../scripts/startup_scorecard.py) |

Subtracting that stack from the accepted 88.13-second baseline gives **40.127–40.496 seconds**. That is **2.18–2.20× faster** and **54.0–54.5% less startup time** than the accepted baseline.

The development installation originally occupied the **90–100+ second** range. Against that original experience, the current floor is approximately **2.22–2.49× faster**, or roughly **55–60% of the wait removed**.

## Individual multipliers

The cumulative result is made from several large local speedups rather than one universal shortcut.

| Boundary | Before | After | Speedup | Time removed |
| --- | ---: | ---: | ---: | ---: |
| Accepted main-menu campaign | 88.13s | 62.60s | **1.41×** | **29.0%** |
| AshLib callback | 9.778s | 2.712–2.343s | **3.61–4.17×** | **72.3–76.0%** |
| GraphicsLib callback | 8.503s | 5.465s | **1.56×** | **35.7%** |
| Variant merge/parse | 3.289s | 0.324s | **10.15×** | **90.1%** |
| Weapon loader | 3.338s | 0.998s | **3.34×** | **70.1%** |
| Projectile loader | 2.349s | 1.004s | **2.34×** | **57.3%** |
| Ship-hull loader | 2.653s | 0.754s | **3.52×** | **71.6%** |
| Rules CSV merge | 0.959s | 0.166s | **5.78×** | **82.7%** |
| Rule tokenizer | 0.742s | 0.578s | **1.28×** | **22.1%** |
| Cache identity construction | 1.613s | 0.452s | **3.57×** | **72.0%** |

The asset tools found another conspicuous multiplier outside the accumulated startup stack: progressive JPEGs took **8.75×** as long to decode as baseline JPEGs containing the same pixels. See [Progressive JPEG costs nine times the decode](2026-07-28-progressive-jpeg-costs-nine-times-the-decode.md).

## How much repeated work disappeared

The component telemetry represents **64,739 cache or memo hits** and **192,089 counted operations removed or shortcut** in the stacked warm path.

| Repeated work | Count per measured launch |
| --- | ---: |
| Texture prefetch enqueues skipped | **50,879** |
| Image decodes bypassed | **21,652** |
| Pixel conversions bypassed | **21,652** |
| Derived-color calculations bypassed | **21,652** |
| Merged variant JSON hits | **5,138** |
| Merged weapon JSON hits | **2,921** |
| Merged projectile JSON hits | **1,159** |
| Merged hull JSON hits | **2,471** |
| Merged rules CSV hits | **1** |
| Linear duplicate scans replaced by hash checks | **21,059** |
| Repeated tokenizations served from the memo | **30,726** |
| Prepared command-package hits | **671** |
| Provider real-path resolutions avoided | **12,103** |
| Redundant resource-index reads avoided | **5** |

The 64,739 cache-or-memo total includes prepared texture hits, merged JSON/CSV hits, tokenizer memo hits, and prepared command-package hits. The larger 192,089 total also counts the queue, decode, conversion, color, scan, and identity stages that no longer execute.

## The computer-science changes

Most of the successful changes use familiar tools applied at the right boundary.

The campaign-rules loader performed a linear scan through the existing rules under a trigger for each of 21,059 registrations. [PR #286](https://github.com/teamleaderleo/starsector-preflight/pull/286) replaced those repeated scans with an exact `(trigger, ruleId)` hash set, turning each duplicate check into average **O(1)** membership while preserving the original ordered insertion.

The variant, weapon, projectile, hull, and rules caches turn repeated merge-and-parse work into keyed lookups. The key includes the exact game JAR and the ordered providers whose contents can affect the answer, so a matching profile gets the prepared value and a changed profile learns another one.

The tokenizer is ordinary process-local memoization: 62,340 calls, 31,614 distinct inputs, and 30,726 repeats. The cache stores the immutable token description and returns fresh mutable token objects to each caller.

The shared profile-identity pass applies the same principle one level higher. Six caches previously reread the same index, rehashed the same game JAR, and resolved providers separately. [PR #300](https://github.com/teamleaderleo/starsector-preflight/pull/300) reduced six index reads to one and 12,797 provider path resolutions to 694 memoized parent-directory resolutions, while retaining content hashing.

## How the work was found

The project advanced by asking narrower questions whenever a result did not match the model.

The first accepted prepared-pixel result was only [1.5%](2026-08-01-the-first-valid-startup-number.md), despite profiling that made the texture work look much larger. That mismatch led to a critical-path probe, which found the loading thread spending roughly [27 seconds waiting on a one-thread prefetcher](2026-08-01-the-loading-thread-waits-on-a-one-thread-prefetcher.md). Removing that wait and composing it with prepared pixels produced the accepted 29% campaign.

The visible 0% pause then prompted exact progress and method probes. Those showed that the game was spending [18–19 seconds inside vanilla SpecStore](2026-08-02-zero-percent-is-spec-store.md), and further probes isolated the merge-and-parse boundary now used by the variant, weapon, projectile, hull, and rules caches.

The rule-command package cache was predicted to recover most of a 641ms phase. It recovered about 165ms instead. Measuring why showed that the successful class load, verification, initialization, and construction dominated the misses—and also exposed **1.613 seconds of repeated cache-identity work before the JVM started**. That finding became PR #300 and saved more time than the command cache that revealed it.

Reading [Fast Rendering as prior art](../prior-art-starsector-render.md) also corrected an earlier conclusion about its texture prefetcher. It had already removed the same queue by replacing the caller and using workers. Preflight reaches the seam differently: it prepares repeatable results so the next launch does not perform the work at all. The review also identified the JSON/spec path as open ground and helped prioritize the SpecStore campaign.

## Design lineage

The design direction reflects ideas already familiar from web and compiler tooling:

- [TanStack Query](https://tanstack.com/query/latest/docs/framework/react/guides/query-keys) uses stable query keys to decide which cached answer belongs to which input, while its [prefetching model](https://tanstack.com/query/latest/docs/framework/react/guides/prefetching) moves known future work ahead of demand. Preflight applies the same broad ideas to game and mod profiles: exact keys, prepared results, and invalidation when the inputs change.
- [SWC](https://swc.rs/) demonstrates the value of performing expensive transformation work ahead of use and handing the runtime a ready artifact. Preflight does this for pixels, merged data, and eventually other deterministic startup products.
- The remaining tools are classic computer science: memoization, hash maps, precomputation, content-addressed artifacts, bounded concurrency, and replacing repeated linear work with direct lookup.

The hard part was not inventing a novel cache. It was identifying the correct pure boundary inside an opaque, obfuscated, mod-heavy Java application, proving what could be reused, and leaving the original behavior available when it could not.

## Next

The immediate performance milestone is a fully composed unattended campaign covering every landed cache and the current direct-launch path. That will replace the arithmetic floor with a single end-to-end distribution.

The user-facing work is tracked in [issue #294](https://github.com/teamleaderleo/starsector-preflight/issues/294): a simple desktop launcher, clear uninstall behavior, and a front page that leads with the proven result. The longer program—including direct resource-provider lookup, persistent script bytecode, cross-platform packaging, and later prepared-audio experiments—is in the [roadmap](../roadmap.md).
