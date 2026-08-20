# Public beta release writing kit

This is the release-day source of truth for public descriptions, GitHub Release copy, download-page
copy, and short social/forum blurbs. It is not a substitute for the exact evidence documents.
Replace every bracketed field after the candidate is frozen and the packaged benchmark/report/package
checks are complete.

The full public announcement is [beta-announcement-draft.md](beta-announcement-draft.md). The shorter
forum/Reddit version is [beta-announcement-leo-draft.md](beta-announcement-leo-draft.md). The
intentionally excessive hook reservoir is [Public-writing sales inventory](public-writing-sales-inventory.md).

## Headline options

Use the exact candidate result once available. Until then, keep the development chronology explicit.
These options deliberately range from restrained to hard sell so the final venue can choose without
inventing copy from scratch.

- **Preflight 0.1.0: a performance launcher for Starsector**
- **Preflight public beta: 89.00s → 15.53s median on my 83-mod Starsector setup**
- **Preflight: faster Starsector launches, plus playtime, profiles, mod diagnostics, and a lot more**
- **Preflight public beta: the Starsector performance launcher that got out of hand**
- **I tried to reduce one loading screen and accidentally made a Starsector companion app**
- **Preflight: measure your launch, manage the mod setup, then get into Starsector**

Do not silently replace the controlled median with the 101s → 15.25s chronological headline in a
context that reads like a controlled before/after.

## Deck / subtitle options

These can sit under the title on GitHub, a landing page, Patreon, or a longer announcement.

- **A free, open-source launcher that prepares repeated startup work, measures the result on your own installation, and falls back to the original game path when an exact check no longer matches.**
- **Built-in before/after benchmark, Starsector playtime tracking, named mod profiles, launch settings through 2,000-point battles, storage/recovery tools, signed updates, deep setup analysis, and a read-only mod linter.**
- **The performance investigation went from a 101-second observed high to a 15.53-second controlled median, then kept going until it had profiles, diagnostics, rollback rehearsals, and a Hangar.**

## The 15-second sale

### Player-first

Preflight makes heavily modded Starsector launch sooner and lets you measure the difference yourself.
It also tracks playtime, remembers named mod setups, puts the useful game settings beside Launch,
checks the mod stack without starting the game, and tells you what preparation will cost before it
writes anything.

### Skeptical-user-first

Preflight does not permanently patch Starsector or your mods. Runtime shortcuts are pinned to the
code they were reviewed against and decline individually when that code changes. The desktop host is
not a generic shell, ordinary launches upload no logs or telemetry, and the downloaded package
carries a receipt describing its native commands, writes, child processes, links, and endpoints.

### Modder/developer-first

The same profiling work that found the startup bottlenecks also powers a read-only mod linter,
profile census, and deep setup checker. The repository keeps failed experiments and corrected
measurements, and the release path applies the same attitude to signed updates, rollback, exact
package bytes, checksums, SBOMs, and capability receipts.

## One-paragraph description

Preflight is a free, open-source performance launcher for Starsector. It prepares repeatable texture,
data, generated-code, and audio work ahead of launch, checks that the exact game and ordered mod
inputs still match, and reuses the result while they do. The desktop also includes a normal-versus-
Preflight startup benchmark, Starsector playtime tracking, named mod profiles, common and extended
game settings, storage planning and recovery, deep read-only setup analysis, privacy-conscious
support tools, signed updates, and a locally generated wireframe Hangar. The CLI adds profile census,
launch-preview/direct-launch tools, and a read-only mod linter. Runtime shortcuts fall back to the
original game path when their exact checks do not match.

## GitHub Release body source

# Preflight 0.1.0

Preflight is a free, open-source performance launcher for Starsector.

On the 83-mod development installation, the latest same-profile controlled comparison measured five
ordinary launches at an **89.00-second median** and five Preflight launches at a **15.53-second
median**. The lowest recorded Preflight launch in that comparison was **15.25 seconds**. The earlier
observed high was roughly **101 seconds**.

