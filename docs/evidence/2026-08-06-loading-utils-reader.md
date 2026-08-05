# LoadingUtils shared reader no longer allocates 1MiB per input

## Result

Starsector's shared `LoadingUtils.super(InputStream)` reader allocated a fresh 1MiB buffer for every
input, decoded each chunk into a synchronized `StringBuffer`, copied the final string, then compiled
and ran a regex to remove carriage returns. This path also reads every `mod_info.json` before normal
resource initialization.

The exact, source-bound rewrite now uses `InputStream.readAllBytes`, compacts carriage-return bytes
in place, decodes UTF-8 once, and preserves unconditional stream closure and `IOException`
propagation. Changed game class/archive/loader identities decline the entire transform; all other
LoadingUtils caches and probes still compose on the same reviewed class.

On Starsector's bundled x86-64 Zulu 17 JVM under Rosetta, six alternating passes over all 17,666
installed JSON/CSV/spec files (47,529,806 bytes) produced identical text for every file and measured:

- vanilla median: **761.978ms**;
- optimized median: **276.073ms**;
- reduction: **485.905ms (-63.8%, 2.76x)**.

The retained benchmark is
`docs/evidence/2026-08-06-loading-utils-reader-benchmark.java`.

## Live gate

The clean unattended gate
`~/.starsector-preflight/runs/loading-utils-reader-clean-restored-profile-20260806-045457`
reached the main menu in **18.88s** and stopped normally. The optimized reader handled 1,300 calls
and 3,124,440 bytes in 150ms, removing 36,075 carriage returns. All 40 reviewed transformations
applied with zero decline, unavailable plan, or contained failure. The established texture profile
remained exact, with 15,469 prepared-texture hits, three known misses, 6,184 generated-normal
journal hits, and zero fallback.

The adjacent historical record is 18.80s, so the one live run does not establish a wall-time shift.
The exact corpus replay establishes the CPU/allocation reduction; removing roughly 1.3GB of
per-call scratch allocation from this launch also improves GC and thermal headroom on constrained
machines.
