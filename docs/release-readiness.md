# Release readiness

**Status:** private development preview; not approved for public distribution
**Updated:** 2026-08-07

Preflight has a credible performance result and a verified cross-platform packaging pipeline. It is
not ready to publish merely because it is fast. This checklist is the release boundary for the
first public beta.

## Blocking before public distribution

- [ ] Receive written Fractal Softworks authorization for distributing the tool and for the
  runtime-instrumentation/compatibility-analysis approach. Start from the
  [permission request draft](fractal-permission-request.md).
- [ ] Confirm the public product name, repository/app title, trademark attribution, and disclaimer.
- [ ] Replace the development bundle identifier with a namespace controlled by the project before
  users install persistent packages.
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

- [ ] Give the first-run flow a primary action: detect installation, explain required disk space,
  choose Recommended and Balanced by default, prepare, and launch.
- [ ] Show preparation progress, cancellation/recovery, current profile identity, cache use, and the
  expected effect of changing storage policy.
- [ ] Provide preview-first cleanup and two removal choices: launcher/app only, or all
  Preflight-owned caches and evidence. Never include game, mod, save, or preference deletion.
- [ ] Add an explicit update check and signed install flow. Never surprise-install an update.
- [ ] Add **Send run report** using the bounded diagnostics export, with disclosure, ZIP digest and
  size, consent, progress, cancel/retry, case receipt, retention deadline, and deletion instructions.
- [ ] Keep automatic crash upload separate, default off, and out of scope unless its consent and
  privacy lifecycle are complete.
- [ ] Surface Recommended, Conservative, and Off/troubleshooting. Keep raw plan flags behind an
  Advanced disclosure and let the engine enforce dependencies.
- [ ] Preserve the ordinary game settings users expect: resolution, fullscreen, sound,
  antialiasing, UI scaling, and battle size.

## Compatibility and correctness gate

- [ ] Every Recommended runtime plan has an exact class/source/loader gate, bounded health report,
  independent kill switch, and vanilla fallback on uncertainty.
- [ ] Unknown game or mod versions visibly distinguish adapter decline, cache miss/rejection,
  wrapper failure, and runtime integrity failure.
- [ ] Preparation, profile switching, cleanup, and launch share ownership so stale concurrent state
  can't be published or deleted.
- [ ] Cache corruption, interruption, stale profile data, and low-disk behavior are exercised without
  damaging the installation.
- [ ] Fast Rendering, GraphicsLib, BoxUtil, and the reviewed large-mod profile complete startup,
  campaign, combat, simulation, retreat, save, reload, and clean exit scenarios.
- [ ] Audio transitions, title/refit visuals, simulation opponents, campaign notifications, and
  frame-time/FPS reporting have regression coverage matching the defects found during live pilots.

## Release artifacts and support

- [ ] Publish checksums, an SBOM/dependency inventory, MIT license, third-party notices, privacy
  statement, installation/removal instructions, known limitations, and release notes.
- [ ] Verify that no Starsector, mod, save, activation, or other third-party proprietary content is
  present in packages, diagnostics fixtures, screenshots, or source history.
- [ ] Prepare a support template that asks for product/game/mod identities, preset, storage policy,
  launch result, and optional run-report case ID without requesting private logs by default.
- [ ] Prepare a rollback/kill-switch notice path for a bad adapter or release.
- [ ] Make forum and Reddit announcements from the same reviewed claims and link to the evidence
  instead of copying an ever-growing benchmark table into each post.

## Publication rule

The first public post should lead with the user outcome, the exact reviewed profile, a controlled
before/after result, the fail-open boundary, visible disk cost, and beta limitations. It shouldn't
claim support for every platform, mod, future update, or machine. A draft structure is in
[release-post-draft.md](release-post-draft.md).
