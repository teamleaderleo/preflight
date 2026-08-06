# How Preflight moved an 83-mod launch from a 62.6-second waypoint to a 15.88-second record

**Status:** publication spine; every performance claim links to retained evidence
**Profile:** Starsector 0.98a-RC8, 83 mods, M5 MacBook Air, bundled x86-64 Zulu 17 under Rosetta
**Updated:** 2026-08-07

This is the readable index for the optimization campaign. It is deliberately shorter than the
engineering handoff and more structured than the evidence directory. Future release notes and the
long-form technical post should grow from this file without losing the distinction between a
controlled comparison, a replay benchmark, a correctness-only launch, and an isolated record.

## Result and measurement contract

The chronology below starts at **62.6 seconds**, the accepted prepared-texture waypoint after an
88.13-to-62.60-second controlled campaign—not the original vanilla baseline. The current warm
record is **15.88 seconds**, preceded by clean **16.66-second cold** and
**16.28-second warm** production gates. The record retained 42/42 transformed-class cache hits,
15,469 prepared-texture and pixel-conversion hits, active adapter health, and no adapter decline or
failure. It is a record, not a promised median; its exact evidence is in
[the Codex lazy fleet-member report](evidence/2026-08-06-codex-lazy-fleet-members.md).

The strongest whole-stack causal result remains the earlier fifteen-launch randomized comparison:
five vanilla, five texture-only, and five complete-stack launches. Vanilla measured **80.09s** and
the then-current complete stack measured **42.36s**, with every round agreeing on the effect within
1.9s. See [the whole-stack comparison](evidence/2026-08-03-the-whole-stack-measured-at-once.md).

The rules used throughout the campaign are:

- game-log-to-main-menu and wrapper wall time are different metrics;
- a JFR or diagnostic probe changes the launch it measures;
- a single launch can't establish sub-second effects under roughly ±1.4s whole-launch noise;
- replays establish narrow CPU/I/O effects, while real launches establish integration and visuals;
- browser use, memory pressure, temperature, and back-to-back thermal throttling are recorded rather
  than normalized away; and
- failures and rejected experiments remain evidence instead of disappearing from the history.

The reproducible harness and condition definitions are in [startup-benchmark.md](startup-benchmark.md).

## Milestones

| Date and accepted gate | Main-menu time |
| --- | ---: |
| Accepted prepared-texture waypoint | 62.60s |
| Preparation-before-launch work | 34.66 / 35.54s |
| Merged-read cache | 33.42 / 34.15s |
| Profile-stable startup JSON, five-run median | 29.61s |
| Deduplicated Janino pack, five-run median | 25.58s |
| Prepared-audio path index, three-run median | 24.76s |
| Resource priority and WebP-tail work | 23.68s, then 23.03s |
| Collapsed texture/loading pipeline | 18.01 / 18.04s |
| Loading-screen redraw and title-tail work | 17.09 / 16.68s, then 16.21s |
| Current production gates | 16.66 / 16.28 / **15.88s** |

The complete milestone ledger, including diagnostic and non-comparable runs, remains in
[next-llm-handoff.md](next-llm-handoff.md).

## 1. First make the number real

The first gains came from correcting the experiment rather than changing the game. Direct launch
removed human launcher latency; main-menu state and game logs replaced guessed visual gaps; JFR
showed which thread was runnable or waiting; and unattended shutdown made repeated cohorts viable.

Key reports:

- [The first valid startup number](evidence/2026-08-01-the-first-valid-startup-number.md)
- [The bimodality was the anchor](evidence/2026-08-01-the-bimodality-was-the-anchor.md)
- [Four seconds before the JVM logs anything](evidence/2026-08-03-four-seconds-before-the-jvm-logs-anything.md)
- [The startup benchmark measures the real fast preset](evidence/2026-08-05-startup-benchmark-fast-preset.md)

## 2. Textures: stop waiting, then stop repeating

Starsector's loading thread could wait roughly 27 seconds on a one-thread image prefetch queue.
After that wait, the same launch still reopened sources, decoded images, converted pixels, copied
buffers, calculated colors, and allocated power-of-two padding that shaders never sampled.

