# Public beta release writing kit

This is the release-day source for public descriptions, download-page copy, and short forum/social
blurbs. The tag-owned release notes live in [releases/0.1.0.md](releases/0.1.0.md), while
[Release readiness](release-readiness.md) and the live [#652](https://github.com/teamleaderleo/preflight/issues/652)
remain authoritative for what still has to happen before the first public beta release goes live.

Development results can be named now. Candidate benchmark, package, native-machine, lifecycle, and
report-intake facts stay bracketed until one immutable candidate generation produces them.

The longer announcement is [beta-announcement-draft.md](beta-announcement-draft.md). The short
forum/Reddit post is [beta-announcement-leo-draft.md](beta-announcement-leo-draft.md).

## Headline options

- **Preflight 0.1.0: a free, open-source fast launcher for Starsector**
- **Preflight public beta: ~101s → 13.69s on my 83-mod development setup**
- **Preflight: faster Starsector launches, tracked playtime, setup checks, and mod tools**

Use the packaged-candidate result once it exists. Until then, label the 101s → 13.69s result as the
development setup.

## Subtitle options

- **A fast Starsector launcher with its own normal-versus-Preflight benchmark.**
- **Faster launches, tracked playtime, launch settings, storage/recovery, setup checks, signed updates, and a mod linter.**

## Short product description

Preflight is a free and open-source fast launcher for Starsector. On my 83-mod M5 MacBook Air
development setup, startup moved from roughly **101 seconds to a 13.69-second best run**, about a
**7.4× speedup**. The desktop includes its own normal-versus-Preflight benchmark and also tracks
playtime, puts the useful game settings beside Launch, checks large mod setups, plans storage before
preparation, handles repair and recovery, ships signed updates, and includes a mod linter.

## GitHub Release body source

# Preflight 0.1.0

Preflight is a free and open-source fast launcher for Starsector.

On my 83-mod M5 MacBook Air development setup, startup moved from roughly **101 seconds to a
13.69-second best run**, about a **7.4× speedup**. That development setup uses Starsector's bundled
x86-64 Java runtime through Rosetta.

The desktop includes its own normal-versus-Preflight benchmark so you can measure your installation.

The public candidate recorded **[PACKAGED CANDIDATE BENCHMARK RESULT]** on **[CANDIDATE HARDWARE /
GAME / RUNTIME]**.

Features include **tracked playtime!!!!!**, faster campaign-map movement on my setup, game settings
beside Launch, custom battle sizes through **2,147,483,647 deployment points**, mod dependency/setup
checks, a mod linter, storage planning and recovery, signed updates, and a wireframe Hangar generated
from installed ships.

On my current 83-mod setup, learned Compact data settles around **1.1 GB**. First preparation needs
more working room; Preflight calculates the actual requirement before it starts writing.

## Compatibility

Preflight leaves Starsector and mod JARs, executables, and assets unchanged. It does not directly
edit campaign saves or put prepared data into them; Starsector still owns normal save writes.
Prepared data lives in Preflight's own area. Runtime optimizations live inside the launched game
process.

If a runtime shortcut does not recognize the code it expects, it steps aside and the normal game
path runs.

Profile switching and launch settings are explicit game-owned preference changes. The app shows the
change and keeps a backup.

## Packages and updates

**Accepted release package set:** [ACCEPTED PACKAGE MATRIX / FILENAMES]

The Windows/macOS/Linux desktop packages include a minimal Preflight Java runtime, so ordinary
desktop use does not require a system JDK.

The first beta does not use paid Apple Developer ID or Windows Authenticode publisher identities, so
macOS and Windows can show their normal unknown-developer warnings. Accepted native downloads ship
with checksum material, and supported in-app updates use the project's updater signing key. Linux
`.deb` installations remain package-manager-managed.

**Candidate package identity / checksums:** [ACCEPTED PACKAGE IDENTITY / CHECKSUM SUMMARY]

Use [Downloads and installation](downloads.md) for OS-specific install steps.

## Known beta limits

Real-game testing has been deepest on Apple silicon macOS. The public beta goes live after the frozen
candidate also completes the required native Windows and native x86-64 Linux real-game exercises.

- **Windows real-game exercise:** [WINDOWS NATIVE REAL-GAME RESULT]
- **Linux real-game exercise:** [LINUX NATIVE REAL-GAME RESULT]
- There is no Intel Mac package in the first beta.
- The reviewed game version is **0.98a-RC8**.
- The accepted package gets its own benchmark before release.

See [Known limitations](known-limitations.md) for the complete list.

## Support and reporting

**Copy setup** produces a compact support summary. Help can create a separate support ZIP and show
what it contains before sending. The first beta sends one only when you press Send.

Fill these from the accepted packaged run:

- **Tagged service canary:** [TAGGED REPORT CANARY RESULT]
- **Hands-on packaged cancel/retry/delete pass:** [PACKAGED REPORT CANARY RESULT]
- **Retention/deletion wording:** [ACCEPTED REPORT RETENTION / DELETE FACTS]

## Candidate evidence sentence

Use one short sentence in the final post:

> The published packages completed **[NATIVE WINDOWS/LINUX + HOSTED LIFECYCLE SUMMARY]**, the packaged
> startup benchmark recorded **[CANDIDATE BENCHMARK SUMMARY]**, and the packaged support-report path
> completed **[REPORT CANARY SUMMARY]** before release.

Fill it only from the frozen candidate.

## Feature reservoir

- **Fast launch.** Reuse matching texture, merged/spec-data, generated-bytecode, audio, and related
  startup work.
- **Built-in benchmark.** Compare a normal launch with a Preflight launch and copy the result.
- **Tracked playtime!!!!!** Keep a local total for sessions launched through Preflight and export the
  history as JSON or spreadsheet-safe CSV.
- **Launch settings.** Resolution, fullscreen, sound, antialiasing, UI scale, RAM, and custom battle
  size live beside Launch.
- **Battle size.** The numeric field accepts values through **2,147,483,647** while writing
  Starsector's own `battleSize` preference.
- **Storage planning and recovery.** See the preparation requirement before writing, stop safely, and
  repair damaged prepared data.
- **Setup checks and mod linting.** Inspect dependency/setup problems and measurable asset/config costs.
- **Support and updates.** Copy a setup summary, review a deeper support ZIP before sending it, and use
  signed desktop updates.
- **Hangar.** Generate wireframe ships from the installed hull catalog and sprites.
- **CLI.** `doctor`, `scan`, `--dry-run`, explicit launcher selection, and the same Java engine as the desktop.

## Storage language

On the reviewed 83-mod development profile, learned Compact data settles around **1.1 GB**. First
preparation needs more temporary working room, and the desktop calculates the current installation's
own requirement before starting.

Use the detailed storage table only when the storage modes themselves are the topic.

## Download block

**Release:** [RELEASE URL]

- Windows x86-64: [WINDOWS DOWNLOAD URL]
- macOS Apple silicon: [MACOS DOWNLOAD URL]
- Linux x86-64 AppImage: [APPIMAGE DOWNLOAD URL]
- Linux x86-64 Debian package: [DEB DOWNLOAD URL]
- Standalone Java/archive downloads: [STANDALONE DOWNLOAD URLS]
- Checksums and all release assets: [RELEASE URL]

## Evidence links

- Performance and optimization history: **[LINK]**
- Product contract: **[LINK]**
- Release readiness: **[LINK]**
- Known limitations: **[LINK]**
- Privacy: **[LINK]**
- Source and releases: **[LINK]**

## Support-the-project copy

Preflight is free and open source. If you want to support development:

- GitHub Sponsors: [GITHUB SPONSORS URL]
- Patreon: https://www.patreon.com/cw/teamleaderleo

Sponsorship supports development time, testing hardware, hosting, release work, and future projects.

## Very short description

> A free, open-source fast launcher for Starsector with its own benchmark, tracked playtime, launch
> settings, setup checks, storage/recovery tools, signed updates, and a mod linter.

Preflight is an independent, unofficial project. It isn't affiliated with or endorsed by Fractal
Softworks.
