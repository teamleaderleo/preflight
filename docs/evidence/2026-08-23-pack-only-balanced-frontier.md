# Pack-only Balanced frontier, 2026-08-23

## Question

Balanced kept every prepared texture twice: once as a loose SPFT blob and once in the startup-ordered SPFP pack. The pack is what the launch path reads. This pass measured whether the loose copy still bought enough correctness or speed to justify roughly doubling the texture footprint.

## Full-profile result

Installation profile: `2995668308ac3d31d645ccac30fb1a7e644e64fce5609050a1488df4cadc5af6`

Prepared-texture profile: `59b01b122061beb68082b31362931426b887f3d84055cb189a7633ba799025bd`

The exact full manifest contains 32,919 logical entries and about 30,638 distinct blobs. Its SPFP pack is 2,259,086,856 bytes. Removing the loose copies reduced the prepared cache from roughly 4.5 GB to 2.3 GB.

Ordinary unattended launches against the pack-only cache:

| Session | Result | Prepared-texture result |
| --- | ---: | --- |
| `20260823-001220` | 17.21s | 15,469 hits, 3 source fallbacks |
| `20260823-003011` | 16.11s, 16.19s, 16.26s | 15,469 hits per run, 3 source fallbacks |
| `20260823-011408` | 16.27s, 16.48s, 16.56s | 15,469 hits per run, 3 source fallbacks |
| `20260823-013233` | 16.74s, 17.43s, 17.32s | 15,469 hits per run, 3 source fallbacks |
| `20260823-014823` | 17.18s | 15,469 hits, 3 source fallbacks |

The three stable misses are `entry-missing` rather than pack failures. All 15,469 prepared uploads bypassed pixel conversion. The duplicate and pack-only layouts were timing-equivalent within run noise.

The 20-second observation earlier in the session did not reproduce after the desktop workload was reduced. It was not a missing-cache condition.

The final three-run cohort was taken after restarting Codex and clearing the machine again. Its
cache counters and transformation set were identical. Time spent inside texture loading remained
2.02 to 2.04 seconds, while the total texture-load span grew from 15.11 to 15.25 seconds in the
earlier cohort to 15.28 to 15.56 seconds. The difference accumulated between calls in the game's
serialized load sequence rather than inside the pack reader.

## Comparison with the historical 15-second cohort

The historical 15.25-second run used the old true-size texture preset, enabled by
`preflight.padding.unpadded=true`. That preset avoided about 949 MB of zero-padded upload data on
this corpus. It was removed from the default by #755 because a prepared-pixel fallback could break
the allocation/upload invariant and produce texture corruption or launch failures. Current
Balanced uses the padded coherent-direct path.

The exact source commit used for the historical run was rebuilt and launched against an equivalent
version-2 pack-only cache. It reached the main menu in 16.63 seconds in this session. Current code
reached 16.11 to 17.43 seconds across the clean cohorts above. This shows that pack-only retention
does not cost the current safe path meaningful launch time. It does not establish 15.25 seconds as
the expected result for today's Balanced preset because the two presets move different amounts of
texture data and have different fallback guarantees.

## Pack integrity cost

SPFP version 3 verifies a stored CRC32C for every entry read. The exact 14,774-access startup replay was alternated between the earlier version-2 pack and the current version-3 pack in fresh bundled x86 JVMs.

| Format | Runs, ms | Median |
| --- | --- | ---: |
| v2 | 1036.320, 1038.501, 1071.180, 1024.897, 1088.660 | 1038.501 |
| v3 CRC32C | 1127.881, 1169.807, 1101.667, 1134.435, 1147.989 | 1134.435 |

The measured median cost was 95.934 ms. Removing the integrity check would not recover the missing second and would weaken the fallback boundary.

## Storage tiers observed during exploration

These are single observations. They describe the shape of the frontier and are not a release claim.

| Retained cache | Launch |
| ---: | ---: |
| 206 MB Minimal | 54.38s |
| 478 MB selective, loose plus pack | 39.15s |
| 746 MB selective, loose plus pack | 33.45s |
| 726 MB selective, pack-only | 26.80s |
| 987 MB selective, pack-only | 22.20s |
| 2.3 GB full pack-only | 16.11 to 17.21s |

Pack-only changes the useful frontier. A sub-gigabyte tier remains plausible, while full Balanced can halve its final texture footprint without giving up the launch result.

## Correctness boundary

A loose blob may be removed only after the exact profile pack opens successfully against the complete manifest entry set. Loose data stays when another surviving profile lacks a usable exact pack. If the active pack later becomes unreadable, health reports a rebuild and the runtime falls back to the original installed image rather than serving unverified bytes.

Preparation and the storage planner also recognize an exact warm pack-only profile. They do not
rehash or decode the installed texture sources, and they report zero additional texture build
space instead of claiming that the removed loose copy must be rebuilt. The desktop distinguishes
the retained result from the temporary free space needed during a first build.

The installed corpus has 32,920 candidate paths and 32,919 manifest entries because one TGA is
unsupported. The first exact-hit implementation treated that intentional omission as a stale
manifest and rebuilt every texture in 171.33 seconds. The corrected check probes only paths absent
from the manifest. On the same cache it recognized 30,638 packed blobs plus the unsupported path,
hashed zero files, predicted zero additional bytes, and completed the texture stage in 770 ms. The
whole preparation command, including census, indexes, identity work, and lookup verification,
completed in 7.11 seconds.

The first preparation still needs transient space for loose blobs while it constructs and verifies the pack. Removing that peak requires a separate streaming pack builder. This change targets retained disk use first.
