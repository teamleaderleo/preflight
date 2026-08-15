# What the one-time preparation actually costs

**Date:** 2026-08-15
**Repository head:** `f6c525741d4149cebe3d9171a23975716af8cd39`
**Preflight jar:** `9825f90b044d38cfde06e9176aa3fb06139eabac89a50d5b756c7d567383cdd9`
**Report:** `prepare --report`, retained outside the repository
**Status:** one run, one machine, one profile

The beta announcement told readers that "first preparation takes a couple of minutes on a big mod
list". Nothing had measured it. This is one cold preparation of the same 83-mod profile the
[controlled campaign](2026-08-15-controlled-vanilla-fast-campaign.md) used, into an empty cache
directory, so it is the path a first-run user takes rather than a rebuild over existing data.

## Result

**200.77 seconds of wall clock** — 3 minutes 21 seconds. The tool's own measure of the work,
excluding JVM startup and exit, is 197.80s.

| stage | status | duration | what it reported |
| --- | --- | --- | --- |
| storage-plan | SUCCESS | 1.03s | predicted 4,912,134,402 additional bytes |
| storage-plan, revalidated under ownership | SUCCESS | 0.97s | same prediction |
| resource-index | SUCCESS | 0.70s | 59,383 entries |
| classpath-index | SUCCESS | 0.82s | 18,021 entries |
| census | SUCCESS | 0.97s | profile `2995668308ac…`, 83 enabled mods |
| spec-store-identity | SUCCESS | 1.54s | |
| **textures** | SUCCESS | **195.28s** | 32,920 candidates, 30,639 unique, 0 cache hits, 30,638 blobs built, 2,255,699,674 blob bytes |
| lookup-verification | SKIPPED | 0.00s | opt-in, not requested |

Textures are **97% of the preparation**. Everything else together is under 6 seconds. That is the
expected shape — the other stages build indexes over metadata, and this stage decodes and stores
thirty thousand images — but it is worth stating plainly, because it means preparation time tracks
the size of a profile's art rather than its mod count.

Options were the defaults: 4 workers, 256 MiB, `balanced` texture storage, parallel stages.

## Disk

The finished cache directory holds **4.76 GB** for this one profile, against a predicted 4.91 GB of
additional bytes. The announcement's "about 4.5 GB" is the right ballpark for one prepared profile.

This is the second observation of that footprint, not the first: [prepare.md](../prepare.md) and
[performance-storage-tradeoffs.md](../performance-storage-tradeoffs.md) already record an observed
~4.53 GB. Tonight's is 4.76 GB — about 230 MB higher, on a profile whose mods have changed since.
The upper bound both documents cite, 11.74 GB, is exactly what tonight's plan predicted. Two
observations roughly a quarter of a gigabyte apart is a reason to quote the figure as a ballpark
rather than to pick whichever is newer.

The development machine's own cache is **11.16 GB**, but that is not the same measurement: it has
accumulated several profiles and texture-storage policies across months of experiments. A reader
sizing their disk wants the 4.76 GB figure, and the difference between the two is the reason the
app ships preview-first cleanup.

## Free space it demands before it will start

Preparation refuses to begin unless a conservative upper bound plus a reserve fits, so the space a
user needs free is much larger than the cache they end up with. From the read-only plans for this
profile:

| | Balanced | Fastest |
| --- | ---: | ---: |
| predicted additional bytes | 4,912,134,402 | 10,706,333,898 |
| conservative upper bound | 11,744,225,102 | 10,806,997,194 |
| safety reserve | 1,174,422,510 | 1,080,699,719 |
| **free space required to start** | **12.92 GB** | 11.89 GB |
| cache actually produced | 4.76 GB | 10.03 GB |

The reserve is 10% of the upper bound in both cases.

Balanced demands *more* free space than Fastest while producing a cache less than half the size.
That is not a defect: Fastest stores raw upload-ready pixels, so its size is known almost exactly
up front, while Balanced's bound has to assume every texture might compress to nothing useful and
allow for pack duplication on top. The bound is what preparation refuses against, not the estimate.

## Fastest storage, same profile, same conditions

A second cold preparation into a second empty cache directory, identical except for
`--texture-storage fastest`:

| | Balanced (default) | Fastest |
| --- | ---: | ---: |
| cache directory on disk | 4.76 GB | **10.03 GB** |
| unique texture blob bytes | 2,255,699,674 | 5,334,811,814 |
| texture stage | 195.28s | 200.84s |
| wall clock | 200.77s | 205.19s |
| blobs built | 30,638 | 30,638 |

**Fastest costs 5.27 GB more on disk, not the "about 3 GB" the announcement claimed.** The 3 GB
figure matches the difference in *texture blob bytes* alone — 5.33 GB against 2.26 GB is 3.08 GB —
but the pack is stored uncompressed under `fastest` as well, and what a reader sizing a disk sees is
the whole cache directory. The announcement has been corrected to the directory figure.

The two runs took 200.77s and 205.19s, a 4.4s spread on one run each. That is not enough to claim
`fastest` prepares faster or slower; on this profile the storage choice bought disk, not
preparation time. What it is meant to buy is launch time, which these runs did not measure.

## Preparation with textures skipped

A third cold preparation of the same profile, `--no-textures`, into a third empty cache:

| | Balanced | `--no-textures` |
| --- | ---: | ---: |
| cache directory on disk | 4.76 GB | **10.9 MB** |
| wall clock | 200.77s | 5.57s |
| tool's own measure | 197.80s | 5.35s |

Stages that still ran: census 3,474.9ms, resource-index 1,302.9ms, classpath-index 1,143.4ms,
spec-store-identity 1,872.4ms. Textures and lookup-verification were skipped. `readiness` is
identical between this run and the full one on every field, including
`cacheArtifactsPrepared: true`.

So the entire disk cost and 97% of the time is the texture stage, and a user who wants Preflight's
metadata work without the texture store can have it for about eleven megabytes. `--no-resource-index`
and `--no-classpath` narrow it further.

**What that costs at launch is not measured here.** The prepared-texture path is where the 25.53s
attributed to prepared textures and prefetch bypass came from, but that figure comes from a
different campaign, and subtracting it from this one would be arithmetic rather than a measurement.
A textures-free launch has not been timed. Doing it properly needs a campaign against a cache
prepared this way, and the harness would want a flag to stop it re-preparing the textures it was
told to skip.

## What this does not say

**The game's files were warm.** Eleven launches ran on this machine in the two hours before this
preparation, so much of the game and mod data was already in the OS page cache. A genuine
first-ever preparation on a cold machine has to read that data from disk and can only be slower
than this. Treat 200.77s as a floor for this profile on this hardware, not a typical figure.

**One run.** No repeat, no second profile, no second machine, and no comparison against the
`fastest` texture-storage setting, which stores pixels uncompressed and would trade disk for time
differently.

**Four workers is the default, not a tuned value.** Whether the texture stage scales with more of
them on this hardware is unmeasured.