The exact release candidate recorded **[CANDIDATE BENCHMARK RESULT]** on **[GAME VERSION / HARDWARE /
RUNTIME]**. Results depend on hardware, mods, storage, cache warmth, memory pressure, and system load.
Preflight includes the same before-and-after benchmark in the desktop app so you can measure your own
installation.

The loading-time experiment did not stay a loading-time experiment.

## What is in the first beta

- **Prepared startup work.** Textures, merged/spec data, generated mod bytecode, audio, and other
  repeatable inputs can be prepared ahead of launch and reused while their exact identity still
  matches.
- **Reviewed runtime shortcuts.** Recommended mode applies exact-code-gated shortcuts inside the
  launched game process. A changed or unknown target uses the original game path. Conservative keeps
  the broad startup caches while omitting mod-specific/gameplay shortcuts, and Off gives a clean
  troubleshooting route without removing the rest of the application.
- **Built-in startup benchmark.** Compare a normal launch with a Preflight launch on the current
  installation without desktop-input automation. **Copy result** produces a compact shareable
  comparison.
- **Starsector playtime tracking.** Preflight keeps a durable local total for sessions it launches and
  can observe. **Copy playtime** shares the useful summary; the engine can also export versioned JSON
  and spreadsheet-safe CSV.
- **Named mod profiles.** Create, search, switch, rename, duplicate, and delete saved profiles.
  Switching previews the exact enabled-mod change and saves a backup before applying it. Duplicating
  a profile does not duplicate mods, saves, or prepared data.
- **Launch settings that go beyond the vanilla launcher ritual.** Resolution, fullscreen, sound,
  antialiasing, UI scale, RAM, and battle size live beside Launch. The extended battle-size controls
  can offer presets through **2,000 deployment points** while writing Starsector's own preference.
- **Read-only mod-stack diagnosis.** `preflight analyze setup` can report missing enabled mods,
  invalid metadata, duplicate mod IDs, missing or disabled required dependencies, and selected
  resolved variants that reference hulls absent from the active profile without launching or
  modifying the game.
- **Storage planning.** Preflight calculates the current profile before writing, shows predicted and
  conservative requirements, accounts for reusable data, and can offer a minimal-disk preparation
  when the normal cache will not fit.
- **Recovery and repair.** Preparation can be stopped safely; damaged prepared data can be repaired
  for the exact profile; low disk is refused before unsafe writing; failed runs expose Relaunch,
  **Copy setup**, Get help, and Dismiss.
- **Preview-first cleanup and removal.** Cleanup shows what is reclaimable. Removing the application
  and removing all Preflight-owned data are separate choices. Starsector, mods, and saves stay
  outside both scopes.
- **Privacy-conscious support.** Copy setup produces bounded public support text. The separate support
  ZIP uses a fixed allowlist, has size limits and disclosures, and excludes saves, game/mod assets,
  screenshots, audio, caches, arbitrary logs, and credentials. Sending is separate from creation and
  review; automatic failed-run reporting is a separate setting and starts off.
- **Signed updates.** Supported updater packages can review a newer release, verify it, install it,
  and restart when the user asks. Debian packages remain package-manager-managed. Incompatible cache
  representations can coexist so rolling the app back does not first destroy the prepared data an
  older version understands.
- **Power-user launch tooling.** `doctor` shows discovery without launch, `scan` inventories the
  enabled profile, `--dry-run` prints the selected command without starting Starsector, explicit
  compatible launchers can be wrapped without replacing them, and the optional direct path uses
  Starsector's own saved launch preferences.
- **Read-only mod linting.** Inspect one mod or a whole profile for measurable asset/configuration
  costs without editing files, assigning scores, or ranking mods.
