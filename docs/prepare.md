# Single-command profile preparation

Prepare every renderer-independent cache and write a validation report:

```bash
java -jar preflight.jar prepare
```

Preflight discovers Starsector, reads the enabled profile, prepares reusable artifacts, validates them, and writes:

```text
~/.starsector-preflight/cache/reports/preparation-latest.json
```

Preparation writes only Preflight-owned, content-addressed data and its validation report. The
installation, mods, saves, launcher, game preferences, and VM parameter files remain unchanged.

Before any write, the command scans the winning texture set and calculates a storage plan from
encoded content hashes, decoded dimensions and alpha channels, deduplication, reusable checked
blobs, the profile pack, and filesystem free space. The initial gate uses the expected temporary
build peak plus a 512 MiB to 1 GiB reserve. The writer checks live free space again before every
large blob write and before publishing the exact pack, so an unusual corpus stops safely instead
of filling the disk. The same checks run whether preparation starts from the desktop app, the CLI,
or installation.

Inspect the plan without writing anything:

```bash
java -jar preflight.jar prepare --plan
java -jar preflight.jar prepare --plan --json --texture-storage balanced
```

The player-facing plan shows the expected temporary requirement and finished retained size.
`predictedAdditionalBytes`, `upperBoundAdditionalBytes`, `safetyReserveBytes`, and `usableBytes`
remain in the JSON report for diagnostics. The upper value is a raw codec ceiling, not a normal
disk requirement and does not control the initial gate. Existing loose blobs count as reusable
only after their full checked read succeeds. On the reviewed 83-mod cold profile, the expected
temporary peak is about 4.9 to 5.0 GB and the finished full texture pack is about 2.26 GB. The
read-only plan leaves a nonexistent target directory nonexistent.

## Pipeline

The default command starts the independent census, resource-index, and classpath-index stages
together, joins them before their dependants, and then prepares the enabled cache families:

1. enabled-profile census
2. loose-resource provider index build or artifact reuse
3. resource-index validation
4. persistent JAR/classpath profile build or reuse
5. classpath metadata validation
6. exact SpecStore profile identity build or reuse
7. prepared texture pack/blob build or reuse
8. texture-manifest validation
9. an atomic report write

Other Recommended caches are learned or materialized at their own exact runtime boundaries; this
command doesn't pretend to prepare them offline. A stage can be skipped or rejected without
turning the report into a claim that it was prepared.

Add semantic lookup verification:

```bash
java -jar preflight.jar prepare --verify-lookups
```

That runs the deterministic baseline-versus-index comparison for both available indexes and fails the preparation result on any provider mismatch.

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

`--deep` rehashes source JARs during classpath validation. The texture memory budget applies to concurrent image decoding, conversion, blob reads, and writes.

Opening-stage overlap is bounded to two helper threads in addition to the calling thread; texture
decoding doesn't begin until those jobs have joined. Use `--serial-stages` (or
`-Dpreflight.prepare.parallel=false`) as a diagnostic kill switch. `--parallel-stages` overrides a
disabled system property for a command.

Individual stages may be disabled:

```bash
java -jar preflight.jar prepare --no-textures
java -jar preflight.jar prepare --no-classpath
java -jar preflight.jar prepare --no-resource-index --no-textures
```

Texture preparation requires the loose-resource index. Disabling that index causes the texture stage to be reported as skipped rather than silently using an unverified provider set.

## Repeat runs

An unchanged repeat run can report:

- resource index artifact hit after profile rescan and fingerprint comparison
- classpath profile hit after ordered JAR metadata comparison
- prepared texture blob hits without ImageIO decoding
- zero lookup-equivalence mismatches

Changing enabled mod order rebuilds ordered profile artifacts while preserving content-addressed JAR inventories and prepared texture blobs whose source content is unchanged.

## Report

Every stage records:

- `SUCCESS`, `FAILED`, or `SKIPPED`
- duration
- artifact hits and builds
- validation counts and problems
- diagnostics

The report also has a separate readiness section. Preparation never installs an in-memory
transformation by itself; a launch preset must select a reader, exact code/profile identities must
match, and runtime validation must pass. A prepared artifact can therefore be ready while its
adapter declines on a different game or mod build. Fast Rendering remains an optional, separately
identified launcher/ownership target.

This distinction keeps offline preparation useful without overstating runtime integration or activation readiness.
