# Self-hosted VPS verification

The repository can use a personal repository-level GitHub Actions runner while keeping repository-controlled code out of the persistent host process. GitHub supplies the queue, manual trigger, commit status, and logs; the VPS supplies CPU and memory through a fixed operator-owned launcher and fresh rootless Podman containers.

The workflow deliberately has no `pull_request` trigger. It accepts manual dispatches and exact owner-only commands on same-repository pull requests, so public fork code cannot request work on the persistent VPS.

## Trust boundary

```text
GitHub Actions control plane
  -> outbound HTTPS connection from the runner service
  -> unprivileged preflight-runner account
  -> root-owned /usr/local/libexec/starsector-preflight-ci-v1
  -> exact commit archive treated as inert input on the host
  -> disposable rootless Podman warm phase when requested
  -> fresh source extraction + offline rootless Podman acceptance phase
```

The selected repository revision is never checked out or executed on the host by the VPS workflow. The workflow resolves an exact 40-character commit SHA and passes only that SHA, a fixed suite enum, and `online` or `offline` to `/usr/local/libexec/starsector-preflight-ci-v1`. If the launcher is missing, incorrectly owned, or writable by the runner account, verification fails closed.

The launcher itself is installed from a reviewed repository revision by an operator running the bootstrap script as root. Once installed, an Actions job cannot replace it. The repository copy under `build/ci/` is installation source and review material; the workflow does not execute that copy.

The runner account must not have `sudo`, SSH private keys, egress-service credentials, access to a Docker or Podman control socket, or permission to read another service account's files. Every container runs rootless, drops Linux capabilities, disables privilege escalation, uses a read-only container root, and has CPU, memory, and PID limits. A container still shares the host kernel and therefore does not provide the isolation of a separate VM.

## Source, network, and dependency handling

For each run, the host-owned launcher downloads the exact public GitHub commit archive over HTTPS into a private per-run directory. The archive is size-bounded and hashed, then mounted read-only into the container. Extraction occurs only inside a disposable container into a fresh writable workspace.

Maven receives a fresh per-run writable local repository. That directory is deleted after the job, so one verification job cannot poison a writable dependency cache used by a later job.

An `online` request has two container phases. The first runs the requested Maven suite with network access solely to warm that per-run dependency cache. The second deletes and freshly re-extracts the source workspace, reuses only the per-run Maven cache, adds Maven `--offline`, disables container networking with `--network=none`, and reruns the same suite. Only the second phase is the accepted verification result.

An `offline` request skips the warm phase. It is available only when an operator has provisioned `/var/lib/starsector-preflight-ci/m2` as a root-owned, non-writable dependency seed. The seed is mounted read-only and copied into the disposable per-run cache inside the container. If that seed is absent or writable by the runner account, offline verification fails instead of silently enabling network access or using a shared writable cache.

The online warm phase intentionally has no host credentials or persistent writable repository/cache mount. Repository-controlled code can use the network there, but only from inside the constrained disposable container. The accepted phase always runs without network access.

## One-time VPS preparation

The bootstrap script currently supports Debian and Ubuntu. Run it only from a revision you have reviewed for host-side installation changes:

```bash
sudo bash ./scripts/bootstrap-vps-runner.sh prepare --swap-gib 1
```

This installs rootless Podman dependencies, creates the locked `preflight-runner` account, configures subordinate UID/GID ranges, optionally creates swap when none exists, installs the reviewed launcher as root-owned `/usr/local/libexec/starsector-preflight-ci-v1`, and builds the local image `localhost/starsector-preflight-build:1`.

The bootstrap prints the installed launcher's SHA-256. Record it when changing the launcher. The expected ownership and permissions are:

```bash
stat -c '%U %G %a %n' /usr/local/libexec/starsector-preflight-ci-v1
# root root 755 /usr/local/libexec/starsector-preflight-ci-v1
```

