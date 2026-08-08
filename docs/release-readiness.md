# Release readiness

**Status:** private development preview; Fractal Softworks guidance requested, awaiting response
**Updated:** 2026-08-09

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
- [x] Record the first-beta package trust policy. macOS and Windows packages will ship without paid
  platform identities, with the expected Gatekeeper/SmartScreen warnings stated before download.
  Tagged artifacts still require exact package-boundary verification and published SHA-256 files.
- [x] Provision and back up the Tauri updater signing key; ship only metadata and packages verified
  by its public key. The encrypted recovery key has owner-only permissions outside the repository,
  its password is stored separately in macOS Keychain, the private values are GitHub Actions
  secrets, and only the public key is a repository variable.
- [x] Keep Apple signing/notarization and Windows Authenticode outside the first-beta gate. They can
  be added later without changing the cache or runtime-adapter model. The exact trust boundary is
  recorded in [the package and report decision](evidence/2026-08-08-package-trust-and-report-boundary.md).
- [ ] Test clean install, update, rollback, launcher ownership, ordinary removal, and full Preflight
  data removal on each published platform. Development CI now copies the macOS app and performs
  real `.deb` and NSIS install/verify/remove cycles on Ubuntu and Windows. The isolated macOS
  package completed signed forward update, rejected-signature recovery with a byte-identical app,
  checked-package rollback, app-only removal with separate data retained, and full-data removal
  with game, mod, and save sentinels retained. Every native install exercise now runs the packaged
  engine's preview and confirmed all-data paths inside a disposable home. It also validates the
  shipped campaign scenario, seals an intentional no-game result as `skipped`, and runs a no-launch
  platform-adapter probe that must return a valid driver or a specific unavailable reason.
  Native package exercises also use the checked [license-free Fusion contract](fusion-acceptance.md)
  for spaces-and-Unicode discovery, cold preparation, warm reuse, dry-run launch non-execution,
  diagnostics filtering, and installation byte identity. Ubuntu ARM64 can run its portable source
  half, but can't validate the published x86-64 Linux package. Windows and Linux still need a
  completed hosted run of those checks, along with update/rollback and hosted-candidate completion.
  The first-beta checksum, warning, install, and removal instructions
  are now documented for every package. A manual private
  candidate path now exercises the real signing credentials and complete three-platform artifact
  assembly without granting its job release-publication permission. Candidate files are
  authenticated and encrypted before every upload because public-repository workflow artifacts are
  readable by signed-in GitHub users. A local update-signed macOS build has passed DMG/updater-tree
  equivalence and bundled-runtime smoke verification. See the
  [signed macOS lifecycle evidence](evidence/2026-08-08-signed-update-rollback-rehearsal.md).
- [ ] Exercise the licensed game on real Windows and Linux installations. CI package builds and
  synthetic fixtures don't prove game integration. The
  [cross-platform evidence plan](cross-platform-evidence-plan.md) separates free hosted package
  checks, emulated compatibility checks, and native beta evidence so each result carries only the
  claim it supports.
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
  and no update surprise-installs. The native host admits one installation at a time, refuses
  concurrent checks and mutations, and releases that state on every failed or changed offer.
- [x] Add **Send run report** using the bounded diagnostics export, with disclosure, ZIP digest and
  size, consent, progress, cancel/retry, case receipt, retention deadline, and deletion instructions.
  The private receiving service, local Java-export interoperability check, and desktop
  consent/upload/cancel/receipt/delete path are complete.
- [x] Provision the production intake with a private bucket, automatic expiration, encrypted grant
  signing, per-client edge brakes, an exact 500 MiB daily grant ceiling, and a synthetic live
  create/upload/finalize/delete canary. The canary left the bucket empty.
- [ ] Put the intake origin in a packaged release candidate and exercise disclosure, consent,
  cancel/retry, receipt, and deletion through the packaged UI. Distributed builds continue to omit
  the origin until that final canary passes. A local update-signed macOS package has completed
  disclosure, consent, fail-closed receipt recovery, retry, and a receipt whose exact object was
  verified through authenticated R2 access and then deleted through authenticated operator access.
  The desktop now retains an unexpired deletion receipt across restarts and removes it on deletion,
  dismissal, or expiry. A local intake server now exercises the native host's complete HTTP
  boundary: create, byte-exact streaming, finalize and receipt validation; cancellation followed by
  authorized cleanup deletion; and explicit receipt deletion. A production-origin release-mode DMG
  has now completed packaged disclosure, consent, upload, receipt persistence and deletion through
  its scoped grant. A second 3,762,549-byte canary cancelled after 256 KiB, confirmed server cleanup,
  retained the local ZIP, then retried to a matching receipt whose object was deleted through its
  scoped grant. A final click-through of the receipt's delete button and the first complete hosted
  candidate matrix remain. See [the packaged report canary](evidence/2026-08-08-packaged-report-canary.md).
- [x] Keep automatic crash upload separate, default off, and out of scope unless its consent and
  privacy lifecycle are complete. No automatic crash-upload path is present.
- [x] Surface Recommended, Conservative, and Off/troubleshooting. Keep raw plan flags behind an
  Advanced disclosure and let the engine enforce dependencies.
- [x] Preserve the ordinary game settings users expect: resolution, fullscreen, sound,
  antialiasing, UI scaling, battle size, and the effective launcher-owned JVM heap. Heap changes
  refuse ambiguous launchers and keep an exact backup of the edited file.

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
  target-specific SBOMs. The final verifier also pins every updater URL to either the exact GitHub
  release tag matching the manifest version or the inert private-candidate origin; arbitrary HTTPS
  hosts, another tag, and mixed modes fail. Publication and final release notes remain gated. See
  [the updater release origin boundary](evidence/2026-08-09-updater-release-origin-boundary.md).
- [ ] Verify that no Starsector, mod, save, activation, or other third-party proprietary content is
  present in packages, diagnostics fixtures, screenshots, or source history. Core release archives
  now fail closed against an exact file manifest, compare every archived byte with its staged file,
  reject unsafe archive entries, and restrict the runnable JAR to reviewed project and dependency
  namespaces. Desktop engines now use that verified JAR, match every project resource to its source,
  inventory the bundled Java runtime, and require an exact native artifact set. A full-history audit
  rejects game/save/archive/crash paths, unexpected binary files, screenshots, and unreviewed large
  blobs. CI now extracts the DMG, NSIS, Debian, and AppImage payloads and re-verifies the embedded
  engine and reviewed resources, then exercises ordinary native installation and removal where the
  package has an installer. The macOS exercise also starts the real native host for a no-game
  automation probe, pins its System Events disclosure, and rejects bundled-Java permission
  attribution. The exact evidence is recorded in
  [the native-package boundary](evidence/2026-08-07-native-package-boundary.md). A final tagged-candidate
  run remains.
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
