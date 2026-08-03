# Profile hash and resolution memo benchmark

The general merged-read cache adds a broad identity over every provider under `data/`. Without
memoization that identity re-hashes and re-resolves providers already consumed by the four spec JSON
identities. Measurement also exposed a separate duplicate: `CurrentTextureCache` read, checksummed,
decoded and equality-checked the 8 MB resource index, then `RunCommand` immediately decoded the same
artifact again to build the dependency identities.

## Method

`docs/evidence/2026-08-03-profile-hash-memo-benchmark.java` runs the exact seven identity builders in
`RunCommand` order. The baseline was compiled in a detached worktree at `4c85ec4`; current was
compiled from the working tree. Five fresh JVMs per condition were alternated over the exact live
83-mod resource index:

```text
profile: a4a4d1445edcf45bbaf448db7c4e047ee43b0a7cfa15a6d7a446edb1cec3a11f
index sha256: e3989b19c7aba1da6b14c16fc28308361023d5ec9644c33f2e12770b56c7acaa
JVM: Java 21.0.10, aarch64
```

The current incremental figure excludes `index-read`: production already holds that exact
checksummed `ResourceIndex` from `CurrentTextureCache`, and the change passes it forward. The
baseline includes the read because production at `4c85ec4` performed it a second time.

## Result

Medians of five alternating runs, milliseconds:

| | baseline `4c85ec4` | current | change |
| --- | ---: | ---: | ---: |
| merged-read identity phase | 208.409 | 86.311 | **-122.098** |
| all identity bodies | 417.503 | 351.748 | **-65.755** |
| redundant resource-index read | 211.049 | 0 | **-211.049** |
| **incremental dependency-profile preparation** | **632.543** | **357.934** | **-274.609** |

All ten runs produced the same seven identities. This is a 0.275 s offline reduction in work before
the child JVM is spawned, not a claim about main-menu time; the next controlled launch must confirm
the whole preparation path.

The difference between the 122 ms merged-read phase win and the 66 ms whole identity-body win is the
cost of populating the per-preparation hash and resolution maps for earlier corpora. The net remains
positive, and those maps are also what make the broad identity safe to add without giving back a
material part of the previous preparation win.
