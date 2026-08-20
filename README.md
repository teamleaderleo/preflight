# Preflight

**A free, open-source performance launcher for Starsector. On my 83-mod setup, a controlled
comparison measured an 89.00-second normal-launch median and a 15.53-second Preflight median.**

> Preflight is an independent, unofficial project. It isn't affiliated with or endorsed by Fractal
> Softworks.

> **Release candidate.** Public downloads are coming after the packaged Windows, macOS, and Linux
> releases finish their final checks. Progress is tracked in [Release readiness](docs/release-readiness.md).

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/images/desktop-home-dark.png">
  <img alt="Preflight ready to launch Starsector" src="docs/images/desktop-home-light.png">
</picture>

**101 seconds → 15.25 seconds on the 83-mod development installation.**

Preflight started as an attempt to answer one question: why can heavily modded Starsector spend so
long getting to the main menu, and how much of that work is the same every single launch?

Quite a lot of it.

Preflight prepares repeatable texture, data, generated-code, and audio work ahead of launch, binds
that prepared work to the game and ordered mod inputs that produced it, and reuses it while those
inputs still match. Runtime shortcuts are checked against the exact code they were reviewed against.
If something is missing, changed, damaged, or unfamiliar, the original game path handles it.

That investigation also turned into a much larger Starsector companion app: a before-and-after
startup benchmark, local playtime tracking, named mod profiles, common and extended game settings,
storage planning and recovery, deep read-only setup analysis, privacy-conscious support tools,
signed updates, and a mod linter.

## A lot more than a launch button

- **Measure your own result.** The desktop benchmark compares a normal launch with a Preflight launch
  on the current installation. **Copy result** produces a compact shareable comparison without raw
  evidence, private paths, or logs.
- **Track Starsector playtime.** A durable local ledger follows sessions launched through Preflight
  even if the desktop minimizes or exits afterward. **Copy playtime** shares total/session context,
  and the engine can export versioned JSON plus spreadsheet-safe CSV.
- **Keep named mod profiles.** Save, search, switch, rename, duplicate, and delete profiles. Switching
  previews the exact `enabled_mods.json` change and saves a backup first. Duplicating a profile does
  not duplicate mods, saves, or prepared data.
- **Put the launch settings you actually care about beside Launch.** Resolution, fullscreen, sound,
  antialiasing, UI scale, RAM, and battle size are available without a separate launcher ritual.
  Battle-size presets can extend past the vanilla slider through **2,000 deployment points** while
  still using Starsector's own `battleSize` preference.
- **Understand a giant mod setup without starting the game.** `preflight scan` inventories the
  enabled profile. The separate read-only deep setup check can report missing enabled mods, broken
  metadata, duplicate mod IDs, missing or disabled required dependencies, and resolved variants that
  point at hulls absent from the active profile.
- **Know the storage cost before writing.** Preflight calculates the current profile's predicted and
  conservative requirements, reuses matching data, keeps a reserve, and offers a tiny minimal-disk
  route when the normal preparation does not fit.
- **Recover without guessing.** Preparation can stop safely, damaged prepared data can be repaired
  for the exact profile, cleanup is previewed before deletion, and failed runs offer **Copy setup**,
  Relaunch, and Get help directly from Home.
- **Share support data deliberately.** Copy setup produces useful public facts without paths,
  credentials, saves, or arbitrary logs. The separate support ZIP is allowlisted, disclosed before
  sending, size-bounded, cancellable, and excludes game/mod assets, saves, screenshots, audio,
  caches, arbitrary logs, and credentials. Accepted reports carry retention/deletion information.
- **Update through a signed path.** The desktop can review a newer release, verify it, install it, and
  restart when asked. The release process also exercises install, upgrade, rollback, and removal
  across macOS, Windows, and Linux.
- **Inspect mods without editing them.** `preflight lint` reports measurable asset and configuration
  problems for one mod or a complete profile. It gives no score, changes no files, and can explain
  why each finding costs something.

The native desktop packages bring their own minimal Java runtime, so the desktop app does not ask you
to install a system JDK. The desktop host exposes a fixed set of typed commands rather than an
arbitrary shell. Every native package also carries a machine-readable capability receipt describing
the commands, writes, child processes, links, and network endpoints available to that exact package.

The normal path is still simple: open Preflight, let it find Starsector, press **Prepare and launch**
once, then press **Launch Starsector** on later runs. The rest is there when you want it.

