# Self-hosted VPS verification

The repository can use a personal repository-level GitHub Actions runner while keeping repository-controlled execution inside a fresh rootless Podman container. GitHub supplies the queue, manual trigger, commit status, and logs; the VPS supplies CPU and memory.

The workflow deliberately has no `pull_request` trigger. It accepts manual dispatches and exact owner-only commands on same-repository pull requests, so public fork heads cannot reach the persistent runner.

## Layout and trust boundary

```text
GitHub Actions control plane
  -> pinned checkout action resolves an exact commit
  -> unprivileged preflight-runner account
  -> root-owned /usr/local/libexec/starsector-preflight-ci-v1
  -> git archive of the exact commit (no repository file is executed)
  -> disposable rootless Podman container
  -> disposable writable source tree and per-run Maven cache
```

The workflow checks the launcher's root ownership, non-writable mode, and SHA-256 digest before checkout. The launcher accepts only an exact lowercase 40-character commit SHA, a fixed suite enum, and `online-verify` or `offline-verify`. It does not source repository files or accept command, image, mount, cache, or resource-limit overrides.

The launcher creates a Git archive from the requested commit and mounts only that archive, a new writable workspace, and a new per-run Maven cache. Archive extraction and Maven begin inside the container. The live checkout, runner registration, SSH files, host environment, Podman socket, and other project state are not mounted.

The runner account must have no supplementary groups, `sudo`, SSH private keys, egress-service credentials, Docker socket access, or permission to read another service account's files. Podman is rootless. The container:

- drops every Linux capability and sets `no-new-privileges`;
- uses a read-only root filesystem and private IPC and UTS namespaces;
- has fixed 768 MiB memory, 0.85 CPU, and 512 PID limits;
- uses rootless `slirp4netns` for online verification or `--network=none` offline;
- never pulls an image during a job.

A container shares the host kernel, so this is not a VM boundary. A malicious build can exhaust its assigned job storage and attack the host kernel, but it cannot intentionally persist through a shared Maven cache or execute a repository-controlled host entrypoint.

Workflow definitions themselves remain trusted control-plane code. Do not dispatch a modified VPS workflow from an unreviewed branch, add broad automatic triggers, or allow non-owner comments to reach this runner.

## One-time VPS preparation

The bootstrap supports Debian and Ubuntu. Run it only from a reviewed repository commit:

```bash
sudo bash ./scripts/bootstrap-vps-runner.sh prepare --swap-gib 1
```

Preparation installs rootless Podman dependencies, creates the locked `preflight-runner` account, refuses supplementary group memberships, configures subordinate UID/GID ranges, optionally creates swap, and enables user lingering when available. It also:

- installs the launcher as root-owned mode `0555` at `/usr/local/libexec/starsector-preflight-ci-v1`;
- creates root-owned state and dependency-seed paths under `/var/lib/starsector-preflight-ci`;
- creates a runner-owned mode `0700` directory for disposable jobs;
- builds `localhost/starsector-preflight-build:1` from [`build/ci/Containerfile`](../build/ci/Containerfile).

The command prints the installed launcher digest and image ID. The launcher digest must match `EXPECTED_LAUNCHER_SHA256` in [`vps-verify.yml`](../.github/workflows/vps-verify.yml). A missing, locally edited, or stale launcher fails before checkout.

### Seed dependencies for offline verification

The workflow never shares a writable Maven cache between jobs. Online jobs copy the operator seed into a fresh cache, fetch missing dependencies there, and delete it afterward. Offline jobs copy the same seed and run with `--network=none`.

Refresh the seed from a reviewed exact commit:

```bash
sudo bash ./scripts/bootstrap-vps-runner.sh refresh-cache \
  --source-sha "$(git rev-parse HEAD)" \
  --suite full
```

The refresh archives that commit and runs its Maven build inside the same constrained rootless container with network access. Only after a successful run does the bootstrap atomically promote the cache as root-owned, read-only state. Normal jobs cannot modify it or poison later jobs. Refresh each opt-in profile that must work offline (`analysis` or `coverage`) because those profiles may resolve additional plugins.

