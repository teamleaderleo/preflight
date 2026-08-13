# Startup optimization scorecard

**Updated:** 2026-08-11

The development installation progressed from a roughly **101-second observed worst case** and an
**88.13-second initial controlled median** to a **15.88-second fastest warm launch**. The project
headline is **101 seconds → 15.88 seconds**.

This page collects the directly measured component changes and operation counts that explain that
progression. It doesn't manufacture an end-to-end baseline by adding results from different
installation states.

## Measured components

| Change | Measured saving | Source |
| --- | ---: | --- |
| Prepared textures and prefetch bypass | **25.530s** | [29% campaign](2026-08-01-twenty-nine-percent-when-they-compose.md) |
| AshLib repeated ship JSON | **7.066–7.435s** | [AshLib report](2026-08-02-ashlib-startup-json-cache.md) |
| GraphicsLib compact auto-generation replay | **4.821s** | [GraphicsLib report](2026-08-02-graphicslib-compact-autogen-replay.md) |
| Merged variant JSON | **~2.700s net** | [PR #275](https://github.com/teamleaderleo/preflight/pull/275) |
| Merged weapon JSON | **~2.000s net** | [PR #278](https://github.com/teamleaderleo/preflight/pull/278) |
| Merged projectile JSON | **~1.100s net** | [PR #281](https://github.com/teamleaderleo/preflight/pull/281) |
| Merged ship-hull JSON | **~1.700s net** | [PR #284](https://github.com/teamleaderleo/preflight/pull/284) |
| Merged campaign-rules CSV | **~0.680s net** | [PR #288](https://github.com/teamleaderleo/preflight/pull/288) |
| Campaign-rule duplicate index | **0.561s observed** | [PR #286](https://github.com/teamleaderleo/preflight/pull/286) |
| Rule-token memo | **~0.150s** | [PR #291](https://github.com/teamleaderleo/preflight/pull/291) |
| Prepared rule-command package map | **~0.165s** | [PR #298](https://github.com/teamleaderleo/preflight/pull/298) |
| Shared cache-profile identity pass | **1.161s** | [PR #300](https://github.com/teamleaderleo/preflight/pull/300) |

These component results were measured at their own boundaries. Some overlap on the whole-launch
critical path, so they remain individual measurements rather than an artificial sum.

## Individual multipliers

| Boundary | Before | After | Speedup | Time removed |
| --- | ---: | ---: | ---: | ---: |
| Accepted main-menu texture campaign | 88.13s | 62.60s | **1.41×** | **29.0%** |
| AshLib callback | 9.778s | 2.712–2.343s | **3.61–4.17×** | **72.3–76.0%** |
| GraphicsLib callback | 8.503s | 5.465s | **1.56×** | **35.7%** |
| Variant merge/parse | 3.289s | 0.324s | **10.15×** | **90.1%** |
| Weapon loader | 3.338s | 0.998s | **3.34×** | **70.1%** |
| Projectile loader | 2.349s | 1.004s | **2.34×** | **57.3%** |
| Ship-hull loader | 2.653s | 0.754s | **3.52×** | **71.6%** |
| Rules CSV merge | 0.959s | 0.166s | **5.78×** | **82.7%** |
| Rule tokenizer | 0.742s | 0.578s | **1.28×** | **22.1%** |
| Cache identity construction | 1.613s | 0.452s | **3.57×** | **72.0%** |

The asset tools found another conspicuous multiplier outside startup composition: progressive JPEGs
took **8.75×** as long to decode as baseline JPEGs containing the same pixels. See
[Progressive JPEG costs nine times the decode](2026-07-28-progressive-jpeg-costs-nine-times-the-decode.md).

## Repeated work removed

The component telemetry represents **64,739 cache or memo hits** and **192,089 counted operations
removed or shortcut** in the stacked warm path.

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

The 64,739 cache-or-memo total includes prepared texture hits, merged JSON and CSV hits, tokenizer
memo hits, and prepared command-package hits. The larger total also counts queue, decode,
conversion, color, scan, and identity stages that no longer execute.

## What changed in the code

The campaign-rules loader performed a linear scan through existing rules under a trigger for each of
21,059 registrations. [PR #286](https://github.com/teamleaderleo/preflight/pull/286) replaced those
scans with an exact `(trigger, ruleId)` hash set, turning each duplicate check into average **O(1)**
membership while preserving ordered insertion.

The variant, weapon, projectile, hull, and rules caches turn repeated merge-and-parse work into
keyed lookups. Their identities include the exact game JAR and ordered providers whose contents can
affect the answer. A changed profile learns a different value.

The tokenizer uses process-local memoization: 62,340 calls, 31,614 distinct inputs, and 30,726
repeats. It stores an immutable token description and returns fresh mutable token objects to each
caller.

The shared profile-identity pass applies the same principle one level higher. Six caches previously
reread the same index, rehashed the same game JAR, and resolved providers separately.
[PR #300](https://github.com/teamleaderleo/preflight/pull/300) reduced six index reads to one and
12,797 provider path resolutions to 694 memoized parent-directory resolutions while retaining
content hashing.

## How the work was found

The first accepted prepared-pixel result was only
[1.5%](2026-08-01-the-first-valid-startup-number.md), despite profiles that made texture work look
much larger. That mismatch led to a critical-path probe, which found the loading thread spending
roughly [27 seconds waiting on a one-thread prefetcher](2026-08-01-the-loading-thread-waits-on-a-one-thread-prefetcher.md).
Removing the wait and composing it with prepared pixels produced the accepted 29% campaign.

The visible 0% pause prompted exact progress and method probes. They found
[18–19 seconds inside vanilla SpecStore](2026-08-02-zero-percent-is-spec-store.md), then isolated the
merge-and-parse boundary used by the variant, weapon, projectile, hull, and rules caches.

The rule-command package cache was predicted to recover most of a 641ms phase and recovered about
165ms. Measuring why also exposed **1.613 seconds of repeated cache-identity work before the game
JVM started**. That finding became PR #300 and saved more time than the command cache that revealed
it.

The complete progression continues in [Optimization history](../optimization-history.md).
