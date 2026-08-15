# Preflight

**A performance launcher for Starsector. Preflight prepares repeatable loading work ahead of time,
then launches the same game and mod profile with those results ready to use.**

> Preflight is an independent, unofficial project. It isn't affiliated with or endorsed by Fractal
> Softworks.

> **Release-candidate preparation.** Public downloads aren't available yet. The remaining technical
> work is a hosted three-platform candidate, its exact-package benchmark, and final install/update/
> removal checks. Windows and Linux real-game runs determine how broadly the first beta can claim
> support; Fractal Softworks' requested guidance remains a separate publication decision. The
> current checklist is in [Release readiness](docs/release-readiness.md).

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/images/desktop-home-dark.png">
  <img alt="Preflight ready to launch Starsector" src="docs/images/desktop-home-light.png">
</picture>

**101 seconds → 15.88 seconds on an 83-mod development installation.** The initial five-run median
was 88.13 seconds.

Preflight found much of Starsector's heavily modded startup time in work whose answer was already
determined by the game build, enabled mod order, and resource files. It prepares those answers once,
stores them under exact content identities, and reuses them on matching launches. Starsector still
constructs its live objects, applies mod ordering, registers scripts, uploads textures, and runs mod
logic.

## The measured result

| Reference point | Main-menu time | Meaning |
| --- | ---: | --- |
| Observed early high | **~101s** | Worst case seen on the development installation |
| Initial five-run baseline | **88.13s** | Median of five unaccelerated launches |
| Best validated warm gate | **15.88s** | Best validated result on the later 83-mod profile |

These are chronological reference points from one M5 MacBook Air running Starsector 0.98a-RC8 and
the game's bundled x86-64 Java runtime through Rosetta. The latest production gates were 16.66
seconds cold, 16.28 seconds warm, and 15.88 seconds warm. Current whole-launch run-to-run spread on
the reviewed machine is roughly ±0.6 seconds. The 15.88-second gate retained all 42 transformed
class-cache hits, 15,469 prepared texture and pixel-conversion hits, healthy adapters, and no
adapter decline or failure.

Hardware, mods, storage, cache warmth, memory pressure, translation, and temperature all affect the
result. Preflight's benchmark lets each installation measure its own normal and accelerated launch.
The development measurements and their context are collected in
[Optimization history](docs/optimization-history.md).

## What Preflight handles

- **Preparation.** Textures, merged data, generated mod bytecode, and audio are prepared under the
  exact game and ordered-mod profile that produced them.
- **Launch.** Recommended mode applies reviewed runtime shortcuts inside the child game JVM and
  tracks which adapters ran, declined, or failed.
- **Profiles.** Named mod profiles retain their own identities and prepared data. Switching a
  profile previews the exact `enabled_mods.json` change and saves a backup.
- **Storage.** The desktop app calculates a conservative disk requirement before writing, shows
  current use, and previews cleanup before anything is removed.
- **Game settings.** Resolution, fullscreen, sound, antialiasing, UI scale, RAM, and battle size are
  available beside the launch button.
- **Evidence.** The benchmark compares a normal launch with Preflight. A separate support ZIP
  contains bounded, disclosed metadata and excludes saves, assets, screenshots, recordings, caches,
  and arbitrary logs.

The desktop path is designed around a single routine: detect Starsector, prepare the current profile,
and launch. Recommended optimizations and Balanced storage are the defaults.

## From install to launch

On first open, Preflight searches the usual installation folders. If Starsector isn't there, choose
the folder containing `Starsector.app`, `starsector.exe`, or `starsector.sh`.

![Preflight asking for a Starsector installation](docs/images/walkthrough-setup.png)

Once the current mod profile is prepared, the home screen keeps the routine controls together. The
large button launches Starsector; resolution, battle size, RAM, antialiasing, UI scale, fullscreen,
and sound can be changed beside it.

![Preflight ready to launch an 83-mod profile](docs/images/walkthrough-ready.png)

The optional benchmark runs a normal launch and a Preflight launch, then compares their main-menu
times. Support ZIPs are separate: Preflight shows their contents, exclusions, size, and checksum
before anything can be sent.

![Preflight benchmark and support controls](docs/images/walkthrough-benchmark.png)

## Compatibility and containment

