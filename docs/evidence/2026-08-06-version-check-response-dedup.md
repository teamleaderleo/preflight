# Process-local version-check response deduplication

## Finding

The enabled LunaLib 2.0.5 and Nexerelin 0.12.2b archives each contain a fork of the same version
checker. On the reviewed 83-mod profile both discover the same 74 version files and start separate
worker pools over largely identical HTTP URLs. In the preceding raw startup profile, LunaLib's
`getRemoteVersionFile` accounted for 114 samples and Nexerelin's for 52. This work is asynchronous
and is not a defensible direct wall-time claim, but it duplicates network, parsing, CPU, and thermal
load while the main thread is still loading.

## Boundary

Two exact, source-bound adapters replace only the two reviewed `URL.openStream()` calls in each
checker: the per-mod response and the Starsector-version response. The targets pin the complete
class and source-JAR hashes, Java 17 class version, loader kind, method descriptors, and the exact
count of original calls. Any update or shape drift retains the original code.

Successful HTTP(S) responses are retained as bytes for this game process only. Every caller gets a
fresh `ByteArrayInputStream`, so scanner ownership and stream position remain independent. There is
no disk cache, TTL, or reuse across launches; the next launch still performs fresh checks. A failed
owner fetch is removed before its exception is exposed, and a waiter retries independently rather
than treating the other checker's failure as authoritative. Non-HTTP URLs bypass the deduplicator.

This is deliberately mod-specific. If either exact mod version is absent or changes, only that
target is skipped. Vanilla, other mods, and the rest of the platform-independent `--fast` stack are
unchanged.

## Verification

Unit tests execute successful reuse with independent streams, failure and retry behavior,
non-HTTP bypass, both bytecode rewrites, and fail-closed shape/hash checks. The opt-in installed-JAR
test transformed both real archives and found exactly two reviewed reads in each. Full
`mvn verify` passed across all modules.

The unattended profiled live gate is retained at:

`~/.starsector-preflight/benchmarks/20260806-005705`

It reached the main menu in 24.45s under sampling and shut down normally. The adapter report was
ACTIVE with 35 transformations applied, zero declines, zero unavailable plans, and zero contained
failures. Before shutdown interrupted the still-running background checks, telemetry recorded:

| metric | value |
| --- | ---: |
| exact targets installed | 2 |
| network fetches | 41 |
| reused responses | 31 |
| failed fetches | 0 |
| retry fetches | 0 |
| cached URLs | 41 |
| process-local bytes | 16,010 |

The 31 reuses are directly observed, not extrapolated to all 74 mod entries. Because this was a
single sampled launch and the work is asynchronous, retain the optimization as a CPU/network/heat
reduction with a live compatibility gate, not as a quantified startup wall-time improvement.
