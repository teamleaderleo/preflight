# Public-writing sales inventory

This is the overcomplete reservoir for public Preflight writing. Pull from it for README copy,
Patreon posts, forum announcements, release notes, interviews, Sponsors, or anything else that needs a
shorter slice of the project.

Current code, tests, the product contract, release readiness, and retained evidence outrank this file
when a fact changes.

## Front-facing voice

- `teamleaderleo` funding profiles are creator profiles, not permanent Preflight landing pages. Keep
  the profile identity broad, mention the current public focus briefly, and put benchmarks, feature
  lists, and project-specific support details in posts or project pages.
- Prefer **made** or **created** to **built** in promotional copy unless `built` is the natural
  technical verb.
- Let good facts stand. Do not append a joke, caveat, explanation, or personality tag merely because
  the sentence is short.
- Avoid self-conscious creator framing such as “got out of hand,” “I got carried away,” “apparently
  this was not enough,” or “accidentally became a companion app.”
- Short feature copy can be blunt. **Tracked playtime!!!!!** is fine.
- Use **112.17s → 13.69s** as the readable development headline. Keep the old 89.00s → 15.53s campaign
  for evidence conversations rather than making every public surface carry it. It is an A/B comparison,
  not a higher class of elapsed-time observation.
- Use concrete behavior instead of adjectives such as safe, robust, privacy-focused, secure, or
  compatible.
- Do not add defensive prose after campaign/runtime claims. **Faster campaign-map movement on my
  setup** is enough in short player copy.
- Do not explain the joke after **2,147,483,647 deployment points**. The number is the line.
- Saved launch profiles are a secondary convenience. Do not sell Preflight as a mod manager.
- In short posts, stop when the fact has landed.

## The strongest sale

Preflight is a free and open-source fast launcher for Starsector. On my 83-mod M5 MacBook Air
development setup, startup moved from **112.17 seconds to 13.69 seconds**, about an
**8.19× speedup**.

The desktop includes its own normal-versus-Preflight benchmark, so players can measure their own
installations.

The broader feature set is useful without a long explanation: tracked playtime, launch settings,
custom battle size, setup checks, storage/recovery, signed updates, a wireframe Hangar, and a mod
linter.

## Hooks for a heavily modded player

Lead with whichever few fit the venue:

- 112.17s → 13.69s on the development setup;
- built-in normal-versus-Preflight benchmark;
- **Tracked playtime!!!!!**
- faster campaign-map movement on my setup;
- battle size through **2,147,483,647 deployment points**;
- resolution/fullscreen/sound/AA/UI scale/RAM beside Launch;
- dependency/setup checks;
- storage requirement shown before preparation;
- repair and recovery;
- mod linting;
- Windows/macOS/Linux desktop packages with their own Java runtime.

Useful short line:

> Preflight launches Starsector faster, measures the difference on your own install, tracks your
> playtime, and puts the useful launcher/settings stuff in one place.

## Hooks for somebody who distrusts launchers

Use behavior:

- Starsector and mod JARs, executables, assets, and saves remain unchanged;
- runtime optimizations live inside the launched game process;
- an optimization that does not recognize its target steps aside and the normal game path runs;
- prepared data lives below Preflight's own area;
- profile/settings writes are explicit and backed up;
- the desktop host exposes a fixed command surface rather than an arbitrary shell;
- packages carry a machine-readable capability receipt for native commands, writes, child processes,
  links, and network endpoints.

Useful line:

> If a runtime shortcut does not recognize the code it expects, it steps aside and the normal game
> path runs.

## Hooks for a privacy-conscious user

Use behavior:

- ordinary launches upload no logs or telemetry;
- **Copy setup** produces a small support summary;
- the deeper support ZIP has a fixed inclusion/exclusion contract;
- the app shows the support file before sending;
- sending is an explicit action;
- accepted uploads have retention/deletion information;
- automatic failed-run upload is absent from the first beta.

Useful line:

> The first beta sends a support file only when you press Send.

## Hooks for a mod author

- `preflight lint --path ./MyMod` can inspect one mod;
- whole-profile lint understands provider order and cross-mod relationships;
- `preflight scan` inventories a large enabled profile;
- `preflight analyze setup` can surface missing/disabled dependencies, duplicate IDs, broken
  metadata, and selected resolved-reference problems;
- progressive JPEGs measured about **8.75× slower** through the game's ImageIO path;
- calibration across **86** installed mod directories produced a median of zero findings;
- **44/86** were completely clean.

Useful line:

> I pointed the profiling tools at the mods too. Most were fine. The useful part is having a
> measurement when something is not.

When bringing a finding upstream, lead with the measurement or reproduction.

## Hooks for a developer or open-source reader

- the benchmark history retains wrong measurements and corrections;
- rejected optimizations remain documented;
- exact target checks guard runtime transforms;
- source-linked evidence backs the major performance claims;
- the same Java engine serves desktop and CLI;
- signed updater and rollback exercises;
- incompatible cache representations can coexist across rollback;
- exact-package capability receipts;
- checksums, dependency inventories, and SBOMs;
- release evidence tied to the package bytes rather than only the source revision.

Useful line:

> The repository keeps the experiments that failed and the checks that were added because of them.

## Hooks for a supporter

Support pays for:

- development time;
- testing hardware;
- cross-platform package/release work;
- hosting;
- compatibility work;
- future software and creative projects.

