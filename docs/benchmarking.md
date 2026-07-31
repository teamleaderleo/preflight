# Benchmarking

**To actually run one, see [the repeated startup benchmark](startup-benchmark.md).** That
harness drives the protocol below against the reviewed installation: three conditions
(vanilla, agent-only, enabled), shuffled inside every round, resumable, with the report
refusing to accept a result below five successful runs per condition. This page remains the
contract and the command reference.

For the runtime/launcher campaign matrix (vanilla+bundled-Java, vanilla+alternate-Java, FR
— each OFF and warm), the per-combination outcome list, the community launch-time envelope,
and why identities must never be merged, see
[community-evidence.md](community-evidence.md).

## Report these separately

- Baseline without Preflight
- First launch after building or invalidating caches
- Repeat launch with warm Preflight caches
- Cold-ish filesystem launch where the platform permits a reproducible procedure

## Record for every run

- Hardware model, CPU architecture, and memory
- Operating system version
- Java vendor and full version
- Starsector version
- Fast Rendering build and platform port
- JVM arguments
- Ordered enabled-mod profile fingerprint
- Preflight commit and configuration
- Browser/background-load state
- Memory pressure or swapping observations

## Scenario result contract

Record one manually driven startup scenario with three separate phases:

1. process start to usable main menu;
2. main menu to representative campaign readiness;
3. campaign readiness to first representative combat readiness.

The recorder accepts runner-supplied UTC timestamps and writes one deterministic JSON result:

```bash
java -jar preflight.jar benchmark scenario \
  --run-id 20260717-100000 \
  --scenario-id campaign-combat-v1 \
  --mode enabled-warm-hit \
  --iteration 1 \
  --profile-fingerprint PROFILE_SHA256 \
  --process-start 2026-07-17T10:00:00Z \
  --main-menu-ready 2026-07-17T10:00:12Z \
  --campaign-ready 2026-07-17T10:01:02Z \
  --first-combat-ready 2026-07-17T10:01:32Z \
  --exit-code 0 \
  --adapter-counter texture.hits=17 \
  --adapter-counter texture.misses=2 \
  --cache-counter entries=15 \
  --cache-counter bytes=4096 \
  --output benchmark.json
```

Supported modes are:

- `off-coldish`
- `off-warm`
- `enabled-build-miss`
- `enabled-warm-hit`
- `enabled-corrupt-artifact`
- `enabled-changed-profile`

The version 1 schema records:

- run ID, scenario ID, mode, iteration, and optional profile fingerprint;
- all four milestone timestamps;
- each phase duration plus process-to-campaign and process-to-combat totals;
- bounded adapter counters, cache counters, and disable reasons;
- process exit code and successful-exit classification.

Counter names use lowercase letters, digits, dots, underscores, and hyphens. Each adapter/cache domain accepts at most 64 counters. A result accepts at most 16 disable reasons. Counter ordering and disable-reason ordering are canonical in the JSON output.

## Raw scenario comparison

Compare two or more version 1 scenario records with:

```bash
java -jar preflight.jar benchmark compare \
  results/off-warm-1.json \
  results/off-warm-2.json \
  results/enabled-warm-hit-1.json \
  results/enabled-warm-hit-2.json \
  --output raw-comparison.json
```

The raw comparison command:

- requires one scenario ID and one non-null profile fingerprint across every input;
- rejects duplicate run IDs and duplicate mode/iteration pairs;
- reconstructs every record through the versioned result validator;
- verifies that stored durations agree with the milestone timestamps;
- preserves every successful and unsuccessful run in deterministic mode/iteration order;
- reports successful and failed counts per mode;
- reports minimum, median, and maximum for each successful-run duration metric.

Statistics exclude nonzero-exit runs while retaining those runs in the report. Raw scenario comparison is useful for recorder validation and early diagnostics. It does not carry the binary, runtime, launcher, or artifact identity needed for a campaign claim.

