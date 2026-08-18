# Packed prepared-texture store

## Result

The balanced prepared-texture cache previously opened and checked one content-addressed SPFT file
for every hit. The reviewed startup makes 15,473 lookups and serves 15,469 textures comprising
2,116,422,119 upload bytes. Even after the resource-index snapshot removed source checks, time
inside the exact prepared-texture `load()` seam was 4,559ms.

Each profile can now materialize its existing SPFT blobs into one indexed SPFP file. The runtime
opens and validates the pack once, then uses positional reads on the shared channel. It still checks
the complete SPFT structure and the manifest identity of every result. Any pack open or range-read
failure disables the pack for that process and immediately retries the authoritative loose blob;
the blob-checksum diagnostic deliberately bypasses the trusted pack.

For profile `59b01dc050f39a9f07053bd168cc8c1ecd55086b429b2d732456f87ca217a702`,
the pack contains 30,638 distinct blobs for 32,919 manifest entries. It is 2,204,050,670 bytes and
took 1.16--1.28s to stage and publish from an already-valid loose cache. An unchanged pack validates
as a hit in 67ms and is not copied again.

The final learned-order diagnostic was:

`~/.starsector-preflight/runs/packed-texture-learned-order-clean-20260806-041642`

It reached the menu in **18.80s**, applied all reviewed transformations, shut down normally, and
reported:

- 15,470 pack reads, 2,116,618,727 decoded bytes, and zero pack failures;
- the same 15,469 game-facing hits / 2,116,422,119 bytes and three known misses;
- 1,632ms inside prepared-texture `load()` and 255ms inside direct-buffer preparation.

Against the validated loose-cache diagnostic, the exact serving seam fell **4,559 -> 1,632ms
(-2,927ms, -64.2%)**. Supporting single-run wall times fell from 22.35s to 18.80s. These are clean
diagnostics rather than a randomized cohort, so the seam delta is the causal result and the wall
delta is supporting evidence.

## Self-tuning physical order

A pack initially uses deterministic logical-resource order. On normal JVM shutdown the runtime
writes the distinct successful pack-access order to a 1.4MB checksummed SPFO sidecar. The next
preparation places observed blobs first in that order and appends unseen blobs in stable logical
order. This is profile-specific and self-tunes without platform or mod knowledge.

The progression was:

| Layout | Wall time | Exact `load()` seam |
| --- | ---: | ---: |
| One pack, content-hash order | 21.51s | 3,570ms |
| One pack, logical-resource order | 20.20s | 2,910ms |
| Logical order, learning run | 19.12s | 1,836ms |
| Learned access order | **18.80s** | **1,632ms** |

Some of the last two-run difference may be ordinary cache/thermal noise; the retained mechanism is
justified by its deterministic layout and exact telemetry, not by claiming 320ms from one pair.

## Rejected read-ahead experiment

A 16MiB shared read-ahead window was tested and removed. The game's order is not locally coherent
enough even after lexical layout: 7,589 window hits versus 7,881 misses caused 132.2GB of physical
reads for a 2.2GB pack. The exact seam regressed to 16,563ms and wall time to 35.59s. The final code
uses bounded positional reads only.

## Failure and update behavior

- Missing, stale, malformed, wrong-profile, or wrong-entry-set packs are ignored; loose blobs stay
  authoritative.
- A failure after configure disables the pack and retries the loose blob on the same lookup.
- Missing or corrupt access-order sidecars are only lost tuning hints and never disable a pack.
- A changed mod/resource profile selects a different manifest and pack path automatically.
- Pack writes and order-sidecar writes use temporary files plus atomic replacement where supported.
- The existing checksum diagnostic remains available when distrust of local cache storage matters
  more than launch latency.

The current pack duplicates the loose SPFT corpus so fallback and repair remain possible. A future
GUI storage policy may prune unreferenced profiles and, after an independently repairable packed
format exists, optionally trade fallback redundancy for disk space.
