# Compact preparation and intermediate publication

**Date:** 2026-08-23

**Profile:** Starsector 0.98a-RC8, 83 enabled mods, M5 MacBook Air

**Result:** Compact keeps the launch-observed texture frontier in 1.09 GB. Removing per-file durable
flushes reduced its cold preparation from 92.30 seconds to 16.51 seconds and full Balanced from
198.56 seconds to 44.62 seconds.

## Compact frontier

One real launch observed 16,013 logical texture paths representing 14,774 distinct source images.
Building only that set produced:

| | Result |
| --- | ---: |
| Logical texture entries | 16,013 |
| Distinct prepared blobs | 14,774 |
| Prepared pixel bytes | 2,074,073,333 |
| Finished pack | 1,087,894,442 bytes |
| Loose build data released after publication | 1,498,567,832 bytes |

The pack bytes and physical order match the earlier access-ordered prototype. Its accepted startup
measurement was 14.17425 seconds with 15,469 prepared hits, three source fallbacks, and no
pixel-conversion fallbacks.

## The preparation bottleneck

The first product build used four workers and a 256 MiB memory budget:

| | External wall | Texture stage | User CPU | System CPU |
| --- | ---: | ---: | ---: | ---: |
| Compact, original publication | 92.30s | 87.80s | not recorded | not recorded |
| Compact, 8 workers / 512 MiB | 92.45s | 87.82s | 51.07s | 47.32s |
| Compact, build intermediates | **16.51s** | **11.96s** | 33.31s | 16.31s |
| Full Balanced, build intermediates | **44.62s** | **39.12s** | 76.44s | 28.78s |

Doubling workers and memory did nothing. The builder was forcing each content-addressed loose SPFT
to stable storage, then reading all of them into one pack and deleting them after validation. With
14,774 Compact blobs and 30,638 full-profile blobs, filesystem publication dominated the run.

Loose SPFTs are now explicitly rebuildable pack inputs. A successful write is still a complete
checksummed SPFT. Pack construction verifies every SPFT payload while copying it, forces the single
finished pack, reopens it, checks its exact identity and order, and only then releases the loose
inputs. An interrupted or damaged intermediate is validated before reuse and rebuilt when invalid.
Standalone prepared-texture writes keep their original durable atomic contract.

## Storage admission

The current planner estimates the representation this build will actually write, keeps a 512 MiB
to 1 GiB free-space reserve, and large writes retain live free-space guards. The raw pixel ceiling is
diagnostic data, not the player-facing requirement or the initial admission threshold.

On these cold runs:

| | Finished pack | Measured loose inputs | Current required-free estimate |
| --- | ---: | ---: | ---: |
| Compact | 1,087,894,442 B | 1,498,567,832 B | 3,207,042,449 B |
| Balanced | 2,259,086,856 B | 2,758,182,590 B | 6,221,258,874 B |

The required-free number includes metadata and the reserve. The app presents the finished data and
temporary requirement separately. It does not show the raw codec ceiling.
