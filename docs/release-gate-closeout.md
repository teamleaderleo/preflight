# Release-gate closeout plan

**Prepared:** 2026-08-19  
**Source reviewed:** `main` at `231b07e8965956ef177a40a31c4e8b930cf4e3e7`

This is the remaining release-gate work that can be prepared before the final release-candidate bytes exist. It keeps owner-only repository administration, candidate-byte evidence, and engineering changes separate so each lane can advance independently.

## 1. Owner-only repository administration

### Release-signing Environment (#720)

The workflow contract is already fail-closed. The three jobs that can build updater-signed packages reference one GitHub Environment named exactly:

`release-signing`

Create and configure that Environment before the next signed candidate. A workflow that references a missing Environment can cause GitHub to create an empty Environment; its signing secret lookups are empty and the Distribution validation fails. Treat an auto-created empty Environment as unconfigured.

#### Environment secrets

Create these two signing secrets in `release-signing`:

- `RELEASE_TAURI_SIGNING_PRIVATE_KEY`
- `RELEASE_TAURI_SIGNING_PRIVATE_KEY_PASSWORD`

Keep the values out of issues, pull requests, logs, screenshots, commands, and repository files. The generated public key remains a variable named `PREFLIGHT_UPDATER_PUBLIC_KEY`.

`PREFLIGHT_CANDIDATE_ARCHIVE_PASSWORD` is a separate candidate-artifact encryption secret. It is release-only in practice and may also be moved into `release-signing` for narrower access. Its move is independent of the updater-key migration.

`PREFLIGHT_REPORT_INTAKE_ORIGIN` is an HTTPS origin rather than a secret. Keeping its production value as a `release-signing` Environment variable gives release builds a narrow production scope while ordinary builds remain unconfigured.

#### Environment protection

Use selected deployment branches/tags:

- branch: `main`
- release tags: `v*`

Those two refs cover manually dispatched signed candidates and publication from `main`, plus tagged Distribution jobs.

Add a required reviewer when an independent release approver exists. Enable **Prevent self-review** only when another eligible reviewer can approve; enabling it in a one-person release team deadlocks the release job. Disable administrator bypass for this Environment when an independent emergency path already exists and strict approval is desired. If owner-only emergency deployment is required, keep the bypass enabled and record its use in the release issue.

Environment secrets become available only after the protection rules pass.

#### Migration order and legacy secret removal

1. Create/configure `release-signing` with the branch/tag policy above.
2. Add `RELEASE_TAURI_SIGNING_PRIVATE_KEY` and `RELEASE_TAURI_SIGNING_PRIVATE_KEY_PASSWORD` to that Environment. Enter values only in GitHub's secret UI.
3. Confirm `PREFLIGHT_UPDATER_PUBLIC_KEY` still contains the matching public key as a variable.
4. Dispatch **Distribution** from `main` with `signed_candidate=true`.
5. Before approval, verify each updater-signing job is waiting at the `release-signing` gate and has executed no signing/build step.
6. Approve the Environment deployment and verify the signing-credential validation passes on Linux, Windows, and macOS.
7. Verify the private signed candidate reaches complete-candidate verification and remains an encrypted workflow artifact.
8. Delete repository-level legacy secrets `TAURI_SIGNING_PRIVATE_KEY` and `TAURI_SIGNING_PRIVATE_KEY_PASSWORD`.
9. Delete repository-level duplicates of the two `RELEASE_*` names if temporary duplicates were created during migration. Leave the Environment copies as the sole private updater-key values.
10. Re-run the signed candidate and verify it still succeeds through Environment approval.
11. Search the current tree for legacy maintainer instructions and references. The release docs must name only the `RELEASE_*` Environment secrets.

Expected behavior before migration: signed candidate jobs either wait on whatever Environment policy already exists or reach credential validation with empty `RELEASE_*` values and fail. The code does not fall back to the legacy repository-level names.

Expected behavior after migration: each updater-signing job waits for `release-signing` admission, receives the Environment secrets after admission, validates them, signs its own platform updater artifact, and contributes to the encrypted complete private candidate. Ordinary source/development builds continue without signing credentials.

### Main branch/ruleset (#607)

The smallest useful policy is one always-present aggregate check plus a PR-only main branch.

After the `Merge gate` workflow has completed once on a pull request, create an active branch ruleset:

- target branch: `main`
- require a pull request before merging
- required approvals: `0` for the smallest solo-maintainer policy, or `1` when human review is part of the release policy
- require status checks before merging: **`Merge gate`** from GitHub Actions
- require branches to be up to date before merging
- block force pushes
- block branch deletion