- **A Hangar that belongs to the installation.** The app can discover installed hulls and remember a
  selected display ship. Featured ships can be traced locally from installed sprite data into
  Preflight's wireframe rendering rather than shipping Starsector ship artwork, and the wireframe can
  be tuned per installation/hull.

The native desktop package contains its own minimal Preflight Java runtime. Ordinary desktop users do
not need to install a system JDK; the standalone JAR remains available for people who want the
terminal path.

## What happens when Starsector or a mod updates?

Compatibility is not one giant on/off bit.

Prepared content validates its own exact inputs. Generic prepared paths can keep working wherever
those inputs still satisfy their contracts. Runtime adapters are pinned to the exact code they were
reviewed against; if one target changes, that adapter declines and the original game code runs for
that work instead of applying an old transform because the class happens to have the same name.

A sufficiently large game/launcher/runtime change can still require a Preflight application update.
The useful distinction is that one changed mod does not automatically mean every cache and every
other reviewed path must be thrown away.

## The performance record

The development chronology is not one benchmark stretched across months. Say which number means
what.

| Reference point | Main-menu time | Meaning |
| --- | ---: | --- |
| Observed early high | **~101s** | Worst case seen on the development installation |
| Initial controlled baseline | **88.13s** | Earlier 77-mod five-run median |
| Latest same-profile baseline | **89.00s** | Five normal launches on the 83-mod profile |
| Latest same-profile Preflight result | **15.53s** | Five Preflight launches in that same controlled session |
| Lowest run in that comparison | **15.25s** | One of those five Preflight launches |
| Exact public candidate | **[CANDIDATE RESULT]** | Must come from the final distributed package |

The latest same-profile campaign interleaved the two conditions, cooled the machine for 240 seconds
before each launch, and excluded none of the ten runs. The development machine is an M5 MacBook Air
running Starsector 0.98a-RC8 with the game's bundled x86-64 Java runtime under Rosetta.

## Storage language

On the reviewed 83-mod development profile, one cold-preparation measurement retained:

- **Balanced:** 4.76 GB complete prepared-data directory;
- **Minimal disk:** 10.9 MB;
- **Fastest:** 10.03 GB complete prepared-data directory.

Balanced needed 12.92 GB free before starting in that measurement because the safety bound is larger
than the final retained directory. Do not present 12.92 GB as the finished cache size. Do not present
the older 3.08 GB texture-only delta as a whole-cache delta.

The desktop calculates the local profile before writing. Published reference numbers are examples,
not requirements for every installation.

## What Preflight leaves alone

Preflight does not permanently patch Starsector or mod JARs, executables, assets, or
saves. Runtime changes live inside the launched game JVM and disappear when it exits. Prepared data
lives in Preflight's own area.

Two explicit backed-up actions can update game-owned preferences:

1. named-profile activation changes the enabled-mod selection after preview;
2. the launch-settings editor changes the corresponding reviewed game/launcher settings.

Anything Preflight cannot identify safely continues through the original path.

## Package trust and updates

**Platforms for this release:** [PACKAGE MATRIX]

**Package trust:** the first beta does not use paid Apple Developer ID or Windows Authenticode
publisher identities. macOS and Windows may therefore show their standard unknown-developer warning.
Each native download is paired with a SHA-256 manifest.

The native desktop packages contain their own minimal Preflight Java runtime; a system JDK is not an
installation prerequisite for ordinary desktop use.

Preflight's in-app updater uses a separate project signing key. Before installation, the desktop
rechecks the exact update offer the user reviewed and Tauri verifies the downloaded updater
signature. Failure leaves the current version runnable.

The release pipeline verifies the complete artifact set and exercises native package installation,
upgrade, rollback, and removal. Publication requires **[FINAL TAGGED CANDIDATE EVIDENCE SUMMARY]**.

Every platform package carries a machine-checked capability receipt that binds its reviewed native
commands, writes, child processes, links, and network endpoints to that package. The public release
also carries checksums, dependency inventories, CycloneDX SBOMs, license/notices/privacy material,
and known limitations for people who want to inspect more than the download button.

