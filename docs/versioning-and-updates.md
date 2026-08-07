# Versioning and updates

Preflight has three independent identities:

1. **Application version** — the desktop host, CLI, Java agent, adapter catalog, and bundled runtime
   shipped together as one SemVer release.
2. **Compatibility identity** — the exact game, mod archive, class, loader, and method fingerprints
   accepted by that release. One application release can contain many accepted identities for the
   same plan.
3. **Profile identity** — the selected installation, ordered mods, source content, preparation
   policy, and cache-format inputs. Any number of profiles can coexist under one application
   release, and matching content reuses the same artifacts.

A new game or mod version doesn't imply a new profile-only fix. Existing generic caches continue
to work wherever their input contracts still match. Exact bytecode adapters decline unfamiliar
targets and the original code runs. Supporting a changed target requires a signed Preflight update
when its reviewed fingerprint or transformation logic changes.

## Application updates

The desktop app checks one fixed HTTPS feed after setup and whenever the user asks. A release is
offered only when Tauri considers its SemVer newer than the installed application. Installation
requires **Install and restart**; Preflight doesn't update during a game, preparation, or another
update. An available release appears on Home and beside Settings, while its notes, verification
boundary, and install action stay together on the Settings page.

Before downloading, the app rechecks the feed and requires the exact version, target, URL,
signature, notes, and date the user approved. A withdrawn or replaced offer returns to the update
check instead of installing stale state. The downloaded platform artifact must pass the updater
key signature embedded in the installed app. Download, signature, or installation failure leaves
the current version runnable.

The built-in updater covers macOS, Windows, and Linux AppImage packages. Debian packages stay with
the package manager that installed them. Old release packages and their checksums remain the
rollback path; Preflight doesn't maintain several installed application versions side by side.
The first beta downloads a complete signed application artifact rather than a binary delta. Caches,
profiles, evidence, and game files aren't part of that download and remain in place across updates.

The first beta should have one stable feed. A beta channel can be added later as a separate signed
endpoint and an explicit user choice. Stable clients must never receive beta builds through version
ordering alone.

Updater-key rotation needs a transition release signed by the old key and embedding the new public
key. If the private key is suspected compromised, suspend the feed; OTA can't establish a new root
of trust safely from a key an attacker may hold. Recovery then uses a manually downloaded package
and its independently published checksum.

## Compatibility across releases

Application updates preserve the Preflight home and rediscover its profiles and content-addressed
artifacts. Cache files carry format versions and readers reject unsupported data. An incompatible
writer must also change the artifact identity or namespace so the new representation can coexist
with data needed by an older release. Migration stays copy-on-write; an update doesn't rewrite the
game, mods, saves, or every existing cache in place.

The public-beta cache layout is now the established namespace for texture blobs and packs,
resource indexes, manifests, prepared audio, spec stores, classpath indexes, and generated
bytecode. Their current paths remain unchanged. A later binary-format version automatically moves
that store into a suffixed namespace, while coupled formats such as classpath profiles and archive
indexes move together. The previous directory remains available to an older Preflight package, so
rolling back doesn't first destroy the preparation needed by that release.

Cache pruning operates on the active format namespaces. Older namespaces are retained for rollback
until the user explicitly clears broader cached data; an application update doesn't silently treat
an unfamiliar old representation as disposable space. The update review warns that a format change
can temporarily retain both copies before installation starts. The storage view includes bytes
outside the active categories as **Other Preflight data**, so retained formats remain part of the
visible total even when the current release can't classify their contents more narrowly.

Named mod profiles are user data rather than application state. A future profile schema needs an
explicit reader or migration and must preserve the previous file until the replacement is accepted.

## When an application update is required

- a game or mod update changes an exact adapter target;
- discovery, launcher, JVM, graphics, audio, or preference behavior changes;
- an adapter implementation or dependency graph changes;
- a cache representation changes incompatibly;
- the desktop, updater, report intake, security boundary, or bundled runtime changes; or
- a known-bad compatibility identity must be removed.

Adding remote transformation rules would make a compatibility download equivalent to executable
code and create a second update system. The first beta keeps transformations inside signed
application releases. A later signed advisory feed may disable a known-bad plan or fingerprint
without enabling new code; it should contain only narrowing decisions and fail open when offline.
