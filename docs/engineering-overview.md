# Engineering overview

Preflight began as a performance investigation into heavily modded Starsector and grew into a cross-platform launcher and companion app. The interesting part is not one cache or one benchmark. It is the sequence of engineering decisions required to find repeated work inside an obfuscated JVM application assembled from the base game and dozens of independently maintained mods, move that work to better boundaries, prove the replacement behavior, and then turn the result into a product that can survive changing inputs, failed launches, restarts, updates, and ordinary player use.

On the reviewed 83-mod development installation, the observed startup arc moved from roughly **101 seconds to a 13.69-second best run**. That headline is a development chronology, not one synthetic A/B pair. The repository keeps the direct comparison campaigns, intermediate measurements, rejected experiments, and later controls separately so the engineering story does not depend on one screenshot or one benchmark number.

## The recurring pattern

Most of the largest wins followed the same loop:

1. profile or instrument the real launch rather than infer the bottleneck from the loading screen
2. find repeated deterministic work at the boundary where it actually occurs
3. cache or prepare the result without replacing the game's live objects or mod ordering
4. bind reuse to the exact game/mod inputs that produced it
5. decline to the original path when the target is changed, unsupported, stale, damaged, or ambiguous
6. measure the result again and keep the failures that changed the design

The first implementation is often not the final abstraction. Several of Preflight's strongest changes came from discovering that a local cache was sitting above a more useful shared boundary, or that the cache decision itself happened after the expensive work had already been serialized.

## Shared data reads instead of a sixth loader cache

The visible 0% loading plateau led to five loader-specific JSON/CSV caches for variants, weapons, projectiles, hulls, and campaign rules. Those changes took `SpecStore` from **19.8s to 9.8s**, but the remaining profile showed that the same merge-and-parse work was still being repeated below those loaders.

One measured launch issued **39,017 JSON calls across 8,378 distinct paths**. The repeated work was not really a variant-cache problem or a weapon-cache problem. It belonged at the common data-read boundary used by the game and mods.

Preflight moved that work into a shared memoized layer beneath the loader-specific caches. The remaining merged-read seam moved from **2.172s to 0.300s**. Stored JSON text was also replaced with a typed-tree representation so cached data could be rehydrated without reparsing text. That representation was replayed through the installed JSON runtime and compared across roughly **990,000 recursively visited values** before it became part of the normal path.

The useful lesson was not “cache JSON.” It was that five local caches exposed the wrong abstraction boundary.

## Put the cache decision before the bottleneck

The first prepared-texture implementation had healthy cache-hit counters and a disappointing end-to-end result. Profiling showed why: the loading thread could still wait roughly **27 seconds** behind Starsector's single-threaded texture prefetch queue before the prepared-texture decision was even consulted.

Moving the cache lookup ahead of that queue changed the critical path. Later texture work also stopped allocating power-of-two upload padding that the logical images did not need, removing **1.22 GiB of VRAM padding** in the measured full load.

This was one of the project's recurring failure modes: an optimization can be locally correct and still be placed on the wrong side of the cost it is supposed to avoid.

## Rebuildable texture data does not need per-file durability

Preflight initially made thousands of rebuildable texture intermediates durable before writing the final pack. On the reviewed profile, the broader preparation path reached **200.77s** and **4.76 GB**.

The final design treats those intermediates as what they are: rebuildable staging data. They stream into one final published pack instead of forcing thousands of individual files. The retained Compact preparation endpoint is **16.21s** with roughly **1.1 GB** of storage on the same development profile.

Physical layout mattered after publication cost was fixed. Writing the same logical Compact texture corpus in observed startup order instead of alphabetical order moved launch from **33.53s to 14.174s**. The data set did not change. Its physical order did.

That sequence is why the texture work is better described as storage and I/O engineering than as “another cache.”

## Generated code: remove repeated compilation, then remove repeated representation

The game and mods use Janino to generate Java bytecode during startup. Preflight first memoized **228 compilation requests**, reducing the measured compiler seam from **18.014s to 2.364s**.

The persisted result then exposed a second problem. Across those requests, the cache contained **36,332 generated-class occurrences** but only **280 unique classes**. Deduplicating the stored class maps reduced them from **145.96 MiB to 1.13 MiB**, while replay moved from **1.501s to 29ms**.

