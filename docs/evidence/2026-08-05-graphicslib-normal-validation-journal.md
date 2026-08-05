# GraphicsLib normal validation is memoized across unchanged launches

Date: 2026-08-05

Profile: 83 enabled mods, GraphicsLib 1.12.1, macOS on Apple M5, bundled x86-64
Zulu 17 under Rosetta, `--fast`

## Result

The lazy generated-normal path no longer rereads and CRC-checks the same 215.6 MB of PNGs on
every warm launch. An advisory persistent journal reduced its measured validation cost from
1,196ms to 197ms while retaining 6,184/6,184 lazy hits and zero fallback. GraphicsLib's complete
application-load callback fell from 2.17s to 1.02s; the two adjacent unattended launches reached
the main-menu marker in 27.79s and 26.63s respectively.

Retained runs:

- cold population: `~/.starsector-preflight/runs/gfx-normal-journal-cold-20260805-141442`
- warm reuse: `~/.starsector-preflight/runs/gfx-normal-journal-warm-20260805-141542`

## Safety contract

The journal is an optimization of successful validation, never a source of texture data. Each
entry records the exact GraphicsLib cache root, direct-child filename, byte size, nanosecond file
modification time, and filesystem file identity. A hit requires every value to match a current
non-symlink regular file. Any mismatch runs the prior complete PNG signature, header, chunk,
IDAT/IEND, length, and CRC validation before replacing the entry.

Missing, truncated, malformed, wrong-root, overlarge, duplicate, or unwritable journal state is
ignored. The affected launch performs complete validation and the original GraphicsLib load and
regeneration path remains the fallback for any invalid PNG. Journal publication uses a temporary
file and atomic replacement where the filesystem supports it. A killed process can therefore lose
new memo entries, but cannot turn an unvalidated file into a cache hit.

The journal lives in Preflight's existing cache directory and is written by a JVM shutdown hook.
The unattended probe sends `SIGTERM`, allowing that hook to publish the cold launch's 6,184
validated entries. Unit coverage proves warm reuse, changed-file revalidation, corrupt-PNG
fallback, path containment, and malformed-journal fallback.

## Follow-up: one metadata probe per warm hit

The first journal implementation resolved each direct-child cache path with
`Files.isRegularFile()` and then immediately captured `BasicFileAttributes` for the actual journal
comparison. Both operations query the same filesystem metadata. The authoritative attribute
capture already rejects missing files, directories, and symlinks before comparing size,
nanosecond mtime, and file identity, so the preliminary query contributed no safety.

The runtime now validates the exact `cache/<direct-child>_normal.png` syntax without touching the
filesystem, then performs the one authoritative attribute capture. Telemetry counts those probes.
An unattended warm launch retained 6,184/6,184 hits, zero fallback, zero validated bytes, and
exactly 6,184 metadata probes. Validation fell from the retained 197ms warm result to 131ms (33.5%
less), and the main-menu marker arrived at 25.40s. Full `mvn verify` passed.

Retained run:

- `~/.starsector-preflight/benchmarks/20260805-155203/runs/fast-1`

The harness deliberately terminated the game after detecting the menu, so `run.json` records the
expected launcher exit 143. Its lifecycle scan found no fatal evidence and the benchmark accepted
the run. This is evidence for the validation seam, not a 66ms end-to-end startup claim; whole-launch
noise is larger.

## Exact measurements

Cold population:

- calls/hits/fallbacks: 6,184 / 6,184 / 0
- journal hits/misses: 0 / 6,184
- bytes fully validated: 215,643,372
- validation time: 1,196ms
- `autoGenMissingNormalMaps`: 1.86s
- GraphicsLib callback: 2.17s

Warm reuse:

- calls/hits/fallbacks: 6,184 / 6,184 / 0
- journal hits/misses: 6,184 / 0
- bytes fully validated: 0
- validation time: 197ms
- journal load/write failures: 0 / 0
- `autoGenMissingNormalMaps`: 0.71s
- GraphicsLib callback: 1.02s

Both runs reported an active adapter, reached the main menu, and left no Starsector process
running. The warm run completed with wrapper exit 0. The cold population run had already written
its complete telemetry and reached the menu before `SIGTERM`; Starsector then emitted its known
shutdown-only OpenAL/display cleanup errors, so the wrapper conservatively classified that run as
`FATAL_LOG_EVIDENCE`. Those post-menu cleanup lines do not affect the measured validation seam or
the journal publication, and the adjacent clean warm run supplies the end-to-end acceptance.