Require only `Merge gate` in branch settings. It queries GitHub Actions for the current pull request head and consumes every repository pull-request workflow that actually ran for that PR, while requiring `Source boundary` as the always-present baseline. Path-filtered workflows therefore remain scoped by their existing triggers, and new PR workflows automatically join the aggregate when they run. The gate waits for the observed workflow set to settle before reporting success, uses the newest rerun of each workflow name, and labels failed setup/action steps separately from product/check failures. Both classes remain blocking until a successful rerun replaces the failed run.

The Desktop workflow keeps its current internal native/package scoping, so a renderer-only change can satisfy the aggregate without forcing an unrelated package matrix. Docs-only changes keep their existing light fan-out.

For an emergency/admin path, prefer a ruleset bypass actor with bypass mode **Pull requests only**. That preserves a PR/audit trail while allowing the selected owner/admin to bypass the normal rule set. Use an always-bypass actor only when repository recovery requires direct pushes, and record every use.

The connected GitHub tool can edit repository contents but exposes no Environment/ruleset settings action. The checklist above therefore remains owner work; repository settings should be considered pending until the GitHub UI shows the active rules.

### Publication admission

The existing publication path already separates candidate creation from public release: tagged Distribution creates a draft, and **Publish verified release** manually revalidates the exact preserved Distribution artifact before it can undraft the release.

This preparation branch adds two publication admission checks:

- `release-signing` Environment approval on the publication job;
- the release tag commit must be reachable from current `origin/main` before undrafting.

That closes the remaining direct-publication path where an admin could tag an unreviewed side commit and manually publish its otherwise byte-consistent draft.

## 2. Cross-process desktop race refresh (#678)

### Current main already closes several pieces of the 2026-08-18 issue

Current native operation state still lives in one process-local `OperationCoordinator`, but later work changed the failure modes around it:

- preparation owns and clears the exact spawned child PID;
- renderer restart restores native update-install ownership;
- newer native operation recovery reconciles renderer state with native state;
- #703 is adding persistent automatic-report claims plus durable removal fencing, so duplicate automatic report ownership and destructive report-removal overlap are being handled at the report authority boundary.

Those changes make a distributed cross-process operation coordinator unnecessary for the beta product.

### Races that remain when two desktop processes are allowed

A second packaged desktop process starts with its own idle coordinator. That leaves these user-visible races:

1. **Preparation/game ownership:** process A can own a live preparation/game PID while process B sees its own coordinator as idle and attempts another conflicting start.
2. **Update ownership:** process A can be downloading/installing an update while process B sees no local update owner and starts preparation, diagnostics, report work, or another update check/install path.
3. **Manual report/diagnostics ownership:** process A can own a report upload or diagnostics export while process B admits an operation that the single-process coordinator would normally serialize.
4. **Exit/restart ownership:** update restart and process exit clean up only the resources owned by that process. A second surviving desktop process can preserve a conflicting UI/native lifetime.

#703's durable automatic-report claim/removal fence should be allowed to own its report-specific cross-process rules. A single desktop process removes the broader admission races without adding a file-lock protocol to every native command.

### Beta product

Use one packaged Preflight desktop instance. A later invocation should hand off to the existing process, reveal/unminimize the main window, and focus it. Ignore launch arguments and working directory for the first beta unless a reviewed deep-link/file-open feature is added later.

The official Tauri v2 single-instance plugin supports Windows, macOS, and Linux and its callback is the intended handoff point. Register it before other plugins so a second process exits before normal app initialization produces side effects.

Current upstream risk: `tauri-plugin-single-instance` v2.4.3 is the latest released plugin located during this review, while an open upstream Windows fix addresses a race between mutex creation and the event window. Treat the chosen Windows implementation/revision as candidate evidence, not an assumption.

### Bounded implementation seam

The implementation can stay within:

- `preflight-desktop/src-tauri/Cargo.toml`
- `preflight-desktop/src-tauri/Cargo.lock`
- `preflight-desktop/src-tauri/src/lib.rs`
- a focused native/package test if needed

No renderer capability is required. No cross-process operation database, distributed lock graph, or report coordinator is required.

Because #703 is actively changing native report authority, keep this PR independent of its report files. Land the single-instance change after selecting a plugin revision that passes the packaged Windows burst test below; #703 can merge first without forcing a conflict.

### Candidate single-instance test plan

