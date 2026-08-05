# Adapter transformation pipeline collapse

Date: 2026-08-06

## Question

Once texture reconstruction fell below two seconds, did Preflight's own bytecode work become a
meaningful part of startup, and could the independent rewrites sharing one class avoid repeatedly
parsing and serializing that class?

## Measurement

The adapter report now records elapsed nanoseconds and input/output sizes for every successful exact
transformation. This measures only `AdapterTransformationRegistry.transform`, on the game's x86-64
JVM under Rosetta, and does not infer transformation cost from log gaps.

The first instrumented learned-order `fastest` launch spent **1,348.566ms** in 40 transformations.
The largest entries were:

- `SpecStore`: 274.520ms
- MagicLib `MagicPaintjobManager`: 228.754ms
- `WeaponSpecLoader`: 103.313ms
- `ResourceLoaderState`: 53.015ms
- `CombatEngine`: 50.510ms

Run: `transform-timing-fastest-20260806-060404` (19.85s to main menu).

## Change

The four LoadingUtils rewrites, six SpecStore rewrites, and five WeaponSpecLoader rewrites now retain
their standalone exact-shape transforms but also expose in-memory mutations. The registry parses one
`ClassNode`, applies every eligible mutation, computes frames once where a cache adds control flow,
and writes one class. Any exception discards the partial tree and retains the original; the previous
independent pipeline remains as the fallback for SpecStore and WeaponSpecLoader.

An exact installed LoadingUtils microbenchmark on the bundled JVM measured the old sequence at
1.927ms and the composed sequence at 0.603ms (-68.7%); their output bytes were identical.

## Live result

Two post-change unattended learned-order `fastest` launches reached the main menu in **18.42s** and
**18.67s**, both cleanly stopping with all 40 transformations applied and zero decline or contained
failure. The first is a new record, 0.29s below the prior 18.71s record.

The new per-class timings were:

- `SpecStore`: 78.343ms and 86.942ms (versus 274.520ms)
- `WeaponSpecLoader`: 84.659ms and 79.258ms (versus 103.313ms, with an intervening cold sample at
  200.260ms before its pipeline was collapsed)
- LoadingUtils: 18.861ms in the second run

Runs:

- `collapsed-spec-weapon-fastest-20260806-060953`
- `collapsed-transforms-confirmation-20260806-061151`

The whole-launch samples support the absence of regression and establish a new observed record. The
per-transformation timers are the causal boundary for the pipeline change.

## Rejected adjacent ideas

- Preserving compressed frames instead of expanding them across every plan changed ASM stack samples
  only from 6.47% to 6.30% and produced a 19.64s profiled launch versus 20.00s before. The 68-file
  experiment was deleted.
- A manifest-assisted prepared-texture reader was 692.452ms versus 690.574ms for the existing reader
  over ten shuffled exact-order passes and was deleted.
- A segmented memory-mapped texture payload copy measured 790.965ms versus 471.994ms for positional
  `FileChannel` heap copies and was deleted.

## Follow-up: remaining shared pipelines

The remaining large independent pipelines were then collapsed without weakening their existing
shape checks or runtime fallbacks:

- MagicLib's `MagicPaintjobManager` now uses a two-pass streaming visitor. The first pass proves the
  exact field and call counts without mutation; the second directly copies untouched methods and
  rewrites only the three reviewed methods. Stack maps and debug data remain the originals. The
  executable same-size replacement/add fixture and fail-closed cases still pass.
- `ShipHullSpecLoader` now applies its phase attribution, prepared hull JSON cache, and concise log
  rewrite to one tree and computes frames once.
- `ResourceLoaderState` now applies its phase/startup marker and priority index to one tree and
  serializes once.
- The resource resolver now applies the always-on thread-local source-hint correctness repair and
  optional resource probe to one tree. Any composition exception retries the correctness repair on
  the original bytes, so the optional optimization cannot suppress it.

The causal per-class timers moved as follows across clean live samples:

- MagicLib paintjob manager: the pre-change 201--316ms range became 187--227ms.
- `ShipHullSpecLoader`: 39--45ms became 31--42ms.
- `ResourceLoaderState`: 54--57ms became 34--41ms.
- resource resolver: 51.839ms before its collapse and 36.797ms after.

The best total time inside all 40 transformations was **1,068.072ms**, versus the first
instrumented **1,348.566ms**. Per-plan JIT and class-loading order remain noisy, so the individual
timers—not differences between whole launches—are the causal evidence.

Four unattended learned-order `fastest` gates during the follow-up measured 19.24s, 18.75s,
**18.01s**, 18.36s, and the final all-collapsed confirmation measured **18.04s**. The last two
near-record runs establish a repeatable near-sub-18 floor. Every final run stopped the game, applied
all 40 exact transformations, and reported zero decline and zero contained failure.

Runs:

- `magic-streaming-fastest-20260806-0620-20260806-062032`
- `magic-streaming-confirmation-20260806-0622-20260806-062114`
- `hull-collapsed-fastest-20260806-0623-20260806-062310`
- `resource-collapsed-fastest-20260806-0625-20260806-062453`
- `all-collapsed-fastest-20260806-0627-20260806-062645`

## Next target

No remaining repeated transformation pipeline is individually large. MagicLib is still the largest
adapter timer because the class itself is large; a persistent transformed-class cache could remove
most repeat-launch adapter CPU, but its key must bind source bytes, agent implementation, selected
plan, and runtime readiness. Do not weaken those identities merely to cross 18 seconds.
