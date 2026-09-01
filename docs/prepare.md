# Profile preparation

## TL;DR

```bash
java -jar preflight.jar prepare
```

Preflight reads the current Starsector/mod profile, does reusable work ahead of launch, validates the result, and stores it under Preflight's own cache directory.

It doesn't rewrite the installation, mods, saves, launcher, game preferences, or VM parameter files.

Want to know the disk cost first? Use:

```bash
java -jar preflight.jar prepare --plan
```

That's the normal mental model. The rest of this page is the exact pipeline and CLI detail.

## What preparation writes

The latest validation report is written to:

```text
~/.starsector-preflight/cache/reports/preparation-latest.json
```

Prepared artifacts are content-addressed and tied to the inputs that produced them. Matching data can be reused on later runs; changed inputs select different profile artifacts.

Before writing large data, Preflight calculates the expected temporary peak, finished retained size, reusable checked data, and a free-space reserve. It checks free space again before large writes and final publication, so an unusual profile can stop before filling the disk.

The same storage checks apply whether preparation starts from the desktop app, CLI, or installation flow.

For JSON planning detail:

```bash
java -jar preflight.jar prepare --plan --json --texture-storage balanced
```

The player-facing plan shows the temporary requirement and finished retained size. More detailed quantities such as `predictedAdditionalBytes`, `safetyReserveBytes`, and `usableBytes` stay in the JSON report for diagnostics.

On the reviewed 83-mod development profile, Balanced needs about 2.32 GiB free while preparing and finishes around 2.26 GB. Compact needs about 1.15 GiB free and finishes around 1.09 GB. Those are development observations, not universal requirements; Preflight calculates the current profile's actual plan.

## Pipeline

The default preparation runs independent opening work concurrently where it can, joins dependencies before later stages, and then prepares the enabled offline cache families:

1. enabled-profile census
2. loose-resource provider index reuse or creation
3. resource-index validation
4. persistent JAR/classpath profile reuse or creation
5. classpath metadata validation
6. exact SpecStore profile identity reuse or creation
7. prepared texture pack/blob reuse or creation
8. texture-manifest validation
9. atomic report publication

Some Recommended optimizations learn or materialize data at their own runtime boundaries. `prepare` doesn't claim those were prepared offline when they weren't.

A stage can succeed, fail, or be skipped independently, and the report says which happened.

## Extra lookup verification

```bash
java -jar preflight.jar prepare --verify-lookups
```

This compares deterministic baseline lookups with the prepared indexes and fails preparation on a provider mismatch.

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

`--deep` rehashes source JARs during classpath validation. The texture memory budget covers concurrent image decoding, conversion, blob reads, and writes.

Opening-stage overlap is bounded. Texture decoding starts after the required opening jobs have joined.

For debugging, you can force serial/parallel opening stages:

```bash
java -jar preflight.jar prepare --serial-stages
java -jar preflight.jar prepare --parallel-stages
```

Individual stages can also be disabled:

```bash
java -jar preflight.jar prepare --no-textures
java -jar preflight.jar prepare --no-classpath
java -jar preflight.jar prepare --no-resource-index --no-textures
```

Texture preparation depends on the loose-resource index. If that index is disabled, the texture stage reports itself as skipped instead of silently using an unverified provider set.

## Repeat runs

An unchanged repeat can reuse:

- the resource-index artifact after profile/fingerprint checks;
- the classpath profile after ordered JAR metadata checks;
- prepared texture blobs without decoding the same source again.

Changing enabled-mod order creates the ordered profile artifacts that need a new identity while still allowing unchanged content-addressed JAR/texture data to be reused where valid.

## Report and runtime readiness

Each stage records status, duration, reuse/creation counts, validation results, and diagnostics.

Preparation and runtime activation are separate decisions. A prepared artifact can exist while a runtime adapter declines because the game/mod code no longer matches the target it was reviewed against.

A launch preset must select the relevant reader, exact identities must match, and runtime validation must pass before the in-memory shortcut is used.

That distinction lets offline preparation remain useful without pretending every prepared artifact is active in every launch.
