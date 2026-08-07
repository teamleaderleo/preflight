#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
  echo "Usage: scripts/download-private-candidate.sh RUN_ID [destination]" >&2
  exit 2
fi

run_id="$1"
[[ "$run_id" =~ ^[0-9]+$ ]] || { echo "RUN_ID must be numeric" >&2; exit 2; }
repository_root="$(cd "$(dirname "$0")/.." && pwd -P)"
destination="${2:-$PWD/preflight-private-candidate-$run_id}"
mkdir "$destination"
destination="$(cd "$destination" && pwd -P)"

if [[ -z "${PREFLIGHT_CANDIDATE_ARCHIVE_PASSWORD:-}" ]]; then
  if [[ "$(uname -s)" != "Darwin" ]]; then
    echo "Set PREFLIGHT_CANDIDATE_ARCHIVE_PASSWORD before downloading a private candidate." >&2
    exit 2
  fi
  PREFLIGHT_CANDIDATE_ARCHIVE_PASSWORD="$(security find-generic-password \
    -a "$USER" -s dev.starsector.preflight.candidate-artifact -w)"
  export PREFLIGHT_CANDIDATE_ARCHIVE_PASSWORD
fi

encrypted_directory="$(mktemp -d "${TMPDIR:-/tmp}/preflight-candidate.XXXXXX")"
trap 'rm -rf -- "$encrypted_directory"' EXIT

gh run download "$run_id" \
  --repo teamleaderleo/preflight \
  --name "preflight-private-signed-candidate-$run_id" \
  --dir "$encrypted_directory"

count=0
while IFS= read -r -d '' file; do
  name="$(basename "$file" .pfcandidate)"
  node "$repository_root/preflight-desktop/scripts/candidate-crypt.mjs" \
    decrypt "$file" "$destination/$name"
  count=$((count + 1))
done < <(find "$encrypted_directory" -type f -name '*.pfcandidate' -print0)
test "$count" -gt 0 || { echo "No encrypted candidate files were downloaded" >&2; exit 1; }

if [[ -f "$destination/candidate-latest.json.sha256" ]]; then
  (cd "$destination" && shasum -a 256 -c candidate-latest.json.sha256)
fi
echo "Decrypted $count authenticated candidate files into $destination"
