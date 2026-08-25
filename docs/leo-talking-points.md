# Leo's Preflight talking points

Read this before a forum post, Patreon update, video, stream, interview, release conversation, or any
other moment where Preflight has to fit into human working memory.

The announcement drafts are sources for finished prose. The deliberately larger hook reservoir is
[Public-writing sales inventory](public-writing-sales-inventory.md).

## Front-facing voice

- Prefer **made** or **created** to **built** in promotional/public copy unless `built` is the natural
  technical verb.
- Let an interesting fact stand. Do not automatically append a joke, disclaimer, personality tag, or
  explanation after it.
- Avoid self-conscious creator framing such as “got out of hand,” “I got carried away,” “apparently
  this was not enough,” or “accidentally became a companion app.”
- Do not foreground the old 89.00s → 15.53s controlled pair unless the conversation is specifically
  about that experiment. The current readable development headline is **~101s → 13.69s**.
- Do not pad campaign/runtime claims with defensive prose. **Faster campaign-map movement on my setup**
  is enough for short player copy.
- Do not explain `INT32_MAX` after the number has landed. **2,147,483,647 deployment points** can stand
  on its own.
- Short feature lists can be blunt. **Tracked playtime!!!!!** is valid copy.
- Do not sell saved profiles as a mod manager. They are a launcher convenience, not the headline.
- Use concrete behavior instead of labels such as safe, robust, privacy-focused, or compatible.
- For short public posts, stop writing when the reader already has the useful fact.

## The sale

Preflight is a free and open-source fast launcher for Starsector. On my 83-mod M5 MacBook Air
development setup, startup moved from roughly **101 seconds to a 13.69-second best run**, about a
**7.4× speedup**. The desktop includes its own normal-versus-Preflight benchmark.

The rest of the product is useful on its own: tracked playtime, launch settings beside Launch,
custom battle size, setup checks, storage planning/recovery, signed updates, a wireframe Hangar, and
a mod linter.

## Lead with these numbers

Current development setup:

- roughly **101s → 13.69s** to the main menu;
- about a **7.4× speedup**;
- **83 enabled mods**;
- **M5 MacBook Air**;
- Starsector's bundled x86-64 Java runtime through Rosetta.

The public package gets its own retained benchmark before release.

The older 89.00s → 15.53s controlled campaign remains useful evidence, but it is not the public
headline.

## The player-facing feature set

Use what fits the venue. Do not turn every post into the full inventory.

- **Tracked playtime!!!!!** Durable local total for Starsector sessions Preflight launches and can
  observe.
- **Built-in benchmark.** Normal-versus-Preflight comparison on the player's installation.
- **Faster campaign-map movement on my setup.** Keep this as a measured player-facing claim without
  promoting it into a universal FPS number.
- **Game settings beside Launch.** Resolution, fullscreen, sound, antialiasing, UI scale, RAM, and
  battle size.
- **Battle size through 2,147,483,647 deployment points.** Preflight writes Starsector's own
  `battleSize` preference.
- **Setup checks.** Missing/disabled dependencies, duplicate mod IDs, broken metadata, and selected
  broken references can be surfaced without starting the game.
- **Storage planning.** The app calculates the current preparation requirement before writing.
- **Repair and recovery.** Preparation can stop, interrupted work can be recovered, and damaged
  prepared data can be repaired.
- **Mod linter.** One-mod and whole-profile modes for measurable asset/configuration findings.
- **Signed updates.** Explicit review/install path with rollback exercised in the release process.
- **Hangar.** Installed ships can be represented as locally generated wireframes.
- **Desktop + CLI.** Same Java engine; desktop packages bring their own minimal Java runtime.

Saved launch profiles exist and can be useful, but do not lead with them in general player copy.

## What actually removed the time

The useful short explanation is repeated work being moved out of the launch path at better
boundaries.

### Texture critical path

The loading thread could wait roughly **27 seconds** behind a single-threaded prefetch queue before
the prepared-texture decision was consulted. Moving that decision ahead of the queue fixed the
placement problem.

