#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github" / "workflows" / "vps-verify.yml"
LAUNCHER = ROOT / "build" / "ci" / "starsector-preflight-ci-v1"
BOOTSTRAP = ROOT / "scripts" / "bootstrap-vps-runner.sh"


def require(text: str, needle: str, source: Path) -> None:
    if needle not in text:
        raise AssertionError(f"{source}: required trust-boundary marker missing: {needle!r}")


def forbid(text: str, needle: str, source: Path) -> None:
    if needle in text:
        raise AssertionError(f"{source}: forbidden host-side trust-boundary marker present: {needle!r}")


def main() -> None:
    workflow = WORKFLOW.read_text(encoding="utf-8")
    launcher = LAUNCHER.read_text(encoding="utf-8")
    bootstrap = BOOTSTRAP.read_text(encoding="utf-8")

    # The persistent runner may execute only the fixed operator-owned launcher.
    require(workflow, "/usr/local/libexec/starsector-preflight-ci-v1", WORKFLOW)
    require(workflow, "^[0-9a-f]{40}$", WORKFLOW)
    require(workflow, 'exec "$launcher" "$REQUESTED_SHA" "$REQUESTED_SUITE" "$network_mode"', WORKFLOW)
    forbid(workflow, "actions/checkout@", WORKFLOW)
    forbid(workflow, "scripts/verify-in-container.sh", WORKFLOW)
    forbid(workflow, "./scripts/", WORKFLOW)

    # The operator-owned launcher validates its own host boundary and only treats
    # the selected revision as a read-only archive until execution is in Podman.
    require(launcher, '[[ "$(stat -c \'%u\' -- "$self")" == "0" ]]', LAUNCHER)
    require(launcher, "launcher must not be writable by group or others", LAUNCHER)
    require(launcher, '[[ "$sha" =~ ^[0-9a-f]{40}$ ]]', LAUNCHER)
    require(launcher, '--volume "$archive:/input/source.tar.gz:ro"', LAUNCHER)
    require(launcher, '--volume "$workspace:/workspace:rw"', LAUNCHER)
    require(launcher, '--volume "$run_cache:/maven-cache:rw"', LAUNCHER)
    require(launcher, '--cap-drop=all', LAUNCHER)
    require(launcher, '--security-opt=no-new-privileges', LAUNCHER)
    require(launcher, '--read-only', LAUNCHER)
    require(launcher, '--network=none', LAUNCHER)
    require(launcher, 'acceptedNetworkMode=offline', LAUNCHER)
    require(launcher, 'run_phase warm yes', LAUNCHER)
    require(launcher, 'run_phase verify no', LAUNCHER)
    require(launcher, 'seed_args+=(--volume "$seed_cache:/maven-seed:ro")', LAUNCHER)
    require(launcher, 'rm -rf /workspace/src', LAUNCHER)
    require(launcher, 'rm -rf -- "$run_root"', LAUNCHER)
    forbid(launcher, '$repo_root:/workspace', LAUNCHER)
    forbid(launcher, '$HOME/.cache/starsector-preflight/m2', LAUNCHER)

    # Provisioning must install the reviewed launcher as root-owned executable
    # material. Actions never perform this installation step.
    require(bootstrap, 'launcher_path="/usr/local/libexec/starsector-preflight-ci-v1"', BOOTSTRAP)
    require(bootstrap, 'install -m 0755 -o root -g root', BOOTSTRAP)
    require(bootstrap, '"$launcher_source" "$launcher_path"', BOOTSTRAP)
    forbid(bootstrap, '"$home/.cache/starsector-preflight/m2"', BOOTSTRAP)

    print("VPS runner trust-boundary contract OK")


if __name__ == "__main__":
    main()
