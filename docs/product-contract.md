# Product, compatibility, and support-upload contract

**Status:** executable product boundary for the desktop beta
**Updated:** 2026-08-20

Public distribution hasn't started. The product is named **Preflight**. The maintainer decision
recorded on 2026-08-20 in [#950](https://github.com/teamleaderleo/preflight/issues/950) treats the
2026-08-07 Fractal Softworks request as courtesy correspondence; a reply is outside the publication
gate. Preflight remains an independent, unofficial project with the existing descriptive-use
attribution and disclaimer. This contract describes product behavior and makes no endorsement claim.

## A shared engine, several entry points

The CLI and desktop application call the same Java engine. The desktop host has a fixed set of
typed commands; it isn't an arbitrary shell. Normal acceleration starts Starsector through
`preflight run --optimization-preset recommended` (with `--fast` retained as its compatibility
alias), which wraps the selected existing launcher and adds the in-memory Java agent. The game
installation, launcher script, mod archives, and saves aren't rewritten.

Preflight can't accelerate a process it didn't start. Users can launch through:

- the desktop application's **Launch Starsector** button;
- `preflight run --fast` directly;
- an installed Preflight application/command integration; or
- `preflight run --fast --launcher <their launcher>` when a rendering mod or compatibility package
  supplies a different script.

A raw vanilla launcher, a third-party `.bat`, or a compatibility app launched on its own remains
raw. Supporting those paths means teaching Preflight to wrap their command, not installing a
machine-wide `JAVA_TOOL_OPTIONS` hook or editing their files behind the user's back.

## What is modified

There are three distinct kinds of change:

1. **Runtime transformations.** Exact reviewed classes are transformed in the child JVM's memory.
   Nothing is written back to a game or mod JAR. This is technically deep integration, but the
   boundary is narrow: class bytes, source archive, classloader, protection domain, required method
   descriptors, and cache identity are checked before a plan can install.
2. **Content-addressed acceleration data.** Prepared textures, audio, merged JSON, generated
   bytecode, resource indexes, and reports live below Preflight's own home. Each format checks its
   own source identity and prepared representation before use. A miss, corrupt entry, uncertain
   source, or runtime validation failure takes the original game path. The current texture
   source-identity limit is documented in the [cache and save-safety
   audit](evidence/2026-08-25-cache-and-save-safety-audit.md).
3. **Explicit preferences.** A confirmed named-profile switch backs up only
   `mods/enabled_mods.json`, stages the complete replacement beside it, rechecks the reviewed source,
   and requests an atomic move; filesystems without atomic-move support use a staged same-directory
   replacement. The launch-settings surface keeps Starsector's settings global and writes only its
   existing `resolution`, `fullscreen`, `sound`, `numAASamples`, `screenScale`, and
   `gameplaySettings` preferences after the player confirms that Starsector, launchers, settings
   editors, and mod managers are closed for the Apply commit. Under the Preflight-only operation
   lease, the engine rereads raw preferences and the selected launcher file, revalidates effective
   coupled values, preserves unrelated values, publishes, and rereads the resulting state. Drift
   causes refusal and review; compensation restores only values still equal to Preflight's
   publication. The lease coordinates Preflight processes only, so external programs remain
   independent. The registration serial is never read into the backup or exposed to the desktop
   interface. An explicit memory change can update the heap flag in one unambiguous launcher-owned
   `vmparams` file inside the selected installation; Preflight saves the exact original file and
   refuses ambiguous layouts.

Each desktop package includes a
[machine-checked capability receipt](capability-receipt.md) for this boundary. It names the exact
engine JAR and source revision, every renderer-to-native command, fixed link and compiled network
endpoint, the allowed write families, and the child-process families. Package verification refuses
an altered receipt or an unreviewed change to the source files that own these capabilities.

## Update and mod drift

Application releases, compatibility fingerprints, and content profiles are separate version axes.
One current Preflight release can support many exact game/mod identities and any number of cached
profiles. The [versioning and update contract](versioning-and-updates.md) records when an application
update is required and how incompatible cache formats must coexist for rollback.

The safe default for unknown code is **decline, report, and continue with original bytes**. Targets
for vanilla and specific mods are pinned to exact identities; a new game/mod build doesn't receive
an old transformation merely because a class has the same name. Cache bridges separately validate
their artifact and input identity before serving a hit.

That provides graceful degradation, not immortality. A future release can still change the launcher
command, preference format, classloading topology, native runtime, or discovery layout enough that
the wrapper itself needs an update. The beta must therefore distinguish:

- **adapter declined:** game continues, optimization unavailable, health report explains why;
- **cache miss/rejection:** original loader runs, cache can be rebuilt or repaired;
- **wrapper/launcher failure:** the game didn't start and Preflight must say so plainly; and
- **runtime integrity failure:** disable the affected runtime shortcut for the session and retain
  evidence, rather than claiming the session is fully accelerated.

Compatibility with Fast Rendering, Starsector Rendering, BoxUtil, GraphicsLib, Nexerelin, or any
other mod is evidence-based. Ownership detection already leaves Janino compilation to Fast
Rendering when its custom loader owns that seam. This doesn't justify a blanket “all versions of
all mods” claim; each mod-specific plan must remain exact and independently disableable.

## Launch settings UX

The desktop **Launch** page and `preflight launch-settings` now expose the game-owned values rather
than duplicating them:

- resolution, fullscreen, and sound from the vanilla launch panel;
- antialiasing and UI scaling from the vanilla options panel; and
- battle size from the same `gameplaySettings` preference as the in-game slider.

Battle size uses the selected installation's merged minimum and the same `battleSize` preference as
the game. `maxBattleSize` only defines the vanilla settings slider, so Preflight accepts custom
values through the positive Java-integer range consumed by the game. It doesn't rewrite base
`settings.json`; opening the vanilla slider can reset a value above that slider's installed maximum.

For the release candidate, these values remain global Starsector settings shared by vanilla and
Preflight launches. Apply has an explicit quiescent boundary: the player confirms Starsector, its
launcher, settings editors, and mod managers are closed until the commit finishes. The engine performs
the strongest available content-and-identity reread immediately before each publication attempt,
verifies the resulting state afterward, preserves unrelated preference values, and asks for review
whenever the reviewed state has changed.

Preflight's cross-process operation lease coordinates Preflight commands. It does not lock Starsector,
launchers, settings editors, mod managers, or other external programs. Per-launch and profile-scoped
setting overrides stay outside the release-candidate lane.

## Cache controls UX

The primary control is a preset, not a wall of bytecode-plan names. The CLI, desktop host, and agent
now carry a typed choice end to end:

- **Recommended (default):** every optimization that passed its live correctness gate; exactly the
  behavior of `--fast`. This includes exact-gated combat paper cuts where the installed game and
  mod bytecode match; an explicit per-plan override still wins.
- **Conservative:** broadly applicable, immutable-input startup caches only; omit mod-specific and
  gameplay-runtime shortcuts.
- **Off / troubleshooting:** no adapter transformations and no profiling recorder overhead. The
  wrapper may still provide process ownership and a bounded outcome report.

An **Advanced** disclosure can group independently switchable domains such as textures, prepared
audio, merged/spec JSON, generated scripts, vanilla gameplay indexes, GraphicsLib, and other
exact-version mod adapters. The engine must own the dependency graph. The GUI may request
“prepared textures off”; it must not assemble an internally inconsistent set of raw agent flags.

Launch settings remain global Starsector preferences for the release candidate. Cache contents
remain shareable by content identity; toggling a reader off doesn't delete their data. Cleanup and
storage policy stay separate, preview-first actions.

## Voluntary support upload

“Send diagnostics” should upload the exact bounded ZIP already produced by `evidence export`; it
must not create a broader telemetry path. The consent screen shows the existing inclusion/exclusion
list, the byte count, and the ZIP SHA-256 before sending. The first beta keeps this manual. Automatic
failed-run reporting remains unavailable until native background ownership, exact-run deduplication,
and durable case-scoped deletion authority satisfy #662, #669, and #679.

The service flow is:

1. Client asks a small HTTPS intake service for a new case and short-lived upload grant, sending only
   product version, ZIP byte count, and SHA-256.
2. Service applies IP/network rate limits and issues a random object key plus a short-lived,
   case-specific PUT grant. The grant is signed by the service, never placed in a URL, and authorizes
   one immutable write through the Worker's private R2 binding.
3. Client uploads the ZIP, then asks the service to finalize the case.
4. Service verifies size, ZIP structure and decompression limits, bounded manifest schema, entry
   allowlist, per-entry hashes, and the outer SHA-256 before marking it accepted. A Worker can
   compute SHA-256 with Web Crypto
   ([Workers documentation](https://developers.cloudflare.com/workers/runtime-apis/web-crypto/)).
5. Service returns a case ID plus a server-signed receipt covering object key, digest, size,
   received time, product version, and retention deadline. The app displays and copies that receipt.

The embedded application has no durable secret. A secret shipped in a desktop binary is extractable,
so it can't prove that an upload came from an untampered official client. The intake therefore
treats every request as anonymous hostile input. The receipt proves what the service accepted;
strict server-side format checks, private storage, short retention, and rate limits constrain abuse.
Cloudflare's Worker-native rate-limiting bindings cap report creation and mutating intake requests
before archive processing. Those bindings are intentionally permissive and local to a Cloudflare
location, so accepted grants also pass through an exact 500 MiB limit for each UTC day, coordinated
by one SQLite Durable Object per day
([Rate Limiting API](https://developers.cloudflare.com/workers/runtime-apis/bindings/rate-limit/)).
If uploader identity becomes necessary, GitHub's device flow can add an explicit sign-in without
asking users to paste tokens
([GitHub documentation](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps#device-flow)); it shouldn't be required for ordinary beta feedback.

Operational defaults should include a small maximum object size, private bucket, short retention,
no public object URLs, least-privilege intake credentials, deletion by case ID, and a visible privacy
statement. Server-side processing must treat ZIPs and JSON as hostile input despite the client-side
allowlist.

Cloudflare's own Worker Logs cover request, custom, and exception logs inside the intake service;
they don't replace desktop consent, disclosure, redaction, bundle construction, or the separate
choice to upload a report. Server observability never enables desktop reporting. The first beta has
no automatic failed-run reporting control.

## Update, removal, and storage contract

The default desktop path presents one primary action, **Launch Starsector**, and an honest
before/after result. Advanced controls remain available without becoming prerequisites.

- Updates may be checked in the background, but installation is explicit. Every Tauri updater
  artifact must have its project-key signature in the feed, and a failed verification must leave
  the installed version runnable. This free update signature is separate from paid Apple Developer
  ID or Windows Authenticode identities; the first beta doesn't require those platform identities.
- The app shows current cache/evidence use and the effect of Balanced versus Fastest before changing
  policy. Cleanup is preview-first and never runs while the game or preparation owns the profile.
  Its desktop plan keeps the current profile and every readable named profile, summarizes every
  removal reason, and caps the disclosed path sample. Applying it rebuilds the plan under the
  shared operation lease before deleting anything.
- Removing launcher integration and removing all Preflight-owned data are separate choices. Both
  enumerate what will be removed; neither removes Starsector, mods, saves, or game-owned settings.
  The first scope removes OS launch shortcuts and the installed command engine while retaining
  prepared data and evidence. The second also removes caches, profiles, evidence, and backups under
  Preflight's home. A packaged desktop app remains subject to the platform's normal package
  uninstaller; a running app doesn't attempt to delete its own bundle.
- A profile or game update selects new content identities. Old data remains removable through the
  same preview-first storage flow rather than accumulating invisibly forever.
- Sending a support ZIP is a deliberate action after creation and review, with a digest, progress,
  cancel/retry behavior, case receipt, retention information, and deletion path. It isn't ambient
  telemetry.

## Operation lifecycle

Preparation, launch, confirmed profile activation, launch-setting changes, and confirmed cache
pruning use one cross-process operation lease between Preflight processes. The CLI owns the lease, so
the rule applies equally to the desktop application, terminal use, and installed launch shortcuts.
Read-only previews don't take it. The lease provides no exclusion against Starsector, launchers,
settings editors, mod managers, or other external programs; launch-setting Apply therefore also
requires the explicit quiescent confirmation described above.

The operating system releases the lease if its owner exits or crashes. A small JSON owner record
identifies the operation and installation for diagnostics. When a later operation finds an
interrupted record, it removes only PID-tagged atomic-write remnants below Preflight's own home,
then continues. Completed content-addressed artifacts remain reusable; the game installation isn't
part of recovery.

Preparation also emits a versioned JSON-line progress stream while retaining its existing human
stage messages. The desktop host drains stdout and stderr concurrently, forwards those events to
the interface, and can stop the child process. A stop may leave an incomplete temporary file, which
is never published as an artifact and is reclaimed through the same interrupted-owner recovery.

The blocking implementation and publication checks are tracked in
[Release readiness](release-readiness.md).
