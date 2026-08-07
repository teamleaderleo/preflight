# Release readiness

**Status:** private development preview; Fractal Softworks guidance requested, awaiting response
**Updated:** 2026-08-07

Preflight has a credible performance result and a verified cross-platform packaging pipeline. It is
not ready to publish merely because it is fast. This checklist is the release boundary for the
first public beta.

## Blocking before public distribution

- [ ] Receive written Fractal Softworks authorization for distributing the tool and for the
  runtime-instrumentation/compatibility-analysis approach. The
  [permission request](fractal-permission-request.md) was sent on 2026-08-07.
- [x] Use **Preflight** as the public product, repository, and application name.
- [ ] Confirm descriptive use of the Starsector name, trademark attribution, and disclaimer with
  Fractal Softworks.
- [x] Use the project-controlled `io.github.teamleaderleo.preflight` bundle identifier before users
  install persistent packages.
- [x] Make the first beta wait for platform signing. Development packages can remain unsigned for
  CI, but tagged macOS and Windows artifacts fail before upload unless their platform signatures
  verify.
- [ ] Provision and back up the Tauri updater signing key; ship only metadata and packages verified
  by its public key. Keep the private key out of the repository and client.
- [ ] Configure and verify Apple signing/notarization and Windows signing if they are release gates.
- [ ] Test clean install, update, rollback, launcher ownership, ordinary removal, and full Preflight
  data removal on each published platform. Development CI now copies the macOS app and performs
  real Debian and NSIS install/verify/remove cycles; signed-candidate update, rollback, and
  user-data lifecycle checks remain.
- [ ] Exercise the licensed game on real Windows and Linux installations. CI package builds and
  synthetic fixtures don't prove game integration.
- [ ] Run a fresh controlled before/after cohort using the exact release candidate. Publish the
  median/distribution it supports; label the 15.88-second development run as a warm record.

## Blocking product work

- [x] Complete the first-run flow. It detects the installation, selects Recommended and Balanced,
  calculates the exact profile's predicted and conservative disk requirements, refuses unsafe free
  space, prepares a cold profile, and launches from one action.
- [x] Show preparation progress, cancellation/recovery, current profile identity, cache use, and the
  expected effect of changing storage policy.
- [x] Provide preview-first cleanup that retains the current and readable named profiles, groups
  every proposed removal, bounds path samples, and recalculates under shared ownership before
  deletion.
- [x] Provide two removal choices: launcher/app only, or all Preflight-owned caches and evidence.
  Never include game, mod, save, or preference deletion.
- [x] Add a background update check and explicit signed install/restart flow. Development builds
  report that their channel is disabled, Linux package installs stay with their package manager,
  and no update surprise-installs.
- [ ] Add **Send run report** using the bounded diagnostics export, with disclosure, ZIP digest and
  size, consent, progress, cancel/retry, case receipt, retention deadline, and deletion instructions.
  The private receiving service, local Java-export interoperability check, and desktop
  consent/upload/cancel/receipt/delete path are complete. Production provisioning, abuse-rate
  limits, public operator details, and a signed-package canary remain, so distributed builds still
  omit the intake origin.
- [ ] Keep automatic crash upload separate, default off, and out of scope unless its consent and
  privacy lifecycle are complete.
- [x] Surface Recommended, Conservative, and Off/troubleshooting. Keep raw plan flags behind an
  Advanced disclosure and let the engine enforce dependencies.
- [x] Preserve the ordinary game settings users expect: resolution, fullscreen, sound,
  antialiasing, UI scaling, and battle size.

## Compatibility and correctness gate

- [x] Every Recommended runtime plan has an exact class/source/loader gate, bounded health report,
  independent kill switch, and vanilla fallback on uncertainty. A checked 58-plan catalog maps
  direct and composed plans to their exact host boundaries. Shutdown reports state each plan's
  scope, filter, registration or composition state, host target count, and fallback. The global and
  per-plan switches cover direct targets and shared-class composition, and transformed-bytecode
  cache identities include the effective scope and filter.
- [x] Unknown game or mod versions visibly distinguish target/version mismatch, source-binding
  rejection, unavailable or declined plans, shadowing, ordinary cache misses, cache rejection,
  runtime-wrapper fallback, contained adapter failure, and runtime-integrity failure. The compact
  health report keeps normal learning misses informational while rejection and failure signals make
  the verdict partial and provide a specific next action.
- [x] Preparation, profile switching, cleanup, and launch share ownership so stale concurrent state
  can't be published or deleted.
- [x] Cache corruption, interruption, stale profile data, and low-disk behavior are exercised without
  damaging the installation. Binary cache codecs reject truncation and checksum drift, stale-profile
  cleanup is fail-closed, and the conservative planner refuses low space before creating the cache
  root. Packaged-process tests forcibly kill preparation and inject `ENOSPC` before atomic
  publication. The next packaged run recovers its lease and PID-tagged temporary; failed publication
  preserves the old artifact, removes the new temporary, and leaves the installation byte-identical.
- [ ] Fast Rendering, GraphicsLib, BoxUtil, and the reviewed large-mod profile complete startup,
  campaign, combat, simulation, retreat, save, reload, and clean exit scenarios.
- [ ] Audio transitions, title/refit visuals, simulation opponents, campaign notifications, and
  frame-time/FPS reporting have regression coverage matching the defects found during live pilots.

## Release artifacts and support

- [ ] Publish checksums, an SBOM/dependency inventory, MIT license, third-party notices, privacy
  statement, installation/removal instructions, known limitations, and release notes. The private
  release pipeline now assembles the legal/privacy files, path-correct checksums, and five validated
  target-specific SBOMs; publication and final release notes remain gated.
- [ ] Verify that no Starsector, mod, save, activation, or other third-party proprietary content is
  present in packages, diagnostics fixtures, screenshots, or source history. Core release archives
  now fail closed against an exact file manifest, compare every archived byte with its staged file,
  reject unsafe archive entries, and restrict the runnable JAR to reviewed project and dependency
  namespaces. Desktop engines now use that verified JAR, match every project resource to its source,
  inventory the bundled Java runtime, and require an exact native artifact set. A full-history audit
  rejects game/save/archive/crash paths, unexpected binary files, screenshots, and unreviewed large
  blobs. CI now extracts the DMG, NSIS, Debian, and AppImage payloads and re-verifies the embedded
  engine and reviewed resources. A final signed-candidate run remains.
- [x] Prepare a support template that asks for product/game/mod identities, preset, storage policy,
  launch result, and optional run-report case ID without requesting private logs by default.
- [x] Prepare a rollback/kill-switch notice path for a bad adapter or release.
- [ ] Make forum and Reddit announcements from the same reviewed claims and link to the evidence
  instead of copying an ever-growing benchmark table into each post.

## Publication rule

The first public post should lead with the user outcome, the exact reviewed profile, a controlled
before/after result, the fail-open boundary, visible disk cost, and beta limitations. It shouldn't
claim support for every platform, mod, future update, or machine. A draft structure is in
[release-post-draft.md](release-post-draft.md).
