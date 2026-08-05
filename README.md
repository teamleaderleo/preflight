# Starsector Preflight

**A faster launcher for heavily modded Starsector.**

Preflight prepares work that Starsector and its mods would otherwise repeat on every launch, then starts the same game and mod profile using those prepared results.

```bash
java -jar preflight.jar install
```

The install command creates a normal local launcher. For unattended launches, Preflight can also use Starsector's saved display and sound settings directly:

```bash
java -jar preflight.jar run --direct
```

## Current scorecard

On the development machine — 83 mods, M5 MacBook Air — a full modded startup takes **80.09 seconds**.
With Preflight it takes **42.36 seconds**.

| | |
| --- | ---: |
| Vanilla, no Preflight | **80.09s** |
| Preflight, everything enabled | **42.36s** |
| Removed | **37.74s (47.1%)** |
| Speedup | **1.89×** |

That is a measured campaign, not an estimate: fifteen unattended launches, five per condition,
conditions interleaved within every round, all fifteen accepted. Every round agrees to within 1.9
seconds on a 37-second effect. The full result, including what moved and what did not, is in
[The whole stack, measured at once](docs/evidence/2026-08-03-the-whole-stack-measured-at-once.md).

Reproduce it yourself:

```bash
scripts/run-startup-benchmark.sh --unattended --conditions vanilla,fast,full --rounds 5
```

Two notes on reading that number. The 80.09s baseline is a quiet machine with warm caches and no
launcher; the lived experience on this installation was the **90–100+ second** range, and against
that a 42.36s load is roughly **2.1–2.4×**. And this is one machine on one mod profile — the
harness exists so anyone can produce their own.

