# Public beta release writing kit

This is the release-day source for public descriptions, download-page copy, and short forum/social
blurbs. The release notes live in [releases/0.1.0.md](releases/0.1.0.md), the detailed evidence stays
in the linked technical documents, and [Public writing style](public-writing-style.md) is the voice
reference when this material gets turned into finished prose.

Replace every bracketed field after the packaged candidate checks complete.

The longer announcement is [beta-announcement-draft.md](beta-announcement-draft.md). The shorter
forum/Reddit post is [beta-announcement-leo-draft.md](beta-announcement-leo-draft.md).

## Headline options

Use the packaged-candidate result once it exists. Until then, keep the development result labelled as
the development result.

- **Preflight 0.1.0: a performance launcher for Starsector**
- **Preflight public beta: 89.00s → 15.53s median on my 83-mod Starsector setup**
- **Preflight: faster Starsector launches, plus playtime, profiles, and mod diagnostics**

The ~101s high and 15.25s low belong to the development chronology. Use 89.00s → 15.53s when the
headline reads as a controlled before/after comparison.

These are options, not a rule of three: add or delete candidates when there is an actually good
reason, rather than keeping three because three looks like copywriting.

## Subtitle options

- **A free, open-source Starsector launcher that prepares repeated startup work and includes its own before/after benchmark.**
- **Faster launches, local playtime, named mod profiles, useful launch settings, storage/recovery tools, signed updates, setup analysis, and a read-only mod linter.**

## Short product description

Preflight is a free, open-source performance launcher for Starsector. It prepares work the game and
mods would otherwise repeat during startup and reuses it while the relevant inputs still match, and
then, because the performance investigation kept finding adjacent things worth fixing, the desktop
also grew a normal-versus-Preflight benchmark, Starsector playtime tracking, named mod profiles,
launch settings, storage and recovery tools, read-only setup analysis, support tools, signed updates,
and a locally generated wireframe Hangar; the CLI adds profile census, launch preview, direct-launch
tools, and a read-only mod linter.

## Player-first paragraph

Preflight makes heavily modded Starsector launch sooner and lets you measure the difference on your
own installation, but the thing I actually ended up with is broader than the loading-time project:
it tracks playtime, remembers named mod setups, puts the useful game settings beside Launch, checks
the mod stack without starting the game, and shows preparation disk use before it begins, with the
more technical machinery available when you want to go looking for it.

## Compatibility paragraph

Preflight keeps Starsector JARs, mod JARs, executables, assets, and saves outside the acceleration
path. Runtime optimizations live inside the launched game process, and when reviewed game or mod code
changes the affected shortcut steps aside so Starsector can handle that work normally.

## Performance paragraph

On the 83-mod development installation, the latest controlled comparison measured five ordinary
launches at an **89.00-second median** and five Preflight launches at a **15.53-second median**, with
the lowest Preflight launch in that comparison at **15.25 seconds** and the earlier observed
development high at roughly **101 seconds**.

The comparison used one M5 MacBook Air running Starsector 0.98a-RC8 with the game's bundled x86-64
Java runtime under Rosetta. The desktop includes the same before-and-after benchmark so players can
measure their own installation.

**Packaged candidate:** [CANDIDATE BENCHMARK RESULT / HARDWARE / GAME / RUNTIME]

## Feature reservoir

This is an inventory for assembling venue-specific copy, not a mandate to publish twelve equal-weight
bullets. In conversational writing, follow the causal groupings and let several of these live in one
sentence when that is how the thought naturally runs.

- **Prepared startup work.** Reuse matching texture, merged/spec-data, generated-bytecode, audio, and
  related startup work.
- **Built-in benchmark.** Compare a normal launch with a Preflight launch and copy a compact result.
- **Starsector playtime.** Keep a local total for sessions launched through Preflight and export the
  history as JSON or spreadsheet-safe CSV.
- **Named mod profiles.** Save, search, switch, rename, duplicate, and delete profiles; switching
  previews the mod change and saves a backup first.
- **Launch settings.** Resolution, fullscreen, sound, antialiasing, UI scale, RAM, and battle size
  live beside Launch, with battle-size presets through 2,000 deployment points on a standard setup.
- **Storage planning.** See the preparation requirement before writing and use a minimal-disk route
  when the normal plan will not fit.
- **Recovery.** Stop preparation safely, repair damaged prepared data, relaunch after failures, or use
  the normal-speed game path when acceleration data cannot be used.
- **Setup analysis.** Inspect the active mod stack for missing mods, metadata problems, duplicate IDs,
  dependency problems, and selected invalid references without launching the game.
- **Support tools.** Copy a small setup summary or review a deeper support ZIP before sending it.
- **Signed updates.** Supported desktop packages verify updates before installation; `.deb` packages
  continue through the package manager.
- **Mod linter.** Inspect measurable asset and configuration costs without editing files or assigning
  a score.
- **Hangar.** Trace installed ship art locally into Preflight's wireframe presentation.

## Storage language

On the reviewed 83-mod development profile, one preparation measurement retained **4.76 GB** in
Balanced, **10.9 MB** in Minimal disk, and **10.03 GB** in Fastest. Balanced needed 12.92 GB free
before starting in that measurement because the preflight check kept extra room for worst-case
preparation; the desktop calculates the current installation's own plan.

Use a table when the three storage modes are being compared directly. Use the sentence above when the
point is simply that disk use is visible and installation-specific.

## Package and install language

**Platforms for this release:** [PACKAGE MATRIX]

The desktop packages contain their own minimal Preflight Java runtime, so ordinary desktop use does
not require a system JDK. The first beta ships without paid Apple Developer ID or Windows
Authenticode publisher identities, which means macOS and Windows can show their standard
unknown-developer warnings; each native download is paired with a SHA-256 manifest, and supported
in-app updates use a separate project signing key.

Use [Downloads and installation](downloads.md) for release-day links and OS-specific install steps.

## Known beta limits

- Real-game testing has been deepest on Apple silicon macOS.
- Windows and Linux have substantial automated package and lifecycle coverage; broader real-machine
  Starsector exercise continues through the beta.
- There is no Intel Mac package in the first beta.
- The reviewed game version is **0.98a-RC8**. Other versions can receive fewer speedups until changed
  targets are reviewed.
- Development performance numbers describe the stated machine and 83-mod profile. Other
  installations can differ substantially.
- First preparation can take several minutes and gigabytes on a large profile. The app calculates
  the local plan before starting.

This is one of the places where a list earns its keep: the reader is checking concrete limits. See
[Known limitations](known-limitations.md) for the complete list.

## Download block

**Release:** [RELEASE URL]

- Windows x86-64: [WINDOWS URL]
- macOS Apple silicon: [MACOS URL]
- Linux x86-64 AppImage: [APPIMAGE URL]
- Linux x86-64 Debian package: [DEB URL]
- Checksums and all release assets: [RELEASE URL]

## Support block

If something fails, start with **Copy setup**, which produces a small support summary for forum,
Discord, or issue reports; when deeper diagnostics are useful, Help can create a support ZIP and
show its contents before sending. Ordinary game launches upload no logs or telemetry, and automatic
failed-run reporting starts off.

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

Preflight is an independent, unofficial project and isn't affiliated with or endorsed by Fractal
Softworks.
