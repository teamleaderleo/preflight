# Offline preparation dependency graph

**Date:** 2026-08-06

**Profile:** Starsector 0.98a-RC8 with 83 enabled mods; warm resource/classpath artifacts, balanced
texture preparation

## Current serial observation

The latest successful preparation report took 17,994ms and recorded:

| stage | observed time | actual prerequisite |
| --- | ---: | --- |
| profile census | 2,912ms | installation only |
| resource index | 1,473ms | installation only |
| classpath index | 319ms | installation and cache only |
| SpecStore identity | 1,691ms | resource and classpath indexes |
| balanced textures | 11,590ms | resource index |
| lookup verification | skipped | whichever indexes are enabled |

The implementation currently executes that table strictly top to bottom. Static inspection gives
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

## Implementation boundary

Do not simply submit every current stage to a common executor. Census, resource indexing, and
texture preparation traverse overlapping mod files; texture preparation already owns a bounded
worker pool and memory budget. Overlap can trade wall time for disk contention, duplicate reads,
and higher thermal load—especially on lower-end machines.

The next experiment should preserve the report and artifact contracts while alternating these
candidate schedules on a stable profile:

1. current serial baseline;
2. census, resource index, and classpath index started together, then the existing joins;
3. resource and classpath indexes together, with census delayed or derived from their enumeration;
4. resource index followed by textures while classpath/SpecStore use a separately bounded lane.

Measure wall time, process CPU, bytes read, and peak resident memory. Keep a serial kill switch and
make artifact publication atomic before enabling any schedule by default. This concerns offline
preparation only; no game, OpenGL, mod callback, or Starsector main-thread work is eligible here.
