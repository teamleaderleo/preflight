# Release-gate closeout plan

**Prepared:** 2026-08-19  
**Source refreshed through:** `main` at `94bd0ac6cba220615af5af4d5be82c23675a70e9`  
**#678/#817 reconciled through:** `main` at `56e908abe8f97ea75e35128ff193166f904c9d5b`

This file separates repository-owner administration, engineering preparation, and evidence that genuinely requires final release-candidate bytes.

## #720 — release-signing Environment

The Distribution workflow already fails closed. Its three updater-signing jobs reference the GitHub Environment named exactly:

`release-signing`

Configure that Environment before the next signed candidate. A missing referenced Environment can become an empty Environment in GitHub; empty `RELEASE_*` lookups then fail the signing validation.

### Required private signing secrets

Create these Environment secrets inside `release-signing`:

- `RELEASE_TAURI_SIGNING_PRIVATE_KEY`
- `RELEASE_TAURI_SIGNING_PRIVATE_KEY_PASSWORD`

Enter their values only through GitHub's secret UI. Keep private signing values out of issues, pull requests, logs, screenshots, commands, and repository files.

Keep the matching public key as the variable `PREFLIGHT_UPDATER_PUBLIC_KEY`.

`PREFLIGHT_CANDIDATE_ARCHIVE_PASSWORD` is a separate candidate-artifact encryption secret. It can also live in `release-signing` for narrower release-only access, but its scope is independent of the updater-key migration.

`PREFLIGHT_REPORT_INTAKE_ORIGIN` is an HTTPS origin. A `release-signing` Environment variable is the narrowest useful production scope for it.

### Environment protection

Allow the refs that actually execute release jobs:

- branch `main` for manually dispatched signed candidates and publication;
- release tags `v*` for tagged Distribution runs.

Add a required reviewer when another eligible release approver exists. Enable **Prevent self-review** only when that second approver exists. Choose Environment administrator bypass deliberately and record emergency use in the release issue.

Environment secrets become available to a protected job after the Environment rules pass.

### Migration and verification order

1. Create/configure `release-signing` with `main` and `v*` admission.
2. Add the two `RELEASE_*` private signing secrets to that Environment.
3. Confirm the matching `PREFLIGHT_UPDATER_PUBLIC_KEY` variable remains configured.
4. Dispatch **Distribution** from `main` with `signed_candidate=true`.
5. Before approval, verify the updater-signing jobs wait at the `release-signing` gate and have executed no signing/build step.
6. Approve the deployment and verify signing-credential validation passes on Linux, Windows, and macOS.
7. Verify complete private-candidate assembly succeeds and the retained candidate remains encrypted as a workflow artifact.
8. Delete repository-level legacy secrets `TAURI_SIGNING_PRIVATE_KEY` and `TAURI_SIGNING_PRIVATE_KEY_PASSWORD`.
9. Delete any temporary repository-level duplicates named `RELEASE_TAURI_SIGNING_PRIVATE_KEY` or `RELEASE_TAURI_SIGNING_PRIVATE_KEY_PASSWORD`; leave the Environment copies as the sole private updater-key values.
10. Re-run the signed candidate and verify it succeeds through Environment approval.

Before migration, signing jobs receive empty `RELEASE_*` values and fail credential validation; the workflow has no legacy-name fallback. After migration, Environment admission precedes private-key access and signing. Ordinary development builds continue without private signing credentials.

This preparation branch also refreshes `preflight-desktop/README.md` and `docs/downloads.md` so the maintainer runbook names only `release-signing` and the `RELEASE_*` signing secrets.

## #607 — main branch/ruleset

Current GitHub reports `main` unprotected. The smallest useful policy is one trusted repository-owned aggregate status plus PR-only main.

This branch adds a **Merge gate publisher** on `pull_request_target`. The publisher executes only the reviewed base revision, never checks out or executes pull-request code, and has only `contents: read`, `actions: read`, and `statuses: write`. It publishes one commit-status context named **Merge gate** directly onto the current pull-request head SHA.

The trusted aggregate queries that head SHA's GitHub Actions runs, filters them to the current pull request, requires `Source boundary` as the always-present baseline, and waits for every repository pull-request workflow that actually ran for that PR. Existing path filters therefore continue to decide which expensive workflows apply. The newest rerun of each workflow name wins. Runner/action bootstrap failure, workflow cancellation requiring a rerun, and product/check failure are reported separately; every unsuccessful class remains blocking until a successful current-head rerun replaces it.

