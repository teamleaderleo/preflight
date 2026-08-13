#!/usr/bin/env python3
"""Static assertions for the persistent VPS runner trust boundary."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github" / "workflows" / "vps-verify.yml"
LAUNCHER = ROOT / "build" / "ci" / "starsector-preflight-ci-v1"


def require(text: str, needle: str, label: str) -> None:
    if needle not in text:
        raise AssertionError(f"{label}: missing {needle!r}")


def reject(text: str, needle: str, label: str) -> None:
    if needle in text:
        raise AssertionError(f"{label}: forbidden {needle!r}")


def main() -> None:
    workflow = WORKFLOW.read_text(encoding="utf-8")
    launcher = LAUNCHER.read_text(encoding="utf-8")

    # Repository bytes must not become host-executed code in the workflow.
    reject(workflow, "uses: actions/checkout@", "VPS workflow")
    for command in (
        "bash ./scripts/",
        "bash scripts/",
        "sh ./scripts/",
        "sh scripts/",
        "python ./scripts/",
        "python scripts/",
        "python3 ./scripts/",
        "python3 scripts/",
        "node ./scripts/",
        "node scripts/",
    ):
        reject(workflow, command, "VPS workflow")

    # The installed host launcher is bound to the exact immutable workflow revision
    # before it receives the selected repository SHA.
    require(workflow, "WORKFLOW_SHA: ${{ github.sha }}", "VPS workflow")
    require(
        workflow,
        "contents/build/ci/starsector-preflight-ci-v1?ref=${WORKFLOW_SHA}",
        "VPS workflow",
    )
    require(workflow, "expected_launcher_sha256=", "VPS workflow")
    require(workflow, 'actual_launcher_sha256="$(sha256sum "$launcher"', "VPS workflow")
    require(
        workflow,
        'actual_launcher_sha256" != "$expected_launcher_sha256',
        "VPS workflow",
    )
    require(
        workflow,
        'exec "$launcher" "$REQUESTED_SHA" "$REQUESTED_SUITE" "$network_mode"',
        "VPS workflow",
    )

    # The operator-owned launcher accepts only bounded typed input.
    require(launcher, '[[ "$sha" =~ ^[0-9a-f]{40}$ ]]', "VPS launcher")
    require(launcher, "full|focused|analysis|coverage|package", "VPS launcher")
    require(launcher, "online|offline", "VPS launcher")

    # Persistent-host ownership and rootless-container checks.
    require(launcher, '[[ "$(id -u)" -ne 0 ]]', "VPS launcher")
    require(launcher, 'stat -c \'%u\' -- "$self"', "VPS launcher")
    require(launcher, "(8#$mode_bits & 022) == 0", "VPS launcher")
    require(launcher, "Podman must be rootless", "VPS launcher")
    require(launcher, "dependency seed cache must be root-owned", "VPS launcher")
    require(
        launcher,
        "dependency seed cache contains entries writable by the runner account",
        "VPS launcher",
    )

    # Repository-controlled bytes enter only a disposable, constrained container.
    for flag in (
        "--pull=never",
        "--cap-drop=all",
        "--security-opt=no-new-privileges",
        "--read-only",
        "--memory=",
        "--cpus=",
        "--pids-limit=",
        "--network=none",
    ):
        require(launcher, flag, "VPS launcher")
    require(launcher, '$archive:/input/source.tar.gz:ro', "VPS launcher")
    reject(launcher, "bash ./scripts/verify-in-container.sh", "VPS launcher")
    reject(launcher, "podman.sock", "VPS launcher")

    # A networked warm-up may fetch dependencies, but the accepted rerun is offline.
    require(launcher, "run_phase warm yes", "VPS launcher")
    require(launcher, "run_phase verify no", "VPS launcher")
    require(launcher, "acceptedNetworkMode=offline", "VPS launcher")

    # Receipts retain enough identity to audit what was accepted.
    for field in (
        "launcherVersion=%s",
        "sourceSha=%s",
        "sourceArchiveSha256=%s",
        "suite=%s",
        "imageId=%s",
        "imageDigest=%s",
        "limits=memory:%s cpus:%s pids:%s",
    ):
        require(launcher, field, "VPS launcher")

    print("VPS trust-boundary policy checks passed.")


if __name__ == "__main__":
    main()