Run against installed candidate packages on all three desktop platforms:

1. Start Preflight and wait for the main window/native host to become ready.
2. Launch a second invocation. Assert the first window becomes visible/focused and the second process exits promptly.
3. Minimize the first window and repeat; assert reveal/unminimize/focus.
4. Start preparation in the first process, then invoke Preflight again; assert focus-only handoff and one preparation owner/PID.
5. Start a report upload/diagnostics export in the first process, then invoke again; assert handoff only and no second native operation.
6. Enter update install/restart, invoke again during install and immediately after restart; assert at most one admitted desktop process and that the restarted process reacquires admission.
7. On Windows, launch at least eight concurrent second invocations in a burst before the first window is fully settled. Repeat the burst enough times to exercise the upstream mutex/event-window race. Assert one surviving desktop process, no hung second process, and one handoff callback per admitted retry.
8. Repeat the burst after install, after update, and after rollback.
9. Verify installer uninstall/removal still completes when the app is closed and no singleton helper/process remains.

A Windows burst failure blocks the plugin revision. It does not justify adding a distributed operation coordinator; choose/fix the single-instance admission implementation instead.

## 3. Exact candidate preparation

### Candidate-scoped package lifecycle

Use the new **Candidate package lifecycle** workflow after a successful private signed Distribution run. Its input is that Distribution run ID.

The workflow:

1. runs only when dispatched from `main`;
2. downloads `preflight-private-signed-candidate-<run id>` from that exact successful run;
3. decrypts only on an ephemeral runner;
4. runs `scripts/verify_complete_release.py` over the complete candidate;
5. verifies the source revision in the capability receipt equals the Distribution run head SHA and that the run used `.github/workflows/distribution.yml` from `main`;
6. checks each platform package against its candidate checksum manifest;
7. uses the retained candidate package as the **newer** package;
8. builds only an older rehearsal package from the exact candidate source revision;
9. exercises install, upgrade to the exact candidate, byte-identical rollback to the retained older package, ordinary removal, and separately owned data preservation;
10. uploads a small lifecycle receipt containing the Distribution run ID, candidate source revision/version, exact candidate package name, byte size, SHA-256, and checksum-manifest identity.

The workflow never rebuilds the candidate package and never uploads decrypted candidate bytes.

This lifecycle lane covers one installable package per desktop platform (`.dmg`, NSIS `.exe`, `.deb`). Signed updater-forward/signature-rejection evidence remains a separate candidate test because Linux's desktop updater uses the AppImage updater artifact while `.deb` updates stay with the package manager.

### Exact packaged engine for #418

Select the engine from the installed/extracted **exact candidate package that passed the complete-release and platform checksum checks**. The engine JAR digest must equal `engineJarSha256` in that platform's `CAPABILITIES-<platform>.json` receipt.

Run the benchmark harness with an explicit candidate engine path:

```bash
scripts/run-startup-benchmark.sh --engine <exact-candidate-engine-or-installed-app-path> ...
```

For extra operator clarity, pass the expected engine SHA-256 when using the harness option that accepts it. The harness verifies package-adjacent `bundle.json`, records `engineSource=candidate`, the selected engine path/version, repository/source identity, and the actual JAR SHA-256. A checkout `preflight-cli/target/preflight.jar` run remains development evidence.

Retain the machine-readable result as:

`docs/evidence/<date>-candidate-startup-benchmark.json`

and a short reviewed interpretation as:

`docs/evidence/<date>-candidate-startup-benchmark.md`

The JSON should contain the harness `benchmark-summary.json` unchanged. The Markdown receipt should record candidate Distribution run ID, source revision, package name/SHA-256, capability-receipt engine SHA-256, Starsector/game profile identity, OS/hardware/runtime identity, vanilla and accelerated run counts, medians, acceptance verdict, and any drift/failure reason.

Acceptance for release evidence:

- candidate source revision equals the complete candidate receipt;
- packaged engine SHA-256 equals the platform capability receipt;
- `engineSource` is `candidate`;
- at least five accepted vanilla and five accepted accelerated launches for the release comparison;
- every paired run uses the same sealed installation/profile/launcher/runtime/settings identity;
- drift guard remains clear;
- raw `benchmark-summary.json` is retained beside the interpretation.

### Production report-intake canary

Prerequisites before the final packaged UI canary:

- #703 merged with its final capability-bound report filesystem operation and report-authority tests green;
- exact candidate package selected and checksum/capability verified;
- production `PREFLIGHT_REPORT_INTAKE_ORIGIN` configured for the release build as an exact HTTPS origin;
- production Worker and private bucket deployed;
- retention lifecycle, grant-signing key, rate limit, and daily grant ceiling active;
- one synthetic failed run/evidence fixture available that stays inside the disclosed ZIP boundary;
- operator access available to confirm cleanup/deletion without exposing intake credentials in evidence.

Candidate canary sequence:

1. create the support ZIP from the packaged UI and review inclusion/exclusion disclosure;
2. begin upload and cancel after a bounded partial transfer;
3. confirm the server-side case/object cleanup completed and the local ZIP remained;
4. retry the same ZIP and verify receipt byte count/SHA-256 matches the disclosed local ZIP;
5. restart the exact candidate and verify the unexpired deletion receipt persists;
6. delete through the case-scoped grant and confirm the receipt clears locally;
7. verify the bucket/case is empty for the canary and retain only bounded receipt metadata in release evidence.

### Checksums, SBOMs, dependency inventory, legal/privacy/install docs

The existing release assembler and complete-release verifier already prepare these from exact release source/package bytes:

- core archive and JAR checksums;
- platform checksum manifests;
- `latest.json.sha256`;
- five CycloneDX SBOMs plus `SBOM-SHA256SUMS.txt`;
- `DEPENDENCY_INVENTORY.md`;
- `LICENSE`;
- `THIRD_PARTY_NOTICES.md`;
- `PRIVACY.md`;
- `KNOWN_LIMITATIONS.md`;
- install/removal instructions and release notes carried from the reviewed source.

Before tagging, update `docs/releases/0.1.0.md`: it still labels itself draft and contains development-only performance numbers. Replace those with the exact candidate benchmark/package claims and keep unsupported Windows/Linux real-game performance claims out.

The maintainer installation docs also need the #720 signing-secret names refreshed before release. Maintainer text should describe `release-signing` plus the two `RELEASE_*` Environment secrets and the separate public-key variable.

### Final source-history and package-content audit

Prepare this exact closeout sequence for the candidate source revision:

1. confirm the release candidate source revision is the current reviewed `main` commit and the checkout is clean;
2. run the full PR/release verification suite, including `scripts/verify_source_boundary.py` against current tracked content and reachable history;
3. run the complete-release verifier against the decrypted candidate and require one common clean `sourceRevision` across all capability receipts;
4. verify every platform checksum manifest and updater signature/URL pair;
5. extract DMG, NSIS, Debian, and AppImage payloads through the existing native package verifier and compare the embedded engine/legal/runtime resources with the reviewed release inputs;
6. review the exact complete candidate file inventory for logs, diagnostics, screenshots, game/mod/save content, unexpected binaries, symlinks, and extra files;
7. confirm SBOM/dependency inventories and notices match the final source lockfiles and package set;
8. confirm release notes, privacy, limitations, install/removal text, unsigned-package warnings, macOS Apple-silicon scope, and Linux updater/package-manager distinction all match the final packages;
9. retain lifecycle, benchmark, report-canary, checksum, source-boundary, package-boundary, and capability receipts under the final source revision;
10. only then create/review the release tag and draft candidate. Public publication remains the separate manual verified-release step.

## 4. Blockers kept outside engineering

### Owner/secrets

- configure and approve the `release-signing` Environment;
- enter the private updater-key values under the two `RELEASE_*` Environment secret names;
- keep/relocate the candidate archive password as an owner-managed release secret;
- configure the exact production report-intake origin and production service credentials where they belong;
- activate the main branch ruleset after `Merge gate` has emitted a successful check at least once.

### Publication/trademark

Fractal Softworks guidance, descriptive Starsector trademark use, attribution, disclaimer wording, and the owner's publication decision remain owner/legal policy. Engineering can finish candidate verification while that answer is pending. Public release waits for the accepted policy.

## 5. Bytes-dependent closeout only

These steps intentionally wait for the final candidate bytes:

- run the candidate package lifecycle workflow against the exact signed Distribution run;
- run three-platform signed update/signature-rejection/rollback evidence where applicable;
- run the #418 packaged-engine benchmark and retain `benchmark-summary.json`;
- run the final production report-intake packaged UI canary;
- run the final complete release/source-history/package-content audit;
- replace draft performance/package statements in `docs/releases/0.1.0.md` with accepted candidate evidence;
- tag and create the reviewed draft release;
- publish only through **Publish verified release** after every release gate is accepted.
