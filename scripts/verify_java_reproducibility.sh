#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
work_root="$(mktemp -d)"
trap 'rm -rf "$work_root"' EXIT

first="$work_root/first"
second="$work_root/second"
mkdir -p "$first" "$second" "$work_root/m2"

git -C "$repo_root" archive HEAD | tar -x -C "$first"
git -C "$repo_root" archive HEAD | tar -x -C "$second"

build_workspace() {
  local workspace="$1"
  (
    cd "$workspace"
    MAVEN_USER_HOME="$work_root/m2" ./mvnw \
      --batch-mode \
      --no-transfer-progress \
      -DskipTests \
      package
  )
}

build_workspace "$first"
build_workspace "$second"

artifacts=(
  "preflight-core/target/preflight-core-0.1.0.jar"
  "preflight-agent/target/preflight-agent-0.1.0.jar"
  "preflight-cli/target/preflight.jar"
)

failed=0
for artifact in "${artifacts[@]}"; do
  left="$first/$artifact"
  right="$second/$artifact"
  if [[ ! -f "$left" || ! -f "$right" ]]; then
    echo "reproducibility: missing expected artifact $artifact" >&2
    failed=1
    continue
  fi

  left_sha="$(sha256sum "$left" | awk '{print $1}')"
  right_sha="$(sha256sum "$right" | awk '{print $1}')"
  printf '%s  %s\n' "$left_sha" "$artifact"

  if [[ "$left_sha" != "$right_sha" ]]; then
    echo "reproducibility: byte mismatch for $artifact" >&2
    echo "  first:  $left_sha" >&2
    echo "  second: $right_sha" >&2
    failed=1
  fi
done

if (( failed != 0 )); then
  exit 1
fi

echo "Java release artifacts are byte-for-byte reproducible across two clean workspaces."
