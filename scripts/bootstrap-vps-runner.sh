#!/usr/bin/env bash
set -Eeuo pipefail

runner_user="preflight-runner"
runner_name="$(hostname -s)-starsector-preflight"
swap_gib=0
repository_url=""
runner_download_url=""
runner_sha256=""

usage() {
  cat <<'USAGE'
Prepare a Debian/Ubuntu VPS and register a repository-level GitHub Actions runner.

Prepare the host, install the reviewed root-owned launcher, and build the local test image:
  sudo bash scripts/bootstrap-vps-runner.sh prepare [--runner-user USER] [--swap-gib N]

Register the runner after GitHub provides a temporary registration token:
  sudo bash scripts/bootstrap-vps-runner.sh register \
    --repository-url https://github.com/OWNER/REPOSITORY \
    --runner-download-url https://github.com/actions/runner/releases/download/...tar.gz \
    --runner-sha256 HEX_SHA256 \
    [--runner-user USER] [--runner-name NAME]

The download URL, checksum, and temporary token come from:
Repository Settings -> Actions -> Runners -> New self-hosted runner.
USAGE
}

fail() {
  echo "error: $*" >&2
  exit 1
}

require_root() {
  [[ "${EUID:-$(id -u)}" -eq 0 ]] || fail "run this command as root (for example with sudo)"
}

repo_root() {
  local script_dir
  script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
  cd -- "$script_dir/.." && pwd -P
}

runner_home() {
  getent passwd "$runner_user" | cut -d: -f6
}

run_as_runner() {
  local home uid runtime_dir
  home="$(runner_home)"
  uid="$(id -u "$runner_user")"
  runtime_dir="/run/user/$uid"
  install -d -m 0700 -o "$runner_user" -g "$runner_user" "$runtime_dir"
  runuser -u "$runner_user" -- env \
    HOME="$home" \
    USER="$runner_user" \
    LOGNAME="$runner_user" \
    XDG_RUNTIME_DIR="$runtime_dir" \
    "$@"
}

ensure_subid() {
  local file flag start end
  file="$1"
  flag="$2"
  grep -qE "^${runner_user}:" "$file" && return 0
  start="$(awk -F: '
    BEGIN { max = 99999 }
    NF >= 3 { candidate = $2 + $3; if (candidate > max) max = candidate }
    END { block = 65536; print int((max + block) / block) * block }
  ' "$file")"
  end=$((start + 65535))
  usermod "$flag" "$start-$end" "$runner_user"
}

create_swap() {
  local size_bytes
  (( swap_gib > 0 )) || return 0
  if swapon --show=NAME --noheadings | grep -q .; then
    echo "Swap already exists; leaving it unchanged."
    return 0
  fi
  [[ ! -e /swapfile ]] || fail "/swapfile already exists but is not active"
  size_bytes=$((swap_gib * 1024 * 1024 * 1024))
  if ! fallocate -l "$size_bytes" /swapfile; then
    dd if=/dev/zero of=/swapfile bs=1M count=$((swap_gib * 1024)) status=progress
  fi
  chmod 0600 /swapfile
  mkswap /swapfile >/dev/null
  swapon /swapfile
  grep -qE '^/swapfile\s' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
}

prepare_host() {
  require_root
  [[ -r /etc/os-release ]] || fail "cannot identify the operating system"
  # shellcheck disable=SC1091
  source /etc/os-release
  case "${ID:-}" in
    debian|ubuntu) ;;
    *) fail "this bootstrap currently supports Debian and Ubuntu, found ${ID:-unknown}" ;;
  esac

  export DEBIAN_FRONTEND=noninteractive
  apt-get update
  optional_packages=()
  apt-cache show passt >/dev/null 2>&1 && optional_packages+=(passt)
  apt-get install -y --no-install-recommends \
    ca-certificates curl git jq podman uidmap slirp4netns fuse-overlayfs \
    "${optional_packages[@]}"

  if ! id "$runner_user" >/dev/null 2>&1; then
    useradd --create-home --shell /bin/bash "$runner_user"
    passwd --lock "$runner_user" >/dev/null
  fi

  ensure_subid /etc/subuid --add-subuids
  ensure_subid /etc/subgid --add-subgids
  create_swap

  local home root build_context launcher_source launcher_path launcher_sha256
  home="$(runner_home)"
  root="$(repo_root)"
  build_context="$home/preflight-build-image"
  launcher_source="$root/build/ci/starsector-preflight-ci-v1"
  launcher_path="/usr/local/libexec/starsector-preflight-ci-v1"

  [[ -f "$launcher_source" ]] || fail "missing reviewed launcher source: $launcher_source"
  install -d -m 0750 -o "$runner_user" -g "$runner_user" \
    "$home/actions-runner" \
    "$build_context"
  install -d -m 0755 -o root -g root \
    /usr/local/libexec \
    /var/lib/starsector-preflight-ci
  install -m 0644 -o "$runner_user" -g "$runner_user" \
    "$root/build/ci/Containerfile" "$build_context/Containerfile"
  install -m 0755 -o root -g root \
    "$launcher_source" "$launcher_path"
  launcher_sha256="$(sha256sum "$launcher_path" | awk '{print $1}')"

  if command -v loginctl >/dev/null 2>&1; then
    loginctl enable-linger "$runner_user" || true
  fi

  if [[ "$(stat -fc %T /sys/fs/cgroup 2>/dev/null || true)" != "cgroup2fs" ]]; then
    echo "warning: cgroups v2 was not detected; rootless CPU/memory limits may be unavailable." >&2
  fi

  run_as_runner podman build --pull=always \
    --tag localhost/starsector-preflight-build:1 \
    --file "$build_context/Containerfile" \
    "$build_context"
  run_as_runner podman image inspect \
    --format 'installed build image: {{.Id}}' \
    localhost/starsector-preflight-build:1

  cat <<EOF_SUMMARY

