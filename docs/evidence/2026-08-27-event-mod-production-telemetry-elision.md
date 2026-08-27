# Production event-mod telemetry-call elision

**Status:** accepted with limit; exact structural, installed-class, full-suite, and live campaign checks passed, but one non-lockstep run does not establish an FPS uplift

## Why this was worth checking

The exact commodity event-mod memo already avoids the expensive unchanged recomputation. A prior
controlled campaign cycle recorded 55,933,399 unchanged hits, but an ordinary production wrapper
still invoked `CommodityEventModMemoRuntime.hit()` on every hit and `delegated()` on every miss.
Those methods stopped writing counters when campaign timing was disabled, yet each call still read
the volatile telemetry gate.

This change moves that launch-stable choice to transformation time. A profiling launch retains the
two counter calls. An ordinary launch emits neither call. The per-call runtime enable gate, exact
input/output validation, retained vanilla method, and `LinkageError` fail-open path are unchanged.

The persistent transformation cache cannot mix these wrapper shapes. Its context hashes the agent
implementation identity and every effective `preflight.*` property, including the campaign-timing
property that selects telemetry. Frame-time diagnostic runs disable that cache in any case.

## Verification

The structural test proves both emitted shapes:

- profiling wrapper: one `enabled`, one `hit`, one `delegated`, and one fail-open call;
- ordinary wrapper: one `enabled`, zero `hit`, zero `delegated`, and one fail-open call;
- both wrappers retain the original method twice and keep all 14 memo fields private and transient.

The opt-in installed-class integration test passed against the exact local
`starfarer_obf.jar`/`starfarer.api.jar` pair. It executed the reviewed real classes through first
capture, unchanged hits, input mutations, output mutations, and the missing-accessor fail-open
case. Java 17 `./mvnw verify` also passed: 365 core tests, the complete agent suite, 1,113 CLI unit
tests with three skipped, 54 CLI integration tests with three skipped, and 22 synthetic tests with
one skipped.

## Live result

One Preflight-only `campaign-sample-paused-unpaused.json` run completed every semantic step in one
owned Starsector process. The selected save remained paused for the initial observation, completed
the paused warmup and 45-second settled window, used the mapped pause control once, completed the
transition buffer and 45-second unpaused window, then stopped the exact process.

Runtime telemetry recorded 60 exact applied transformations, zero contained failures, and an
enabled event-mod memo with production telemetry disabled. `hits`, `delegated`, and
`fastValidationUnavailable` all remained zero, as expected for the ordinary emitted shape.

The frame result is context, not a before/after claim:

- paused settled: 3,563 frames, 58.00 average FPS, 29.50 one-percent low, 33.9 ms p99, and
  2.75 ms/s stutter burden;
- unpaused settled: 1,651 frames, 49.64 average FPS, 13.66 one-percent low, 73.2 ms p99, and
  77.93 ms/s stutter burden.

Sampling also bounds the value of this edit. The ordinary event-mod wrapper was absent from all
242 paused campaign samples, but remained in 45 of 618 unpaused campaign samples; 42 samples
(6.80%) landed directly in the wrapper. The preceding same-route run had 41 of 676 wrapper-leaf
samples (6.07%). That non-lockstep difference is noise-compatible and does not show a reduced
sample share. The remaining exact validation body, not the removed no-op calls, is still the
meaningful event-mod cost.

## Claim boundary

Ordinary production bytecode no longer performs telemetry calls that cannot record anything. The
change preserves exact gating, transient-only memo state, vanilla fallback, and save compatibility.
This evidence does **not** claim an FPS uplift; it narrows the next investigation to the wrapper's
validation work and the broader `Market.advance`/economy cluster.
