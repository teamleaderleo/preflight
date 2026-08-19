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

## #678 — packaged desktop single-instance contract

Resolved on main by #795. The selected beta correctness contract is **one packaged Preflight desktop process per user/session**; focus/reveal/handoff IPC is optional later product polish, not a release gate.

### Implemented lifetime guard

The cross-process guard is acquired in the tiny native `main.rs` entrypoint before the Tauri application and process-local `OperationCoordinator` exist.

- Unix prefers the validated owner-private runtime directory and otherwise creates/validates an owner-private `0700` fallback before opening the stable lock name with `O_NOFOLLOW` and holding an exclusive `flock`.
- Windows holds a session-local named kernel mutex.
- A normal second invocation gets a bounded two-second collision grace and then exits cleanly without creating a second normal Tauri lifetime or operation coordinator.
- Tauri updater restart uses the same grace because the replacement process may start before the old process has exited; the child becomes primary as soon as the old lifetime releases the guard.
- Windows collision handles are closed before retry so a waiting replacement cannot accidentally keep the old mutex object alive itself.
- Guard-acquisition errors fail closed with a bounded diagnostic.

The Java `OperationLease` continues to serialize Java-backed CLI mutations. The native single-instance guard closes the separate packaged-Desktop lifetime gap for update/export/benchmark/game/preparation ownership.

### Installed-package evidence

Run against the exact installed candidate on Linux, Windows, and macOS:

1. start Preflight and wait for the first native host; launch a second invocation and require the second process to exit within the bounded collision grace without creating a second normal app lifetime;
2. repeat while preparation/game ownership is active and require exactly one admitted desktop coordinator and one owned game/preparation lifetime;
3. repeat during diagnostics export and update operations; the second invocation must still exit without admitting independent native operation state;
4. exercise updater restart overlap and require the replacement process to acquire the guard immediately after the old lifetime releases it;
5. on Windows, burst at least eight simultaneous invocations during first-instance startup and require exactly one primary while every non-primary invocation exits within the bounded grace; **no focus/handoff assertion is part of this contract**;
6. repeat the burst after install, update, and rollback;
7. after the primary exits and after uninstall/removal, verify no helper process or live singleton ownership remains.

A failure to maintain one admitted normal desktop lifetime blocks the candidate. Window focus/reveal behavior does not.

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

### Local-only diagnostics candidate evidence

The first beta deliberately ships no remote report-intake capability. Final candidate evidence should prove that smaller boundary instead of exercising a service the package cannot contact.

On each exact candidate package:

1. open Help and create the bounded disclosed support ZIP;
2. verify the inclusion/exclusion disclosure and retained local ZIP bytes/SHA-256;
3. verify Help exposes no remote review/send/delete controls and Settings exposes no automatic-report toggle;
4. verify a stale automatic-report preference cannot inspect, export, or send a failed run once authoritative local-only status is known;
5. verify a transient intake-status read failure remains fail-closed for runtime remote actions without being treated as an authoritative state reset;
6. verify the packaged native build contains no configured report-intake origin and Distribution supplied no report-intake environment key;
7. verify full Preflight-data removal clears local reporting preferences/receipts without contacting a remote service.

Remote reporting remains post-beta work and requires its own reviewed authority, migration, retention, and deletion evidence before a later release enables it.

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
- after this publisher is on `main`, verify one successful **Merge gate** head status and activate the `main` ruleset;
- resolve Fractal Softworks guidance, descriptive Starsector trademark use, attribution, disclaimer wording, and the owner's publication decision.

## Candidate-byte-dependent closeout

These steps genuinely wait for final bytes:

- run **Candidate package lifecycle** against the exact successful signed Distribution run;
- run candidate signed-update/signature-rejection/rollback evidence where applicable;
- run the #418 exact packaged-engine benchmark and retain the raw receipt;
- run the final packaged local-only diagnostics capability audit;
- run the final complete-release/source-history/package-content audit;
- replace draft release-note performance/package claims with accepted candidate evidence;
- tag and stage the reviewed draft;
- publish only through **Publish verified release** after every release gate is accepted.