Preflight doesn't rewrite game or mod JARs, executables, assets, activation data, or saves. Runtime
changes exist only in the launched JVM and disappear when the game exits. Two explicit, backed-up
features can update game-owned preferences: profile activation and the launch-settings editor.

| Situation | Result |
| --- | --- |
| A game or mod file changes | A different content identity is selected |
| A prepared entry is missing or invalid | The original loader handles that request |
| A reviewed class fingerprint changes | That runtime transformation declines |
| Preparation is interrupted | Completed immutable blobs remain reusable |
| The conservative disk bound doesn't fit | Preparation refuses before writing |
| Cleanup or removal is requested | Preflight shows the exact owned targets first |

Unsupported work continues through the game's original path. A future launcher layout or game
update can still require a Preflight update, so unknown versions aren't advertised as tested. The
full boundary is in the [Product contract](docs/product-contract.md), with current limitations in
[Known limitations](docs/known-limitations.md).

## Development quick start

Public packages aren't available during the preview. Build the self-contained CLI and Java agent
with JDK 17 and Maven 3.9 or newer:

```bash
mvn verify
```

The resulting launcher is `preflight-cli/target/preflight.jar`. Install the local launcher and
prepare the detected profile:

```bash
java -jar preflight-cli/target/preflight.jar install --prepare
```

Launch through the ordinary Starsector launcher path:

```bash
java -jar preflight-cli/target/preflight.jar run --optimization-preset recommended
```

Inspect discovery without launching the game:

```bash
java -jar preflight-cli/target/preflight.jar doctor
```

Unattended launches can use Starsector's saved display and sound settings directly:

```bash
java -jar preflight-cli/target/preflight.jar run --direct \
  --optimization-preset recommended
```

The desktop app uses the same command engine and safety checks. Its development and packaging
instructions are in [preflight-desktop](preflight-desktop/README.md).

## Where the time went

The largest early finding was a one-thread texture prefetch queue. The loading thread could wait
roughly 27 seconds for that queue, then repeat source hashing, image decoding, pixel conversion,
buffer copying, color calculation, and padding. Bypassing the wait and serving validated,
upload-ready pixels removed 25.53 seconds in the accepted controlled campaign.

Once textures became cheap, the visible 0% pause became clear. Vanilla `SpecStore` spent roughly
18–19 seconds rebuilding variants, weapons, projectiles, hulls, campaign rules, and related
registries. Much of that time was repeated JSON and CSV merge-and-parse work. Prepared tagged trees
now supply the stable input while Starsector creates fresh live objects as usual.

The remaining tail exposed repeated work in mod callbacks. AshLib repeatedly resolved the same hull
and variant JSON while constructing render information. GraphicsLib repeated automatic texture-map
discovery even when its generated files were already valid. Exact memoization and compact replay
reduced those callback sequences without taking ownership of their live state.

| Change | Measured result |
| --- | ---: |
| Prepared textures and prefetch bypass | **25.53s saved; 1.41× overall** |
| AshLib ship JSON memoization | **7.07–7.44s removed from the callback** |
| GraphicsLib compact replay | **4.82s removed from the measured sequence** |
| Merged variant JSON | **10.15× faster merge/parse; ~2.7s net** |
| Merged weapon, projectile, and hull JSON | **~4.8s net combined** |
| Shared cache-profile identity | **1.613s → 0.452s** |

Texture uploads also fell from 3.65 GiB to 2.43 GiB after empty power-of-two padding was removed.
The measured component runs contain 64,739 direct cache or memo hits and 192,089 counted operations
removed or shortcut. The source-linked chronology, unsuccessful branches, and per-change arithmetic
live in [Optimization history](docs/optimization-history.md), the
[Experiment ledger](docs/experiment-ledger.md), and the
[Accumulated scorecard](docs/evidence/2026-08-02-accumulated-startup-scorecard.md).

## Storage choices

Balanced is the default. It keeps upload-ready texture pixels in LZ4 blocks when compression saves
meaningful space and retains raw storage where compression buys little. Fastest keeps every pixel
array raw and trades disk space for less decode CPU.