## Collect run evidence

After Starsector exits and `run.json` is finalized, bind the scenario result to the run directory:

```bash
java -jar preflight.jar benchmark collect \
  ~/.starsector-preflight/runs/20260717-100000-000-ab12cd34 \
  --scenario results/enabled-warm-hit-1.json \
  --output results/enabled-warm-hit-1.collected.json
```

The collector reads `run.json`, `profile.json`, `summary.json`, optional `adapter.json`, and the scenario record into bounded byte snapshots. Parsing and SHA-256 use the same bytes. Collection rejects:

- unfinished runs or scenario milestones outside the finalized run interval;
- profile or exit-code disagreement;
- OFF/ENABLED adapter-mode disagreement;
- missing enabled-mode adapter evidence;
- incomplete wrapper or JFR-recorded process identity;
- `run.json` paths that do not identify the collected profile and adapter evidence;
- evidence files that escape the run directory through symbolic links.

The collected version 1 record retains the full scenario, selected run/JFR/adapter evidence, wrapper and child-runtime comparison identities, texture artifact identity, and exact hashes of every parsed source file.

## Campaign comparison

Compare collected records for the reportable campaign result:

```bash
java -jar preflight.jar benchmark compare-runs \
  results/off-warm-1.collected.json \
  results/off-warm-2.collected.json \
  results/enabled-warm-hit-1.collected.json \
  results/enabled-warm-hit-2.collected.json \
  --output campaign.json
```

Campaign comparison rejects mixed:

- scenario IDs and enabled-mod profile fingerprints;
- Preflight JAR hashes;
- wrapper JVM identities;
- JFR-recorded child JVM, OS, and CPU identities;
- launcher kinds;
- texture artifact identities within one mode;
- run IDs and mode/iteration pairs.

Enabled records require exact texture profile, manifest, and index hashes. Every input record is retained in the output, including unsuccessful runs. Duration statistics use successful runs and report minimum, median, and maximum per mode.

The version 2 campaign report also includes `primaryComparison` when both `off-warm` and `enabled-warm-hit` have at least one successful run. For every startup duration it reports:

- `baselineMedianMs` for `off-warm`;
- `candidateMedianMs` for `enabled-warm-hit`;
- `deltaMs` as candidate minus baseline, so a negative value means the enabled median is lower;
- `improvementPercent` as `(baseline - candidate) / baseline * 100`, so a positive value means the enabled median is lower.

`primaryComparison` records `campaignMinimumSuccessfulRunsPerMode: 5` and sets `campaignMinimumMet` only after each primary mode has five successful runs. This keeps early diagnostic deltas visible while distinguishing them from the planned campaign threshold.

Nonzero-exit runs remain in the campaign record and stay out of the median calculation. `primaryComparison` is `null` until both primary modes have a successful run. A zero baseline median produces a `null` improvement percentage rather than an undefined division result.

A changed-profile fallback case intentionally has a different profile identity. Collect and report it separately from the fixed-profile OFF-versus-ENABLED campaign.

## Procedure

1. Use one fixed enabled-mod profile and launch target.
2. Perform one discarded setup launch after changing software.
3. Capture at least five successful `off-warm` runs.
4. Capture at least five successful `enabled-warm-hit` runs.
5. Capture one `enabled-build-miss` and one `enabled-corrupt-artifact` case.
6. Record one changed-profile fallback case as a separate report.
7. Preserve raw run directories, JFR files, scenario records, collected records, console notes, and unsuccessful results.
8. Generate the campaign report from collected records.
9. Report every individual result, per-mode median/minimum/maximum, and the primary median delta/improvement percentage.
10. Exclude runs affected by updates, indexing jobs, thermal throttling, or severe swap activity, while preserving them with an exclusion reason.

Filesystem cache state differs by platform. Describe the exact method used instead of calling a result fully cold. The benchmark contracts establish comparable evidence; they make no performance claim by themselves.
