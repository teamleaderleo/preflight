# Beta announcement draft

Long source copy for the forum, Reddit, release editing, and anywhere else that needs more of the
story than the short post. The shorter version is
[beta-announcement-leo-draft.md](beta-announcement-leo-draft.md).

Keep candidate-specific fields bracketed until the retained release candidate produces them. The
Starsector forum takes BBCode rather than Markdown; use the release-day link blocks in
[downloads.md](downloads.md#release-day-link-kit) and convert the prose before posting there.

---

## Preflight: a free, open-source fast launcher for Starsector

This is **Preflight**, a free and open-source fast launcher for Starsector.

On my 83-mod M5 MacBook Air development setup, startup moved from roughly **101 seconds to a
13.69-second best run**, about a **7.4× speedup**. That is Starsector's x86-64 Java runtime running
through Rosetta.

The desktop has its own normal-versus-Preflight benchmark. **You can measure yours yourself.**

**Download:** [RELEASE URL]

The public package gets its own retained benchmark before this post goes live:

**[PACKAGED CANDIDATE BENCHMARK RESULT]** on **[CANDIDATE GAME / HARDWARE / RUNTIME]**.

## Features

- **Tracked playtime!!!!!**
- Faster campaign-map movement on my setup.
- Battle size up to **2,147,483,647 deployment points** (`INT32_MAX`).
- Resolution, fullscreen, sound, antialiasing, UI scale, RAM, and battle size beside Launch.
- Mod dependency/setup checks.
- A mod linter.
- Storage planning before preparation.
- Repair, cleanup, and recovery tools.
- Signed updates.
- A wireframe Hangar generated from installed ships.
- Windows, macOS, and Linux desktop packages with their own Java runtime.
- The same Java engine behind the desktop and CLI.

The launcher also has the power-user stuff I wanted while working on it: `doctor` for launcher
discovery, `scan` for a profile census, `--dry-run` for the exact launch command, and explicit
launcher selection when you use another compatible wrapper.

## Disk use

On my current 83-mod setup, the learned Compact data settles around **1.1 GB**. First preparation
needs more working room. Preflight calculates the actual requirement for the current installation
before it starts writing.

There is also a minimal-disk path when the normal prepared-texture route does not fit.

## Compatibility

Preflight leaves Starsector and mod JARs, executables, assets, and saves unchanged. Prepared data
lives in Preflight's own area. Runtime optimizations live inside the launched game process.

If a runtime shortcut does not recognize the code it expects, it steps aside and the normal game
path runs.

Profile switching and launch settings are explicit game-owned preference changes. The app shows the
change and keeps a backup.

## Where the speed came from

There was not one cache that removed eighty seconds.

One early texture cache was sitting behind the single-threaded prefetch queue that was already
blocking the loading thread for roughly 27 seconds. Moving the prepared-texture decision ahead of
that queue changed the critical path.

Once texture work got cheaper, repeated JSON/CSV reconstruction became visible. Five loader-specific
caches led to a shared lower read layer, and the remaining merged-read seam moved from **2.172s to
0.300s**.

Janino compilation was another large seam. Memoizing 228 compilation requests moved that work from
**18.014s to 2.364s**. The persisted output then showed a second problem: 36,332 generated-class
occurrences represented only 280 unique classes. Deduplication shrank the stored class maps from
**145.96 MiB to 1.13 MiB** and replay from **1.501s to 29ms**.

Texture storage had its own second act. Removing per-file durability from rebuildable intermediates
brought one preparation path from **200.77s to 16.21s**. On the same logical Compact texture corpus,
laying the files out in observed startup order produced a **14.174s** launch versus **33.53s** in
alphabetical order.

There is campaign/runtime work too. Mutation-tracked indexes removed the sector-wide validation work
measured as **79.1M entity-reference checks → 0**, and a separate memoized path served **117.9M**
unchanged commodity calls.

The deeper technical record is in [Engineering overview](engineering-overview.md),
[Optimization history](optimization-history.md), and the [Experiment ledger](experiment-ledger.md).

## The experiments that failed

Some of the useful results were failures.

An early texture-cache pilot had healthy hit counters and broken visuals. A timing split turned out
to be a stale benchmark anchor. Java Flight Recorder's clock was wrong under one runtime setting. A
GraphicsLib replay made its measured path slower. AppCDS did not earn a place in the shipped path.

Those records remain in the repository.

## Support and updates

**Copy setup** produces a compact support summary. A separate support ZIP shows what it contains
before sending, and the first beta sends one only when you press Send.

Supported in-app updates use the project's updater signing key. The release process exercises
installation, update, rollback, and removal against the package set.

## Release package status

The first public beta release goes live after one retained candidate package set completes the
required package, native-machine, benchmark, lifecycle, and report checks.

Fill these from that candidate before posting:

- **Windows real-game exercise:** [WINDOWS NATIVE REAL-GAME RESULT]
- **Linux real-game exercise:** [LINUX NATIVE REAL-GAME RESULT]
- **Accepted package/checksum summary:** [ACCEPTED PACKAGE MATRIX / CHECKSUM SUMMARY]
- **Packaged benchmark:** [PACKAGED CANDIDATE BENCHMARK RESULT]
- **Report/update/lifecycle evidence:** [FINAL CANDIDATE EVIDENCE SUMMARY]

There is no Intel Mac package in the first beta. The reviewed game version is **0.98a-RC8**.

## AI assistance

I used ChatGPT/Codex and Claude Code throughout development. The source, tests, measurements, failed
experiments, and release work are in the repository.

## How to use it

1. Download the package for your system.
2. Open Preflight. If it does not find Starsector, choose the game folder.
3. Press **Set up and launch**.
4. On later runs, press **Launch Starsector**.

**Download:** [RELEASE URL]

If you want to support development:

- GitHub Sponsors: [GITHUB SPONSORS URL]
- Patreon: https://www.patreon.com/cw/teamleaderleo

Preflight is an independent, unofficial project. It is not affiliated with or endorsed by Fractal
Softworks.
