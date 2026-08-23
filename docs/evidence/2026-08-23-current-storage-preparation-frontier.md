# Current storage and preparation frontier, 2026-08-23

## Scope

This note updates the player-facing storage example after pack-only retention. It also records the
overnight preparation and launch observations used to decide whether the product needs another
storage tier before the first beta.

Installation profile:
`2995668308ac3d31d645ccac30fb1a7e644e64fce5609050a1488df4cadc5af6`

Prepared-texture profile:
`59b01dc050f39a9f07053bd168cc8c1ecd55086b429b2d732456f87ca217a702`

Preparation used four workers, a 256 MiB decoded-texture budget, the same installed game and
83-mod profile, and a new cache directory for each cold condition. The source files were warm in
the operating-system cache. These are development observations rather than promises for another
profile or machine.

## Current retained states

| Retained state | Finished directory | Texture pack | Tool-reported cold preparation | Measured warm launch |
| --- | ---: | ---: | ---: | ---: |
| Minimal | 11 MB before first launch; 204 MB observed after learning | none | 3.690s | 54.38s to 56.51s in noisy diagnostics |
| Balanced | 2.3 GB | 2,259,086,856 bytes | **38.33s current path** | low-15 to low-16-second regime |
| Compact | 1.1 GB | 1,087,894,442 bytes | **17.25s current path** | 14.17s accepted launch |
| Uncompressed | 5.2 GB | 5,338,090,204 bytes | 184.346s | 15.97s median |

Balanced's current texture stage took 32.814 seconds and external wall time was 38.33 seconds.
Compact's texture stage took 12.576 seconds and external wall time was 17.25 seconds. The same
corpora previously took 198.56 and 92.30 seconds because every checked loose pack input was forced
to storage individually. Build intermediates are no longer forced; the completed pack is still
forced once, reopened, and validated before publication. The 184.346-second Uncompressed result
predates this correction. Minimal skips the texture stage entirely.

One matching Balanced preparation completed in 4.092 seconds after the pack already existed. Its
texture stage took 2.369 seconds to apply and verify a stable learned order without recreating loose
texture blobs.

## Why the old numbers changed

The earlier 4.76 GB Balanced directory retained every prepared texture twice: a loose checked SPFT
blob and the startup-ordered SPFP pack built from those blobs. The launch path reads the pack.

Current preparation checks each loose input while copying it into the pack, then releases the input
when no other surviving profile needs it. The completed pack is reopened against the exact manifest
before publication completes. That reduces this profile to about 2.3 GB without changing the
successful upload set or requiring the complete loose set and pack to coexist.

The earlier 10.03 GB high-disk figure had the same duplication. Its current pack-only equivalent is
about 5.2 GB.

## Launch result

The uncompressed pack did not beat Balanced in whole-launch timing. Its isolated replay is about
446 ms faster because it performs no LZ4 decode, but that difference did not survive the rest of the
serialized game launch. Calling the mode `fastest` overstates the evidence. The CLI spelling stays
available for compatibility; the desktop and current documentation call it **Uncompressed**.

Balanced remains the useful knee on this profile. It uses about 2.9 GB less than Uncompressed and
delivers the same whole-launch regime.

## Compact learned-set exploration

Selective pack observations established this rough shape:

| Retained prepared data | One observed launch |
| ---: | ---: |
| 478 MB, loose plus pack | 39.15s |
| 746 MB, loose plus pack | 33.45s |
| 726 MB, pack only | 26.80s |
| 987 MB, pack only | 22.20s |
| 1,087,894,442-byte learned pack | **14.17s** |

Those packs were selected with knowledge of the observed startup access corpus. Two deterministic
600 MB selectors based on texture count and decode cost both landed around 36.9 seconds because a
small number of expensive misses dominated the result. Hit count was not a useful objective.

The later learned-order prototype retained 14,774 blobs in a 1,087,894,442-byte pack. Its accepted
launch clock was 14.174 seconds with 15,469 prepared hits, 3 source fallbacks, and no pixel
conversion fallbacks. Writing the same contents alphabetically produced a 33.53-second launch;
reordering the pack into observed access order took 2.02 seconds and restored the 14.17-second
result. Physical order is part of the performance contract even when the logical contents match.

The prototype's extract-and-repack path took 76.65 seconds. That is not an optimized preparation
pipeline, but it establishes that the current three-minute full conversion is not fundamental to a
smaller tier.

A roughly 1 GB option is now credible. It is not ready as a preparation choice yet. The successful
pack learned from a previous complete profile; a fresh install still needs a bounded access
observer that records logical misses before manifest lookup. A real product tier also needs an
exact partial-pack health contract and a clear fallback when a later startup path needs an omitted
texture.

The Minimal footprint is awaiting a real launch remeasurement. Its previous 204 MiB result included
152,606,335 bytes of generated-bytecode request bundles duplicated exactly by a 1,183,935-byte pack.
Current source removes those bundles only after the pack is written, reopened, and byte-checked.

## Product decision

- Keep Balanced as the default.
- Keep Minimal as the automatic low-space escape hatch.
- Present raw storage as Uncompressed and advanced, with no promised whole-launch gain.
- Build the fresh-install learning boundary before exposing the learned compact tier.

The older codec and loose-plus-pack measurements remain useful history. They no longer describe the
finished directory written by current source.
