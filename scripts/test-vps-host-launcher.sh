#!/usr/bin/env bash
set -Eeuo pipefail

project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
launcher="$project_root/build/ci/starsector-preflight-ci-v1"
workflow="$project_root/.github/workflows/vps-verify.yml"
test_root="$(mktemp -d)"
trap 'rm -rf -- "$test_root"' EXIT

fail() {
  echo "test-vps-host-launcher: $*" >&2
  exit 1
}

mkdir -p \
  "$test_root/bin" \
  "$test_root/state/jobs" \
  "$test_root/state/maven-seed-v1" \
  "$test_root/repository/scripts"
touch "$test_root/state/maven-seed-v1.lock"

cat >"$test_root/bin/flock" <<'FAKE_FLOCK'
#!/bin/bash
exit 0
FAKE_FLOCK

cat >"$test_root/bin/podman" <<'FAKE_PODMAN'
#!/bin/bash
set -Eeuo pipefail

case " $* " in
  *" info "*)
    printf 'true\n'
    ;;
  *" image exists "*)
    exit 0
    ;;
  *" image inspect "*)
    printf 'sha256:test-image\n'
    ;;
  *" rm --force "*)
    exit 0
    ;;
  *" run --rm "*)
    : >"$PREFLIGHT_FAKE_PODMAN_LOG"
    volume_count=0
    for argument in "$@"; do
      printf '%s\n' "$argument" >>"$PREFLIGHT_FAKE_PODMAN_LOG"
      if [[ "$argument" == *":/input/source.tar:"* ]]; then
        archive="${argument%%:/input/source.tar:*}"
        tar -tf "$archive" >"$PREFLIGHT_FAKE_ARCHIVE_LIST"
      fi
      [[ "$argument" == *":/"* ]] && ((volume_count += 1)) || true
    done
    printf 'volumeCount=%s\n' "$volume_count" >>"$PREFLIGHT_FAKE_PODMAN_LOG"
    ;;
  *)
    echo "unexpected fake Podman invocation: $*" >&2
    exit 70
    ;;
esac
FAKE_PODMAN
chmod 0755 "$test_root/bin/flock" "$test_root/bin/podman"

repo="$test_root/repository"
git -C "$repo" init --quiet
git -C "$repo" config user.name "VPS launcher test"
git -C "$repo" config user.email "vps-launcher-test@example.invalid"
cat >"$repo/pom.xml" <<'POM'
<project><modelVersion>4.0.0</modelVersion><groupId>test</groupId><artifactId>test</artifactId><version>1</version></project>
POM
cat >"$repo/scripts/verify-in-container.sh" <<PAYLOAD
#!/bin/bash
touch "$test_root/host-payload-ran"
PAYLOAD
chmod 0755 "$repo/scripts/verify-in-container.sh"
git -C "$repo" add .
git -C "$repo" commit --quiet -m "malicious revision"
sha="$(git -C "$repo" rev-parse HEAD)"

export PREFLIGHT_CI_TESTING=1
export PREFLIGHT_CI_TEST_ROOT="$test_root"
export PREFLIGHT_FAKE_PODMAN_LOG="$test_root/podman.log"
export PREFLIGHT_FAKE_ARCHIVE_LIST="$test_root/archive.list"

(
  cd "$repo"
  "$launcher" "$sha" full offline-verify
) >"$test_root/receipt"

[[ ! -e "$test_root/host-payload-ran" ]] ||
  fail "repository-controlled host payload executed"
grep -qx 'scripts/verify-in-container.sh' "$test_root/archive.list" ||
  fail "adversarial file was not transferred into the container archive"
grep -qx -- '--network=none' "$test_root/podman.log" ||
  fail "offline verification did not disable networking"
grep -qx -- '--cap-drop=all' "$test_root/podman.log" ||
  fail "container did not drop capabilities"
grep -qx -- '--security-opt=no-new-privileges' "$test_root/podman.log" ||
  fail "container did not disable privilege escalation"