The two changes solve different problems: the first avoids repeated compilation, the second avoids repeatedly storing and replaying equivalent generated output.

## Runtime work did not stop at startup

The same profiling approach found high-frequency work during campaign simulation.

A sector-wide entity lookup path repeatedly validated large lists. Mutation-tracked indexes moved **227,805 full-list validations to zero** and **79.1 million entity-reference checks to zero** in the adjacent live pilots that established the before/after work. The lookups still happen; the expensive validation scans do not.

A separate campaign hotspot repeatedly recomputed commodity state that had not changed. The final memoized path served **117.9 million unchanged calls** while delegating the comparatively small set of real state changes to the original implementation.

These are operation-count claims rather than a universal FPS claim. The repository retains the measured campaign profiles, but the final work was not reduced to one controlled frame-rate number.

## Exact fallback is part of the optimization

Preflight does not permanently patch Starsector or mod JARs. Runtime changes exist only inside the launched child JVM.

Prepared artifacts and bytecode adapters are bound to the inputs and code they were reviewed against. If a game class, mod archive, provider order, cache representation, or expected target changes, the optimization declines and the original game path remains available.

This matters because the surrounding system is not under Preflight's control. It includes obfuscated game code, third-party mods, mutable JSON objects, ordered resource overlays, generated classes, changing JARs, and code paths that were not designed around an external accelerator.

Compatibility is therefore not a separate cleanup phase after performance work. The fallback boundary is part of the performance design.

## From performance engine to desktop product

The same Java engine powers the CLI and the desktop application. React renders the interface, while a narrow Rust/Tauri host owns native process and filesystem capabilities. Native Windows, macOS, and Linux packages include the minimal Java runtime used by Preflight, so ordinary desktop use does not depend on a system JDK.

The product grew around the engineering work rather than beside it:

- durable launch/playtime history derived from the launch ledger rather than a second mutable counter
- named mod profiles and launch settings
- storage planning, cleanup, repair, and interrupted-operation recovery
- setup analysis and a mod linter
- bounded diagnostics and support-report handling
- signed update installation with rollback-aware cache formats
- package capability receipts describing native commands, writes, child processes, links, and network endpoints

That product layer forced a different class of correctness work: process identity, ownership locks, restart recovery, stale responses, interrupted preparation, update failure, rollback, and cleanup all matter even when the underlying performance optimization is correct.

## Source-side analysis

The profiling work also produced tools that inspect the mod ecosystem itself. The current linter has reported **1,392 asset/configuration findings across 84 resource roots**, including four broken released configurations, substantial VRAM/decode waste, and progressive textures that decode **8.75× slower** through the measured game path.

The linter is intentionally diagnostic rather than corrective. It reports measurable problems and leaves the installation unchanged.

## Failure history is part of the evidence

The repository keeps rejected and misleading results because several of them changed the architecture:

- prepared textures initially hit the cache while still waiting behind the real serialized bottleneck
- early texture experiments produced cropped, tiled, black, or displaced output despite plausible counters
- one GraphicsLib replay made a small path much slower and was removed
- a supposed timing pattern turned out to depend on a stale benchmark anchor
- JFR timing under one runtime configuration diverged materially from wall time
- AppCDS did not establish a safe enough win for the reviewed obfuscated classes and was removed

The purpose of retaining these is practical: future changes can see which assumptions already failed and which regression boundaries were added because of them.

## Where to go deeper

- [`optimization-history.md`](optimization-history.md) — chronological performance work and accepted/rejected steps
- [`evidence/`](evidence/) — retained measurements and experiment records
- [`performance-storage-tradeoffs.md`](performance-storage-tradeoffs.md) — preparation/storage modes and current tradeoffs
- [`product-contract.md`](product-contract.md) — player-visible behavior and ownership boundaries
- [`capability-receipt.md`](capability-receipt.md) — packaged native capability model
- [`asset-lint.md`](asset-lint.md) — source-side asset/configuration analysis
- [`../preflight-desktop/README.md`](../preflight-desktop/README.md) — desktop architecture and packaging boundary

The short version is simple: profile the real system, move deterministic work to the boundary where it can actually be reused, bind reuse to exact inputs, and keep the original path available whenever that proof stops holding.