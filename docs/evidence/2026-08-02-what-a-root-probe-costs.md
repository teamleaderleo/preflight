# What a resource-root probe costs, and why the count is the missing number

**Date:** 2026-08-02
**Install:** Starsector 0.98a-RC8, 90 resource roots (core + 89 mod directories), macOS 15, M5
**Method:** microbenchmark on both JVMs, no game launch
**Status:** per-probe cost measured; **total cost not established** -- see the tension at the end

Written immediately after [the save reference filter was rejected](2026-08-02-what-is-left-measured-without-launching.md)
for exactly the mistake this document is trying not to repeat: multiplying a large count by an
assumed factor and calling the product an opportunity.

## The per-probe cost

Starsector resolves a resource by walking its root list and calling `File.exists` on each candidate
until one hits. Measured directly, 90 real roots on the real install:

| | native arm64 JDK 21 | game JVM (x86_64, Rosetta 2) | penalty |
| --- | ---: | ---: | ---: |
| probe that misses | 1.77 us | **4.65 us** | 2.6x |
| `exists()` on a file that is there | 0.95 us | **4.40 us** | **4.6x** |

**This is the second Rosetta result, and it points the other way from the first.** The save-scan
benchmark found Rosetta almost free for byte scanning and hash-map work -- both memory-bound. Here
it charges 2.6x to 4.6x, because `File.exists` is a syscall and syscall translation is where Rosetta
is genuinely expensive.

So the emerging rule is not "the game runs at Rosetta prices" but something sharper:

| kind of work | Rosetta penalty | measured in |
| --- | ---: | --- |
| SHA-256 (lost intrinsic) | **11.4x** | [the game runs under Rosetta](2026-08-01-the-game-runs-under-rosetta.md) |
| `File.exists` (syscall) | **4.6x** | here |
| `HashMap.put` (pointer chasing) | 1.2x | [save reference benchmark](2026-08-02-what-is-left-measured-without-launching.md) |
| byte scan (memory bound) | ~1.0x | same |

Anything Preflight can move *out* of the game process gains most on crypto, then on syscalls, and
almost nothing on pure memory work. That is a better targeting rule than the blanket one.

## The extrapolation, and why it is not a result

If every resource probed half the root list before hitting:

| corpus | files | @10 roots | @42 roots | @84 roots |
| --- | ---: | ---: | ---: | ---: |
| spec json + csv | 13,069 | 0.61s | 2.55s | 5.10s |
| janino sources | 4,640 | 0.22s | 0.91s | 1.81s |
| textures | 33,579 | 1.56s | 6.56s | 13.11s |
| **total** | | **2.39s** | **10.02s** | **20.02s** |

Ten seconds would be the largest single item left. **It is also almost certainly wrong**, and the
evidence against it is our own.

## The tension

The post-bypass profile
([the game runs under Rosetta](2026-08-01-the-game-runs-under-rosetta.md)) recorded **148 native
samples** in `File.exists` under `com/fs/util/C.Ô00000`, against 4,876 samples on `main`. That is
about 3% of the thread -- on the order of a second, not ten.

Reconciling the two gives the number that actually matters. At 4.65 us per probe, one second of
probing is roughly **215,000 probes**; spread over the ~21,653 resources a launch resolves, that is
about **10 roots per resource, not 42.** The left-hand column of the table, not the middle one.

Which is still 2.4 seconds, and still worth taking -- `ResourceIndex` already knows the winning
provider, so the correct number of probes is **one, or zero**. But it is 2.4 seconds, not 10, and
the difference is entirely in a multiplier this benchmark assumed rather than measured.

Three things could each move it again, and none is settled:

1. **Where files live in root order.** A mod's own textures are found in that mod's root; if the
   loader tries the owning mod early, the average is small. The 10-root figure is derived from a
   profile, not counted.
2. **Whether every resource takes this path.** Textures resolved from Preflight's cache no longer
   probe at all, so the texture row may already be partly paid for.
3. **Negative-lookup caching.** The benchmark repeats the same missing path 200 times, so macOS
   serves most of those from a hot negative dentry cache. A first, cold launch could be worse than
   4.65 us -- which pushes the other way.

## What to do

**Count the probes, do not model them.** The adapter already sits on the resource path; an
instrumented run can report the true probes-per-resource distribution directly, and that single
number turns this from a range spanning 2.4 to 20 seconds into a result. That needs a launch, but
not a timed one -- counts are immune to thermal state.

Only after that is it worth wiring `ResourceIndex` into resolution.