The accepted stack bypasses the redundant prefetch when an exact prepared manifest can serve the
texture, validates sources once, stores upload-ready pixels, and uses true-size allocations on the
Recommended path. Balanced storage keeps lossless LZ4 only where its space saving justifies its
decode cost; Fastest remains the larger raw alternative.

Key reports:

- [The loading thread waits on a one-thread prefetcher](evidence/2026-08-01-the-loading-thread-waits-on-a-one-thread-prefetcher.md)
- [The composed texture campaign saved 29%](evidence/2026-08-01-twenty-nine-percent-when-they-compose.md)
- [The padding is gone](evidence/2026-08-02-the-padding-is-gone.md)
- [Validated texture-index snapshot](evidence/2026-08-06-validated-texture-index-snapshot.md)
- [Balanced texture storage](evidence/2026-08-06-balanced-texture-storage.md)
- [Packed texture store](evidence/2026-08-06-packed-texture-store.md)
- [Unpadded fast default](evidence/2026-08-06-unpadded-fast-default.md)

The prepared-pixel bridge also produced the campaign's clearest warning: dimension-only and
half-invariant prototypes could report excellent hit rates while corrupting the title screen.
Those failed visual pilots are retained under the July 22–23 prepared-pixel evidence series.

## 3. The 0% plateau: cache stable data, not live game objects

The visible 0% pause was mainly vanilla `SpecStore`, which rebuilt variants, weapons, projectiles,
hulls, rules, and related registries for every process. Preflight caches tagged JSON/CSV trees and
merged-reader results underneath the live object constructors. Starsector still constructs and
owns the mutable runtime objects; cache values are immutable prepared inputs with exact profile and
provider identities.

Key reports:

- [The 20-second 0% plateau is vanilla SpecStore](evidence/2026-08-02-zero-percent-is-spec-store.md)
- [SpecStore is no longer a reading problem](evidence/2026-08-03-spec-store-is-no-longer-a-reading-problem.md)
- [Merged-read cache launch](evidence/2026-08-04-merged-read-cache-launch.md)
- [Tagged Spec JSON fidelity](evidence/2026-08-04-tagged-spec-json.md)
- [Tagged rules CSV](evidence/2026-08-05-tagged-rules-csv.md)
- [Profile-stable startup JSON](evidence/2026-08-05-profile-stable-startup-json-cache.md)
- [Core spec, faction, and rules](evidence/2026-08-05-core-spec-faction-and-rules.md)

## 4. Mod callbacks and generated code

After the core loaders became cheap, mod callbacks dominated the remaining tail. AshLib repeatedly
resolved hull and variant JSON. GraphicsLib rediscovered generated normal maps, revisited settings,
and repeated UI lookups. Janino regenerated identical bytecode. Each shortcut is separately pinned
to its reviewed class, source archive, loader, method descriptors, and input identity.

Key reports:

- [AshLib startup JSON cache](evidence/2026-08-02-ashlib-startup-json-cache.md)
- [AshLib variant index](evidence/2026-08-05-ashlib-variant-index.md)
- [GraphicsLib compact autogen replay](evidence/2026-08-02-graphicslib-compact-autogen-replay.md)
- [GraphicsLib lazy generated normals](evidence/2026-08-05-graphicslib-lazy-generated-normals.md)
- [GraphicsLib hot settings](evidence/2026-08-05-graphicslib-hot-settings-cache.md)
- [Janino deduplicated pack](evidence/2026-08-05-janino-deduplicated-pack.md)
- [Version-check response deduplication](evidence/2026-08-06-version-check-response-dedup.md)

## 5. Audio, wrapper, and the last startup tail

Prepared PCM moved Vorbis decode outside the launch while retaining decoder and source identities.
Direct manifest lookup then removed repeated hashing. The wrapper memoized shared profile hashes and
selected independent cache identities on a bounded pool. Asset-progress logging and the loading
screen were narrowed only after their exact overhead was measured.

