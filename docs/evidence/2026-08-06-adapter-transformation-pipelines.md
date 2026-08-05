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

## Follow-up: persistent exact transformed classes

No remaining repeated transformation pipeline was individually large, so the next follow-up moved
from optimizing individual ASM passes to eliminating repeat-launch transformation work.

The adapter now persists successful exact outputs in one checksummed, atomically replaced bytecode
pack. A lookup still happens only after the live class has passed its original class SHA, required
method, source archive SHA/suffix/kind, and classloader gates. The pack context additionally binds:

- the exact runnable agent, core, and ASM implementation archive content;
- the ordered effective target registry, including every plan and required method;
- the effective texture, audio, JSON, rule, resource, GraphicsLib, campaign, and diagnostic feature
  configuration; and
- every `preflight.*` runtime property.

Any missing, malformed, mismatched, or unwritable pack is an ordinary miss: the reviewed
transformation runs and vanilla remains the outer fallback. Frame-time diagnostic transformations
are deliberately not persistent because their per-probe registration is part of the measurement.
Normal startup transformations replay their idempotent installation effects from the exact selected
target and cached bytecode, so runtime gates and telemetry are the same as on a cold transformation.

The final cold population gate reached the menu in **18.74s**, applied all 40 transformations, and
spent **1,126.123ms** inside them. It wrote 40 classes. The immediately following exact-context
launch restored **40/40** classes (**803,490 bytes**), reported zero transformation nanoseconds,
zero misses, declines, read/write failures, or contained failures, and reached the menu in
**17.80s**. Every installation-related telemetry field was identical between the cold and warm
reports. Both launches used the default balanced texture policy and stopped through the normal
shutdown path.

Runs:

- `transformed-cache-final-cold-20260806-064558`
- `transformed-cache-final-warm-20260806-064638`

The cold/warm adapter timers are the causal result: **1.126s -> 0ms**. The 0.94s whole-launch
difference is supporting evidence because startup still contains unrelated scheduling and thermal
noise. Full `mvn verify` is green.
