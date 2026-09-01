# Engineering overview

## TL;DR

Preflight started with one question: **why does a heavily modded Starsector redo so much expensive work every launch?**

The recurring answer was:

```text
measure the real bottleneck
        ↓
find repeated deterministic work
        ↓
move/reuse the answer earlier
        ↓
bind it to exact inputs
        ↓
fall back when those inputs stop matching
```

On the documented 83-mod development setup, the selected startup headline is **112.17s → 13.69s**.

The biggest lesson wasn't “add more caches.” It was **put reusable answers at the boundary where the repeated work actually happens, before the cost you want to avoid**.

If you'd rather get the gentler tour first, read [How Preflight works](how-preflight-works.md). This page is the compact engineering story.

## The recurring pattern

Most useful optimizations followed the same loop:

1. profile/instrument the real launch or runtime path;
2. find repeated work instead of guessing from the loading screen;
3. identify the inputs that make the answer valid;
4. prepare, memoize, or index the result at the useful boundary;
5. let the original path run when identity/validation stops matching;
6. measure again;
7. keep the failures that changed the design.

Several early optimizations were locally correct and still disappointing because they sat on the wrong side of the expensive queue/read/representation.

## Shared data reads: five caches pointed to one lower boundary

The visible 0% loading plateau first produced separate JSON/CSV caches for variants, weapons, projectiles, hulls, and campaign rules. Those changes moved `SpecStore` from **19.8s to 9.8s**.

Profiling then showed the same lower merge/parse work repeating beneath those loaders. One measured launch issued **39,017 JSON calls across 8,378 distinct paths**.

So Preflight moved reuse to the shared data-read boundary. The remaining merged-read seam moved from **2.172s to 0.300s**.

Stored JSON text also became a typed-tree representation so replay didn't need to parse the same text again. The representation was replayed through the installed JSON runtime and compared across roughly **990,000 recursively visited values** before becoming part of the normal path.

The useful lesson: five local caches can be evidence that the reusable answer belongs lower.

## Textures: put the decision before the expensive wait

Prepared textures initially showed healthy hit counters without delivering the expected whole-launch win.

The reason was simple after profiling: the loading thread could still wait roughly **27 seconds** behind Starsector's single-threaded texture-prefetch queue before the prepared-data decision was consulted.

Moving the decision ahead of that wait changed the critical path.

Later texture work also removed unnecessary power-of-two upload padding, cutting **1.22 GiB of VRAM padding** from the measured full load.

So “cache hit” was never enough. The hit had to happen before the cost.

## Texture storage: rebuildable data doesn't need thousands of durable files

An early preparation path made thousands of rebuildable texture intermediates durable. On the reviewed profile, that broader path reached **200.77s** and **4.76 GB**.

The later design streams rebuildable staging data into a final published pack instead. Compact preparation reached **16.21s** with roughly **1.1 GB** of retained data on that development profile.

Physical order also mattered. With the same logical Compact texture corpus:

- alphabetical layout: **33.53s** launch;
- observed startup-access order: **14.174s** launch.

Same data. Different physical order. Very different I/O behavior.

## Generated code: avoid repeated compilation, then avoid repeated duplicate output

Starsector mods can compile Java code at runtime through Janino.

Preflight memoized **228 compilation requests**, moving the measured compiler seam from **18.014s to 2.364s**.

Persisting that result exposed another waste: **36,332 generated-class occurrences** represented only **280 unique classes**.

Deduplication moved the stored class maps from **145.96 MiB to 1.13 MiB**, while replay moved from **1.501s to 29ms**.

Those are two separate optimizations:

1. don't compile the same answer again;
2. don't store/replay the same answer thousands of times either.

## Runtime work: the same idea applies after startup

Profiling found repeated work during campaign simulation too.

One sector-wide lookup path repeatedly revalidated large lists. Mutation-tracked indexes moved **227,805 full-list validations to zero** and **79.1 million entity-reference checks to zero** in the adjacent live pilots that established the before/after work.

Another hotspot repeatedly recomputed commodity state that hadn't changed. The memoized path served **117.9 million unchanged calls**, while real state changes still delegated to the original implementation.

These are operation-count results, not a universal FPS promise. The point is the same one: recompute when the answer changes, not every time somebody asks for it.

## Fallback is part of the optimization

Preflight doesn't permanently rewrite Starsector or mod JARs. Runtime transformations live inside the launched child JVM.

Prepared artifacts and adapters are bound to the inputs/code they were reviewed against. A changed class, archive, provider order, representation, loader, or profile can make one shortcut decline while the original game path stays available.

That lets Preflight be aggressive at narrow, proven boundaries without pretending every current/future mod version is equivalent.

Compatibility therefore isn't a cleanup step after performance work. The eligibility/fallback rule is part of each optimization from the start.

## Turning the performance work into a desktop product

The same Java engine powers the CLI and desktop app. React renders the UI; a narrow Rust/Tauri host owns native process/filesystem/update capabilities.

The product work added its own correctness problems:

- launch/playtime history that survives UI restarts;
- named mod profiles and launch settings;
- disk planning, cleanup, repair, and interrupted-operation recovery;
- setup analysis and mod linting;
- bounded diagnostics/support reports;
- signed updates and rollback-aware formats;
- exact package capability receipts.

A clever accelerator that loses track of processes, corrupts settings after a restart, or deletes the wrong files would still be a bad product. The desktop work exists to make the performance engine dependable in ordinary use.

## Mod/setup analysis

The same investigation tools also became read-only analysis features.

The linter has reported **1,392 asset/configuration findings across 84 resource roots** in the reviewed work, including broken released configurations, avoidable VRAM/decode cost, and progressive textures that decoded **8.75× slower** through the measured game path.

The linter reports problems. It doesn't edit somebody else's mod to guess what the author intended.

## Failures are retained because they prevent reruns of bad ideas

Useful failures include:

- prepared textures hitting while the thread still waited behind the real bottleneck;
- texture experiments with plausible counters but cropped/tiled/black/displaced output;
- a GraphicsLib replay that made a small path slower and was removed;
- a timing pattern that turned out to use a stale benchmark anchor;
- JFR timing under one runtime configuration diverging materially from wall time;
- AppCDS failing to establish a safe enough win for the reviewed obfuscated classes and being removed.

The evidence archive keeps these because “we tried this already, and here is exactly why it failed” is useful engineering information.

## Where to go deeper

You usually need only one next document:

- **chronological optimization history:** [Optimization history](optimization-history.md)
- **raw retained experiments:** [Evidence archive](evidence/)
- **preparation/storage choices:** [Performance and storage tradeoffs](performance-storage-tradeoffs.md)
- **exact player/write/fallback rules:** [Product contract](product-contract.md)
- **desktop/native boundary:** [`preflight-desktop/README.md`](../preflight-desktop/README.md)

Short version again: **measure the real system, reuse deterministic work at the useful boundary, bind reuse to exact inputs, and keep the original behavior available when the proof stops holding.**
