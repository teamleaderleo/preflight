# Offline preparation dependency graph

**Date:** 2026-08-06

**Profile:** Starsector 0.98a-RC8 with 83 enabled mods; warm resource/classpath artifacts, balanced
texture preparation

## Historical serial observation

The latest successful preparation report took 17,994ms and recorded:

| stage | observed time | actual prerequisite |
| --- | ---: | --- |
| profile census | 2,912ms | installation only |
| resource index | 1,473ms | installation only |
| classpath index | 319ms | installation and cache only |
| SpecStore identity | 1,691ms | resource and classpath indexes |
| balanced textures | 11,590ms | resource index |
| lookup verification | skipped | whichever indexes are enabled |

The implementation executed that table strictly top to bottom. Static inspection gives
this real dependency graph:

```text
installation -> census
installation -> resource index -> textures
installation -> classpath index
resource index + classpath index -> SpecStore identity
resource index + classpath index -> optional lookup verification
```

With the observed durations and no resource contention, the graph's mathematical critical path is
resource index plus textures, about 13,063ms. The gap from the serial 17,994ms is 4,931ms (27.4%).
That is an upper bound, not an accepted speedup.

## Accepted bounded schedule

The implementation now starts census and classpath work on two helper threads while the caller
builds the resource index. All three join before SpecStore identity; textures still begin only after
that join, so their existing worker and memory budgets never overlap the opening jobs.

Three position-alternated serial/parallel pairs used the same exact balanced cache. The first serial
run was a filesystem-cold 17.885s outlier; it is retained as evidence but not used as a steady-state
pair claim. The two subsequent reversed pairs measured:

| pair | serial wall | parallel wall | parallel delta |
| --- | ---: | ---: | ---: |
| 2 (parallel first) | 10.103s | 9.183s | -0.920s (-9.10%) |
| 3 (serial first) | 10.268s | 9.309s | -0.959s (-9.34%) |

All six preparations succeeded and emitted identical resource-index, classpath-index, SpecStore,
and texture-manifest identities. The bounded schedule is therefore the default. `--serial-stages`
or `-Dpreflight.prepare.parallel=false` restores the original order; `--parallel-stages` explicitly
reenables overlap. Evidence is in `/tmp/preflight-prepare-parallel.WqiIyv` on the development host.

## Implementation boundary

Do not simply submit every current stage to a common executor. Census, resource indexing, and
texture preparation traverse overlapping mod files; texture preparation already owns a bounded
worker pool and memory budget. Overlap can trade wall time for disk contention, duplicate reads,
and higher thermal load—especially on lower-end machines.

The accepted experiment preserved the report and artifact contracts. More aggressive candidates
remain intentionally unimplemented:

1. derive census from another stage's enumeration;
2. overlap textures with classpath/SpecStore on a separately bounded lane.

Any further schedule must measure wall time, process CPU, bytes read, and peak resident memory.
Artifact publication remains atomic and the serial kill switch remains permanent. This concerns
offline preparation only; no game, OpenGL, mod callback, or Starsector main-thread work is eligible.
