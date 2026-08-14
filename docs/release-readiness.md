# Release readiness

**Status:** release-candidate preparation; Fractal Softworks guidance requested, awaiting response

**Updated:** 2026-08-12

Preflight has a credible performance result and a verified cross-platform packaging pipeline. It is
still inside the release boundary below.

| Area | Current state |
| --- | --- |
| Publication policy | Guidance requested August 7; maintainer decision pending |
| Desktop product | First-run, preparation, profiles, settings, updates, reports, cleanup, and removal implemented |
| Package lifecycle | Local macOS candidate passes; complete hosted three-platform candidate pending |
| Real-game coverage | Development macOS profile exercised; Windows and Linux runs pending |
| Performance record | **101s → 15.88s** established; a controlled 89.00s/15.53s pair now measured on one profile; packaged benchmark still to run |
| Publication | No public release until the candidate and publication decision are accepted |

## Blocking before public distribution

- [ ] Resolve the publication policy after the requested Fractal Softworks guidance window. The
  [permission request](fractal-permission-request.md) was sent on 2026-08-07; this is an owner/legal
  decision rather than an unfinished engine feature.
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
  diagnostics filtering, and installation byte identity. The current 2026-08-11 checkout's ordinary
  unsigned macOS DMG passed package extraction, native-host boot, embedded-engine verification,
  disposable installation and removal, that synthetic contract, and game/mod/save retention without
  launching Starsector. Ubuntu ARM64 can run its portable source
  half, but can't validate the published x86-64 Linux package. Windows and Linux still need a
  completed hosted run of those checks, along with hosted-candidate completion.
  Update and rollback are no longer hand-driven work: the dispatch-only
  [package lifecycle rehearsal](package-lifecycle-rehearsal.md) builds two versions on each hosted
  runner and checks the upgrade, a byte-identical rollback, and separately owned data across every
  step. Its [first hosted run](evidence/2026-08-14-package-lifecycle-first-hosted-run.md) passed on
  Linux, Windows, and macOS on 2026-08-14. This box stays open for the remaining hosted-candidate
  work; update and rollback are no longer the gap.
  The first-beta checksum, warning, install, and removal instructions
  are now documented for every package. A manual private
  candidate path now exercises the real signing credentials and complete three-platform artifact
  assembly without granting its job release-publication permission. Candidate files are
  authenticated and encrypted before every upload because public-repository workflow artifacts are
  readable by signed-in GitHub users. A local update-signed macOS build has passed DMG/updater-tree
  equivalence and bundled-runtime smoke verification. See the
  [signed macOS lifecycle evidence](evidence/2026-08-08-signed-update-rollback-rehearsal.md).
- [ ] Run the startup benchmark on the exact release candidate and publish those results beside the
  **101 seconds → 15.88 seconds** development progression. This one cannot be closed by running
  anything today: `scripts/run-startup-benchmark.sh` hardcodes
  `JAR="$PWD/preflight-cli/target/preflight.jar"` and has no packaged-engine mode, so every campaign
  so far has measured the checkout rather than a built package. The
  [controlled campaign](evidence/2026-08-15-controlled-vanilla-fast-campaign.md) of 2026-08-15 did
  close the *other* half of this item — a same-profile vanilla baseline against the shipped preset,
  89.00s versus 15.53s — but it too ran the checkout jar. Closing the box needs harness work first.

## Blocking product work

- [x] Complete the first-run flow. It detects the installation, selects Recommended and Balanced,
  calculates the exact profile's predicted and conservative disk requirements, refuses unsafe free
  space, prepares a cold profile, and launches from one action.
- [x] Show preparation progress, cancellation/recovery, current profile identity, cache use, and the
  expected effect of changing storage policy.
- [x] Detect structurally damaged prepared data and offer an exact-profile repair. Apply rechecks
  profile identity under the durable lease, refuses path-boundary ambiguity, removes only scoped
  metadata/packs, retains shared blobs, and then rebuilds through the normal bounded preparation
  plan.
- [x] Reconcile launch, preparation, automation, report-upload, and update state with the native
  coordinator when a live event subscription fails. The cross-process operation lease remains the
  final authority after desktop restarts.
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
  scoped grant. On 2026-08-12, the packaged desktop created and disclosed a 38,165-byte ZIP,
  uploaded it to the production intake, received a matching accepted receipt, and retained that
  receipt across an exact package reinstall. The ZIP named 12 included evidence entries, listed the
  excluded categories, and used the local calendar date. This report remains under automatic expiry
  so its receipt can be inspected during the owner walkthrough. A final candidate cancel/retry/delete
  sequence and the first complete hosted candidate matrix remain. See
  [the packaged report canary](evidence/2026-08-08-packaged-report-canary.md).
- [x] Keep automatic crash upload separate, default off, and out of scope unless its consent and
  privacy lifecycle are complete. No automatic crash-upload path is present.
- [x] Surface Recommended, Conservative, and Off/troubleshooting. Keep raw plan flags behind an
  Advanced disclosure and let the engine enforce dependencies.
- [x] Preserve the ordinary game settings users expect: resolution, fullscreen, sound,
  antialiasing, UI scaling, battle size, and the effective launcher-owned JVM heap. Heap changes
  refuse ambiguous launchers and keep an exact backup of the edited file.
- [x] Replace the single checked campaign sequence labelled **Benchmark** with an identity-checked,
  permission-free startup coordinator. It refuses different scenarios before launch,
  compares the sealed installation/profile/launcher/runtime/settings identity after each run, owns
  cancellation across both exact-PID lifetimes, and writes one versioned result without desktop
  input, save loading, screenshots, or Accessibility permission.
- [x] Seal startup-to-menu time, deltas, improvement percentage, exact run identity, and measured
  prepared-data disk context into the paired receipt and compact desktop result.
- [ ] Run the packaged startup pair and retain its receipt. Keep campaign/FPS automation and an
  alternating cohort as optional advanced evidence rather than the product benchmark.

## Compatibility and correctness

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

The remaining items below expand compatibility claims during the beta. They don't weaken the exact
identity gates or original-code fallback in the candidate:

- [ ] Exercise the real game on Windows and Linux installations. CI package builds and synthetic
  fixtures don't prove game integration. The
  [cross-platform evidence plan](cross-platform-evidence-plan.md) separates hosted package checks,
  emulated compatibility checks, and native beta evidence so each result carries only the claim it
  supports.
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
  run remains. The 2026-08-11 full-history audit accepted the current tracked tree, including the
  preserved unshipped icon candidates, with no unreviewed oversized blob.
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
