#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 OUTPUT_DIRECTORY" >&2
  exit 2
fi

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
repository_root="$(cd -- "$script_directory/.." && pwd -P)"
output_directory="$1"
mkdir -p -- "$output_directory"
output_directory="$(cd -- "$output_directory" && pwd -P)"

source_date_epoch="${SOURCE_DATE_EPOCH:-$(git -C "$repository_root" show -s --format=%ct HEAD)}"
export SOURCE_DATE_EPOCH="$source_date_epoch"

mvn -f "$repository_root/pom.xml" --batch-mode --no-transfer-progress \
  org.cyclonedx:cyclonedx-maven-plugin:2.9.2:makeAggregateBom \
  -DoutputFormat=json \
  -DoutputName=preflight-java.cdx \
  -DoutputDirectory="$output_directory" \
  -DoutputReactorProjects=false \
  -Dcyclonedx.skipAttach=true \
  -DexcludeArtifactId=preflight-synthetic-startup \
  -Dproject.build.outputTimestamp="$source_date_epoch"

npm exec --yes --package=@cyclonedx/cyclonedx-npm@4.2.1 -- \
  cyclonedx-npm \
  --package-lock-only \
  --omit dev \
  --output-reproducible \
  --output-format JSON \
  --output-file "$output_directory/preflight-desktop-web.cdx.json" \
  "$repository_root/preflight-desktop/package.json"

native_source_directory="$repository_root/preflight-desktop/src-tauri"
native_targets=(
  aarch64-apple-darwin
  x86_64-pc-windows-msvc
  x86_64-unknown-linux-gnu
)

generated_native_files=()
cleanup_generated_native_files() {
  local generated_file
  for generated_file in "${generated_native_files[@]}"; do
    if [[ -f "$generated_file" && "$generated_file" == "$native_source_directory"/* ]]; then
      rm -f -- "$generated_file"
    fi
  done
}
trap cleanup_generated_native_files EXIT

for target in "${native_targets[@]}"; do
  output_name="preflight-desktop-native-$target.cdx"
  generated_file="$native_source_directory/$output_name.json"
  generated_native_files+=("$generated_file")
  cargo cyclonedx \
    --manifest-path "$native_source_directory/Cargo.toml" \
    --format json \
    --spec-version 1.5 \
    --target "$target" \
    --override-filename "$output_name"
  mv -- "$generated_file" "$output_directory/$output_name.json"
done

sbom_files=(
  preflight-java.cdx.json
  preflight-desktop-web.cdx.json
  preflight-desktop-native-aarch64-apple-darwin.cdx.json
  preflight-desktop-native-x86_64-pc-windows-msvc.cdx.json
  preflight-desktop-native-x86_64-unknown-linux-gnu.cdx.json
)

for sbom_file in "${sbom_files[@]}"; do
  jq -e '.bomFormat == "CycloneDX" and (.components | length > 0)' \
    "$output_directory/$sbom_file" >/dev/null
done

(
  cd -- "$output_directory"
  sha256sum "${sbom_files[@]}" > SBOM-SHA256SUMS.txt
)
