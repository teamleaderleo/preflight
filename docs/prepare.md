# Single-command profile preparation

Prepare the reusable startup data for the current profile and write a validation report:

```bash
java -jar preflight.jar prepare
```

Preflight discovers Starsector, reads the enabled profile, prepares the supported offline artifacts,
checks them, and writes:

```text
~/.starsector-preflight/cache/reports/preparation-latest.json
```

Preparation writes under Preflight's own data directory. It leaves the Starsector installation,
mods, saves, launcher, game preferences, and VM parameter files alone.

Before the first write, the command scans the winning texture set and calculates a storage plan from
the current content, decoded image sizes, reusable data, the profile pack, and filesystem free space.
It also keeps a reserve, so the amount of free disk required to *start* can be much larger than the
cache that remains afterward.

Inspect the plan without writing anything:

```bash
java -jar preflight.jar prepare --plan
java -jar preflight.jar prepare --plan --json --texture-storage balanced
```

The JSON plan separates `predictedAdditionalBytes`, `upperBoundAdditionalBytes`,
`safetyReserveBytes`, and `usableBytes`. On the reviewed 83-mod profile, the August 15 Balanced plan
predicted **4.91 GB** of additional data, used an **11.74 GB** conservative upper bound, and required
**12.92 GB free** once its reserve was included; the completed cache was **4.76 GB**. An earlier cold
observation of the same general profile family was about 4.53 GB, which is a useful reminder that
these are profile-specific measurements rather than a universal cache size. The current desktop
calculates the numbers for the installation in front of it.

For the latest reference measurements and the distinction between predicted bytes, the upper bound,
free space required to start, and the finished cache, see
[Performance and storage tradeoffs](performance-storage-tradeoffs.md).

## Pipeline

The default command starts the independent census, resource-index, and classpath-index stages
together, joins them before their dependants, and then prepares the enabled cache families:

1. enabled-profile census
2. loose-resource provider index build or artifact reuse
3. resource-index validation
4. persistent JAR/classpath profile build or reuse
5. classpath metadata validation
6. SpecStore profile identity build or reuse
7. prepared texture pack/blob build or reuse
8. texture-manifest validation
9. atomic report publication

Other Recommended caches are learned or materialized at their runtime seams, so this command does
not claim to prepare every possible optimization offline. A stage can be skipped or rejected and the
report says so.

Add semantic lookup verification with:

```bash
java -jar preflight.jar prepare --verify-lookups
```

That compares the baseline and indexed lookup behavior for the available indexes and fails the
preparation result on any provider mismatch.

## Useful options

```bash
java -jar preflight.jar prepare \
  --game "/path/to/Starsector" \
  --cache-dir "/path/to/preflight-cache" \
  --report "/path/to/preparation.json" \
  --workers 4 \
  --memory-mb 256 \
  --deep \
  --verify-lookups \
  --lookup-queries 10000 \
  --seed 42
```

`--deep` rehashes source JARs during classpath validation. The texture memory budget applies to
concurrent image decoding, conversion, blob reads, and writes.

The opening stages use at most two helper threads in addition to the calling thread, and texture
decoding waits until those jobs have joined. `--serial-stages` (or
`-Dpreflight.prepare.parallel=false`) is available for diagnosis; `--parallel-stages` overrides a
disabled system property for one command.

Individual stages can be disabled:

```bash
java -jar preflight.jar prepare --no-textures
java -jar preflight.jar prepare --no-classpath
java -jar preflight.jar prepare --no-resource-index --no-textures
```

Texture preparation depends on the loose-resource index. If that index is disabled, the texture
stage is reported as skipped rather than silently guessing which provider should win.

## Repeat runs

On an unchanged profile, repeat preparation can reuse the resource index and classpath profile after
their comparisons pass, reuse prepared texture blobs without decoding the source images again, and
report zero lookup-equivalence mismatches when verification is enabled.

Changing enabled-mod order rebuilds the ordered profile artifacts while preserving reusable
content-addressed JAR inventories and prepared texture blobs whose source content stayed the same.

## Report

Each stage records its status (`SUCCESS`, `FAILED`, or `SKIPPED`), duration, artifact reuse/build
counts, validation results, and diagnostics.

Preparation by itself does not activate a runtime transformation. A later launch still checks the
game, profile, and code relevant to each shortcut, so an artifact can be perfectly reusable while a
runtime optimization steps aside on a changed game or mod version. Fast Rendering remains an
optional, separately identified launcher path.
