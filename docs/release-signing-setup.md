# Release signing setup from zero

This page is the computer-use/operator setup for producing Preflight's private signed rehearsal candidate and, later, the tagged final candidate.

The repository-side workflows already exist. You do **not** need Apple Developer ID/notarization or Windows Authenticode for the first beta. The signing described here is Tauri's project-owned updater signature.

The repository currently has **no branch or tag ruleset by owner choice**. Do not recreate one as part of this procedure. Release-secret admission is therefore handled at the `release-signing` Environment plus the workflows' own ancestry, tag-stability, package-digest, lifecycle, and canary verification.

#965 is the live operator checklist and evidence ledger. This document is the durable setup reference. If live #965 and this page ever disagree about current release state, refresh #652/#965 and current `main` before acting.

## Official references

- Tauri updater signing: <https://v2.tauri.app/plugin/updater/#signing-updates>
- Tauri CLI signer reference: <https://v2.tauri.app/reference/cli/#signer-generate>
- Tauri GitHub Actions distribution guide: <https://v2.tauri.app/distribute/pipelines/github/>
- GitHub Environments: <https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments>
- GitHub Actions secrets: <https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets>
- GitHub Actions variables: <https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-variables>
- Tauri Windows publisher signing, which is separate from updater signing: <https://v2.tauri.app/distribute/sign/windows/>

Tauri updater signatures require a private key that must remain secret and backed up; the public key is safe to distribute. Environment secrets are the release credential boundary used by the workflows below.

## What already exists

- `.github/workflows/distribution.yml`:
  - manual `workflow_dispatch` from `main` with `signed_candidate=true` builds an encrypted private/rehearsal candidate;
  - a release tag runs the tagged Distribution path and stages a draft GitHub Release.
- `.github/workflows/candidate-lifecycle.yml` exercises a selected private Distribution candidate on hosted Linux, Windows, and macOS.
- tagged lifecycle/report-canary/publication workflows bind final evidence to the exact tagged Distribution package generation.
- release builds read credentials/configuration from the GitHub Environment named exactly `release-signing`.
- `docs/releases/0.1.0.md` is finalized; #961 is already merged.
- #974 is already merged, so publication requires the exact tagged report-canary receipt.

The manual rehearsal appears in Actions as **Distribution**, not as a separate workflow named “Signed candidate.”

## 1. Start from current main

Immediately before setup, refresh the live repository and #965. Do not reuse a SHA copied from this document.

For local key generation, use a clean checkout. Hosted release jobs use Node 22, so Node 22 is the preferred parity target.

```bash
git switch main
git pull --ff-only
git status --short
cd preflight-desktop
npm ci
```

`git status --short` should be empty. Generate private material outside the repository.

## 2. Generate the Tauri updater keypair

Do this on a machine you control. Never commit the private key, paste it into an issue/chat, or expose it in screenshots/logs.

### macOS / Linux

```bash
mkdir -p ~/.tauri
npm run tauri signer generate -- -w ~/.tauri/preflight-updater.key
```

### Windows PowerShell

```powershell
New-Item -ItemType Directory -Force "$HOME\.tauri" | Out-Null
npm run tauri signer generate -- -w "$HOME\.tauri\preflight-updater.key"
```

Choose a strong password. Back up the private key and password in a password manager/offline location. Losing the updater private key prevents ordinary future updates to already-installed clients unless a separate migration path is built.

The generated **public** key is safe to share. The Environment secret below receives the private-key **contents**; the Environment variable receives the public-key content.

## 3. Generate the private-candidate archive password

```bash
node --input-type=module -e "import { randomBytes } from 'node:crypto'; console.log(randomBytes(48).toString('base64'))"
```

Store the result in a password manager. Do not post it in #965, issues, chat, Actions inputs, or logs.

## 4. Create/configure `release-signing`

In GitHub:

1. Open **Settings → Environments**.
2. Create or open an Environment named exactly `release-signing`.
3. For the **private rehearsal**, restrict deployment to branch `main`.
4. A required reviewer is optional for a solo-maintainer repository. Do **not** enable **Prevent self-review** unless another eligible reviewer actually exists.
5. Do not enable broad tag admission for the rehearsal.

Because the repository intentionally has no tag ruleset, keep Environment tag admission narrow. When the release owner later authorizes the final tag, admit the **exact intended tag name** (for example `v0.1.0`) rather than a broad `v*` pattern when GitHub's Environment rule UI permits it.

Before approving any tagged `release-signing` deployment, the computer-use operator must verify:

- the deployment ref is the intended release tag;
- the tag resolves to the exact frozen/accepted `main` commit recorded in #652/#965;
- the tag has not moved since candidate identity was recorded.

The workflows still perform their own ancestry, ref stability, package digest, lifecycle, canary, and publication checks. This manual Environment check is the admission boundary for release secrets under the current no-ruleset policy.

## 5. Add Environment secrets

Under **Settings → Environments → release-signing → Environment secrets**, add exactly:

- `RELEASE_TAURI_SIGNING_PRIVATE_KEY` — **contents** of the generated Tauri private key;
- `RELEASE_TAURI_SIGNING_PRIVATE_KEY_PASSWORD` — updater-key password;
- `PREFLIGHT_CANDIDATE_ARCHIVE_PASSWORD` — random private-candidate archive password.