### Register the GitHub runner

Open:

```text
Repository Settings -> Actions -> Runners -> New self-hosted runner
```

Choose Linux and the VPS architecture. GitHub displays an exact runner download URL, SHA-256 checksum, and temporary registration token. Pass those values to the verified registration step:

```bash
sudo bash ./scripts/bootstrap-vps-runner.sh register \
  --repository-url https://github.com/teamleaderleo/starsector-preflight \
  --runner-download-url 'OFFICIAL_ACTIONS_RUNNER_TAR_GZ_URL' \
  --runner-sha256 'SHA256_FROM_GITHUB'
```

The script prompts for the temporary token without echoing it. It accepts only an official `github.com/actions/runner/releases/download/` archive, verifies the checksum before extraction, registers the custom `starsector-preflight` label, and installs the runner as a system service under the unprivileged account. Registration also applies the systemd delegation and runtime-directory configuration required by rootless Podman.

No inbound Actions port is needed. Keep the existing SSH firewall policy and reach the VPS through the current jump host. The runner needs outbound HTTPS access to GitHub and Maven repositories only for GitHub coordination, image refreshes, cache refreshes, and online verification.

## Running verification

From GitHub, open **Actions -> VPS verification -> Run workflow**. Select the Git ref, suite, and whether to use offline verification:

- `full` — `mvn verify`; the normal acceptance gate.
- `focused` — the agent and CLI reactor plus dependencies.
- `analysis` — opt-in Error Prone verification.
- `coverage` — opt-in JaCoCo verification.
- `package` — package without tests for quick packaging diagnostics.

For a same-repository pull request, the repository owner can add one exact comment:

```text
/vps verify
/vps verify focused
/vps verify analysis
/vps verify coverage
/vps verify package
```

Comment-triggered verification is online and `/vps verify` defaults to `full`. The workflow reads the pull request through GitHub's API, refuses fork heads, resolves its immutable head SHA, checks the launcher, and checks out that SHA without persisting credentials. The comment route also works on draft pull requests.

The GitHub CLI equivalent is:

```bash
gh workflow run vps-verify.yml \
  --ref main \
  -f suite=full \
  -f offline=true
```

To invoke the same launcher over SSH:

```bash
sudo -iu preflight-runner
cd /path/to/a/starsector-preflight-checkout
/usr/local/libexec/starsector-preflight-ci-v1 \
  "$(git rev-parse HEAD)" full offline-verify
```

The launcher prints a receipt containing its version and digest, source SHA, suite, network mode, image digest, resource limits, source-transfer method, and cache policy.

## Updating trusted components

After changing the launcher, bootstrap script, Containerfile, or dependency seed:

1. Review and merge the change through normal GitHub-hosted CI.
2. Stop or disable the VPS runner.
3. Check out the reviewed exact commit on the VPS.
4. Run `prepare`; run `refresh-cache` when dependencies changed.
5. Confirm the printed launcher digest matches the workflow.
6. Start the runner and run an offline verification.

Jobs use `--pull=never`; a missing image fails instead of silently changing the build environment. Resource limits and mount policy are fixed in the versioned launcher. Change them through review and reinstall rather than through job environment variables.

## Operations and rollback

Useful host checks:

```bash
free -h
df -h
sudo -iu preflight-runner podman system df
sudo -iu preflight-runner podman ps --all
sudo stat /usr/local/libexec/starsector-preflight-ci-v1
sudo find /var/lib/starsector-preflight-ci -maxdepth 2 -printf '%M %u:%g %p\n'
sudo journalctl --unit 'actions.runner.*' --since today
```

To take the runner offline, use the service script in `~/actions-runner`:

```bash
cd /home/preflight-runner/actions-runner
sudo ./svc.sh stop
sudo ./svc.sh uninstall
```

Then remove the runner from **Settings -> Actions -> Runners**. Deleting the runner account, its home, Podman storage, `/var/lib/starsector-preflight-ci`, or `/swapfile` is a separate explicit cleanup decision; none are removed automatically.
