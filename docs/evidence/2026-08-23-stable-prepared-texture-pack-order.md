# Stable prepared-texture pack order

Status: implementation verified locally; real-install timing evidence recorded below.

## Finding

A complete pack-only texture cache could not adopt a learned physical read order. Preparation
returned as soon as it authenticated the exact pack, so the pack stayed in its original manifest
order after the loose blob copies had been removed.

The current fresh Balanced pack and the older fast pack had the same profile, manifest, byte size,
and entries. Their physical order and SHA-256 differed:

- fresh manifest-order pack: `3fc0a0f3c0161b6d2aed7c5c66ad2caa4483048c4357c4cb7aefd5ee22998f52`
- older learned-order pack: `fca0cd7418256362d9f98baf9125795947a1b64a1b23f1395b2a6acd33a74b34`
- each pack: 2,259,086,856 bytes

Reordering the fresh pack from its own authenticated entry ranges produced the older pack's exact
SHA-256. No loose texture blobs were recreated. The rewrite took 2.11 seconds inside the texture
preparation stage on this installation.

## Stability rule

One atypical launch must not retune the pack. A new access order now has to appear in two
consecutive launches before preparation accepts it. Existing version-1 order observations remain
readable. The pack rewrite uses a temporary file, authenticates every source entry while copying,
opens and verifies the completed temporary pack, then replaces the old pack atomically.

If there is not enough temporary space for the pack plus the existing 1 GiB preparation reserve,
the reorder is skipped. The existing pack remains usable.

## Launch campaign

Checkout engine SHA-256:
`f7a2530fd07ca2d8472ddf5b028f5b1c79e04ed89140b53f3056af55ea4602fc`

Session:
`~/.starsector-preflight/benchmarks/20260823-035711`

Five unattended launches used the actual `--fast` preset with 240 seconds of cooling before every
run. The measured clock was the game-start log marker to the main-menu graphics-preload marker.

| Run | Seconds | Transformation cache |
|---:|---:|---|
| 1 | 16.71 | cold for this new engine JAR |
| 2 | 16.07 | ready |
| 3 | 15.86 | ready |
| 4 | 15.92 | ready |
| 5 | 15.94 | ready |

The five-run median was 15.94 seconds. Every run served 15,469 prepared textures covering
2,116,422,119 bytes, with the same three expected fallbacks and zero pixel-conversion fallbacks.
The last three runs were all below 16 seconds.

This campaign shows that the current checkout still reaches the earlier low-15-second regime. It
does not isolate the pack reorder's wall-time effect because the default benchmark cache already
held the learned-order pack before the campaign. The pack identity comparison and exact rewrite
establish the pack-only retuning defect independently.
