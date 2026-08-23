# Performance and storage tradeoffs

**Status:** current design reference

**Reference profile:** Starsector 0.98a-RC8 with 83 enabled mods

**Updated:** 2026-08-23

Preflight moves repeatable work out of launch and into checked artifacts. Disk use is therefore a
visible product tradeoff rather than an implementation detail. The desktop estimates the selected
profile before writing and the CLI enforces the same live free-space checks.

## Current storage choices

The default texture policy is **Balanced**. It keeps one checked, startup-ordered texture pack and
uses lossless LZ4 when compression helps. The CLI's historical `fastest` option keeps the same
upload-ready pixels uncompressed. The desktop calls that option **Uncompressed** because current
whole-launch measurements do not support calling it faster.

The latest cold preparations on the reviewed 83-mod profile found:

| | Balanced | Compact | Uncompressed | Minimal |
| --- | ---: | ---: | ---: | ---: |
| Finished cache directory | **2.3 GB** | **1.1 GB** | **5.2 GB** | **about 50 MB expected after learning** |
| Prepared texture pack | 2,259,086,856 bytes | 1,087,894,442 bytes | 5,338,090,204 bytes | none |
| Tool-reported cold preparation | 32.81s texture stage | 12.58s texture stage | 184.35s older path | 3.69s |
| External cold wall time | 38.33s | 17.25s | not recorded on current path | not recorded |
| Following warm preparation | 4.09s | not measured | not measured | 2.76s |

Balanced used to retain the final pack and every loose texture blob used to make it. That was the
source of the older 4.76 GB figure. Pack-only retention removed the redundant copy after opening and
authenticating the complete pack, reducing the same profile to about 2.3 GB. Uncompressed now costs
about 2.9 GB more than Balanced, almost entirely in its larger pack.

Compact keeps only the 16,013 logical textures observed during a real launch, representing 14,774
distinct source images. Its pack is byte-for-byte identical to the ordered prototype that launched
in 14.17 seconds with 15,469 prepared hits, three safe source fallbacks, and no pixel-conversion
fallbacks. It is an advanced option because a first observed launch is required to learn the set.

The pack-only boundary and launch observations are in
[the 2026-08-23 frontier report](evidence/2026-08-23-pack-only-balanced-frontier.md). The older
[cold-preparation report](evidence/2026-08-15-cold-preparation-cost.md) remains the record of the
previous loose-plus-pack layout.

The Compact corpus, preparation measurements, and corrected intermediate-publication boundary are
recorded in [the Compact preparation report](evidence/2026-08-23-compact-preparation-and-intermediate-publication.md).

## What Uncompressed buys

Balanced stores most prepared texture data as lossless LZ4 blocks and retains raw storage where
compression saves little. Uncompressed stores every prepared pixel array raw.

A controlled exact-replay experiment on the same profile measured the startup access sequence at:

| Texture representation | Pack bytes | Median exact replay |
| --- | ---: | ---: |
| Balanced comparison pack | 2.214 GB | 1,137.457ms |
| Uncompressed raw pack | 5.338 GB | **691.143ms** |

The **446.314ms** difference is real at the isolated texture replay seam. It did not produce a
whole-launch win in the current test. A 5.2 GB uncompressed cache measured a 15.97-second median,
while current Balanced repeatedly reached the low-15 to low-16-second range on the same machine.
Uncompressed remains useful for experiments and machines where decompression behaves differently.
It should not be the default, and the UI should not promise a startup gain.

## Preparation cost

The current cold Balanced preparation completed in **38.33 seconds**, with 32.81 seconds inside the
texture stage. Compact completed in **17.25 seconds**, with 12.58 seconds inside its texture stage.
The same exact corpora previously took 198.56 and 92.30 seconds. The difference was per-blob durable
publication: thousands of checked loose files were forced to storage even though preparation then
packed, authenticated, and deleted them. They are now explicit build intermediates. The final pack
is still forced once, reopened, and validated before publication; a damaged leftover intermediate
is rejected or rebuilt on the next preparation.

The 184.35-second Uncompressed measurement predates that correction and has not been rerun. Minimal
reported 3.69 seconds because it skips textures.

These are one-run diagnostics on a development machine, not a promise for another profile. Raising
the Compact build from four workers and 256 MiB to eight workers and 512 MiB did not change its old
path: 92.45 seconds versus 92.30. That ruled out the resource preset and led to the filesystem fix.
A warm pack-only Balanced preparation completed in 4.09 seconds after applying its stable learned
order.

## Minimal disk after launch

Minimal skips prepared textures while retaining the smaller resource, classpath, merged-data,
spec-data, rules, and generated-bytecode caches. On the reviewed profile, preparation took 5.14
seconds and left about 11 MB on disk. The measured first launch grew an older build to about 204 MiB.
Of that, 152,606,335 bytes were per-request generated-bytecode bundles duplicated exactly by a
1,183,935-byte session pack. Current source deletes each duplicate only after writing, reopening,
and byte-checking the pack. The same corpus should therefore settle around 50 MB, pending a real
launch remeasurement. A following warm launch with the older layout reached the main menu in 56.51
seconds on a busy development machine.

