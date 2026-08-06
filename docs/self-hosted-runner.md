# Self-hosted VPS verification

The repository can use a personal repository-level GitHub Actions runner while executing every Maven build inside a fresh rootless Podman container. GitHub supplies the queue, manual trigger, commit status, and logs; the VPS supplies the CPU and memory.

The workflow deliberately has no `pull_request` trigger. It accepts manual dispatches and exact owner-only commands on same-repository pull requests, so code from public forks cannot execute on the persistent VPS.

## Layout and trust boundary

```text
GitHub Actions control plane
  -> outbound HTTPS connection from the runner service
  -> unprivileged preflight-runner account
  -> disposable rootless Podman container
  -> repository checkout and persistent Maven dependency cache only
```

The runner account must not have `sudo`, SSH private keys, egress-service credentials, access to a Docker socket, or permission to read another service account's files. The container does not use host networking or privileged mode. It drops Linux capabilities, disables privilege escalation, and has CPU, memory, and PID limits.

A container shares the host kernel, so this is not equivalent to a separate VM. It is intended for trusted repository branches and protection against accidental host pollution, runaway builds, and persistent build state.

## One-time VPS preparation

The bootstrap script currently supports Debian and Ubuntu. Run it from a repository checkout on the VPS:

```bash
sudo bash ./scripts/bootstrap-vps-runner.sh prepare --swap-gib 1
```

This installs rootless Podman dependencies, creates the locked `preflight-runner` account, configures subordinate UID/GID ranges, optionally creates swap when none exists, enables user lingering when available, and builds the local image `localhost/starsector-preflight-build:1`.

The image is built from [`build/ci/Containerfile`](../build/ci/Containerfile). It is pulled only during explicit image builds; verification jobs use `--pull=never`. Record the printed image ID when changing the build environment.

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

No inbound Actions port is needed. Keep the existing SSH firewall policy and reach the VPS through the current jump host. The runner needs outbound HTTPS access to GitHub and Maven repositories.

## Running verification

From GitHub, open **Actions -> VPS verification -> Run workflow**. Select the Git ref and one suite:

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

`/vps verify` defaults to `full`. The workflow reads the pull request through GitHub's API, refuses fork heads, resolves the immutable head SHA, and checks out that SHA without persisting credentials. This comment route is also available on draft pull requests and can be invoked through the repository integration used for maintenance work.

The GitHub CLI equivalent for a branch or commit is:

```bash
gh workflow run vps-verify.yml \
  --ref main \
  -f suite=full \
  -f offline=false
```

To run the exact gate directly over SSH as the runner user:

```bash
sudo -iu preflight-runner
cd /path/to/a/preflight-checkout
bash ./scripts/verify-in-container.sh full
```

Set `PREFLIGHT_OFFLINE=1` after the Maven cache is warm to disable all container networking. Resource limits can be changed for a single run with `PREFLIGHT_CONTAINER_MEMORY`, `PREFLIGHT_CONTAINER_CPUS`, and `PREFLIGHT_CONTAINER_PIDS`.

## Updating the build image

After changing the Containerfile or deliberately refreshing its base image:

```bash
sudo -iu preflight-runner
podman build --pull=always \
  --tag localhost/starsector-preflight-build:1 \
  --file /path/to/preflight/build/ci/Containerfile \
  /path/to/preflight/build/ci
podman image inspect --format '{{.Id}}' localhost/starsector-preflight-build:1
```

Jobs never pull an image implicitly. A missing image fails with a bounded setup instruction rather than silently using a different environment.

## Operations and rollback

Useful host checks:

```bash
free -h
df -h
sudo -iu preflight-runner podman system df
sudo -iu preflight-runner podman ps --all
sudo journalctl --unit 'actions.runner.*' --since today
```

To take the runner offline, use the service script in `~/actions-runner`:

```bash
cd /home/preflight-runner/actions-runner
sudo ./svc.sh stop
sudo ./svc.sh uninstall
```

Then remove the runner from the repository's **Settings -> Actions -> Runners** page. Deleting the `preflight-runner` account, its home directory, Podman storage, Maven cache, or `/swapfile` is a separate explicit cleanup decision; none of those are removed automatically.