## The measured result

In the latest same-session comparison, five ordinary launches had an 89.00-second median. Five
Preflight launches had a 15.53-second median, and the lowest recorded launch took 15.25 seconds.

| Reference point | Main-menu time | Meaning |
| --- | ---: | --- |
| Observed early high | **~101s** | Worst case seen on the development installation |
| Initial five-run baseline | **88.13s** | Median of five unaccelerated launches, on the earlier 77-mod profile |
| Earlier validated warm gate | **15.88s** | Previous production gate on the later 83-mod profile |
| Controlled baseline, one session | **89.00s** | Median of five vanilla launches, interleaved with the row below |
| Controlled result, one session | **15.53s** | Median of five Preflight launches in that same session |
| Lowest run in that controlled session | **15.25s** | Lowest of the five accelerated launches |

The first three rows show the development history. They were measured months apart, and the mod
list grew from 77 to 83 along the way. The last two rows come from one comparison on the same
83-mod profile. The order was shuffled inside every round, the machine cooled for 240 seconds
before each launch, and none of the ten runs were excluded. The medians are **73.47 seconds** apart.

All of it is one M5 MacBook Air running Starsector 0.98a-RC8 and the game's bundled x86-64 Java
runtime through Rosetta. The latest production gates were 16.66 seconds cold, 16.28 seconds warm,
and 15.88 seconds warm. Current whole-launch run-to-run spread on the reviewed machine is roughly
±0.6 seconds. The 15.88-second gate retained all 42 transformed-class cache hits, 15,469 prepared
texture and pixel-conversion hits, healthy adapters, and no adapter decline or failure.

Hardware, mods, storage, cache warmth, memory pressure, translation, and temperature all affect the
result. Preflight's benchmark lets each installation measure its own normal and accelerated launch.
The development measurements and their context are collected in
[Optimization history](docs/optimization-history.md).

## Disk and preparation

Preflight calculates the requirement before writing anything. If the default does not fit, it can
offer **Prepare with minimal disk** instead.

| Mode | Finished cache on this 83-mod profile | Observed preparation |
| --- | ---: | ---: |
| **Balanced** (default) | **4.76 GB** | 3m21s in one measured run |
| **Minimal disk** | **10.9 MB** | 5.6s |
| **Fastest** | **10.03 GB** | More disk for a small texture-replay gain |

Balanced needed **12.92 GB** free before starting because the safety check assumes a worst-case
preparation bound; that larger number is not the finished cache size. Actual costs depend on the
artwork in the enabled mods, and the app calculates them for the current profile. Minimal skips
prepared textures while keeping the smaller startup indexes and caches. The measurements and CLI
controls are in [Performance and storage tradeoffs](docs/performance-storage-tradeoffs.md).

Those preparation times came from one development session. The files were already warm in the OS
cache, while temperature, system load, and competing processes were not recorded. They show what
happened in that run rather than what another machine should expect.

## What Preflight actually does

- **Preparation.** Textures, merged data, generated mod bytecode, and audio are prepared under the
  exact game and ordered-mod profile that produced them.
- **Launch.** Recommended mode applies reviewed runtime shortcuts inside the child game JVM and
  tracks which adapters ran, declined, or failed.
- **Benchmark.** The permission-free desktop benchmark compares the same sealed installation and
  profile through normal and Preflight launch paths, then retains one versioned result.
- **Playtime.** A durable local ledger totals how long Starsector remains open across launches that
  Preflight can observe. It continues recording when the desktop minimizes or exits after launch.
  The UI can copy a bounded summary and the engine can export versioned JSON or CSV.
- **Profiles.** Named mod profiles retain their own identities and prepared data. Switching a profile
  previews the exact `enabled_mods.json` change and saves a backup. Saved profiles can be searched,
  renamed, duplicated, and deleted without copying mods, saves, or cache bytes.
- **Storage.** The desktop calculates a conservative disk requirement before writing, separates
  prepared data from evidence, and previews cleanup before anything is removed. Cleanup keeps the
  current and readable saved profiles reachable.
- **Game settings.** Resolution, fullscreen, sound, antialiasing, UI scale, RAM, and battle size are
  available beside the launch button. The extended battle-size ceiling is 2,000 deployment points.