grep -qx -- '--read-only' "$test_root/podman.log" ||
  fail "container root filesystem was not read-only"
grep -q ":/input/source.tar:ro,nosuid,nodev,noexec$" "$test_root/podman.log" ||
  fail "source archive was not mounted read-only"
grep -qx 'volumeCount=3' "$test_root/podman.log" ||
  fail "container received an unexpected host mount"
if grep -Eq 'podman\.sock|/run/user|/\.runner|/\.ssh' "$test_root/podman.log"; then
  fail "container received a control socket or host credential path"
fi
if grep -Fq "$repo:" "$test_root/podman.log"; then
  fail "live repository checkout was mounted into the container"
fi
grep -qx "sourceSha=$sha" "$test_root/receipt" ||
  fail "receipt omitted the exact source SHA"
grep -qx 'network=none' "$test_root/receipt" ||
  fail "receipt omitted the offline network mode"
grep -qx 'limits=memory:768m cpus:0.85 pids:512' "$test_root/receipt" ||
  fail "receipt omitted resource limits"

cache_mount="$(grep ':/maven-cache:rw,nosuid,nodev$' "$test_root/podman.log" | head -n 1)"
cache_path="${cache_mount%%:/maven-cache:*}"
[[ ! -e "$cache_path" ]] ||
  fail "per-run Maven cache survived launcher cleanup"

(
  cd "$repo"
  "$launcher" "$sha" focused online-verify
) >"$test_root/online-receipt"
grep -qx -- '--network=slirp4netns' "$test_root/podman.log" ||
  fail "online verification did not select the bounded rootless network"
if grep -qx -- '--network=none' "$test_root/podman.log"; then
  fail "online verification retained the offline network setting"
fi
grep -qx 'network=slirp4netns' "$test_root/online-receipt" ||
  fail "receipt omitted the online network mode"

podman_log_before="$(wc -c <"$test_root/podman.log")"
if (
  cd "$repo"
  "$launcher" "${sha%?}x" full offline-verify
) >/dev/null 2>&1; then
  fail "launcher accepted a non-hexadecimal commit SHA"
fi
if (
  cd "$repo"
  "$launcher" "$sha" arbitrary offline-verify
) >/dev/null 2>&1; then
  fail "launcher accepted an unknown suite"
fi
if (
  cd "$repo"
  "$launcher" "$sha" full host-network
) >/dev/null 2>&1; then
  fail "launcher accepted an unknown network mode"
fi
if (
  cd "$repo"
  "$launcher" '0000000000000000000000000000000000000000' full offline-verify
) >/dev/null 2>&1; then
  fail "launcher accepted a commit other than the checkout HEAD"
fi
[[ "$(wc -c <"$test_root/podman.log")" == "$podman_log_before" ]] ||
  fail "invalid typed input reached Podman"

expected_digest="$(awk '/EXPECTED_LAUNCHER_SHA256:/ {print $2; exit}' "$workflow")"
if command -v sha256sum >/dev/null 2>&1; then
  actual_digest="$(sha256sum "$launcher" | cut -d' ' -f1)"
else
  actual_digest="$(shasum -a 256 "$launcher" | cut -d' ' -f1)"
fi
[[ "$expected_digest" == "$actual_digest" ]] ||
  fail "workflow launcher digest is stale: expected $actual_digest, found $expected_digest"

launcher_check_line="$(grep -n 'name: Verify host-owned launcher' "$workflow" | cut -d: -f1)"
checkout_line="$(grep -n 'uses: actions/checkout@' "$workflow" | cut -d: -f1)"
(( launcher_check_line < checkout_line )) ||
  fail "workflow checks out repository content before validating the host launcher"
if grep -q 'bash ./scripts/verify-in-container.sh' "$workflow"; then
  fail "workflow still executes a repository-controlled host script"
fi

echo "VPS host launcher adversarial checks passed."
