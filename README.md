# Preflight

**A free, open-source performance launcher for Starsector. On my 83-mod setup, a controlled
comparison measured an 89.00-second normal-launch median and a 15.53-second Preflight median.**

> Preflight is an independent, unofficial project. It isn't affiliated with or endorsed by Fractal
> Softworks.

> **Release candidate.** Public downloads are coming after the packaged Windows, macOS, and Linux
> candidate finishes its remaining release checks. Progress is tracked in
> [Release readiness](docs/release-readiness.md).

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/images/desktop-home-dark.png">
  <img alt="Preflight ready to launch Starsector" src="docs/images/desktop-home-light.png">
</picture>

**101 seconds → 15.25 seconds on the 83-mod development installation.**

Preflight started with one question: how much of a heavily modded Starsector launch is work the game
and mods have already done before? The answer, inconveniently for any hope of keeping this project
small, was quite a lot of it.

Preflight prepares repeatable texture, data, generated-code, and audio work ahead of launch and
reuses it while the relevant game and mod inputs still match; when something changes or prepared
data fails a check, Starsector handles that work normally. Once I started following the time around,
though, the performance work kept uncovering adjacent annoyances, and Preflight gradually acquired a
lot of companion-app behavior around the original launcher job.

## A lot more than a launch button

- **Measure your own result.** The desktop benchmark compares a normal launch with a Preflight launch
  on the current installation, and **Copy result** turns that pair into something you can paste into
  a forum, Discord, or issue without dragging private paths, logs, or the whole run record along with
  it.
- **Track Starsector playtime.** Local play history follows sessions launched through Preflight even
  if the desktop minimizes or exits afterward; **Copy playtime** shares the useful summary, and the
  engine can export versioned JSON plus spreadsheet-safe CSV when you want the actual history.
- **Keep named mod profiles and the useful launch settings together.** Save, search, switch, rename,
  duplicate, and delete profiles, with a preview and backup before the enabled-mod selection changes;
  resolution, fullscreen, sound, antialiasing, UI scale, RAM, and battle size live beside Launch,
  including presets through **2,000 deployment points** while still using Starsector's own
  preference.
- **Interrogate a giant mod setup without starting the game.** `preflight scan` inventories the
  enabled profile, while the deeper read-only setup check can report missing enabled mods, broken
  metadata, duplicate mod IDs, missing or disabled required dependencies, and variants that refer to
  hulls absent from the active profile.
- **See the storage cost before preparation, and recover without ritual sacrifice.** Preflight
  calculates the current profile, reuses matching data, keeps a free-space reserve, and can offer a
  much smaller minimal-disk route when the normal preparation will not fit; preparation can stop
  safely, damaged prepared data can be repaired, and cleanup is previewed before deletion.

And then there is the stuff that tends to appear after you have already decided you understand the
scope of the application: signed updates, **Copy setup** and deeper support reports, cleanup and
removal controls, a locally generated wireframe Hangar, and a read-only mod linter whose most useful
calibration result may be that 44 of the 86 mods I pointed it at were completely clean.

The native desktop packages bring their own minimal Java runtime, so ordinary desktop use does not
require a system JDK, and the everyday loop is pleasantly uneventful: open Preflight, let it find
Starsector, press **Prepare and launch** once, then use **Launch Starsector** on later runs while the
matching prepared work gets reused behind the scenes.

## The measured result

In the latest same-session comparison, five ordinary launches had an **89.00-second median**, five
Preflight launches had a **15.53-second median**, and the lowest recorded launch took **15.25
seconds**.

| Reference point | Main-menu time | Meaning |
| --- | ---: | --- |
| Observed early high | **~101s** | Worst case seen on the development installation |
| Initial five-run baseline | **88.13s** | Earlier 77-mod median |
| Controlled baseline, one session | **89.00s** | Five normal launches on the 83-mod profile |
| Controlled Preflight result | **15.53s** | Five Preflight launches in the same session |
| Lowest run in that comparison | **15.25s** | Lowest of those five Preflight launches |

The latest comparison used one 83-mod profile on an M5 MacBook Air running Starsector 0.98a-RC8 and
the game's bundled x86-64 Java runtime through Rosetta; the launch order was shuffled, the machine
cooled for 240 seconds before each run, and all ten runs were kept.

Hardware, mods, storage, cache warmth, memory pressure, translation, and system load all affect the
result, which is why Preflight's benchmark measures the normal and accelerated launch on each
installation instead of asking everybody to extrapolate from mine. The full development record and
component evidence live in [Optimization history](docs/optimization-history.md).

## Disk and preparation

Preflight calculates the requirement before writing, and if the default will not fit it can offer
**Prepare with minimal disk** instead.

| Mode | Finished cache on this 83-mod profile | Observed preparation |
| --- | ---: | ---: |
| **Balanced** (default) | **4.76 GB** | 3m21s in one measured run |
| **Minimal disk** | **10.9 MB** | 5.6s |
| **Fastest** | **10.03 GB** | More disk for a small texture-replay gain |

