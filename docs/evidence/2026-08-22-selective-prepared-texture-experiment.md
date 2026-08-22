# Selective prepared-texture experiment

**Date:** 2026-08-22

**Issue:** #1065

**Status:** harness and integration probe complete; clean alternating launch comparison pending

## Question

Minimal keeps Preflight's nontexture caches and leaves image loading to Starsector. Balanced prepares
the complete texture corpus and retained about 4.77 GiB on the reviewed profile. This experiment
asks whether a small set of unusually expensive images can recover a material part of the startup
gap without paying Balanced's complete disk cost.

The first candidate is the set of progressive JPEGs observed on a Minimal startup path. This is a
research condition, not a new product mode.

## Candidate

Exact prepared profile fingerprint:

`59b01dc050f39a9f07053bd168cc8c1ecd55086b429b2d732456f87ca217a702`

The installed corpus contained 238 progressive JPEGs. Intersecting the latest Minimal texture trace
selected 143 logical paths and 139 unique prepared blobs. Re-running the retained ImageIO comparison
under Starsector's bundled x86-64 JVM produced:

| corpus | pixels | progressive | baseline | ratio | estimated excess |
| --- | ---: | ---: | ---: | ---: | ---: |
| all 238 progressive JPEGs | 403.3 MP | 49.0 ms/MP | 6.2 ms/MP | 7.88x | 17.27s |
| 143 traced paths | 50.2 MP | 96.3 ms/MP | 11.3 ms/MP | 8.49x | 4.26s |

The selected prepared representation occupies 137,851,596 bytes in its pack, about 131.5 MiB. The
4.26-second figure is a decoder estimate, not a launch result.

## Harness

The compiled test utility is:

`dev.starsector.preflight.cli.SelectivePreparedTextureExperiment`

Inputs:

`SOURCE_FULL_CACHE PROFILE LOGICAL_PATHS_FILE OUTPUT_MINIMAL_CACHE`

The output begins as an isolated copy of the exact Minimal cache used by the comparison. The harness:

1. validates the source manifest and resource index against the requested profile;
2. validates every requested logical path and selected prepared blob;
3. copies only the selected loose blobs and publishes a partial manifest, index, and pack;
4. removes the exact Minimal marker only after every selected artifact has been published.

A failed build remains in Minimal mode. The harness never edits the source cache. It accepts either
one logical path per line or the exact six-column census format used by this investigation.

## Integration probe

One busy-machine diagnostic launch exercised the partial pack under the full shipped `fast` preset:

- source checkout: `8e9db1eb06e7419b6528d8a8742b305a970f8fad`;
- engine SHA-256: `ec9af64ecd08588ef650be0fce3d8c8b4446fa6e1d5ac78be1279fc3c7325f64`;
- main-menu boundary: about 59.5 seconds;
- selected texture hits: 143, all from the pack;
- prepared pixel bytes served: 150,551,448;
- unselected entry fallbacks: 419;
- corruption and pack failures: zero;
- GraphicsLib compact replay applications: one;
- GraphicsLib lazy normal-cache hits: 6,184 of 6,184;
- merged-read hits: 8,268;
- merged-read tagged-tree reconstruction: 178ms inside the cache.

The launcher deliberately stopped the game after the main-menu marker. An OpenAL worker was inside a
native call during shutdown, so lifecycle postprocessing labelled the run failed. The startup report
was complete and establishes the partial-cache routing above. It is not accepted timing evidence.

A preceding probe used generic adapter mode instead of the shipped `fast` preset and omitted the
GraphicsLib compact replay. Its GraphicsLib time does not describe the product and is excluded.

## Decision still required

Run an alternating, quiet-machine comparison between the exact Minimal cache and this exact partial
cache using the full shipped preset. Retain the cache identity, main-menu boundary, selected hits,
fallbacks, and adapter health for every observation. Productize a selective tier only if that
comparison confirms a material wall-time gain.

Nothing from the installed game or mods is stored in the repository.