The status publisher first posts `Merge gate=pending`. It posts `success` only after the aggregate passes and `failure` when an observed scoped workflow fails. If the publisher itself crashes, loses API access, or is cancelled before its final status update, the context stays missing or pending and the branch rule remains closed.

### Bootstrap order

`pull_request_target` workflows come from the default branch. This preparation pull request therefore cannot prove its own final trusted publisher on later synchronize events while the publisher exists only on its topic branch.

After this pull request merges:

1. open or update one ordinary pull request against `main`;
2. verify **Merge gate publisher** runs from the base revision and the PR head receives a commit status named **Merge gate**;
3. verify `Merge gate` stays pending while applicable scoped workflows run and becomes success only after they all pass;
4. then create the active `main` ruleset and select **Merge gate** as the required status context. Select GitHub Actions as the expected source when GitHub offers that source binding.

The owner ruleset should then:

- require a pull request before merging;
- require the **Merge gate** status context;
- require the branch to be up to date before merge;
- block force pushes;
- block branch deletion;
- use `0` required approvals for the smallest solo-maintainer policy, or `1` when human review is part of the release policy.

Require only **Merge gate** in repository settings. Path-scoped jobs remain scoped by their workflow triggers and by Desktop's existing internal classifiers.

For an emergency/admin path, prefer a bypass actor whose bypass mode is **Pull requests only**. That keeps an auditable PR while allowing the selected owner/admin to bypass normal rules. Reserve an always-bypass actor for repository recovery and record each use.

The connected GitHub tooling in this session exposes repository-content and PR actions, but no Environment/ruleset settings action. Treat both settings changes as owner work until the GitHub UI shows them active.

### Publication admission

The existing tagged Distribution path stages a draft and preserves a verified complete-release artifact. This branch strengthens **Publish verified release** with:

- `release-signing` Environment approval;
- a requirement that the release tag commit is reachable from current `origin/main`;
- the existing exact Distribution-run identity check;
- the existing byte-for-byte comparison between draft assets and the preserved verified artifact;
- the existing complete-release re-verification and last-moment tag/draft immutability checks.

That leaves public publication as one explicit reviewed action over already-verified bytes.

## #678 / #817 — accepted packaged Desktop singleton

### Accepted beta contract

#678 is closed as completed by merged #795. The first-beta contract is the smaller behavior that #678 explicitly allowed: one normal packaged Preflight Desktop lifetime per user/session, with a colliding secondary invocation exiting cleanly.

The accepted implementation is already on `main`:

- the cross-process guard is acquired in `preflight-desktop/src-tauri/src/main.rs` before the Tauri app and process-local `OperationCoordinator` are created;
- Unix opens an owner-private lock file without following the final alias and holds an exclusive `flock` for the process lifetime;
- Windows holds a session-local named mutex and closes collision handles before retrying;
- a colliding launch waits for a bounded two-second grace so a Tauri updater replacement can acquire ownership as soon as the old process exits;
- if the existing owner remains alive through that grace, the secondary exits cleanly before normal app initialization;
- acquisition errors fail closed instead of starting an uncoordinated second Desktop lifetime.

Focus, reveal/unminimize, and argument handoff are optional post-beta product polish. They are not candidate blockers for #678. The release plan therefore does not require the Tauri single-instance plugin, a Cargo dependency change, or focus IPC that the accepted implementation does not contain.

### Candidate package evidence

Run these checks against the exact installed final-candidate packages selected by the #818/#841 tagged-byte lifecycle on Linux, Windows, and macOS:

1. start Preflight and wait for the primary packaged process to establish its normal native lifetime;
2. launch a second invocation while the primary remains alive; require the secondary to exit successfully after the bounded collision grace, keep the primary alive, and admit no second normal Desktop lifetime;
3. exercise updater-restart overlap by starting the replacement while the old process still owns the guard, then release the old lifetime within the grace and require the replacement to become the sole primary;
4. after clean primary exit, require a new invocation to acquire immediately, proving process/guard lifetime releases ownership without a stale boolean/helper;
5. repeat singleton admission after install, upgrade, and byte-identical rollback;
6. run concurrent secondary-launch bursts against a held primary on every platform; on Windows include at least eight simultaneous peers so the implemented named-mutex admission path receives explicit contention coverage; every peer must exit and exactly one primary must remain;
7. after ordinary removal/reinstall, require ownership acquisition to work again. A persistent Unix lock-file pathname is acceptable; stale held ownership is not.

