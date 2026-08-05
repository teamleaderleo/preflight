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

## Next target

MagicLib's `MagicPaintjobManager` transformation remains the largest individual adapter cost at
roughly 0.2--0.3s. Its reviewed changes are stack-neutral, but merely disabling frame recomputation
did not reduce the live timer consistently. A streaming two-pass visitor or a source-and-agent-bound
persistent transformed-class cache should be measured next; do not infer a win from writer flags.