Balanced needed **12.92 GB** free before starting in that measurement because the preflight check
keeps extra room for worst-case preparation, even though the finished cache was 4.76 GB. Actual
costs depend on the enabled mods, and the app calculates them for the current profile.

Those preparation times came from one development session and serve as reference points. See
[Performance and storage tradeoffs](docs/performance-storage-tradeoffs.md) for the detailed model and
CLI controls; for the package-level native command and network inventory, see the
[capability receipt](docs/capability-receipt.md).

## From install to launch

On first open, Preflight searches the usual installation folders. If Starsector is elsewhere, choose
the folder containing `Starsector.app`, `starsector.exe`, or `starsector.sh`.

The native desktop package includes its own minimal Preflight Java runtime. A system JDK is required
only for the standalone JAR and development path.

![Preflight asking for a Starsector installation](docs/images/walkthrough-setup.png)

Once the current mod profile is prepared, Home keeps the routine controls together. The large button
launches Starsector; resolution, battle size, RAM, antialiasing, UI scale, fullscreen, and sound can
be changed beside it.

![Preflight ready to launch an 83-mod profile](docs/images/walkthrough-ready.png)

The benchmark opens Starsector normally, opens it again with Preflight, and shows the difference. It
does not need Accessibility permission or desktop-input automation.

![Preflight startup benchmark](docs/images/walkthrough-benchmark.png)

## When the game or a mod changes

Preflight keeps Starsector JARs, mod JARs, executables, assets, and saves outside the acceleration
path. Runtime changes live inside the launched game process and disappear when the game exits. Two
backed-up features can update game-owned preferences: named-profile activation and the launch-settings
editor.

| Situation | What happens |
| --- | --- |
| A game or mod file changes | Preflight uses the new content identity |
| Prepared data is missing or damaged | Starsector handles that work normally |
| Reviewed runtime code changes | That shortcut steps aside |
| Preparation is interrupted | Completed reusable work remains available |
| Preparation needs more disk than is available | Preflight stops before writing |
| Cleanup or removal is requested | Preflight previews its own targets first |

A larger game, launcher, or runtime update can still require a Preflight update. Current limitations
are in [Known limitations](docs/known-limitations.md); the deeper behavioral contract is in the
[Product contract](docs/product-contract.md).

## How this was developed

I used ChatGPT/Codex and Claude Code throughout development, and the repository keeps the experiments
that worked alongside the ones that failed, which is useful here because several of the failures are
more memorable than a clean success log: early prepared-texture pilots reported healthy hit counters
while producing cropped, tiled, black, or displaced visuals; a supposed timing bimodality turned out
to be a stale benchmark anchor; Java Flight Recorder's clock under one runtime setting ran about
2.49 times away from wall clock; a GraphicsLib replay expanded a roughly 0.25-second path to around
1.70 seconds; and AppCDS failed to establish a useful win for the shipped obfuscated classes.

The benchmark became part of the product because measuring the whole launch kept overturning local
assumptions, and the repository keeps the source, regression tests, experiment history, and retained
performance evidence behind the public numbers instead of retelling the work as a suspiciously
straight line toward success.

Ordinary game launches upload no logs or telemetry. Support sending is a separate action, automatic
failed-run reporting starts off, and this is a beta, so bug reports are welcome.

## Development quick start

Public packages are still in release-candidate preparation. Compile the self-contained CLI and Java
agent with JDK 17 and Maven 3.9 or newer:

```bash
./mvnw verify
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

Inspect the resolved setup more deeply without launching or changing it:

```bash
java -jar preflight-cli/target/preflight.jar analyze setup
```

Print the selected launch command without starting Starsector:

```bash
java -jar preflight-cli/target/preflight.jar run --dry-run
```

Unattended launches can use Starsector's saved display and sound settings directly:

```bash
java -jar preflight-cli/target/preflight.jar run --direct \
  --optimization-preset recommended
