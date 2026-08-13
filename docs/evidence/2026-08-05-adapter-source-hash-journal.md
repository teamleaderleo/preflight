# Adapter source archives are no longer rehashed on every warm launch

Date: 2026-08-05

Profile: 83 enabled mods, macOS on Apple M5, bundled x86-64 Zulu 17 under Rosetta, `--fast`

## Result

Preflight's exact adapters bind not only the target class bytes, source kind/path, and classloader,
but also the complete source JAR hash. The game JVM previously recomputed those archive hashes on
every launch. On this profile that meant 11 distinct archives and 16,926,890 bytes hashed under
Rosetta on the startup critical path.

An advisory source-hash journal now reuses a successful SHA-256 only when the current source is the
same real-path, non-symlink regular file with the same filesystem identity, byte size, and
nanosecond modification time. The population launch measured 98ms of complete content hashing. The
adjacent warm launch served all 11 identities from the journal and hashed zero bytes.

Both launches retained all 33 exact transformations with zero declined transformation or contained
failure. Their main-menu markers were 24.96s and 25.07s, a 0.11s range. That wall-clock pair is a
useful stability check, not an end-to-end speed claim; the exact claim is the measured removal of
98ms of archive hashing.

Retained session:

- `~/.starsector-preflight/benchmarks/20260805-155938`
- population: `runs/fast-1`
- warm reuse: `runs/fast-2`

## Safety and failure behavior

The journal contains answers to hashing requests, never transformed code. A hit still has to pass
the target class's own SHA-256, required methods, source kind, source suffix, and classloader
identity. If the archive's path identity, size, or nanosecond mtime differs, Preflight performs the
complete source SHA-256 before the target can match. It captures attributes again after hashing and
rejects a file that changed during the read.

The journal has a magic/version header, bounded entry count, unique absolute paths, and validated
64-character lowercase hashes. Missing, malformed, truncated, duplicate, or trailing state is
discarded and complete hashing resumes. Writes use a temporary file and atomic replacement where
supported; an unwritable journal only loses the optimization. Unit coverage proves warm reuse,
changed-archive rehashing, and malformed-journal fallback. Full `mvn verify` passes.

The file is `adapter-source-hashes-v1.bin` in Preflight's existing cache directory. As with the
GraphicsLib PNG-validation journal, this is a local correctness cache rather than a defense against
an attacker who can rewrite both installation files and Preflight's cache while forging filesystem
metadata. Ordinary mod/game updates change the recorded metadata and rehash automatically.

The unattended benchmark deliberately sends `SIGTERM` after the game's own main-menu marker so JVM
shutdown hooks publish journals and reports. Accordingly each accepted run records launcher exit
143; both lifecycle scans found no fatal evidence.