## Downloads

**Release:** [RELEASE URL]

- Windows x86-64: [WINDOWS URL]
- macOS Apple silicon: [MACOS URL]
- Linux x86-64 AppImage: [APPIMAGE URL]
- Linux x86-64 Debian package: [DEB URL]
- Checksums and all release assets: [RELEASE URL]

Do not add an Intel Mac link to the first beta. The package does not exist.

## Known beta limits

- Real-game testing has been deepest on Apple silicon macOS.
- Windows and Linux have substantial automated package and lifecycle coverage; broader real-machine
  Starsector evidence continues during the beta.
- Reviewed game version: **0.98a-RC8**. Other versions can use fewer optimizations until changed
  targets are reviewed.
- Reviewed mod-specific adapters: **[LIST]**. A mod not listed here is not automatically incompatible;
  it simply has no specific acceleration claim from this list.
- The performance numbers above describe the stated development machine/profile and exact candidate
  record. Other installations can differ substantially.
- Large profiles can take several minutes and gigabytes to prepare the first time. The app calculates
  the current plan before starting.

See [Known limitations](known-limitations.md) for the complete list.

## Support and reporting

If something fails, start with **Copy setup**. It is designed for ordinary forum/Discord/issue
support without exposing paths, credentials, saves, or arbitrary logs.

When deeper evidence is useful, Help can create a disclosed support ZIP. Sending is a separate action
after the file's contents, exclusions, byte count, digest, and retention are shown. Include the case
ID in **[SUPPORT THREAD / ISSUE TEMPLATE]** when one exists. Reports are retained for **[RETENTION]**
and can be deleted using **[DELETION PROCESS]**.

Ordinary game launches upload no logs or telemetry.

## Evidence links

- Performance and optimization history: **[LINK]**
- Exact product contract: **[LINK]**
- Release readiness/evidence: **[LINK]**
- Known limitations: **[LINK]**
- Privacy: **[LINK]**
- Source and releases: **[LINK]**

## Support-the-project copy

Preflight is free and open source. If it saves you a pile of waiting, helps you manage a ridiculous
mod list, or you simply like this kind of obsessive work and want to support it:

- GitHub Sponsors: [GITHUB SPONSORS URL]
- Patreon: https://www.patreon.com/cw/teamleaderleo

Sponsorship/membership supports development time, testing hardware, hosting, release work, and future
projects. It does not unlock a different version of Preflight.

## Short release description

> Preflight is a free, open-source performance launcher for Starsector. It prepares repeatable
> startup work, validates reuse against the current game/mod setup, and falls back to the original
> game path on uncertainty. The desktop includes its own before/after benchmark, Starsector playtime,
> named profiles, extended launch settings, storage/recovery tools, deep setup analysis, signed
> updates, and a locally generated wireframe Hangar; the CLI adds profile census, launch-preview, and
> read-only mod-linting tools.

## Very short description

> A free, open-source Starsector performance launcher with a built-in before/after benchmark,
> playtime tracking, named profiles, extended launch settings, mod-stack diagnostics, storage/recovery
> tools, and signed updates.

## One-line hooks to steal later

- **Faster launches are the headline. The rest of the app is what happened when I kept pulling on the thread.**
- **Measure the speedup yourself, then keep the playtime counter and profile tools even when you are not thinking about optimization.**
- **The package can tell you what it is allowed to write before you decide whether you trust it.**
- **A changed mod can lose one shortcut without turning the whole launcher into an all-or-nothing compatibility gamble.**
- **Yes, the battle-size control goes to 2,000.**
- **No, the desktop app does not require you to install Java first.**
- **The mod linter has no score because most mods are fine and false certainty is not useful.**

Preflight is an independent project and **[FINAL APPROVED DISCLAIMER]**.
