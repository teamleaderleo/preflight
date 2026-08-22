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
| Minimal | 11 MB before first launch; about 204 MiB after learning | none | 3.690s | 54.38s to 56.51s in noisy diagnostics |
| Balanced | 2.3 GB | 2,259,086,856 bytes | 195.079s | low-15 to low-16-second regime |
| Uncompressed | 5.2 GB | 5,338,090,204 bytes | 184.346s | 15.97s median |

Balanced's texture stage took 193.163 seconds. External wall time for that command was 198.56
seconds. The corresponding Uncompressed texture stage took 181.468 seconds. Minimal skips the
texture stage entirely.

One matching Balanced preparation completed in 4.092 seconds after the pack already existed. Its
texture stage took 2.369 seconds to apply and verify a stable learned order without recreating loose
texture blobs.

## Why the old numbers changed

The earlier 4.76 GB Balanced directory retained every prepared texture twice: a loose checked SPFT
blob and the startup-ordered SPFP pack built from those blobs. The launch path reads the pack.

Current preparation opens the complete pack against the exact manifest, verifies it, and then
removes loose texture copies that no other surviving profile needs. That reduces this profile to
about 2.3 GB without changing the successful upload set. The first build still needs temporary
space for the loose inputs while the pack is under construction.

The earlier 10.03 GB high-disk figure had the same duplication. Its current pack-only equivalent is
about 5.2 GB.

## Launch result

The uncompressed pack did not beat Balanced in whole-launch timing. Its isolated replay is about
446 ms faster because it performs no LZ4 decode, but that difference did not survive the rest of the
serialized game launch. Calling the mode `fastest` overstates the evidence. The CLI spelling stays
available for compatibility; the desktop and current documentation call it **Uncompressed**.

Balanced remains the useful knee on this profile. It uses about 2.9 GB less than Uncompressed and
delivers the same whole-launch regime.

## Sub-gigabyte exploration

Selective pack observations established this rough shape:

| Retained prepared data | One observed launch |
| ---: | ---: |
| 478 MB, loose plus pack | 39.15s |
| 746 MB, loose plus pack | 33.45s |
| 726 MB, pack only | 26.80s |
| 987 MB, pack only | 22.20s |

Those packs were selected with knowledge of the observed startup access corpus. Two deterministic
600 MB selectors based on texture count and decode cost both landed around 36.9 seconds because a
small number of expensive misses dominated the result. Hit count was not a useful objective.

A sub-gigabyte option may still be worthwhile for players who value disk more than the last six
seconds. It is not ready as a preparation choice yet. A real product tier needs a deterministic
first-launch policy, an exact partial-pack health contract, and a clear fallback when later startup
paths need an omitted texture. Adding a label before those boundaries exist would disguise an
experiment as a supported mode.

## Product decision

- Keep Balanced as the default.
- Keep Minimal as the automatic low-space escape hatch.
- Present raw storage as Uncompressed and advanced, with no promised whole-launch gain.
- Do not add a fourth storage choice until a deterministic sub-gigabyte plan earns it in a repeated
  launch comparison.

The older codec and loose-plus-pack measurements remain useful history. They no longer describe the
finished directory written by current source.
