#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: $0 OUTPUT_DIRECTORY SBOM_DIRECTORY" >&2
  exit 2
fi

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
repository_root="$(cd -- "$script_directory/.." && pwd -P)"
output_directory="$1"
sbom_directory="$2"

mkdir -p -- "$output_directory"
output_directory="$(cd -- "$output_directory" && pwd -P)"
sbom_directory="$(cd -- "$sbom_directory" && pwd -P)"

if [[ -n "$(find "$output_directory" -mindepth 1 -maxdepth 1 -print -quit)" ]]; then
  echo "release output directory must be empty: $output_directory" >&2
  exit 1
fi

sbom_files=(
  preflight-java.cdx.json
  preflight-desktop-web.cdx.json
  preflight-desktop-native-aarch64-apple-darwin.cdx.json
  preflight-desktop-native-x86_64-pc-windows-msvc.cdx.json
  preflight-desktop-native-x86_64-unknown-linux-gnu.cdx.json
)

required_sources=(
  "$repository_root/preflight-cli/target/preflight.jar"
  "$repository_root/LICENSE"
  "$repository_root/THIRD_PARTY_NOTICES.md"
  "$repository_root/docs/privacy.md"
  "$repository_root/docs/known-limitations.md"
  "$repository_root/docs/dependency-inventory.md"
  "$sbom_directory/SBOM-SHA256SUMS.txt"
)
for sbom_file in "${sbom_files[@]}"; do
  required_sources+=("$sbom_directory/$sbom_file")
done
for source_file in "${required_sources[@]}"; do
  if [[ ! -f "$source_file" || -L "$source_file" ]]; then
    echo "required regular release input is missing: $source_file" >&2
    exit 1
  fi
done

cp -- "$repository_root/preflight-cli/target/preflight.jar" "$output_directory/preflight.jar"
cp -- "$repository_root/LICENSE" "$output_directory/LICENSE"
cp -- "$repository_root/THIRD_PARTY_NOTICES.md" "$output_directory/THIRD_PARTY_NOTICES.md"
cp -- "$repository_root/docs/privacy.md" "$output_directory/PRIVACY.md"
cp -- "$repository_root/docs/known-limitations.md" "$output_directory/KNOWN_LIMITATIONS.md"
cp -- "$repository_root/docs/dependency-inventory.md" "$output_directory/DEPENDENCY_INVENTORY.md"
cp -- "$sbom_directory/SBOM-SHA256SUMS.txt" "$output_directory/SBOM-SHA256SUMS.txt"
for sbom_file in "${sbom_files[@]}"; do
  cp -- "$sbom_directory/$sbom_file" "$output_directory/$sbom_file"
done

cat > "$output_directory/README.txt" <<'TEXT'
Preflight
=========

Requires Java 17 or newer.

Discover and launch Starsector:
    java -jar preflight.jar run

Check discovery without launching:
    java -jar preflight.jar doctor

Install a convenient platform launcher:
    java -jar preflight.jar install

Verify this download on macOS or Linux:
    shasum -a 256 -c preflight.jar.sha256

Verify on Windows PowerShell:
    (Get-FileHash .\preflight.jar -Algorithm SHA256).Hash.ToLower()

Project documentation:
    https://github.com/teamleaderleo/preflight

This archive also contains the MIT license, third-party notices,
privacy statement, known limitations, and release dependency inventories.
TEXT

checksum_command=(sha256sum)
if ! command -v sha256sum >/dev/null 2>&1; then
  checksum_command=(shasum -a 256)
fi

core_files=(
  preflight.jar
  preflight.jar.sha256
  README.txt
  LICENSE
  THIRD_PARTY_NOTICES.md
  PRIVACY.md
  KNOWN_LIMITATIONS.md
  DEPENDENCY_INVENTORY.md
  SBOM-SHA256SUMS.txt
  "${sbom_files[@]}"
)

(
  cd -- "$output_directory"
  "${checksum_command[@]}" preflight.jar > preflight.jar.sha256
  COPYFILE_DISABLE=1 tar -czf preflight.tar.gz "${core_files[@]}"
  zip -q preflight.zip "${core_files[@]}"
  "${checksum_command[@]}" preflight.tar.gz preflight.zip > archives.sha256
)

python3 "$script_directory/verify_release_boundary.py" --dist "$output_directory"
