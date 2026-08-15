#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
seconds="${1:-12}"
padding_events_per_second="${2:-1500}"
recording="${3:-$repo_root/build/jfr-chunk-timestamp-reproducer.jfr}"
parts_dir="${recording%.jfr}-chunks"
classes="$repo_root/preflight-synthetic-startup/target/classes"
main_package="dev.starsector.preflight.synthetic"

mkdir -p "$(dirname "$recording")"
rm -f "$recording"
rm -rf "$parts_dir"

"$repo_root/mvnw" --batch-mode --no-transfer-progress \
  -pl preflight-synthetic-startup -am \
  -DskipTests \
  compile

java \
  -XX:FlightRecorderOptions:maxchunksize=12m \
  -cp "$classes" \
  "$main_package.JfrChunkTimestampReproducer" \
  "$recording" \
  "$seconds" \
  "$padding_events_per_second"

[[ -s "$recording" ]] || {
  echo "reproducer: dump-on-exit did not create $recording" >&2
  exit 1
}

echo
echo "== Full recording summary =="
jfr summary "$recording"

echo
echo "== Full recording marker timeline =="
java -cp "$classes" "$main_package.JfrChunkTimestampAnalyzer" "$recording"

mkdir -p "$parts_dir"
jfr disassemble --max-chunks 1 --output "$parts_dir" "$recording"

mapfile -t chunks < <(find "$parts_dir" -maxdepth 1 -type f -name '*.jfr' -print | sort)
echo
echo "Disassembled ${#chunks[@]} chunk file(s) under $parts_dir"
for chunk in "${chunks[@]}"; do
  echo
  echo "== $(basename "$chunk") marker timeline =="
  java -cp "$classes" "$main_package.JfrChunkTimestampAnalyzer" "$chunk" || true
done

echo
echo "Compare the full-file marker deltas with the one-chunk files above."
echo "A large full-file error that disappears in the split chunks reproduces the timestamp-folding symptom."
