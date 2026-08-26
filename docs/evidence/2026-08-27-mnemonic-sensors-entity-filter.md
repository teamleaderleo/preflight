# Mnemonic Sensors entity-filter copy removal

**Status:** accepted with limit; exact structural, fixture, installed-JAR, and live campaign checks passed

The settled paused/unpaused allocation trace identified Mnemonic Sensors 0.5.1 as the largest
paused mod-owned allocation category. Its `MnemonicSensorsEveryFrameScript.markKnownEntities`
method called Kotlin `filterNotNull()` over every entity, built a second list for matching entities,
then mutated those matches. The exact `filterNotNullTo` stack carried 22.0 MiB of weighted JFR
allocation samples in the paused interval and 12.1 MiB in the unpaused interval. These are sampled
weights, not an allocation census.

## Exact boundary

The retained plan removes only the first materialized list. It iterates the original entity list,
skips nulls before the existing predicate, retains the original matching-entity snapshot, and then
runs the original mutation pass over that snapshot. Entity order, predicate inputs, the mutation
boundary, and mutation order remain unchanged.

Admission requires the exact Mnemonic Sensors class SHA-256
`4b483a40555456cb6b8873dbf14735eaaae6607c947fbcf7d91ba470197dc9a6`, archive SHA-256
`d85c2f52df2477b19cd31f0ab9273e758b50067b7151c4f99fb60cf96d10756e`, Java 17 class version,
reviewed source and loader, exact method descriptor, one `filterNotNull` call, one matching-list
`add`, and the reviewed predicate branch and local-variable shape. Identity or instruction drift,
or a second rewrite, preserves the original bytecode.

The plan adds no field, cross-frame cache, serialization data, or persistent mutation. It therefore
cannot enter a save, retain campaign objects, or change the game's save format. Its independent
kill switch is
`PREFLIGHT_DISABLE_ADAPTER_PLANS=mnemonic-sensors-0.5.1-entity-filter-v1`.

## Verification

The bytecode fixture proves that the rewrite removes only the first snapshot, routes null entries
through the existing predicate-miss loop edge, and retains the matching-list construction. It also
proves fail-closed behavior for a wrong class hash, wrong instruction shape, and a second rewrite.
The installed-archive integration test transforms the exact local Mnemonic Sensors JAR and confirms
that the transformed method has no `filterNotNull` call, keeps one matching-list construction, and
adds the null guard.

Java 17 `./mvnw verify` passed after the change: 365 core tests, 54 CLI integration tests with three
skipped, 22 synthetic tests with one skipped, and the complete agent suite. The installed-JAR test
also passed separately against the reviewed archive.

## Live result

A Preflight-only run of `campaign-sample-paused-unpaused.json` applied the exact plan once and
completed every semantic step in one owned Starsector process. Continue, initial paused-state
observation, 27 seconds of paused warmup, 45 seconds paused, mapped unpause, five seconds of
transition, and 45 seconds unpaused all passed. Runtime telemetry recorded 58 applied transforms,
zero contained failures, and one installed Mnemonic target.

The old `filterNotNullTo` stack was absent from both settled windows: zero of 77 paused campaign
allocation samples and zero of 624 unpaused campaign allocation samples. Mnemonic Sensors still had
one unrelated unpaused 4.0-MiB weighted sample in `addRenderLocations`; that is outside this plan.
The result establishes removal of the targeted sampled stack, not an exact byte count.

The same run's paused settled window recorded 3,383 frames at 58.32 average FPS, 59.17 median,
30.30 FPS 1% low, and 25.71 FPS 0.1% low, with no repeated slow-frame clusters. The unpaused window
recorded 1,811 frames at 49.24 average, 58.82 median, 13.89 FPS 1% low, and 5.79 FPS 0.1% low, with
4.80% of frames in repeated slow-frame clusters. This is workload context, not an A/B FPS claim:
the run was not lockstep with the earlier trace and the remaining unpaused work dominated the
small copy removal.

The bounded machine-readable record is
[`data/2026-08-27-mnemonic-sensors-entity-filter.json`](data/2026-08-27-mnemonic-sensors-entity-filter.json).
The raw JFR remains a disposable local artifact and is not committed.