**This is a waypoint, not a finish line.** The largest known remaining items are the resource-index
read ([#304](https://github.com/teamleaderleo/starsector-preflight/issues/304)), the GraphicsLib and
AshLib callbacks, and the untouched audio and script-bytecode paths in the [roadmap](docs/roadmap.md).

| Repeated work removed | Count |
| --- | ---: |
| Cache or memo hits in the measured launch | **64,739** |
| Counted operations removed or shortcut | **192,089** |
| Empty texture allocation removed | **1.22 GiB** |

The per-change arithmetic, individual multipliers, and source links are in the
[accumulated scorecard](docs/evidence/2026-08-02-accumulated-startup-scorecard.md).

## What we did

We did not start with a predetermined cache design. We traced startup, compared the game log with the visible loading screen, recorded thread activity, added unattended direct launching, and inserted exact probes around increasingly narrow pieces of the loader.

That analysis found three large concentrations of repeat work.

The texture path could leave the loading thread waiting roughly **27 seconds** on Starsector's one-thread prefetch queue. Once the queue returned, the same launch still hashed source files, decoded images, converted pixels, copied buffers, calculated colors, and padded uploads. Removing the wait, moving source validation off the hot path, and serving upload-ready pixels produced the accepted [29% startup campaign](docs/evidence/2026-08-01-twenty-nine-percent-when-they-compose.md).

The visible 0% pause was another large block of real work. Exact progress and method probes showed that vanilla `SpecStore` spent roughly **18–19 seconds** rebuilding variants, weapons, projectiles, hulls, campaign rules, and related registries. Further probes found that most of the useful time was inside repeated JSON/CSV merge-and-parse operations, which gave us a narrow reusable boundary without replacing the game's live objects. The investigation and the complete loader breakdown are in [The 20-second 0% plateau is vanilla SpecStore](docs/evidence/2026-08-02-zero-percent-is-spec-store.md).

The final startup tail contained repeated work in mod callbacks. AshLib repeatedly resolved the same hull and variant JSON while constructing ship-render information. GraphicsLib repeated much of its automatic texture-map discovery even when its generated files were already valid. The resulting changes reduced the AshLib callback by **7.07–7.44 seconds** and the measured GraphicsLib sequence by **4.82 seconds**. See the [AshLib](docs/evidence/2026-08-02-ashlib-startup-json-cache.md) and [GraphicsLib](docs/evidence/2026-08-02-graphicslib-compact-autogen-replay.md) reports.

## Where the time went

| Change | Measured result | Evidence |
| --- | ---: | --- |
| Prepared textures and prefetch bypass | **25.53s saved; 1.41× overall** | [Accepted campaign](docs/evidence/2026-08-01-twenty-nine-percent-when-they-compose.md) |
| AshLib ship JSON memoization | **3.61–4.17× faster callback** | [Report](docs/evidence/2026-08-02-ashlib-startup-json-cache.md) |
| GraphicsLib compact replay | **1.56× faster callback** | [Report](docs/evidence/2026-08-02-graphicslib-compact-autogen-replay.md) |
| Merged variant JSON | **10.15× faster merge/parse; ~2.7s net** | [PR #275](https://github.com/teamleaderleo/starsector-preflight/pull/275) |
| Merged weapon JSON | **3.34× faster loader; ~2.0s net** | [PR #278](https://github.com/teamleaderleo/starsector-preflight/pull/278) |
| Merged projectile JSON | **2.34× faster loader; ~1.1s net** | [PR #281](https://github.com/teamleaderleo/starsector-preflight/pull/281) |
| Merged ship-hull JSON | **3.52× faster loader; ~1.7s net** | [PR #284](https://github.com/teamleaderleo/starsector-preflight/pull/284) |
| Rules CSV, duplicate checks, tokens, command packages | **~1.56s combined** | [#286](https://github.com/teamleaderleo/starsector-preflight/pull/286), [#288](https://github.com/teamleaderleo/starsector-preflight/pull/288), [#291](https://github.com/teamleaderleo/starsector-preflight/pull/291), [#298](https://github.com/teamleaderleo/starsector-preflight/pull/298) |
| Shared cache-profile identity | **1.613s → 0.452s; 3.57× faster** | [PR #300](https://github.com/teamleaderleo/starsector-preflight/pull/300) |
| **All of it, composed and measured** | **80.09s → 42.36s; 1.89× overall** | [2026-08-03 campaign](docs/evidence/2026-08-03-the-whole-stack-measured-at-once.md) |

The texture path also stopped allocating empty power-of-two padding. In one full load, texture uploads fell from **3.65 GiB to 2.43 GiB**, removing **1.22 GiB** while serving more textures. See [The texture padding is gone](docs/evidence/2026-08-02-the-padding-is-gone.md).

## Repeated work removed

The component runs represent **64,739 direct cache or memo hits**. Counting the queue, decode, conversion, scan, and validation stages that no longer execute brings the stacked total to **192,089 operations removed or shortcut**.

| Work avoided or replaced | Count |
| --- | ---: |
| Texture prefetch enqueues skipped | **50,879** |
| Image decodes, pixel conversions, and color calculations bypassed | **64,956** |
| Merged variant, weapon, projectile, hull, and rules values served | **11,690** |
| Repeated rule tokenizations memoized | **30,726** |
| Linear duplicate scans replaced by hash checks | **21,059** |
| Prepared command-package resolutions | **671** |
| Provider real-path resolutions avoided | **12,103** |

One of the clearest algorithmic changes is the campaign-rule duplicate check. Vanilla performed a trigger-local linear scan for each of 21,059 registrations. [PR #286](https://github.com/teamleaderleo/starsector-preflight/pull/286) replaces that repeated scan with an exact hash-set membership check—average **O(1)** lookup—while preserving the game's original insertion order and duplicate behavior.

The other caches are straightforward memoization and precomputation at larger boundaries: exact inputs become a key, the expensive deterministic result is prepared once, and later requests reuse it. A changed game JAR, mod file, or provider order produces a different key rather than reusing the wrong answer.

## How the investigation progressed

The most useful advances often began with a result that did not make sense.

The first valid prepared-pixel campaign saved only [1.5%](docs/evidence/2026-08-01-the-first-valid-startup-number.md), despite profiles that made texture work look much larger. That discrepancy led to a critical-path probe and the discovery that the cache sat behind a [27-second prefetch wait](docs/evidence/2026-08-01-the-loading-thread-waits-on-a-one-thread-prefetcher.md). Fixing the placement of the cache turned the same body of work into the 29% campaign.

The rule-command package map was expected to remove most of a 641ms phase. It removed about 165ms. Measuring why showed that the successful class load was expensive while the failed probes were cheap. The same investigation exposed **1.613 seconds of repeated cache-profile construction before the game JVM even started**, which led to [PR #300](https://github.com/teamleaderleo/starsector-preflight/pull/300) and a larger saving than the feature that revealed it.

We also reviewed [Fast Rendering as prior art](docs/prior-art-starsector-render.md). That review corrected an earlier conclusion about its texture prefetcher, documented where the two projects overlap, and highlighted the untouched JSON/spec path that became the SpecStore campaign. The pattern throughout the project has been the same: inspect the logs and code, ask a narrower question, build the probe that answers it, and let the measurement choose the next change.

## Design ideas

The design uses concepts familiar from application and compiler tooling rather than relying on one exotic trick.

[React Query / TanStack Query](https://tanstack.com/query/latest/docs/framework/react/guides/query-keys) provides a useful mental model for stable cache keys, reusable answers, and invalidation when inputs change; its [prefetching model](https://tanstack.com/query/latest/docs/framework/react/guides/prefetching) is also close to Preflight's goal of moving known work ahead of demand. [SWC](https://swc.rs/) demonstrates the value of doing expensive transformation work ahead of use and handing the runtime a ready artifact.

Preflight applies those ideas alongside classic computer-science tools: memoization, hash maps, precomputation, content-addressed artifacts, bounded concurrency, and replacing repeated linear work with direct lookup. The difficult part is locating a reusable boundary inside an opaque, obfuscated, mod-heavy Java application while keeping Starsector's original behavior available whenever the prepared path does not apply.

## How Preflight works

Preflight prepares deterministic work outside the timed game launch. At startup it identifies the exact game build, enabled mod order, and resource providers that can affect each prepared result.

A matching artifact skips only the work that was already completed. Starsector still constructs its live objects, registers scripts, applies mod ordering, mutates its registries, creates textures, performs OpenGL uploads, and runs the remaining mod logic.

Changed game or mod files select different prepared data. A missing entry, unsupported class, corrupt artifact, or runtime error uses the original loader. Preflight does not edit the game, mods, saves, launcher, or VM parameter files.

## Install and run

Build or download the self-contained `preflight.jar`, then install the local launcher:

```bash
java -jar preflight.jar install
```

Run through the ordinary launcher path:

```bash
java -jar preflight.jar run
```

Run unattended through Starsector's saved launcher settings:

```bash
java -jar preflight.jar run --direct
```

Inspect the detected installation and mod profile without launching:

```bash
java -jar preflight.jar doctor
```

Prepare every reusable cache for the current profile:

```bash
java -jar preflight.jar prepare
```

Prepared textures have two exact, lossless storage policies. `balanced` is the default and
compresses them with LZ4 to use substantially less disk, except where compression saves under 9.1%
and raw storage is faster for nearly no space cost. It retains the same runtime pixels and fail-open
behavior; `fastest` keeps every upload-ready pixel array raw for minimum decode CPU:

```bash
java -jar preflight.jar prepare --texture-storage fastest
java -jar preflight.jar prepare --texture-storage balanced
```

On the 83-mod development profile, `balanced` reduced texture blobs from 5.33 GB to 2.21 GB. Its
five-launch median was 23.15s versus the adjacent raw cohort's 23.08s, so no material startup
regression was measurable on the reviewed macOS/Rosetta system. Existing manifests remain active
until preparation runs again. After changing policies, `java -jar preflight.jar cache prune`
previews the superseded blobs that can be reclaimed; add `--yes` only after reviewing that plan.

Preparation also creates one indexed texture pack per profile. The game opens that pack once and
falls back to the loose blobs on any problem. After a successful launch, the next preparation can
use its checksummed access-order hint to tune physical layout automatically; missing or corrupt
hints are ignored. The packed copy currently retains loose blobs for repair and fail-open safety,
so profile pruning is the supported way to reclaim obsolete versions while the GUI storage-policy
controls are still being built.

On macOS, `install` creates `~/Applications/Starsector Preflight.app`. Linux receives a command and desktop entry. Windows receives a local command launcher.

## What is next

The composed campaign has run, and the number above is its result. Work continues: the resource-index read is now the largest launcher-side cost ([#304](https://github.com/teamleaderleo/starsector-preflight/issues/304)), the GraphicsLib and AshLib callbacks still hold seconds between them, and the audio and script-bytecode paths are untouched.

The user-facing work is tracked in [issue #294](https://github.com/teamleaderleo/starsector-preflight/issues/294): a simple desktop launcher, clear uninstall behavior, and a front page that makes the result easy to verify. The broader plan—including direct resource-provider lookup, persistent script bytecode, cross-platform packaging, and later prepared-audio experiments—is in the [roadmap](docs/roadmap.md).

## Analysis and mod tools

The same codebase contains the tools used to find the startup work: JFR recording, exact startup-phase probes, loader-level attribution, profile comparison, crash detection, and unattended benchmark campaigns. Profiling is optional; normal accelerated launches do not need to pay for a recording.

Preflight also includes a read-only mod linter:

```bash
java -jar preflight.jar lint
java -jar preflight.jar lint --path ./MyMod
```

Across real mod profiles it has found progressive JPEGs that decode **8.75× slower** through the game's ImageIO path, gigabytes of avoidable texture and audio allocation, duplicate and shadowed resources, unused files, extension mismatches, and configuration left outside the top-level value where the game never reads it.

See [Asset Lint](docs/asset-lint.md) and the [evidence archive](docs/evidence/) for the individual investigations.

## Build

Preflight requires JDK 17 and Maven 3.9 or newer:

```bash
mvn verify
```

The runnable launcher and Java agent are produced together at:

```text
preflight-cli/target/preflight.jar
```

## Documentation

- [Accumulated startup scorecard](docs/evidence/2026-08-02-accumulated-startup-scorecard.md)
- [Automatic launch and discovery](docs/automatic-launch.md)
- [Vanilla runtime adapter](docs/vanilla-adapter.md)
- [Repeated startup benchmark](docs/startup-benchmark.md)
- [Asset lint](docs/asset-lint.md)
- [Prior-art review](docs/prior-art-starsector-render.md)
- [Roadmap](docs/roadmap.md)
- [Evidence archive](docs/evidence/)

## Status

Preflight is under active development. Runtime acceleration is pinned to reviewed game classes and exact profile inputs, with the original game path retained for unsupported or changed inputs.

## License

[MIT](LICENSE). Starsector, Fast Rendering, and mod content remain the property of their respective owners and are never included in this repository or its releases.