Preflight stays free and open source. Do not invent supporter-only product access.

## Player candy

### Tracked playtime!!!!!

Starsector has no built-in lifetime counter. Preflight keeps a local total for the sessions it
launches and can observe, including sessions where the desktop minimizes or exits afterward.

### Battle size: 2,147,483,647

Preflight writes Starsector's own `battleSize` preference. The vanilla slider ceiling is not the
integer value limit consumed by the game.

Current shortcuts still include ordinary values such as 600, 1000, 1500, and 2000.

### Game settings beside Launch

Resolution, fullscreen, sound, antialiasing, UI scale, RAM, and battle size live in the launcher.

### High-DPI resolution handling

The desktop uses physical display pixels behind OS scaling when constructing resolution choices. A
Retina desktop reported as 1440×932 at 2× scale is treated as a 2880×1864 panel for those choices.

### Storage before writing

Preflight calculates the current installation's preparation requirement before it starts. On the
83-mod development setup, learned Compact data settles around **1.1 GB**.

### The Hangar

The desktop can read installed hull definitions and locally trace installed ship sprites into
Preflight's wireframe presentation. The package does not need to ship Starsector ship artwork to do
that.

### Faster campaign-map movement

Recommended includes reviewed campaign/runtime shortcuts in addition to startup work. Keep the
short player wording short: **Faster campaign-map movement on my setup.**

## Power-user hooks

### Same engine, desktop and terminal

The desktop and CLI use the same Java engine.

### `doctor`

Find the launcher Preflight selected without starting Starsector.

### `scan`

Inventory the enabled profile: files, bytes, extensions, largest assets/mods, duplicates, provider
order, and related setup information.

### `--dry-run`

Print the selected launcher, command, working directory, adapter mode, and other launch context
without starting the game.

### Explicit launcher selection

Preflight can wrap a selected compatible launcher instead of replacing it on disk.

## Technical hooks

### The cache was on the wrong side of the queue

The loading thread could wait roughly **27 seconds** behind a single-threaded texture prefetch queue
before the prepared-texture decision was consulted. Moving the decision ahead of that queue changed
the critical path.

### 39,017 JSON calls / 8,378 paths

Five loader-specific caches exposed a lower common read boundary. `SpecStore` moved **19.8s → 9.8s**
and the remaining merged-read seam **2.172s → 0.300s**.

### 36,332 generated classes were 280 classes

Memoizing **228** Janino compilation requests moved the compiler seam **18.014s → 2.364s**. The
persisted output then showed **36,332** generated-class occurrences representing **280** unique
classes. Deduplication shrank stored class maps **145.96 MiB → 1.13 MiB** and replay **1.501s →
29ms**.

### The same texture corpus, in a different order

On the same logical Compact corpus, observed startup order launched in **14.174s** versus **33.53s**
in alphabetical order.

### Preparation: 200.77s → 16.21s

Removing per-file durability from rebuildable intermediates and publishing one final pack changed
the preparation/storage path dramatically.

### Campaign indexes

Mutation-tracked indexes removed the sector-wide validation work measured as **79.1M entity-reference
checks → 0**. A separate memoized path served **117.9M unchanged commodity calls**.

## Release/trust hooks

### Signed updates

Updates are checked against the project signing key before installation.

### Rollback

The release process exercises installation, update, rollback, and removal. Incompatible cache
representations can coexist so rollback does not require rewriting every old cache in place.

### Capability receipts

Native packages carry a machine-readable statement of their engine/source identity and allowed native
commands, writes, child processes, fixed links, and network endpoints.

### Package evidence

The first public beta binds its benchmark and lifecycle/support checks to one retained candidate
package set.

## Stronger wording examples

Weak:

> Preflight is careful about compatibility.

Use:

> If a runtime shortcut does not recognize the code it expects, it steps aside and the normal game
> path runs.

Weak:

> Preflight respects privacy.

Use:

> Ordinary launches upload no logs or telemetry. The first beta sends a support file only when you
> press Send.

Weak:

> Preflight has game settings.

Use:

> Resolution, fullscreen, sound, antialiasing, UI scale, RAM, and battle size live beside Launch.

Weak:

> Preflight has setup diagnostics.

Use:

> The setup check can tell you a required dependency is installed but disabled, two installed mods
> claim the same ID, or a selected variant points at a hull missing from the resolved profile.

## Good future-post titles / hooks

- “How 112.17 seconds became 13.69”
- “The cache was on the wrong side of the queue”
- “36,332 generated classes were 280 classes”
- “The same texture pack, in a different order”
- “Tracked playtime!!!!!”
- “Battle size: 2,147,483,647”
- “I pointed the profiler at the mods”
- “The first cache had perfect hit counters and broke the screen”
- “The benchmark was wrong”
- “What exactly can this launcher touch?”
- “Why does a game launcher rehearse rollback?”
- “The desktop app brings its own Java runtime”
- “The same launcher has a GUI, a dry-run, a profile census, and a mod linter”
- “How Preflight draws installed ships”

## Publication boundaries

- current product features can be sold as implemented;
- open PRs are active work, not shipped features;
- development performance evidence remains development-context evidence until the exact release
  candidate has its own retained benchmark;
- first-beta package claims come from the accepted candidate bytes;
- native Windows/Linux real-game evidence must come from the required release exercises;
- report-intake behavior in the public package is candidate evidence until the final packaged path is
  accepted.
