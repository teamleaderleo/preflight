# Release-gate closeout plan

**Prepared:** 2026-08-19  
**Source refreshed through:** `main` at `94bd0ac6cba220615af5af4d5be82c23675a70e9`

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

## #678 — packaged single-instance contract

### Accepted beta behavior

Merged #795 closes the broad two-desktop-process correctness gap with the smaller contract already
allowed by #678: one normal packaged Preflight desktop lifetime per user/session. The guard is
acquired in the native entrypoint before the Tauri app and process-local `OperationCoordinator`
exist.

- Linux/macOS hold an exclusive `flock` on an owner-private no-follow lock file;
- Windows holds a session-local named kernel mutex;
- a normal second launch waits through a bounded two-second collision grace, then exits cleanly if
  the first lifetime still owns the guard;
- an updater-spawned replacement may acquire as soon as the old lifetime releases during that same
  grace;
- guard lifetime, not a persistent boolean/helper process, owns release;
- acquisition errors fail closed before normal app state exists.

Java-backed CLI mutations keep their existing cross-process `OperationLease`; #703 keeps its own
report-specific authority. The single-instance guard prevents a second packaged Desktop lifetime
from constructing independent native/report/update/export/game ownership in the first place.

Focus/reveal/handoff to an already-running window is optional post-beta product polish. It is not a
release requirement and the package gate must not require focus IPC that current production code
does not implement.

### Package test plan

Run against installed candidate packages on Linux, Windows, and macOS:

1. start Preflight and wait for the first normal native host/window;
2. launch a second invocation while the first stays alive; assert the secondary exits cleanly after
   the bounded collision grace and creates no second normal app lifetime/operation coordinator;
3. repeat while preparation/game ownership is active; assert one desktop lifetime and one owned game
   PID;
4. repeat during report upload, diagnostics export, and other native-owned work; assert the secondary
   invocation does not create independent normal app state;
5. during updater restart, assert the replacement process acquires once the old lifetime releases
   within the bounded two-second overlap and no stale guard blocks restart;
6. burst at least eight simultaneous secondary invocations during first-instance startup on each
   supported platform; assert the implemented `flock`/named-mutex admission path never admits a
   second primary lifetime;
7. repeat the concurrency/reacquisition checks after install, update, and rollback;
8. verify ordinary close/reopen releases and reacquires the guard cleanly;
9. verify uninstall/removal leaves no singleton helper/process or stale ownership behind.

Any result that admits two normal packaged Desktop lifetimes, strands the guard across ordinary
close/update/rollback, or prevents the updater replacement from acquiring after the old lifetime
releases blocks the candidate.

## Exact candidate preparation

### Candidate-scoped three-platform lifecycle

This branch adds **Candidate package lifecycle**, keyed by one successful private signed Distribution run ID.

The workflow:

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

The exact candidate package is never rebuilt by this workflow, and decrypted candidate bytes are never uploaded by the lifecycle lane.

The installable package set is `.deb`, NSIS `.exe`, and `.dmg`. Signed updater forward/rejected-signature evidence remains a separate candidate lane because Linux desktop self-update uses the AppImage updater artifact while `.deb` stays with the package manager.

### Exact packaged engine for #418

Select the engine from the installed/extracted exact candidate package after complete-release verification and platform checksum verification. Require its JAR SHA-256 to equal `engineJarSha256` in that platform's `CAPABILITIES-<platform>.json`.

Run:

```bash
scripts/run-startup-benchmark.sh --engine <exact-candidate-engine-or-installed-app-path> ...
```

The harness must report `engineSource=candidate`, verify adjacent `bundle.json`, and record the actual JAR digest. A checkout `preflight-cli/target/preflight.jar` run remains development evidence.

Retain the raw harness output unchanged as:

`docs/evidence/<date>-candidate-startup-benchmark.json`

Retain the reviewed interpretation as:

`docs/evidence/<date>-candidate-startup-benchmark.md`

The Markdown receipt should record Distribution run ID, source revision, candidate package name/SHA-256, capability-receipt engine SHA-256, game/profile identity, OS/hardware/runtime identity, vanilla and accelerated accepted-run counts, medians, acceptance verdict, and any drift/failure reason.

For the release comparison, require at least five accepted vanilla and five accepted accelerated launches with the same sealed installation/profile/launcher/runtime/settings identity and a clear drift guard.

### Production report-intake candidate canary

Prerequisites:

- #703 merged with its final capability-bound report filesystem operations and report-authority tests green;
- exact candidate package checksum/capability verified;
- exact production `PREFLIGHT_REPORT_INTAKE_ORIGIN` configured for the release build;
- production Worker/private bucket, retention lifecycle, grant-signing key, rate limits, and daily grant ceiling active;
- one synthetic failed-run/support fixture inside the disclosed ZIP boundary;
- operator access for confirming cleanup/deletion without exposing service credentials.

Final sequence:

1. create the support ZIP from the packaged UI and review inclusion/exclusion disclosure;
2. cancel after a bounded partial upload;
3. verify server-side cleanup and local ZIP retention;
4. retry the same ZIP and verify receipt bytes/SHA-256;
5. restart the same candidate and verify unexpired receipt persistence;
6. delete through the case-scoped grant and verify the local receipt clears;
7. verify the canary case/object is gone and retain only bounded receipt metadata as evidence.

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

For the final candidate source revision:

1. require the candidate source revision to be the current reviewed `main` commit and the checkout clean;
2. run the full PR/release suite plus `scripts/verify_source_boundary.py` over current tracked content and reachable history;
3. run `scripts/verify_complete_release.py` over the decrypted candidate and require one common clean `sourceRevision` across capability receipts;
4. verify every platform checksum manifest and updater signature/URL pair;
5. extract DMG, NSIS, Debian, and AppImage payloads through the existing native package verifier and compare embedded engine/legal/runtime resources with reviewed release inputs;
6. inspect the exact candidate inventory for logs, diagnostics, screenshots, game/mod/save content, unexpected binaries, symlinks, or extra files;
7. confirm SBOM/dependency inventories and notices match final lockfiles/package set;
8. confirm release notes, privacy, limitations, install/removal text, unsigned-package warnings, Apple-silicon scope, and Linux package-manager/self-update distinction match the final packages;
9. retain lifecycle, benchmark, report-canary, checksum, source-boundary, package-boundary, and capability receipts under the final source revision;
10. create/review the release tag and draft only after those checks. Public publication remains the separate **Publish verified release** action.

## Owner-only blockers

Engineering can continue while these stay open:

- configure/approve `release-signing` and enter the two private `RELEASE_*` signing values;
- keep/relocate the candidate archive password as an owner-managed release secret;
- configure the exact production report-intake origin/service credentials;
- after this publisher is on `main`, verify one successful **Merge gate** head status and activate the `main` ruleset;
- resolve Fractal Softworks guidance, descriptive Starsector trademark use, attribution, disclaimer wording, and the owner's publication decision.

## Candidate-byte-dependent closeout

These steps genuinely wait for final bytes:

- run **Candidate package lifecycle** against the exact successful signed Distribution run;
- run candidate signed-update/signature-rejection/rollback evidence where applicable;
- run the #418 exact packaged-engine benchmark and retain the raw receipt;
- run the final packaged production report-intake canary;
- run the final complete-release/source-history/package-content audit;
- replace draft release-note performance/package claims with accepted candidate evidence;
- tag and stage the reviewed draft;
- publish only through **Publish verified release** after every release gate is accepted.