Later texture work also removed **1.22 GiB** of upload padding.

### Shared data reads

One measured launch issued **39,017 JSON calls across 8,378 distinct paths**. Five loader-specific
caches exposed the lower common read boundary.

`SpecStore` moved **19.8s → 9.8s**, and the remaining merged-read seam moved **2.172s → 0.300s**.

### Texture publication and layout

Removing per-file durability from rebuildable texture intermediates brought one preparation path
**200.77s → 16.21s** and storage **4.76 GB → ~1.1 GB**.

On the same logical Compact texture corpus, observed startup order launched in **14.174s** versus
**33.53s** in alphabetical order.

### Janino

Memoizing **228** Janino compilation requests moved the compiler seam **18.014s → 2.364s**.

The persisted output then revealed **36,332 generated-class occurrences** but only **280 unique
classes**. Deduplication shrank stored class maps **145.96 MiB → 1.13 MiB** and replay **1.501s →
29ms**.

### Campaign runtime

Mutation-tracked indexes removed the sector-wide validation work measured as **79.1M entity-reference
checks → 0**. A separate memoized path served **117.9M unchanged commodity calls**.

The retained optimized campaign session measured the rough-after-load effect too: **9.15 FPS
one-percent low** during the first 30 seconds and **20.45 FPS** after that initial catch-up, or
**2.2×**, while average FPS moved from **46.10 to 55.47**. Keep the label attached: that is warm-up
versus settled play in one run. The repository still has the measurement-only/optimized campaign
benchmark machinery, but these exact numbers are not its retained result.

## Why the benchmark is in the product

The project has retained wrong measurements and failed ideas:

- a texture cache with healthy hit counters and broken visuals;
- a stale benchmark anchor that looked like a timing split;
- a Java Flight Recorder clock that was wrong under one runtime setting;
- a GraphicsLib replay that made its measured path slower;
- AppCDS work that did not earn a shipped win.

The built-in benchmark gives players the same direct measurement path used during development.

## Compatibility in one paragraph

Preflight leaves Starsector and mod JARs, executables, and assets unchanged. It does not directly
edit campaign saves or put prepared data into them; Starsector still owns normal save writes.
Prepared data lives in Preflight's own area. Runtime optimizations live inside the launched game
process. If a runtime shortcut does not recognize the code it expects, it steps aside and the normal
game path runs.

## Disk language

On the current 83-mod development setup, learned Compact data settles around **1.1 GB**. First
preparation needs more temporary working room. The app calculates the actual requirement before
writing.

Use the full storage-mode table only when storage modes are the topic.

## Mod-author hooks

The linter and setup analysis are the useful front door.

Good facts:

- progressive JPEGs measured about **8.75× slower** through the game's ImageIO path;
- calibration across **86** installed mod directories produced a median of zero findings;
- **44/86** were completely clean;
- the linter can inspect one mod or a whole profile;
- setup analysis can surface missing/disabled dependencies, duplicate IDs, broken metadata, and
  selected resolved-reference problems.

When bringing a finding upstream, lead with the measurement or reproduction.

## Developer/open-source hooks

- exact runtime fallback rather than permanent game/mod patching;
- the same Java engine behind desktop and CLI;
- source-linked optimization evidence;
- rejected experiments retained in the repository;
- signed updater and rollback exercises;
- capability receipts for native package commands/writes/processes/links/endpoints;
- exact-package release evidence rather than source-revision-only claims.

## Supporter pitch

Preflight stays free and open source. Sponsorship supports development time, testing hardware,
hosting, release work, compatibility work, and future projects.

Do not invent supporter-only product access.

## Current release boundary

Before the first public beta goes live, fill public package claims from one retained candidate set:

- packaged benchmark;
- native Windows real-game exercise;
- native x86-64 Linux real-game exercise;
- package/checksum identity;
- update/rollback/removal lifecycle;
- packaged support-report checks.

Keep development observations labeled as development observations until the candidate supplies the
release result.