```

The desktop app uses the same command engine. Development and packaging instructions are in
[preflight-desktop](preflight-desktop/README.md).

## Where the time went

The largest early finding was a one-thread texture prefetch queue. The loading thread could wait
roughly 27 seconds for that queue, then repeat source hashing, image decoding, pixel conversion,
buffer copying, color calculation, and padding; moving prepared texture work ahead of that wait
removed 25.53 seconds in the accepted controlled campaign.

Once textures became cheap, the visible 0% pause became clear: vanilla `SpecStore` was spending
roughly 18–19 seconds rebuilding variants, weapons, projectiles, hulls, campaign rules, and related
registries, much of it repeated JSON and CSV merge-and-parse work. Once that enormous lump was
cheaper too, the tail became interesting in a more eclectic way, with AshLib repeatedly resolving
the same hull and variant JSON, GraphicsLib repeating automatic texture-map discovery, and Janino
regenerating highly overlapping class maps; the accepted optimizations reduce that repeated work
while leaving live game objects with Starsector and the mods that own them.

| Change | Measured result |
| --- | ---: |
| Prepared textures and prefetch bypass | **25.53s saved; 1.41× overall** |
| AshLib ship JSON memoization | **7.07–7.44s removed from the callback** |
| GraphicsLib compact replay | **4.82s removed from the measured sequence** |
| Merged variant JSON | **10.15× quicker merge/parse; ~2.7s net** |
| Merged weapon, projectile, and hull JSON | **~4.8s net combined** |
| Shared cache-profile identity | **1.613s → 0.452s** |

Texture uploads also fell from 3.65 GiB to 2.43 GiB after empty power-of-two padding was removed.
The source-linked chronology and per-change arithmetic live in
[Optimization history](docs/optimization-history.md), the
[Experiment ledger](docs/experiment-ledger.md), and the
[Accumulated scorecard](docs/evidence/2026-08-02-accumulated-startup-scorecard.md).

## Storage choices

Balanced is the default. It keeps upload-ready texture pixels in LZ4 blocks when compression saves
meaningful space and retains raw storage where compression buys little, while Fastest keeps every
pixel array raw and trades disk space for less decode CPU.

On the 83-mod development profile, Balanced reduced the texture pack from 5.34 GB to 2.26 GB. Ten
fresh-JVM replays measured the startup access order at 1,137ms for Balanced and 691ms for Fastest,
and a later preparation measured the complete cache directories at about 4.76 GB for Balanced and
10.03 GB for Fastest.

Preparation calculates decoded texture size, deduplication, reusable data, pack duplication, a
safety margin, and current filesystem space. It keeps at least 1 GiB in reserve and stops before
writing when the plan will not fit; existing prepared profiles stay active until a new preparation
completes.

```bash
java -jar preflight-cli/target/preflight.jar prepare --plan --json
java -jar preflight-cli/target/preflight.jar prepare --texture-storage balanced
java -jar preflight-cli/target/preflight.jar prepare --texture-storage fastest
java -jar preflight-cli/target/preflight.jar cache prune --json
```

See [Performance and storage tradeoffs](docs/performance-storage-tradeoffs.md) for the detailed disk
model and pruning behavior.

## Profiles, diagnostics, and removal

<img alt="Preflight saved mod profiles" src="docs/images/desktop-profiles-light.png">

Named profiles preserve ordered mod selections and let prepared data follow the setup it belongs to;
they can be searched, renamed, duplicated, switched, and deleted, and a duplicate can be made before
an experiment without changing the active game profile or copying mods, saves, or prepared data.

Diagnostics are managed separately from acceleration data. **Copy setup** produces a compact support
summary and is also available directly from failed-run recovery, while the deeper ZIP export includes
a small disclosed set of diagnostic text files from recent runs and benchmarks.

Removal has two scopes. Removing the launcher leaves Starsector and Preflight's reusable data in
place; removing all Preflight data includes caches, profiles, retained diagnostics, and backups after
a target review. Neither scope includes Starsector, mods, saves, or game-owned settings.

See [Diagnostics export](docs/diagnostics.md), [Privacy](docs/privacy.md),
[Portable play-history export](docs/play-history-export.md), and
[Downloads and installation](docs/downloads.md).

## Before the public beta

Four release checks remain:

1. Exercise real-game installations on Windows and Linux.
2. Freeze and exercise the complete hosted Windows, macOS, and Linux candidate.
3. Run the startup benchmark from the packaged candidate and retain the result.
4. Complete the packaged support-intake canary cancel/retry/delete sequence.

The Fractal Softworks courtesy request is outside the publication gate. The complete checklist is in
[Release readiness](docs/release-readiness.md), and the broader follow-up work is in the
[Public beta roadmap](docs/beta-roadmap.md).

## Analysis and mod tools

The repository also contains the measurement tools used during the investigation: JFR recording,
startup-phase probes, loader attribution, unattended benchmark campaigns, crash detection, a profile
census, a deep read-only setup checker, and the mod linter. Normal accelerated launches do not
require profiling.

```bash
java -jar preflight-cli/target/preflight.jar lint
java -jar preflight-cli/target/preflight.jar lint --path ./MyMod
java -jar preflight-cli/target/preflight.jar analyze setup
```

Across real profiles, the linter has found progressive JPEGs that decode about 8.75 times slower
through the game's ImageIO path, large texture and audio costs, shadowed resources, extension
mismatches, unused files, and configuration placed where the game never reads it. Its thresholds
were calibrated across 86 installed mod directories: the median was zero findings and 44 of 86 were
completely clean. See [Asset lint](docs/asset-lint.md) for the checks and evidence.

## Documentation

- [Documentation map](docs/README.md)
- [Public writing style](docs/public-writing-style.md)
- [Leo's release talking points](docs/leo-talking-points.md)
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

## Support Preflight

Preflight is free and open source. If it saves you a pile of waiting, helps you manage a ridiculous
mod list, or you simply like this kind of work, you can
[support its development on Patreon](https://www.patreon.com/cw/teamleaderleo).

Support helps with testing hardware, hosting, release work, and development time. The application,
source, features, and public support stay available to everyone.

## License

[MIT](LICENSE). Starsector, Fast Rendering, and mod content remain the property of their respective
owners. The repository and release packages contain none of those assets. Preflight is an
independent, unofficial project and isn't affiliated with or endorsed by Fractal Softworks.