- **Presentation.** Home can use the full Hangar view or a compact launch-first view. Recorded
  playtime visibility and decorative hull motion/direction are display-only preferences. Featured
  Hangar ships can be traced locally from installed hull/sprite data into Preflight's own wireframe
  rendering rather than bundling Starsector ship art.
- **Setup analysis.** A read-only deep pass checks mod metadata/dependencies and selected resolved
  static references without launching or changing the game.
- **Evidence and support.** Help can copy a bounded privacy-safe setup summary for a forum, Discord,
  or support conversation. A separate support ZIP contains disclosed allowlisted metadata and
  excludes saves, assets, screenshots, recordings, caches, arbitrary logs, and credentials.
- **Updates and package identity.** The desktop release path uses the project updater key for signed
  updates, and every package carries a machine-checked capability receipt describing the commands,
  writes, child processes, links, and network endpoints available to that exact package.
- **Analysis tools.** The CLI includes the profiler and measurement tools used during development,
  profile discovery/census/dry-run commands, deep setup analysis, and a read-only mod linter for
  measurable asset and configuration problems.

## From install to launch

On first open, Preflight searches the usual installation folders. If Starsector is not there, choose
the folder containing `Starsector.app`, `starsector.exe`, or `starsector.sh`.

The native desktop package includes its own minimal Preflight Java runtime. A system JDK is required
only for the standalone JAR/development path, not for ordinary desktop use.

![Preflight asking for a Starsector installation](docs/images/walkthrough-setup.png)

Once the current mod profile is prepared, the home screen keeps the routine controls together. The
large button launches Starsector; resolution, battle size, RAM, antialiasing, UI scale, fullscreen,
and sound can be changed beside it.

![Preflight ready to launch an 83-mod profile](docs/images/walkthrough-ready.png)

The benchmark opens Starsector normally, opens it again with Preflight, and shows the difference.
It does not need Accessibility permission or click through the game on your behalf.

![Preflight startup benchmark](docs/images/walkthrough-benchmark.png)

## Compatibility and containment

Preflight does not rewrite game or mod JARs, executables, assets, activation data, or saves. Runtime
changes exist only in the launched JVM and disappear when the game exits. Two explicit, backed-up
features can update game-owned preferences: profile activation and the launch-settings editor.

| Situation | Result |
| --- | --- |
| A game or mod file changes | A different content identity is selected |
| A prepared entry is missing or invalid | The original loader handles that request |
| A reviewed class fingerprint changes | That runtime transformation declines |
| Preparation is interrupted | Completed immutable blobs remain reusable |
| The conservative disk bound does not fit | Preparation refuses before writing |
| Cleanup or removal is requested | Preflight shows the exact owned targets first |

Anything Preflight does not recognize continues through the game's original path. A future launcher
layout or game update can still require a Preflight update. The desktop native boundary is a fixed
set of typed commands, not a generic shell, and every packaged release records its available native
capabilities in the [capability receipt](docs/capability-receipt.md).

The full product boundary is in the [Product contract](docs/product-contract.md), with current
limitations in [Known limitations](docs/known-limitations.md).

## How this was developed

Yes. I used ChatGPT/Codex and Claude Code throughout development.

The repository includes the history of the experiments that were tried, including the ones that
failed. Some of those failures are more informative than the successes:

- early prepared-texture pilots had healthy hit counters while producing cropped, tiled, black, or
  displaced visuals;
- a supposed timing bimodality turned out to be a stale benchmark anchor;
- Java Flight Recorder's clock under one runtime setting ran about 2.49 times away from wall clock;
- a GraphicsLib replay expanded a roughly 0.25-second path to around 1.70 seconds and was removed;
- AppCDS did not establish a safe win for the shipped obfuscated classes and was removed too.

When stuff succeeded, I made sure it continued to succeed. When stuff failed, I figured out why and
made sure the same failure had a regression test or an explicit rejected record where that was
useful. The benchmark exists in the product partly because this project repeatedly demonstrated
that a convincing number can still be measuring the wrong thing.

Preflight checks installed code before applying an optimization. If it does not recognize something,
it leaves it alone. I tried to get the app itself to also be as performant as possible. This should
be better than Microsoft Teams.

Preflight does not modify saves. Ordinary game launches upload no logs or telemetry. Support-report
sending is a separate disclosed action using the bounded ZIP shown in Help, and automatic failed-run
reporting stays off until you enable it. This is still a beta. If you find a problem, please report
it. I will investigate.

