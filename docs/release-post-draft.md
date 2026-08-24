# Public beta release writing kit

This is the release-day source for public descriptions, download-page copy, and short forum/social
blurbs. The tag-owned release notes live in [releases/0.1.0.md](releases/0.1.0.md), while
[Release readiness](release-readiness.md) and the live [#652](https://github.com/teamleaderleo/preflight/issues/652)
remain authoritative for what still has to happen before the first public beta release goes live.

Keep this file readable as public prose rather than turning it into an evidence ledger. Development
results that already exist can be named now. Candidate benchmark, package, native-machine, lifecycle,
and report-intake facts stay bracketed until one immutable candidate generation exists and those
checks have actually completed.

The longer announcement is [beta-announcement-draft.md](beta-announcement-draft.md). The shorter
forum/Reddit post is [beta-announcement-leo-draft.md](beta-announcement-leo-draft.md).

## Headline options

Use the packaged-candidate result only after it exists. Until then, label the 83-mod numbers as the
development comparison rather than quietly turning them into a release-package claim.

- **Preflight 0.1.0: a performance launcher for Starsector**
- **Preflight public beta: 89.00s → 15.53s median on my 83-mod Starsector setup**
- **Preflight: faster Starsector launches, plus playtime, profiles, and mod diagnostics**
- **I tried to reduce one loading screen and ended up making a Starsector companion app**

The ~101-second high and 15.25-second low belong to the development chronology. Use 89.00s → 15.53s
when a headline reads as a controlled before/after comparison. Add or remove headline candidates when
there is a good reason; there is no quota to fill.

## Subtitle options

- **A free, open-source Starsector launcher that prepares repeated startup work and includes its own before/after benchmark.**
- **Faster launches, local playtime, named mod profiles, useful launch settings, storage/recovery tools, signed updates, setup analysis, and a read-only mod linter.**

## Short product description

Preflight is a free, open-source performance launcher and companion app for Starsector. It prepares
work the game and mods would otherwise repeat during startup and reuses it while the relevant inputs
still match. The performance investigation kept finding adjacent things worth fixing, so the desktop
also grew a normal-versus-Preflight benchmark, local playtime tracking, named mod profiles, launch
settings, storage and recovery tools, read-only setup analysis, support tools, signed updates, and a
locally generated wireframe Hangar; the CLI adds profile census, launch preview, direct-launch tools,
and a read-only mod linter.

## GitHub Release body source

# Preflight 0.1.0

Preflight is a free, open-source performance launcher and companion app for Starsector.

On the reviewed 83-mod development installation, the latest controlled comparison measured five
ordinary launches at an **89.00-second median** and five Preflight launches at a **15.53-second
median**. The lowest Preflight launch in that comparison was **15.25 seconds**, while an earlier
observed development high was roughly **101 seconds**. That comparison came from one M5 MacBook Air,
Starsector 0.98a-RC8, and the game's bundled x86-64 Java runtime under Rosetta, so it is a useful
record rather than a promise about every machine.

The public candidate recorded **[PACKAGED CANDIDATE BENCHMARK RESULT]** on **[CANDIDATE HARDWARE /
GAME / RUNTIME]**. Results vary with mods, hardware, storage, cache warmth, memory pressure, and system
load. The desktop includes the same before-and-after benchmark so players can measure their own
installation instead of taking somebody else's timing as universal.

The loading-time experiment did not stay a loading-time experiment. Preflight now remembers named mod
setups, tracks playtime for the sessions it launches and can observe, puts the useful game settings
beside Launch, checks a mod stack without starting the game, and shows preparation storage before it
begins. When something goes wrong, preparation can stop safely, damaged prepared data can be repaired,
and the normal-speed game path remains available without throwing away the rest of the app.

There is more underneath that surface if you want it: read-only mod linting, profile census and setup
analysis, preview-first cleanup, signed desktop updates where the package format supports them, and a
Hangar that traces installed ship art locally into Preflight's own wireframe presentation. Those are
parts of one launcher rather than twelve co-equal headline features; the main point is still getting
from a large mod setup to Starsector with less waiting and enough visibility to understand what the
launcher is doing.

## What happens when Starsector or a mod updates?

Preflight leaves Starsector and mod JARs, executables, assets, and saves unchanged. It can use those
files as inputs to preparation and compatibility decisions without rewriting them.

Runtime optimizations live inside the launched game process. When reviewed game or mod code changes,
the affected shortcut steps aside and Starsector handles that work normally. Prepared work likewise
has to match the inputs that produced it before it is reused. A large enough game, launcher, or
runtime change can still require a Preflight update, but one changed mod does not automatically turn
every other reusable path off.

## Packages and updates

**Accepted release package set:** [ACCEPTED PACKAGE MATRIX / FILENAMES]

The desktop packages contain the reviewed Preflight Java runtime, so ordinary desktop use does not
require a system JDK. The first beta does not use paid Apple Developer ID or Windows Authenticode
publisher identities, which means macOS and Windows can show their normal unknown-developer warnings.
Each accepted native download is paired with its release checksum material, and supported in-app
updates use the project's separate updater signing key. Linux `.deb` installations remain
package-manager-managed.

**Candidate package identity / checksums:** [ACCEPTED PACKAGE IDENTITY / CHECKSUM SUMMARY]

Use [Downloads and installation](downloads.md) for the release-day links and OS-specific install
steps rather than duplicating those procedures here.

## Known beta limits

Real-game testing has been deepest on Apple silicon macOS. The first public beta release — meaning the
GitHub release page and downloadable packages — does **not go live** until the frozen package has also
completed the required native Windows and native x86-64 Linux real-game installation exercises. Fill
the two lines below from those runs before the release post and downloads go public; do not rewrite
them as work that can simply happen later in beta.

- **Windows real-game exercise:** [WINDOWS NATIVE REAL-GAME RESULT]
- **Linux real-game exercise:** [LINUX NATIVE REAL-GAME RESULT]
- There is no Intel Mac package in the first beta.
- The reviewed game version is **0.98a-RC8**. Other versions can receive fewer speedups until changed
  targets are reviewed.
- Development performance numbers describe the stated machine and 83-mod profile. Other installations
  can differ substantially; the accepted package gets its own benchmark before the release goes live.
- On the 83-mod development profile, first preparation measured about 38 seconds in Balanced and
  17 seconds in Compact. The desktop measures the local storage requirement before starting.
  Preparation time varies with the installed content and machine.

See [Known limitations](known-limitations.md) for the complete list.

## Support and reporting

If something fails, start with **Copy setup**, which produces a small support summary suitable for a
forum, Discord, or issue report. When deeper diagnostics are useful, Help can create a support ZIP and
show what it contains before sending. There are no accounts or usage telemetry. The first beta sends
a support file only when you press Send.

The release intake path still has candidate-specific facts that cannot be written ahead of the
candidate. Fill these only from the accepted packaged run:

- **Tagged service canary:** [TAGGED REPORT CANARY RESULT]
- **Hands-on packaged cancel/retry/delete pass:** [PACKAGED REPORT CANARY RESULT]
- **Retention/deletion wording for the public post:** [ACCEPTED REPORT RETENTION / DELETE FACTS]

## Candidate evidence sentence

Use one short sentence in the final post rather than dumping the release ledger into player copy.
For example:

> The published packages completed **[NATIVE WINDOWS/LINUX + HOSTED LIFECYCLE SUMMARY]**, the packaged
> startup benchmark recorded **[CANDIDATE BENCHMARK SUMMARY]**, and the packaged support-report path
> completed **[REPORT CANARY SUMMARY]** before the release went live.

Those placeholders remain placeholders until the frozen candidate produces the corresponding facts.
A private rehearsal, checkout build, or rebuilt package from the same source revision is not a
substitute for the accepted release package.

## Feature reservoir

This is source material for venue-specific copy, not a checklist that must become a wall of bullets.
Pull from it when a paragraph needs a concrete example, and group related features when that is how
the story naturally reads.

- **Prepared startup work.** Reuse matching texture, merged/spec-data, generated-bytecode, audio, and
  related startup work.
- **Built-in benchmark.** Compare a normal launch with a Preflight launch and copy a compact result.
- **Starsector playtime.** Keep a local total for sessions launched through Preflight and export the
  history as JSON or spreadsheet-safe CSV.
- **Named mod profiles.** Save, search, switch, rename, duplicate, and delete profiles; switching
  previews the mod change and saves a backup first.
- **Launch settings.** Resolution, fullscreen, sound, antialiasing, UI scale, RAM, and custom battle
  size live beside Launch.
- **Storage planning and recovery.** See the preparation requirement before writing, use a minimal-disk
  route when needed, stop safely, and repair damaged prepared data.
- **Setup analysis and mod linting.** Inspect the active stack for missing mods, metadata/dependency
  problems, selected invalid references, and measurable asset/configuration costs without editing the
  installation or assigning mods a score.
- **Support and updates.** Copy a small setup summary, review a deeper support ZIP before sending it,
  and use signed desktop updates where the installed package format supports them.
- **Hangar.** Trace installed ship art locally into Preflight's wireframe presentation.

## Storage language

On the reviewed 83-mod development profile, current preparation left about **2.3 GB** in Balanced,
**11 MB** in Minimal disk before its first launch, and **5.2 GB** in the advanced Uncompressed mode.
Minimal later grew to about 204 MiB when its first launch learned the non-texture runtime caches.
The build briefly needs more room than the finished pack uses; the desktop calculates the current
installation's own plan.

Use a table when the three storage modes are being compared directly. Use the sentence above when the
point is simply that disk use is visible and installation-specific.

## Download block

**Release:** [RELEASE URL]

- Windows x86-64: [WINDOWS DOWNLOAD URL]
- macOS Apple silicon: [MACOS DOWNLOAD URL]
- Linux x86-64 AppImage: [APPIMAGE DOWNLOAD URL]
- Linux x86-64 Debian package: [DEB DOWNLOAD URL]
- Standalone Java/archive downloads: [STANDALONE DOWNLOAD URLS]
- Checksums and all release assets: [RELEASE URL]

Do not fill these from rehearsal artifacts. There is no Intel Mac package in the first-beta matrix.

## Evidence links

- Performance and optimization history: **[LINK]**
- Product contract: **[LINK]**
- Release readiness: **[LINK]**
- Known limitations: **[LINK]**
- Privacy: **[LINK]**
- Source and releases: **[LINK]**

## Support-the-project copy

Preflight is free and open source. If it saves you a pile of waiting, helps you manage a ridiculous
mod list, or you simply like this kind of work and want to support it:

- GitHub Sponsors: [GITHUB SPONSORS URL]
- Patreon: https://www.patreon.com/cw/teamleaderleo

Sponsorship supports development time, testing hardware, hosting, release work, and future projects.
The public build stays the public build.

## Very short description

> A free, open-source Starsector performance launcher with a built-in before/after benchmark,
> playtime tracking, named profiles, launch settings, mod-stack diagnostics, storage/recovery tools,
> and signed updates.

Preflight is an independent, unofficial project. It isn't affiliated with or endorsed by Fractal
Softworks.
