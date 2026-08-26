# Minimal disk after the first launch

**Date:** 2026-08-22  
**Measured source:** `bb17449d043fe9dcf30ec8f55435e36a99776ef4`  
**Preflight jar:** `3abc7ac4e7daa4a842daeca89852595f8d8959a23352a79192f61f65b14089af`  
**Game:** Starsector 0.98a-RC8, 83 enabled mods  
**Status:** diagnostic measurement on a busy development machine

The earlier Minimal measurement stopped after `prepare --no-textures`. It correctly measured the
files written by preparation, but it did not measure the runtime caches learned by the first game
launch. This follow-up measures both.

## Preparation

An empty cache directory was prepared with the default four workers and 256 MiB preparation
budget:

```text
java -jar preflight.jar prepare \
  --game /Applications/Starsector.app \
  --cache-dir /private/tmp/preflight-minimal-bench.Czjggp \
  --no-textures
```

Wall time was **5.14 seconds**. The cache occupied about **11 MB** immediately afterward. The
preparation report was
`d94d417ba90a3850f3c257c4951328970553dacc283e4145b4d2f0ca38162fda` and recorded:

| Stage | Time |
| --- | ---: |
| Census | 3.31s |
| Resource index | 1.23s |
| Classpath index | 1.13s |
| Spec-store identity | 1.69s |
| Textures | skipped |

The stages overlap, so their durations do not sum to wall time.

## Launches

The first launch reached the main-menu marker in **78.96 seconds**. It was a learning launch: the
runtime captured the merged/spec-data caches and compiled Janino classes that offline preparation
cannot produce without running the installed game code.

The next launch reached the same marker in **56.51 seconds**. Its adapter report recorded:

- 8,825 merged-reader hits and 58 misses;
- 1,263 projectile, 3,077 weapon, 2,671 hull, and 5,573 variant JSON hits;
- 228 Janino bytecode hits and zero misses;
- prepared textures off by the explicit Minimal configuration.

The first launch expanded the cache to **209,020 KiB**, about **204 MiB**. Its largest contributors
were 147 MiB of generated-bytecode storage and 45 MiB of spec-store data. Those are logical packed
classes and parsed-data caches, not 147 MiB of unique compiled bytecode. The Janino pack contained
about 1.0 MB of unique class bytes and 149.7 MB when expanded.

Current source now removes an individual generated-bytecode bundle only after publishing, reopening,
and byte-checking an exact session pack that contains it. On this corpus, 152,606,335 bytes of request
bundles duplicate a 1,183,935-byte pack. The expected steady footprint is therefore roughly 50 MB,
pending a new real launch measurement; the 204 MiB figure remains the measured result for the older
layout above.

## Interpretation

Minimal is the non-texture mode. It retains resource routing, classpath, merged JSON, dedicated spec
data, rules, and generated-bytecode acceleration. It does not retain the multi-gigabyte prepared
texture corpus.

The 10.9 MB reference number remains a valid measurement of an immediately prepared cache. It is
not a steady-state disk footprint. Public wording must distinguish preparation output from the
additional caches learned during launch.

These launch times are diagnostic. Other applications and development agents were active, and the
machine was not cooled between runs. They establish the behavior and the large difference between
a learning launch and a warm launch. They are not a controlled comparison against Balanced.