The retained singleton receipt must bind the exact release tag, tagged Distribution run ID, source revision, candidate package name/length/SHA-256, platform, primary PID, peer exit results, bounded-grace/reacquisition verdicts, and burst count. A failure to preserve one normal lifetime or to reacquire after the previous owner exits blocks the candidate. Missing focus/reveal/handoff does not.

## Exact candidate preparation

### Rehearsal and tagged final-candidate lifecycle

The existing **Candidate package lifecycle** remains a useful private pre-tag rehearsal keyed by one successful workflow-dispatch signed Distribution run ID. It proves packaging/install mechanics before a release tag exists, but it does not authorize later tag-built bytes.

The rehearsal workflow:

1. requires dispatch from `main`;
2. validates the requested run is the successful `Distribution` workflow at `.github/workflows/distribution.yml`, triggered by `workflow_dispatch` from `main`;
3. checks out that exact Distribution `head_sha` before using the verifier/decryptor;
4. downloads only `preflight-private-signed-candidate-<run id>` from that exact run;
5. decrypts candidate files only on the ephemeral runner;
6. runs `scripts/verify_complete_release.py` and requires the capability receipt source revision to equal the Distribution head SHA;
7. verifies each selected platform package against its candidate checksum manifest;
8. keeps the retained candidate package as the **newer** package;
9. builds only a throwaway older rehearsal package from the exact candidate source revision;
10. exercises install, upgrade to exact candidate, byte-identical rollback to the retained older package, ordinary removal, and separately owned data preservation on Linux, Windows, and macOS;
11. uploads only a small lifecycle receipt binding Distribution run ID, source revision/version, candidate package name/size/SHA-256, and checksum manifest.

The rehearsal candidate package is never rebuilt by this workflow, and decrypted candidate bytes are never uploaded by the lifecycle lane.

For final release acceptance, merged #841's **Tagged candidate package lifecycle** is authoritative. After the protected release tag triggers a successful tag-push Distribution run and stages the draft, dispatch the tagged lifecycle with that exact tag and Distribution run ID. It consumes `preflight-complete-release-<run id>` directly, rebuilds no final package, verifies the tagged source/package identity, exercises Linux/Windows/macOS install → upgrade → exact rollback → removal, and emits receipts binding release tag, tagged Distribution run ID, source revision, package name/length/SHA-256, and checksum manifest. **Publish verified release** requires those receipts to match the same preserved tagged package bytes.

The installable package set is `.deb`, NSIS `.exe`, and `.dmg`. Signed updater forward/rejected-signature evidence remains a separate candidate lane because Linux desktop self-update uses the AppImage updater artifact while `.deb` stays with the package manager.

### Exact packaged engine for #418

Select the engine from the installed/extracted exact tagged final-candidate package after complete-release verification and platform checksum verification. Require its JAR SHA-256 to equal `engineJarSha256` in that platform's `CAPABILITIES-<platform>.json`.

Run:

```bash
scripts/run-startup-benchmark.sh --engine <exact-candidate-engine-or-installed-app-path> ...
```

The harness must report `engineSource=candidate`, verify adjacent `bundle.json`, and record the actual JAR digest. A checkout `preflight-cli/target/preflight.jar` run remains development evidence.

Retain the raw harness output unchanged as:

`docs/evidence/<date>-candidate-startup-benchmark.json`

Retain the reviewed interpretation as:

`docs/evidence/<date>-candidate-startup-benchmark.md`

The Markdown receipt should record release tag, tagged Distribution run ID, source revision, candidate package name/SHA-256, capability-receipt engine SHA-256, game/profile identity, OS/hardware/runtime identity, vanilla and accelerated accepted-run counts, medians, acceptance verdict, and any drift/failure reason.

For the release comparison, require at least five accepted vanilla and five accepted accelerated launches with the same sealed installation/profile/launcher/runtime/settings identity and a clear drift guard.

### Production report-intake candidate canary

Prerequisites:

- #703 merged with its final capability-bound report filesystem operations and report-authority tests green;
- exact tagged final-candidate package checksum/capability verified;
- exact production `PREFLIGHT_REPORT_INTAKE_ORIGIN` configured for the release build;
- production Worker/private bucket, retention lifecycle, grant-signing key, rate limits, and daily grant ceiling active;
- one synthetic failed-run/support fixture inside the disclosed ZIP boundary;
- operator access for confirming cleanup/deletion without exposing service credentials.

Final sequence:

1. create the support ZIP from the packaged UI and review inclusion/exclusion disclosure;
2. cancel after a bounded partial upload;
3. verify server-side cleanup and local ZIP retention;
4. retry the same ZIP and verify receipt bytes/SHA-256;
5. restart the same tagged final candidate and verify unexpired receipt persistence;
6. delete through the case-scoped grant and verify the local receipt clears;
7. verify the canary case/object is gone and retain only bounded receipt metadata as evidence, including the release tag, tagged Distribution run ID, source revision, platform/package name, and package length/SHA-256.

### Checksums, SBOMs, dependencies, legal/privacy/install docs

The existing release assembler and complete-release verifier already prepare or require:

- core JAR/archive checksums;
- platform checksum manifests;
- `latest.json.sha256`;
- five CycloneDX SBOMs plus `SBOM-SHA256SUMS.txt`;
- `DEPENDENCY_INVENTORY.md`;
- `LICENSE`;
- `THIRD_PARTY_NOTICES.md`;
- `PRIVACY.md`;
- `KNOWN_LIMITATIONS.md`;
- install/removal instructions;
- reviewed release notes;
- per-platform capability receipts tied to exact package hashes and one common clean source revision.

`docs/releases/0.1.0.md` remains candidate-byte-dependent: keep it draft until the exact candidate benchmark/package claims exist, then replace development-only performance statements with accepted candidate evidence.

### Final source-history/package-content audit

Complete the source/admin checks before creating the final release tag:

1. require the intended release source revision to be the current reviewed `main` commit and the checkout clean;
2. run the full PR/release suite plus `scripts/verify_source_boundary.py` over current tracked content and reachable history;
3. confirm dependency inventories, notices, privacy/limitations/install/removal text, unsigned-package warnings, Apple-silicon scope, Linux package-manager/self-update distinction, and draft release notes are ready for that source revision;
4. complete the owner-controlled signing/ruleset/publication prerequisites that must hold before tag creation.

Then create the protected release tag from that accepted `main` revision and require the tag-push **Distribution** run to complete successfully and stage the draft. That successful tagged Distribution artifact is the final candidate byte authority for the checks below.

Against that exact tagged final-candidate artifact:

5. run `scripts/verify_complete_release.py` and require one common clean `sourceRevision` across capability receipts matching the tagged revision;
6. verify every platform checksum manifest and updater signature/URL pair;
7. extract DMG, NSIS, Debian, and AppImage payloads through the existing native package verifier and compare embedded engine/legal/runtime resources with reviewed release inputs;
8. inspect the exact candidate inventory for logs, diagnostics, screenshots, game/mod/save content, unexpected binaries, symlinks, or extra files;
9. confirm SBOM/dependency inventories and notices match the exact final package set;
10. run and retain the tagged lifecycle, singleton, benchmark, report-canary, checksum, source-boundary, package-boundary, and capability receipts required for the release, all bound to the same release tag / tagged Distribution generation and exact package identities;
11. replace candidate-dependent release-note claims only from accepted exact-tag evidence.

Public publication remains the separate **Publish verified release** action over those same accepted tagged bytes.

## Owner-only blockers

Engineering can continue while these stay open:

- configure/approve `release-signing` and enter the two private `RELEASE_*` signing values;
- keep/relocate the candidate archive password as an owner-managed release secret;
- configure the exact production report-intake origin/service credentials;
- after this publisher is on `main`, verify one successful **Merge gate** head status and activate the `main` ruleset;
- resolve Fractal Softworks guidance, descriptive Starsector trademark use, attribution, disclaimer wording, and the owner's publication decision.

## Candidate-byte-dependent closeout

After pre-tag source/admin acceptance, the final-byte sequence is:

- create/review the protected release tag from the accepted `main` revision and require the tag-push **Distribution** run to succeed and stage the draft;
- run **Tagged candidate package lifecycle** against that exact release tag and tagged Distribution run;
- run the installed-package singleton admission, collision-burst, and updater-restart reacquisition evidence against those exact tagged Distribution package bytes on Linux, Windows, and macOS;
- run candidate signed-update/signature-rejection/rollback evidence where applicable against the same tagged package generation;
- run the #418 exact packaged-engine benchmark and retain the raw receipt bound to the same tagged package identity;
- run the final packaged production report-intake canary bound to the same tagged package identity;
- run the final complete-release/source-history/package-content audit over those exact tagged bytes;
- replace draft release-note performance/package claims with accepted exact-tag evidence;
- publish only through **Publish verified release** after every release gate is accepted, using those same staged bytes.