On the 83-mod development profile, Balanced reduced the texture pack from 5.34 GB to 2.26 GB. Ten
fresh-JVM replays measured the exact startup access order at 1,137ms for Balanced and 691ms for
Fastest. The raw texture representation itself is about **3.08 GB** larger, and a later cold preparation
measured the complete cache directories at about 4.76 GB for Balanced and 10.03 GB for Fastest — a
roughly **5.27 GB** whole-cache difference on that profile. The exact replay seam improved by about
446ms; whole-launch impact varies with the machine and profile.

Preparation calculates decoded texture size, deduplication, reusable blobs, pack duplication, a
conservative upper bound, and current filesystem space. It keeps at least 1 GiB in reserve and
refuses before writing when the bound doesn't fit. Existing manifests stay active until a new
preparation completes.

```bash
java -jar preflight-cli/target/preflight.jar prepare --plan --json
java -jar preflight-cli/target/preflight.jar prepare --texture-storage balanced
java -jar preflight-cli/target/preflight.jar prepare --texture-storage fastest
java -jar preflight-cli/target/preflight.jar cache prune --json
```

The detailed disk model and safe pruning behavior are in
[Performance and storage tradeoffs](docs/performance-storage-tradeoffs.md).

## Profiles, diagnostics, and removal

<img alt="Preflight saved mod profiles" src="docs/images/desktop-profiles-light.png">

Named profiles preserve ordered mod selections and let prepared data follow the setup it belongs to.
Diagnostics are managed independently from acceleration data. Export includes only allowlisted text
metadata from recent runs and benchmarks, with an in-ZIP disclosure that names every included or
skipped file.

Removal has two scopes. Removing the launcher leaves Starsector and Preflight's reusable data in
place. Removing all Preflight data includes caches, profiles, retained evidence, and backups after a
target review. Neither scope includes Starsector, mods, saves, or game-owned settings.

See [Diagnostics export](docs/diagnostics.md), [Privacy](docs/privacy.md), and
[Downloads and installation](docs/downloads.md) for the exact behavior.

## Before the public beta

The acceleration and desktop workflows are far enough along for a beta. The remaining gates establish
the release claim and platform boundary:

1. Resolve the publication policy after the requested Fractal Softworks guidance window.
2. Exercise clean real-game installations on Windows and Linux. CI, synthetic installs, native
   package boot, and VMware Fusion acceptance already cover the portable engine and packaging paths.
3. Publish and rehearse the exact hosted candidate, including signed update, rollback, report,
   cleanup, removal, and recovery flows.
4. Run the final benchmark pass on the exact candidate and publish those results beside the
   established development record.

The complete publication checklist is in [Release readiness](docs/release-readiness.md). The ordered
product and evidence work is in the [Public beta roadmap](docs/beta-roadmap.md).

## Analysis and mod tools

The repository also contains the measurement tools used during the investigation: JFR recording,
startup-phase probes, loader attribution, unattended benchmark campaigns, crash detection, and a
read-only mod linter. Normal accelerated launches don't require profiling.

```bash
java -jar preflight-cli/target/preflight.jar lint
java -jar preflight-cli/target/preflight.jar lint --path ./MyMod
```

Across real profiles, the linter has found progressive JPEGs that decode 8.75× slower through the
game's ImageIO path, gigabytes of avoidable texture and audio allocation, shadowed resources,
extension mismatches, unused files, and configuration placed where the game never reads it. See
[Asset lint](docs/asset-lint.md) for the checks and evidence.

## Documentation

- [Documentation map](docs/README.md)
- [Optimization history](docs/optimization-history.md)
- [Product contract](docs/product-contract.md)
- [Release readiness](docs/release-readiness.md)
- [Public beta roadmap](docs/beta-roadmap.md)
- [Experiment ledger](docs/experiment-ledger.md)
- [Performance and storage tradeoffs](docs/performance-storage-tradeoffs.md)
- [Automatic launch and discovery](docs/automatic-launch.md)
- [Repeated startup benchmark](docs/startup-benchmark.md)
- [Prior-art review](docs/prior-art-starsector-render.md)
- [Evidence archive](docs/evidence/)

## License

[MIT](LICENSE). Starsector, Fast Rendering, and mod content remain the property of their respective
owners. The repository and release packages contain none of those assets. Preflight is an
independent, unofficial project and isn't affiliated with or endorsed by Fractal Softworks.
