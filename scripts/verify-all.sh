#!/usr/bin/env bash
set -Eeuo pipefail

repository_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd -- "$repository_root"

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "error: required command is missing: $1" >&2
    exit 69
  }
}

phase() {
  printf '\n==> %s\n' "$1"
}

require_command npm
require_command cargo
require_command git

if [[ -x "$repository_root/mvnw" ]]; then
  maven=("$repository_root/mvnw")
else
  require_command mvn
  maven=(mvn)
fi

phase "Java reactor"
"${maven[@]}" --batch-mode --no-transfer-progress verify

phase "Desktop dependencies"
(
  cd preflight-desktop
  npm ci
  npm audit --omit=dev
)

phase "Desktop packaged-engine contract"
(
  cd preflight-desktop
  npm run engine:prepare:verified
  npm run test:release:prepared
)

phase "Desktop frontend"
(
  cd preflight-desktop
  npm test
  npm run build
)

phase "Desktop native host"
(
  cd preflight-desktop
  cargo fmt --check --manifest-path src-tauri/Cargo.toml
  cargo test --locked --manifest-path src-tauri/Cargo.toml
  cargo clippy --locked --manifest-path src-tauri/Cargo.toml --all-targets -- -D warnings
)

phase "Report-intake dependencies"
(
  cd report-intake
  npm ci
)

phase "Report-intake generated bindings"
(
  cd report-intake
  npm run cf-typegen
  git diff --exit-code -- worker-configuration.d.ts
)

phase "Report-intake Worker contract"
(
  cd report-intake
  npm run check
  npm audit --omit=dev
  rm -rf dist
  npx wrangler deploy --dry-run --outdir dist
)

printf '\nRepository-wide verification passed.\n'
