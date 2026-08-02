# Starsector Preflight

**A faster launcher for heavily modded Starsector.**

Preflight prepares work that Starsector and its mods would otherwise repeat on every launch, then starts the same game and mod profile using those prepared results.

```bash
java -jar preflight.jar install
```

The install command creates a normal local launcher. You can also launch directly from the command line:

```bash
java -jar preflight.jar run --direct
```

`--direct` skips the Starsector launcher window and uses its saved resolution, fullscreen, sound, and registration settings.

## Current result

On the development machine, the original heavily modded startup was roughly **90–100+ seconds**. The first accepted end-to-end campaign reduced the measured median from **88.13 seconds to 62.60 seconds**, saving **25.53 seconds (29%)**. Since that campaign, the project has removed another **22.10–22.47 seconds** of measured work from mod callbacks, vanilla data loaders, campaign rules, and Preflight's own launch preparation.

Added together, the current measured component savings are:

```text
25.53  prepared textures and prefetch bypass
 7.07–7.44  AshLib repeated ship JSON
 4.82  GraphicsLib repeated map discovery
 2.70  merged variant JSON
 2.00  merged weapon JSON
 1.10  merged projectile JSON
 1.70  merged ship-hull JSON
 0.68  merged campaign rules CSV
 0.56  campaign rule duplicate index
 0.15  repeated rule tokenization
 0.17  repeated rule command-package searches
 1.16  shared cache-profile identity work
────────────────────────────────────────────
47.63–48.00 seconds accumulated
```

Applied to the accepted **88.13-second** baseline, that produces a current theoretical startup floor of **40.13–40.50 seconds** on the same machine. Direct development launches have already reached the low-40-second range while this work has been accumulating. A complete all-features campaign is the next step; the arithmetic above is the current stack of measured deltas.

## What we found

The project began by tracing startup rather than treating the loading bar as an explanation. We examined Starsector's logs, added unattended direct launching, recorded thread activity, and inserted exact probes around the major loading phases and the individual methods inside them.

That work showed that startup time was concentrated in a few repeatable areas.

The first was texture loading. Starsector could spend roughly **27 seconds** waiting on a single-threaded texture prefetch queue, even when Preflight already had the requested data prepared. After that wait, the loading thread still hashed source files, decoded images, converted pixels one at a time, copied buffers, and padded most uploads to power-of-two dimensions.

The second was the apparent pause at 0%. Exact phase probes showed that the bar was not stuck: vanilla `SpecStore` was spending roughly **18–19 seconds** rebuilding variants, weapons, projectiles, hulls, campaign rules, and related registries. Five loaders accounted for about three quarters of the measured work.

The third was the tail after the visible loading work. A few mod callbacks were doing seconds of repeated parsing and discovery. AshLib repeatedly resolved the same hull and variant JSON while constructing ship render information. GraphicsLib rebuilt large parts of its texture-map discovery even when the generated map cache was already valid.

The reports behind those findings are:

- [Twenty-nine percent, when the two bypasses compose](docs/evidence/2026-08-01-twenty-nine-percent-when-they-compose.md)
- [The 20-second 0% plateau is vanilla SpecStore](docs/evidence/2026-08-02-zero-percent-is-spec-store.md)
- [AshLib startup JSON cache](docs/evidence/2026-08-02-ashlib-startup-json-cache.md)
- [GraphicsLib compact auto-generation replay](docs/evidence/2026-08-02-graphicslib-compact-autogen-replay.md)

## What changed

The accepted texture campaign combined two changes that had to work together. Preflight removed prepared textures from the game's prefetch queue, then supplied upload-ready pixels instead of making the game decode and convert them again. Across 15 accepted unattended launches, `prepared` beat `vanilla` in every round: **88.13 seconds became 62.60 seconds**. The cache and prefetch bypass accounted for **15.88 seconds**, and the prepared-pixel conversion bypass accounted for another **9.65 seconds**.

Later work attacked the loading phases exposed by that campaign.

| Change | Measured result | Report |
| --- | ---: | --- |
| Prepared textures and prefetch bypass | **25.53s saved** | [Campaign](docs/evidence/2026-08-01-twenty-nine-percent-when-they-compose.md) |
| AshLib ship JSON memoization | **7.07–7.44s saved** | [Report](docs/evidence/2026-08-02-ashlib-startup-json-cache.md) |
| GraphicsLib compact replay | **4.82s whole-launch reduction** | [Report](docs/evidence/2026-08-02-graphicslib-compact-autogen-replay.md) |
| Merged variant JSON | **~2.7s net** | [PR #275](https://github.com/teamleaderleo/starsector-preflight/pull/275) |
| Merged weapon JSON | **~2.0s net** | [PR #278](https://github.com/teamleaderleo/starsector-preflight/pull/278) |
| Merged projectile JSON | **~1.1s net** | [PR #281](https://github.com/teamleaderleo/starsector-preflight/pull/281) |
| Merged ship-hull JSON | **~1.7s net** | [PR #284](https://github.com/teamleaderleo/starsector-preflight/pull/284) |
| Merged campaign-rules CSV | **~0.68s net** | [PR #288](https://github.com/teamleaderleo/starsector-preflight/pull/288) |
| Rule duplicate index | **~0.56s net** | [PR #286](https://github.com/teamleaderleo/starsector-preflight/pull/286) |
| Rule tokenizer memo | **~0.15s** | [PR #291](https://github.com/teamleaderleo/starsector-preflight/pull/291) |
| Rule command-package map | **~0.17s** | [PR #298](https://github.com/teamleaderleo/starsector-preflight/pull/298) |
| Shared profile-identity pass | **1.613s → 0.452s** | [PR #300](https://github.com/teamleaderleo/starsector-preflight/pull/300) |

The texture path also stopped allocating empty power-of-two padding. In one full load, texture uploads fell from **3.65 GiB to 2.43 GiB**, removing **1.22 GiB** while serving more textures. See [The texture padding is gone](docs/evidence/2026-08-02-the-padding-is-gone.md).

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

On macOS, `install` creates `~/Applications/Starsector Preflight.app`. Linux receives a command and desktop entry. Windows receives a local command launcher.

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

- [Automatic launch and discovery](docs/automatic-launch.md)
- [Vanilla runtime adapter](docs/vanilla-adapter.md)
- [Repeated startup benchmark](docs/startup-benchmark.md)
- [Benchmarking](docs/benchmarking.md)
- [Asset lint](docs/asset-lint.md)
- [Optimization North Star](docs/optimization-north-star.md)
- [Architecture](docs/architecture.md)
- [Roadmap](docs/roadmap.md)
- [Evidence archive](docs/evidence/)

## Status

Preflight is under active development. Runtime acceleration is pinned to reviewed game classes and exact profile inputs, with the original game path retained for unsupported or changed inputs.

## License

[MIT](LICENSE). Starsector, Fast Rendering, and mod content remain the property of their respective owners and are never included in this repository or its releases.
