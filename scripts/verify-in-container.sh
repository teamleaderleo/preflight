#!/usr/bin/env bash
set -Eeuo pipefail

usage() {
  cat <<'USAGE'
Usage: scripts/verify-in-container.sh [full|focused|analysis|coverage|package]

Environment overrides:
  PREFLIGHT_BUILD_IMAGE             Prebuilt local image (default: localhost/starsector-preflight-build:1)
  PREFLIGHT_CONTAINER_MEMORY        Container memory limit (default: 768m)
  PREFLIGHT_CONTAINER_CPUS          Container CPU limit (default: 0.85)
  PREFLIGHT_CONTAINER_PIDS          Container PID limit (default: 512)
  PREFLIGHT_MAVEN_CACHE             Host Maven cache directory
  PREFLIGHT_OFFLINE                 Set to 1 to disable container networking
  PREFLIGHT_PODMAN_CGROUP_MANAGER   Podman cgroup manager (default: cgroupfs)
  MAVEN_OPTS                        Maven JVM options (default sized for a 1 GiB VPS)
USAGE
}

suite="${1:-full}"
case "$suite" in
  full|focused|analysis|coverage|package) ;;
  -h|--help) usage; exit 0 ;;
  *)
    echo "Unsupported verification suite: $suite" >&2
    usage >&2
    exit 64
    ;;
esac

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
repo_root="$(cd -- "$script_dir/.." && pwd -P)"
image="${PREFLIGHT_BUILD_IMAGE:-localhost/starsector-preflight-build:1}"
memory="${PREFLIGHT_CONTAINER_MEMORY:-768m}"
cpus="${PREFLIGHT_CONTAINER_CPUS:-0.85}"
pids="${PREFLIGHT_CONTAINER_PIDS:-512}"
cache_dir="${PREFLIGHT_MAVEN_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/starsector-preflight/m2}"
offline="${PREFLIGHT_OFFLINE:-0}"
cgroup_manager="${PREFLIGHT_PODMAN_CGROUP_MANAGER:-cgroupfs}"
maven_opts="${MAVEN_OPTS:--Xmx320m -XX:MaxMetaspaceSize=160m -Djava.io.tmpdir=/tmp}"

case "$cgroup_manager" in
  cgroupfs|systemd) ;;
  *)
    echo "PREFLIGHT_PODMAN_CGROUP_MANAGER must be cgroupfs or systemd, got: $cgroup_manager" >&2
    exit 64
    ;;
esac

command -v podman >/dev/null 2>&1 || {
  echo "Podman is not installed." >&2
  exit 69
}

export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}"
export DBUS_SESSION_BUS_ADDRESS="${DBUS_SESSION_BUS_ADDRESS:-unix:path=$XDG_RUNTIME_DIR/bus}"
if [[ ! -d "$XDG_RUNTIME_DIR" ]]; then
  echo "Missing rootless Podman runtime directory: $XDG_RUNTIME_DIR" >&2
  echo "Start a rootless Podman session for this user." >&2
  exit 69
fi
podman image exists "$image" || {
  echo "Build image '$image' is not installed." >&2
  echo "Build it with: podman build --tag $image --file build/ci/Containerfile build/ci" >&2
  exit 69
}

mkdir -p -- "$cache_dir"

maven=(mvn --batch-mode --no-transfer-progress)
case "$suite" in
  full)
    maven+=(verify)
    ;;
  focused)
    maven+=(-pl preflight-agent,preflight-cli -am verify)
    ;;
  analysis)
    maven+=(-Panalysis verify)
    ;;
  coverage)
    maven+=(-Pcoverage verify)
    ;;
  package)
    maven+=(-DskipTests package)
    ;;
esac

podman_runtime=(podman --cgroup-manager="$cgroup_manager")
container_name="starsector-preflight-${suite}-$$"
network_args=()
if [[ "$offline" == "1" ]]; then
  network_args+=(--network=none)
elif [[ "$offline" != "0" ]]; then
  echo "PREFLIGHT_OFFLINE must be 0 or 1, got: $offline" >&2
  exit 64
fi

cleanup() {
  "${podman_runtime[@]}" rm --force "$container_name" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

image_id="$(podman image inspect --format '{{.Id}}' "$image" 2>/dev/null || printf 'unknown')"
podman_json="$(podman info --format json 2>/dev/null || true)"
if [[ -n "$podman_json" ]]; then
  podman_host="$(printf 'rootless=%s cgroupVersion=%s defaultCgroupManager=%s selectedCgroupManager=%s' \
    "$(jq -r '.host.security.rootless // "unknown"' <<<"$podman_json")" \
    "$(jq -r '.host.cgroupVersion // "unknown"' <<<"$podman_json")" \
    "$(jq -r '.host.cgroupManager // "unknown"' <<<"$podman_json")" \
    "$cgroup_manager")"
else
  podman_host="unavailable selectedCgroupManager=$cgroup_manager"
fi
printf 'suite=%s\nimage=%s\nimageId=%s\npodman=%s\nlimits=memory:%s cpus:%s pids:%s\noffline=%s\n' \
  "$suite" "$image" "$image_id" "$podman_host" "$memory" "$cpus" "$pids" "$offline"

set +e
"${podman_runtime[@]}" run --rm \
  --name "$container_name" \
  --pull=never \
  --userns=keep-id \
  --cap-drop=all \
  --security-opt=no-new-privileges \
  --memory="$memory" \
  --cpus="$cpus" \
  --pids-limit="$pids" \
  --tmpfs /tmp:rw,nosuid,nodev,size=192m \
  "${network_args[@]}" \
  --env "HOME=/tmp/home" \
  --env "MAVEN_CONFIG=/maven-cache" \
  --env "MAVEN_OPTS=$maven_opts" \
  --volume "$repo_root:/workspace:rw" \
  --volume "$cache_dir:/maven-cache:rw" \
  --workdir /workspace \
  "$image" \
  "${maven[@]}"
status=$?
set -e

if [[ "$status" -eq 125 || "$status" -eq 126 ]]; then
  cat >&2 <<'MESSAGE'
Podman or the OCI runtime failed before Maven completed. Confirm that rootless
Podman works for the current user and that the selected cgroup manager can
apply memory, CPU, and PID limits, then retry.
MESSAGE
fi
exit "$status"
