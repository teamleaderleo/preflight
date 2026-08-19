# Generation-bound source integrity without restoring launch-path SHA

**Date:** 2026-08-19  
**PR:** #799  
**Scope:** #753 adapter source archives and #766 prepared texture source verification  
**Status:** implementation/proof branch; correctness gates running; representative licensed-profile performance run still required before review-ready status

## Decision

Both reported same-size/restored-metadata findings are valid. The current #753 and #766 fixes pay for byte authority by re-reading source contents repeatedly on launch paths that Preflight exists to shorten. PR #799 keeps exact byte verification while moving reuse onto bounded source generations.

Two invariants remain simultaneous:

1. changed bytes cannot inherit authority from size/mtime alone;
2. the game loading path cannot recover the known multi-second redundant SHA workload.

## #753: archive hashes are exact once per defining-loader generation

`SourceArchiveHashes` now treats the persistent journal as advisory for every new JVM/classloader generation. The first authorization for one defining `ClassLoader` object plus canonical archive/file generation hashes exact archive bytes. Later authorizations from the same generation reuse the exact digest already established from bytes. A new loader/session/generation hashes again.

The session cache is keyed by object identity of the defining loader, canonical real path, regular-file identity, byte size, and nanosecond mtime. The filesystem fields select an already-established digest inside that one live generation; they never authorize persisted content on their own. Concurrent first encounters share one `CompletableFuture<Result>`, so they do not duplicate the same hash.

Telemetry now records:

- `calls`;
- `sessionHits`;
- `distinctGenerations`;
- `journalHits`;
- `hashes`;
- `hashedBytes`;
- `hashMillis`.

The intended representative launch result is `hashes == distinct successful archive generations`, with authorization calls allowed to be larger.

### Active-launch boundary

A defining classloader owns one live code generation. A same-user process that deliberately mutates the exact same archive in place and restores every observed file identity field while that loader remains live requires restart/new generation before archive authority is established again. The transformer still hashes each candidate class's supplied bytes independently before applying a target.

Tests cover persistent-journal restart, same-loader reuse, new-loader same-size/restored-mtime mutation, concurrent/repeated reuse, and the explicit live-generation boundary.

## #766: exact prepare-time content plus cheap prelaunch generation proof

The texture builder already binds each prepared blob to the exact encoded snapshot it decoded: `BulkTexturePreprocessor.prepareSnapshot(...)` hashes those encoded bytes and requires the result to equal the expected source SHA-256 before conversion.

PR #799 adds a second seal after manifest publication:

1. resolve the complete prepared source set from the matching resource index;
2. capture a platform generation token for every unique source;
3. re-hash current source contents in the native engine JVM and require every digest to equal the manifest source SHA-256;
4. require the generation token to remain unchanged across that verification pass;
5. persist a checksummed `SPTG` proof bound to exact manifest SHA-256, profile fingerprint, provider, logical source set, and opaque source-generation tokens.

The reviewed provider implementations are:

- macOS: Foundation `NSURLGenerationIdentifierKey`;
- Windows NTFS: volume/file identity plus latest file USN via `FSCTL_READ_FILE_USN_DATA`;
- Linux: device/inode/kernel change time on a reviewed local-filesystem allowlist.

Automatic launch rebuilds the current resource index and validates the sealed generation tokens before supplying the prepared manifest to the agent. The clean authorization path reads zero source contents. Missing/unsupported/stale proof disables prepared textures for that launch and leaves Starsector's original decoder active.

Unsealed/manual texture contexts have no generation proof. They perform exact source SHA-256 on each prepared lookup after cheap metadata rejection checks. This keeps the expensive behavior available as an explicit diagnostic lane without using mutable metadata as content authority.

### Same-size/restored-mtime regression

The hosted generation-provider test writes one source, seals it, mutates the bytes to a different value of the same length, restores the original mtime, and requires launch-generation validation to fail.

The counterexample has passed on hosted Ubuntu, macOS, and Windows with the provider implementations above. The Windows helper protocol also keeps stdout/stderr separately bounded and parses only explicit machine records from stdout.

### Active-launch boundary

After a successful prelaunch generation validation, deliberate same-user asset mutation during the live game process requires restart/reprepare. Covering that actor inside the live-launch window would require a race-free ongoing generation/watch handoff. Per-lookup content hashing is excluded from the product path because it restores measured multi-second work.

## Retained performance contract

The August 1 Rosetta evidence remains directly comparable to the design question:

- shipped Starsector x86_64 JVM SHA-256: **292 MB/s**;
- native arm64 JDK 21 on the same machine/bytes: **3,314 MB/s**;
- representative texture source corpus: **1,344,517,311 bytes**;
- representative source lookups: **21,653**;
- source SHA occupied **1,076 / 2,631** loading-thread on-CPU samples in the recorded profile;
- removing the per-lookup source hash was the only relevant change in the controlled `fast` condition and improved its median by **6.68 seconds**.

Those retained measurements are why #766 cannot restore full source hashing in `lookup()` without extraordinary new evidence.

## Required representative measurement before review-ready status

The branch changes adapter/cache behavior, so #450 calls for a new comparable launch cohort.

For #753 record:

- authorization calls;
- distinct archive generations;
- exact hashes;
- hashed bytes;
- hash CPU time;
- startup wall time.

For #766 record:

- prepared source entries checked;
- unique source bytes covered;
- generation-provider validation wall time;
- source-content bytes hashed inside the game JVM on the clean automatic path (**target: zero**);
- startup wall time.

Keep JFR sample composition, CPU, I/O, allocation, and wall time as separate quantities under #295/#450. The retained large-profile cohort remains a comparison baseline only where performance-relevant inputs remain comparable.