The 10.9 MB reference figure measures the directory immediately after preparation. It is not the
ongoing footprint. Minimal still avoids the multi-gigabyte prepared texture corpus. The historical
measurement and current cleanup boundary are recorded in
[the Minimal launch report](evidence/2026-08-22-minimal-disk-launch.md).

## Other cache contributors

Prepared textures are only one part of the complete directory:

- **Prepared audio** stores exact decoded PCM and can occupy roughly a gigabyte on a large profile.
  Representative PCM was effectively incompressible, so compressing it adds work for little space
  recovery.
- **Generated bytecode** is small after content deduplication; the measured complete-map pack was
  about 1.13 MiB for the reviewed context.
- **Indexes and manifests** are much smaller than media caches but are part of the exact profile and
  validation boundary.
- **Fallback/repair representations** may coexist with active packs until an explicit prune proves
  they are unreachable.
- **Evidence** such as benchmarks and diagnostics is a separate category from acceleration data.

A development installation that has accumulated old profiles, experiments, and evidence can be
much larger than a new user's one-profile cache. Never use a long-lived development directory as
the expected first-install footprint.

## Free-space safety

Before preparation, Preflight calculates the exact profile's expected temporary peak, finished
retained size, current reusable artifacts, and filesystem free space. The initial gate keeps a
128 MiB to 512 MiB reserve. Every large blob write checks live free space again, and the pack writer
checks its exact byte count immediately before atomic publication.

On the reviewed 83-mod cold profile, Balanced reports about 2.3 GB of finished texture data and
about 2.32 GiB free while preparing. Compact finishes at 1.09 GB and needs about 1.15 GiB free. The
former 16.56 GiB requirement came from stacking every raw fallback and temporary representation.
It is no longer shown or used as the admission threshold.

A new manifest becomes active only after preparation succeeds. Interrupted or failed preparation
must leave the previous valid profile usable. Existing checked blobs can remain reusable, and
cleanup is a separate preview-first operation.

## Cleanup model

Storage should be presented in user-relevant categories:

- **Active acceleration:** artifacts reachable from the selected game, profile, and storage policy.
- **Fallback and repair:** checked redundant forms retained for safe recovery.
- **Other profiles and versions:** valid acceleration data outside the active profile.
- **Evidence:** benchmarks, diagnostics, reports, and other retained measurement output.
- **Reclaimable:** data proven unreachable by a completed dry-run cleanup plan.

`preflight cache prune` is a read-only plan. `preflight cache prune --yes` applies that exact plan
only after reachability is complete. Unknown profiles, unreadable manifests, damaged packs, and
identity ambiguity retain data instead of guessing.

Launch reports and benchmark sessions have a separate retention plan because deleting them cannot
affect launch speed. The desktop keeps the 10 newest launch reports and 5 newest benchmarks when a
player chooses **Free space**. It shows acceleration and evidence separately, combines both safe
plans into one review, and recalculates each plan before deletion. Current and named profiles remain
reachable; Starsector, mods, saves, and settings are outside both cleanup roots.

The same evidence retention runs quietly once the desktop is ready and idle. When the complete
cache grows beyond 12 GiB, the desktop also applies the fail-closed prune plan once: current and
named profiles survive, shared blobs remain reachable, and an unsafe or incomplete plan removes
nothing. Failure is nonblocking and doesn't loop; the explicit review is the visible retry path.

## Resource use during preparation

Preparation hashes resources, decodes media, compresses selected textures, and builds indexes.
Independent stages overlap within a bounded concurrency and memory policy. A serial or low-worker
path remains important for lower-memory or thermally constrained systems; a single high-core
machine is not enough evidence for a universal worker count.

## Experimental lossy texture formats

BC1/BC3 preparation remains experimental. It can reduce GPU-resident bytes substantially, but it is
lossy and requires per-texture fidelity gates plus cross-driver validation. It is not part of the
Balanced/Uncompressed lossless product choice and has no accepted runtime consumer yet.

See the retained capability and fidelity evidence under `docs/evidence/` for that experimental work.

## Product wording rule

The desktop should show a local disk estimate for the discovered profile. Published reference
numbers must identify what they measure: pack bytes, unique blob bytes, complete cache-directory
bytes, or reclaimable bytes. Those quantities are not interchangeable.

The current release-facing storage evidence is
[the 2026-08-15 cold-preparation report](evidence/2026-08-15-cold-preparation-cost.md). Historical
codec and pack experiments remain in the evidence archive and should not override newer whole-cache
measurements when describing what a user needs to have free on disk.