Never put these values in repository files, issues, chat, Actions inputs, screenshots, terminal transcripts, or comments.

## 6. Add Environment variables

Under **Environment variables**, add exactly:

- `PREFLIGHT_UPDATER_PUBLIC_KEY` — generated Tauri public-key content;
- `PREFLIGHT_REPORT_INTAKE_ORIGIN` — reviewed production report-intake HTTPS origin.

Do not invent a placeholder intake origin. The Distribution workflow intentionally fails closed when release configuration is absent.

## 7. Run the private signed rehearsal

This rehearsal does **not** create a public release and does not become final exact-tag benchmark/canary authority.

1. Open **Actions → Distribution → Run workflow**.
2. Select branch `main`.
3. Set `signed_candidate=true`.
4. Start the workflow.
5. Approve `release-signing` if the Environment has an approval rule.
6. Require the complete run to succeed, including core, Linux, Windows, macOS, and private-candidate assembly.
7. Record in #965 only the non-secret evidence: current source SHA, Distribution run ID, conclusions, artifact/package names, and later package sizes/SHA-256 values.

The encrypted rehearsal artifact is named:

`preflight-private-signed-candidate-<distribution-run-id>`

## 8. Exercise that exact rehearsal candidate

1. Open **Actions → Candidate package lifecycle**.
2. Run it on `main`.
3. Set `distribution_run_id` to the successful rehearsal Distribution run ID.
4. Approve `release-signing` if required.
5. Require Linux, Windows, and macOS lifecycle jobs to succeed.
6. Retain the package-bound lifecycle receipts and record their non-secret identities in #965.

This proves the release configuration and hosted candidate pipeline. It does **not** replace the later evidence from the exact tagged final candidate.

## 9. Remove legacy repository-level release credentials and rehearse again

After the first private signed rehearsal succeeds:

1. inspect repository-level Actions secrets for legacy updater-key copies;
2. delete repository-level `TAURI_SIGNING_PRIVATE_KEY` and `TAURI_SIGNING_PRIVATE_KEY_PASSWORD` if present;
3. delete repository-level duplicates named `RELEASE_TAURI_SIGNING_PRIVATE_KEY` / `RELEASE_TAURI_SIGNING_PRIVATE_KEY_PASSWORD` if present;
4. delete the repository-level `PREFLIGHT_CANDIDATE_ARCHIVE_PASSWORD` duplicate;
5. keep the Environment copies as the release-signing authority;
6. rerun the private signed Distribution from `main` and its candidate lifecycle;
7. require the post-cleanup rehearsal to succeed before source freeze.

Never copy secret values into #965 while documenting this cleanup. Record only names removed, run IDs, source SHA, and conclusions.

## 10. Before the final release tag

Do not create a tag merely because the private rehearsal is green. Tag creation/publication remain separate release-owner decisions.

When separately authorized:

1. refresh #652, #965, #818, #418 and current `main`;
2. record the exact frozen accepted source SHA;
3. configure `release-signing` to admit the exact intended tag (for example `v0.1.0`), keeping admission as narrow as the Environment UI permits;
4. verify release notes contain no candidate placeholders;
5. create the intended release tag at that exact frozen SHA;
6. before any tagged Environment approval, verify tag → frozen SHA identity again;
7. let tag-triggered Distribution stage the draft release bytes;
8. run exact-tag lifecycle, singleton/reacquisition/update evidence, packaged-engine benchmark, tagged production report canary, and hands-on packaged report-intake acceptance against those exact bytes;
9. publish only after those package-bound receipts satisfy #818/#965 and the separate release-owner publication decision is made.

Rebuilds from the same source revision are different candidate bytes and do not inherit package-dependent evidence.

## What to record / what never to record

Safe evidence for #965:

- source SHA;
- Distribution/lifecycle/canary/benchmark run IDs;
- job conclusions;
- package names, lengths, SHA-256 values;
- bounded receipts that contain no bearer credentials.

Never record:

- Tauri private key;
- updater-key password;
- candidate archive password;
- deletion bearer credentials;
- decrypted private-candidate artifacts.

## What does and does not cost money

No paid platform signing identity is required for this first-beta path:

- Tauri updater keypair: project-owned and free;
- SHA-256/SBOM/release verification/candidate lifecycle: repository tooling;
- first-beta macOS/Windows packages intentionally remain outside paid Developer ID/notarization and Authenticode.

Paid platform identities can be added later and are separate from updater signing.

## If the first signed run fails

Treat failures as configuration evidence rather than weakening workflows. Common explicit failures include:

- missing `RELEASE_TAURI_SIGNING_PRIVATE_KEY`;
- missing `RELEASE_TAURI_SIGNING_PRIVATE_KEY_PASSWORD`;
- missing `PREFLIGHT_UPDATER_PUBLIC_KEY`;
- missing `PREFLIGHT_REPORT_INTAKE_ORIGIN`;
- `PREFLIGHT_CANDIDATE_ARCHIVE_PASSWORD` shorter than 32 characters;
- manual signed candidate dispatched from a branch other than `main`.

Fix the named configuration and rerun. In #965, record the workflow/run/job identity and visible non-secret error only.