Host preparation complete.
Runner user: $runner_user
Runner home: $home
Operator launcher: $launcher_path
Launcher SHA-256: $launcher_sha256

The launcher is root-owned and is not modified by Actions jobs. Re-run `prepare`
from a reviewed revision when intentionally updating the launcher or build image.

Next, open the repository runner setup page and run the register subcommand with
its exact download URL, SHA-256 checksum, and temporary registration token.
EOF_SUMMARY
}

register_runner() {
  require_root
  [[ -n "$repository_url" ]] || fail "--repository-url is required"
  [[ "$repository_url" == https://github.com/*/* ]] || fail "--repository-url must be a GitHub repository URL"
  [[ -n "$runner_download_url" ]] || fail "--runner-download-url is required"
  [[ "$runner_download_url" == https://github.com/actions/runner/releases/download/* ]] || \
    fail "runner download URL must point to the official actions/runner release"
  [[ "$runner_sha256" =~ ^[0-9a-fA-F]{64}$ ]] || fail "--runner-sha256 must contain 64 hexadecimal characters"
  id "$runner_user" >/dev/null 2>&1 || fail "runner user does not exist; run prepare first"

  local token="${RUNNER_REGISTRATION_TOKEN:-}"
  if [[ -z "$token" && -t 0 ]]; then
    read -r -s -p 'Temporary runner registration token: ' token
    echo
  fi
  [[ -n "$token" ]] || fail "set RUNNER_REGISTRATION_TOKEN or enter it interactively"

  local home runner_dir archive
  home="$(runner_home)"
  runner_dir="$home/actions-runner"
  [[ ! -e "$runner_dir/.runner" ]] || fail "a runner is already configured in $runner_dir"
  if find "$runner_dir" -mindepth 1 -maxdepth 1 -print -quit | grep -q .; then
    fail "$runner_dir is not empty; remove the partial installation before registering"
  fi
  archive="$(mktemp --suffix=.tar.gz)"
  trap 'rm -f "$archive"' RETURN

  curl --fail --location --proto '=https' --tlsv1.2 \
    "$runner_download_url" --output "$archive"
  printf '%s  %s\n' "${runner_sha256,,}" "$archive" | sha256sum --check --status || \
    fail "runner archive checksum did not match"
  tar --extract --gzip --file "$archive" --directory "$runner_dir"
  chown -R "$runner_user:$runner_user" "$runner_dir"

  if [[ -x "$runner_dir/bin/installdependencies.sh" ]]; then
    "$runner_dir/bin/installdependencies.sh"
  fi

  run_as_runner "$runner_dir/config.sh" \
    --unattended \
    --url "$repository_url" \
    --token "$token" \
    --name "$runner_name" \
    --labels starsector-preflight \
    --work _work \
    --replace

  (
    cd -- "$runner_dir"
    ./svc.sh install "$runner_user"
    ./svc.sh start
  )

  unset token RUNNER_REGISTRATION_TOKEN
  echo "Runner '$runner_name' registered and started."
}

[[ $# -gt 0 ]] || { usage; exit 64; }
command_name="$1"
shift

while [[ $# -gt 0 ]]; do
  case "$1" in
    --runner-user) runner_user="${2:?missing value for --runner-user}"; shift 2 ;;
    --runner-name) runner_name="${2:?missing value for --runner-name}"; shift 2 ;;
    --swap-gib) swap_gib="${2:?missing value for --swap-gib}"; shift 2 ;;
    --repository-url) repository_url="${2:?missing value for --repository-url}"; shift 2 ;;
    --runner-download-url) runner_download_url="${2:?missing value for --runner-download-url}"; shift 2 ;;
    --runner-sha256) runner_sha256="${2:?missing value for --runner-sha256}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) fail "unknown argument: $1" ;;
  esac
done

[[ "$runner_user" =~ ^[a-z_][a-z0-9_-]*$ ]] || fail "invalid runner user: $runner_user"
[[ "$swap_gib" =~ ^[0-9]+$ ]] || fail "--swap-gib must be a non-negative integer"

case "$command_name" in
  prepare) prepare_host ;;
  register) register_runner ;;
  help|-h|--help) usage ;;
  *) fail "unknown subcommand: $command_name" ;;
esac
