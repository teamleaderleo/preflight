# Access-aware Balanced texture experiment harness

**Date:** 2026-08-13

**Issue:** #324

**Status:** harness implemented; real-profile measurement pending

## Purpose

This experiment asks whether some currently-LZ4 startup-hot texture blobs should be stored raw under a small additional disk budget. It does **not** change the product default. `balanced` remains the default 1.30x compression-ratio policy until exact replay evidence and a real launch justify any change.

The retained reference reservoir is the previously measured learned-order prepared-pack seam: roughly 1,137.457 ms for the reviewed Balanced condition versus 691.143 ms all-raw, a 446.314 ms causal seam difference at the cost of roughly 3.08 GB additional pack data. The goal here is to recover a useful fraction of that seam with much smaller +128 MiB, +256 MiB, +512 MiB, and +1 GiB budgets.

## Harness

The compiled test utility is:

`dev.starsector.preflight.cli.AccessAwareTexturePackExperiment`

It is intentionally test/evidence tooling rather than a product CLI command.

### `profile`

Inputs:

`profile CACHE PROFILE OUTPUT_DIR PASSES`

The profiler:

1. loads the current texture manifest and checksummed learned-access sidecar;
2. reconstructs the same codec-independent learned pack order used by preparation;
3. writes two **separate experiment packs** under `OUTPUT_DIR`: the current Balanced selection and an all-raw-where-the-counterpart-exists selection;
4. verifies raw/LZ4 decoded `PreparedTexture` equality;
5. alternates paired raw/LZ4 reads and records a median cost for each observed LZ4 candidate;
6. ranks positive candidates by measured time recovered per additional byte with stable tie breakers;
7. emits deterministic selection files for +128, +256, +512, and +1024 MiB budgets;
8. writes `access-aware-profile.json` with all measurements and selection metadata.

The per-entry timings are **selection input**, not the final seam result. Instrumenting each read would contaminate a whole-corpus timing, so final comparison is performed separately with `replay`.

### `replay`

Inputs:

`replay CACHE PROFILE PLAN_FILE OUTPUT_PACK`

The replay command builds the selected candidate pack outside the timed region, then times one complete read of the exact learned successful-access corpus without per-entry timers. It prints a JSON result containing:

- selected raw candidate count;
- exact access count;
- decoded pixel bytes;
- deterministic output checksum;
- whole-corpus milliseconds;
- candidate pack bytes;
- growth relative to the active Balanced pack.

`plan-balanced.paths` is emitted as an empty-selection baseline. The operator should alternate **fresh JVM invocations** of that baseline and each candidate plan, matching the existing texture-pack benchmark discipline. The checksum, decoded bytes, and access count must remain identical across conditions.

## Required measurement procedure

For the exact reviewed 0.98a-RC8 / 83-mod profile:

1. build/test the reactor so the experiment class is compiled;
2. run `profile` against the retained real cache using Starsector's bundled JVM environment;
3. inspect `access-aware-profile.json` for missing raw counterparts or pathological measurements;
4. alternate fresh-process `replay` runs for Balanced and each budget plan, retaining the full observation series;
5. compare medians/distributions and pack-size deltas;
6. if a candidate wins materially, run a normal integration launch with that candidate policy before proposing any default change.

A result from the per-entry profiler alone is insufficient to change `balanced`. A whole-launch result is likewise reported separately from the exact pack seam.

## Safety boundary

The harness writes only to the caller-supplied experiment output directory. It reads the live cache but does not replace the active manifest, `.spfo` learned-order sidecar, active `.spfp` pack, or product policy. Unknown plan identities and missing raw candidates fail closed during replay.
