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
- [ ] Decide whether the first beta waits for platform signing. Documented unsigned warnings aren't
  a substitute for an intentional trust decision.
- [ ] Provision and back up the Tauri updater signing key; ship only metadata and packages verified
  by its public key. Keep the private key out of the repository and client.
- [ ] Configure and verify Apple signing/notarization and Windows signing if they are release gates.
- [ ] Test clean install, update, rollback, launcher ownership, ordinary removal, and full Preflight
  data removal on each published platform.
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

- [ ] Every Recommended runtime plan has an exact class/source/loader gate, bounded health report,
  independent kill switch, and vanilla fallback on uncertainty. The global and per-plan environment
  switches now cover both direct targets and shared-class composed rewrites, with cache identities
  separated by the effective scope and filter. Shutdown reports now state both and include the
  prepared-pixel and padding safety counters. The remaining plan-by-plan gate and health-report
  inventory still needs to be closed before marking this complete.
- [ ] Unknown game or mod versions visibly distinguish adapter decline, cache miss/rejection,
  wrapper failure, and runtime integrity failure.
- [x] Preparation, profile switching, cleanup, and launch share ownership so stale concurrent state
  can't be published or deleted.
- [ ] Cache corruption, interruption, stale profile data, and low-disk behavior are exercised without
  damaging the installation. Binary cache codecs reject truncation and checksum drift, failed
  atomic publication preserves the existing destination and removes its temporary file, operation
  leases recover stale PID-tagged temporaries, stale-profile cleanup is fail-closed, and the
  conservative storage planner refuses low space before creating the cache root. An end-to-end
  killed preparation and injected `ENOSPC` run still need to exercise the packaged process boundary.
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
  namespaces. Native packages, diagnostics fixtures, screenshots, and source history still need
  their own final review.
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