The image is built from [`build/ci/Containerfile`](../build/ci/Containerfile). It is pulled only during explicit image builds; verification jobs use `--pull=never`. Record the printed image ID when changing the build environment.

After merging a revision that changes the launcher or when migrating an older runner, re-run `prepare` from the reviewed revision before invoking VPS verification. Until the operator-owned launcher exists, the workflow intentionally fails closed.

### Register the GitHub runner

Open:

```text
Repository Settings -> Actions -> Runners -> New self-hosted runner
```

Choose Linux and the VPS architecture. GitHub displays an exact runner download URL, SHA-256 checksum, and temporary registration token. Pass those values to the verified registration step:

```bash
sudo bash ./scripts/bootstrap-vps-runner.sh register \
  --repository-url https://github.com/teamleaderleo/preflight \
  --runner-download-url 'OFFICIAL_ACTIONS_RUNNER_TAR_GZ_URL' \
  --runner-sha256 'SHA256_FROM_GITHUB'
```

The script prompts for the temporary token without echoing it. It accepts only an official `github.com/actions/runner/releases/download/` archive, verifies its checksum before extraction, registers the custom `starsector-preflight` label, and installs the runner as a system service under the unprivileged account.

No inbound Actions port is needed. The runner needs outbound HTTPS access to GitHub and, for the optional online warm phase, Maven repositories.

## Running verification

From GitHub, open **Actions -> VPS verification -> Run workflow**. Select one suite:

- `full` — `mvn verify`; the normal acceptance gate.
- `focused` — the agent and CLI reactor plus dependencies.
- `analysis` — opt-in Error Prone verification.
- `coverage` — opt-in JaCoCo verification.
- `package` — package without tests for packaging diagnostics.

For a same-repository pull request, the repository owner can add one exact comment:

```text
/vps verify
/vps verify focused
/vps verify analysis
/vps verify coverage
/vps verify package
```

`/vps verify` defaults to `full`. The workflow reads the pull request through GitHub's API, refuses fork heads, resolves the immutable head SHA, and sends that SHA to the host-owned launcher. It does not check the selected revision out on the VPS host.

To invoke the launcher directly over SSH as the runner user, pass an exact commit SHA:

```bash
sudo -iu preflight-runner \
  /usr/local/libexec/starsector-preflight-ci-v1 \
  0123456789abcdef0123456789abcdef01234567 \
  full \
  online
```

`online` means “warm dependencies, then accept only the offline rerun.” Use `offline` as the final argument only after provisioning the read-only operator seed described above.

Every run prints a bounded verification receipt containing the launcher version, selected source SHA, source archive SHA-256 and byte count, suite, requested network mode, accepted `offline` network mode, image ID/digest when available, and resource limits. Phase markers in the log identify the optional online warm phase and the offline acceptance phase.

## Updating the build image or launcher

After changing `build/ci/Containerfile` or `build/ci/starsector-preflight-ci-v1`, review the change and re-run:

```bash
sudo bash ./scripts/bootstrap-vps-runner.sh prepare --swap-gib 1
```

The command replaces the installed launcher with the reviewed copy, rebuilds the local image with an explicit pull, and prints the resulting launcher checksum and image ID. Actions jobs never perform these operator updates.

## Operations and rollback

Useful host checks:

```bash
free -h
df -h
sudo -iu preflight-runner podman system df
sudo -iu preflight-runner podman ps --all
sudo journalctl --unit 'actions.runner.*' --since today
```

To disable VPS verification immediately, stop the runner service. To retire the launcher as well, remove it explicitly as root after the runner is stopped:

```bash
cd /home/preflight-runner/actions-runner
sudo ./svc.sh stop
sudo ./svc.sh uninstall
sudo rm -f /usr/local/libexec/starsector-preflight-ci-v1
```

Then remove the runner from the repository's **Settings -> Actions -> Runners** page. Deleting the `preflight-runner` account, its home directory, Podman storage, the optional root-owned dependency seed, or `/swapfile` remains a separate explicit cleanup decision.