Key reports:

- [Prepared-audio path index](evidence/2026-08-05-prepared-audio-path-index.md)
- [Prepared-audio direct read](evidence/2026-08-06-prepared-audio-direct-read.md)
- [Launch profile parallel selection](evidence/2026-08-06-launch-profile-parallel-selection.md)
- [Concise asset progress logs](evidence/2026-08-06-concise-asset-progress-logs.md)
- [Loading-screen redraw rate](evidence/2026-08-06-loading-screen-redraw-rate.md)
- [Main-menu save-descriptor memo](evidence/2026-08-06-main-menu-save-descriptor-memo.md)

## 6. Startup speed exposed gameplay defects—and enabled gameplay work

The same exact-adapter boundary now handles measured campaign and combat hotspots and several game
or mod correctness defects: linear entity lookup, deployment icons, radar type checks, simulation
opponent staleness, campaign notification calculations, GraphicsLib hot settings, OpenAL stale
errors, memory-pressure reporting, and combat JVM integrity. These don't all contribute to the
15.88-second startup number and must not be marketed as though they do.

Entry points:

- [Frame-time and FPS reporting](evidence/2026-08-05-frame-time-fps-reporting.md)
- [A failed entity lookup scans the sector](evidence/2026-08-02-a-failed-lookup-scans-the-sector.md)
- [Campaign radar type set](evidence/2026-08-05-campaign-radar-type-set.md)
- [Stale simulation opponents](evidence/2026-08-04-stale-simulation-opponents.md)
- [Commodity event-mod campaign hotspot](evidence/2026-08-05-commodity-event-mod-campaign-hotspot.md)
- [OpenAL stale stream-source error](evidence/2026-08-05-openal-stream-source-stale-error.md)
- [Combat JVM safeguard](evidence/2026-08-05-combat-jvm-safeguard.md)

## 7. Rejected ideas matter

Several plausible optimizations were slower, unsafe, or attributed to the wrong wall-time path:

- resource-probe caching lost real files and remains excluded from Recommended;
- quoted-number/schema promotion and broad floating-point shortcuts regressed;
- segmented memory mapping was slower than bounded positioned reads;
- GraphicsLib traversal replay failed its validation economics;
- AppCDS could not establish a safe obfuscated-class win;
- aggressive prepared-pixel prototypes corrupted visuals despite apparently healthy counters; and
- asynchronous network/version-check samples were not treated as critical-path wall time.

Start with [A listing is not what the filesystem was asked](evidence/2026-08-03-a-listing-is-not-what-the-filesystem-was-asked.md),
[GraphicsLib traversal replay rejected](evidence/2026-08-06-graphicslib-traversal-replay-rejected.md),
and [the AppCDS gate](evidence/2026-08-06-appcds-obfuscated-class-gate.md).

## Compatibility and release claims

Most prepared data and cache logic is platform-independent. Exact runtime adapters intentionally
aren't assumed portable: class/source/loader drift makes a target decline and leaves original
bytes in place. That is graceful compatibility, not proof of equal acceleration on every platform.
Windows and Linux need real beta evidence because CI can't redistribute or execute the licensed
game installation.

The public launch choices are therefore product boundaries rather than individual bytecode flags:

- **Recommended:** all live-gated startup and gameplay plans;
- **Conservative:** portable startup plans, with padded texture allocation and no gameplay or
  mod-specific targets; and
- **Off:** wrapper/process reporting only, with transforms and profiling disabled.

The exact safety and update behavior is specified in [product-contract.md](product-contract.md).

## Material still needed for the long-form post

- a diagram of the original serial loader and the prepared-data boundaries;
- a platform table once Windows and Linux beta reports arrive;
- a disk/time chart comparing Balanced and Fastest on at least three hardware classes;
- a clean controlled cohort for the current 15–17-second stack;
- before/after campaign and combat frame-time distributions, not only averages; and
- a concise regression narrative covering the visual, simulation, retreat, audio, and race bugs
  found during live pilots and the invariant each added.
