# Windows old/current engine comparison

This small alternating set does **not show a consistent startup regression** in the
current engine. Median process-start-to-interactive-menu time was 21.822 seconds for
the older engine and 22.013 seconds for the current engine: +0.191 seconds (about 0.9%).
The current engine had both the lower minimum and lower maximum. Three pairs do not
establish statistical equivalence or rule out smaller regressions.

## Fixed conditions

The existing Windows cohort runner measured `processStartedAt → mainMenuInteractiveAt`.
Both engines use the same Windows v2 interactive-menu boundary. All runs used forced
`llvmpipe`, Recommended, sound on, windowed 1024×720, the same prepared cache and all
83 enabled mods. Big Red's Windows VM had 14 guest processors and 20 GiB RAM, with
host performance and guest high-performance power settings. No FastRendering was used.

One old/current warm-up pair was excluded in advance, followed by three measured
old/current pairs. A small host driver reused the existing one-run harness, with
20 seconds between completed cohorts. It stopped on a failed cohort and performed
guest shutdown and GPU handback automatically. This was a bounded diagnostic experiment,
not a randomized release campaign or native GUI acceptance check.

Engine identities:

- Old source `1cc0c242116329997460cbd488b5e7fede19a8b0`; JAR SHA-256
  `6abf2f7f7f1be2b5a16d1840c48296a6326e2f1b34f36fe501f467c1938960ea`.
  The normal rebuild matched the historical 16.424-second engine byte-for-byte.
- Current engine source `b4536217fbd5ec3592d1d62eff44512c607df7db`; rebuilt from
  documentation-only successor `0eb1479c`, with the same JAR SHA-256 as the earlier
  current-engine set: `9193f8aca5cb44a26a7296bfd96337ca170938d1f2be5ff84b2c1594934ab450`.
- Shared PowerShell runner SHA-256:
  `59637eb23a4250b183404d64a50d3363bc5da557a7c84dfc7e70bf56e3da53d4`.
- Enabled-mods SHA-256:
  `76227ce91333c202271e541774f3e86fd8711c2542d63a81cfd18a4dc0a6997f`.

All recorded cohort identity fields matched across all eight runs except start time,
engine path and engine hash. This includes launcher/JVM hashes, display geometry,
direct launch options, cache preparation status and optimization flags.

## Retained results

All cohort names below have prefix `20260907-` and suffix `-windows-startup-2x2`.

| Stage | Old cohort | Old seconds | Current cohort | Current seconds |
| --- | --- | --- | --- | --- |
| Excluded warm-up | `125018` | 21.435 | `125135` | 21.153 |
| Pair 1 | `125241` | 21.822 | `125359` | 22.013 |
| Pair 2 | `125517` | 21.556 | `125626` | 18.453 |
| Pair 3 | `125746` | 28.828 | `125909` | 22.579 |

| Engine | Fastest | Slowest | Median |
| --- | --- | --- | --- |
| Old | 21.556 s | 28.828 s | 21.822 s |
| Current | 18.453 s | 22.579 s | 22.013 s |

All eight runs passed adapter health, served 2,049 prepared sounds with zero audio
failures, and exited gracefully. The slow old-engine observation remains included.
Neither engine reproduced 16.424 seconds. These results argue against attributing
today's higher times solely to recent code changes; they do not identify the cause
of the difference from the historical session. No new stock baseline or speedup
claim is established.

## Evidence and cleanup

Raw cohort ZIPs and host fingerprints remain in
`/home/leo/Windows-Share/Diagnostics/`. Exact engine inputs are retained in its
`windows-engine-comparison-20260907/` subdirectory. Driver state, per-cohort logs,
identity and detailed results remain under
`/home/leo/Projects/preflight/benchmark-results/windows-engine-comparison-20260907/`;
the identity and detailed results also have a local Mac copy in the same relative
benchmark directory. These private artifacts are not game assets in Git.

The test task was removed, no game processes remained, and the original scheduled
task was untouched. The comparison service finished inactive. Independent checks
confirmed the VM shut off, the GPU on i915, and GDM active. Rebuildable Maven outputs
and the temporary historical-source worktree were retired. No boot configuration
or saved game settings were changed.