Every package also carries a [machine-checked capability receipt](docs/capability-receipt.md) listing
the commands, writes, child processes, links, and network endpoints available to that exact package.

## Development quick start

Public packages are not available during the preview. Compile the self-contained CLI and Java agent
with JDK 17 and Maven 3.9 or newer:

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

Print the exact selected launch command without starting Starsector:

```bash
java -jar preflight-cli/target/preflight.jar run --dry-run
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
| Merged variant JSON | **10.15× quicker merge/parse; ~2.7s net** |
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
Fastest. The raw texture representation itself is about **3.08 GB** larger, and a later cold
preparation measured the complete cache directories at about 4.76 GB for Balanced and 10.03 GB for
Fastest, a roughly **5.27 GB** whole-cache difference on that profile. The exact replay seam improved
by about 446ms; whole-launch impact varies with the machine and profile.

Preparation calculates decoded texture size, deduplication, reusable blobs, pack duplication, a
conservative upper bound, and current filesystem space. It keeps at least 1 GiB in reserve and
refuses before writing when the bound does not fit. Existing manifests stay active until a new
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
They can be searched, renamed, duplicated, switched, and deleted. A duplicate can be made before an
experiment without changing the active game profile or duplicating mods, saves, or prepared bytes.
The local play-history ledger can be exported as a versioned JSON document with an optional
spreadsheet-safe CSV view.

Diagnostics are managed independently from acceleration data. **Copy setup** produces a compact
privacy-safe summary for ordinary support and is also available directly from failed-run recovery.
The separate ZIP export includes only allowlisted text metadata from recent runs and benchmarks,
with an in-ZIP disclosure that names every included or skipped file.

Removal has two scopes. Removing the launcher leaves Starsector and Preflight's reusable data in
place. Removing all Preflight data includes caches, profiles, retained evidence, and backups after a
target review. Neither scope includes Starsector, mods, saves, or game-owned settings.

See [Diagnostics export](docs/diagnostics.md), [Privacy](docs/privacy.md),
[Portable play-history export](docs/play-history-export.md), and
[Downloads and installation](docs/downloads.md) for the exact behavior.

## Before the public beta

The desktop product is largely there. The remaining gates establish the release claim and platform
boundary:

1. Resolve the publication policy after the requested Fractal Softworks guidance window.
2. Exercise clean real-game installations on Windows and Linux. CI, synthetic installs, native
   package boot, and VMware Fusion acceptance already cover substantial portable-engine and package
   behavior.
3. Freeze and exercise the exact hosted candidate, including signed update, rollback, report,
   cleanup, removal, recovery, and final package identity.
4. Run the final benchmark pass on the exact candidate and publish that result beside the
   established development record.

The complete publication checklist is in [Release readiness](docs/release-readiness.md). The ordered
product and evidence work is in the [Public beta roadmap](docs/beta-roadmap.md).

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

The setup checker can flag deterministic active-profile problems such as missing or disabled required
dependencies, duplicate mod IDs, broken enabled-mod metadata, and winning variants that reference a
hull absent from the resolved profile. It does not launch or modify Starsector.

Across real profiles, the linter has found progressive JPEGs that decode about 8.75 times slower
through the game's ImageIO path, gigabytes of avoidable texture and audio allocation, shadowed
resources, extension mismatches, unused files, and configuration placed where the game never reads
it. Its thresholds were calibrated across 86 installed mod directories: the median was zero findings
and 44 of 86 were completely clean. It has no score or automatic fixer. See
[Asset lint](docs/asset-lint.md) for the checks and evidence.

## Documentation

- [Documentation map](docs/README.md)
- [Leo's release talking points](docs/leo-talking-points.md)
- [Public-writing sales inventory](docs/public-writing-sales-inventory.md)
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
mod list, or you simply like this kind of obsessive work, you can
[support its development on Patreon](https://www.patreon.com/cw/teamleaderleo).

Support helps with testing hardware, hosting, release work, and the time that goes into maintaining
and extending the project. The application, source, features, and public support stay available to
everyone.

## License

[MIT](LICENSE). Starsector, Fast Rendering, and mod content remain the property of their respective
owners. The repository and release packages contain none of those assets. Preflight is an
independent, unofficial project and isn't affiliated with or endorsed by Fractal Softworks.
