#!/usr/bin/env bash
set -euo pipefail

ROOT="${ROOT:-$(pwd)}"
OUT="${1:-$ROOT/target/jfr-sample-coverage}"
DURATION="${JFR_COVERAGE_DURATION_SECONDS:-65}"
PERIOD="${JFR_COVERAGE_SAMPLE_PERIOD_MS:-10}"
WARMUP="${JFR_COVERAGE_WARMUP_MS:-2000}"
SOURCE="$ROOT/scripts/jfr-sample-coverage/ExecutionSampleCoverageProbe.java"
CLASSES="$OUT/classes"

rm -rf "$OUT"
mkdir -p "$CLASSES"

java -version > "$OUT/java-version.txt" 2>&1
{
  echo "host_uname=$(uname -a)"
  echo "host_machine=$(uname -m)"
  echo "java_home=${JAVA_HOME:-}"
  echo "duration_seconds=$DURATION"
  echo "sample_period_ms=$PERIOD"
} > "$OUT/environment.txt"

javac --release 17 -d "$CLASSES" "$SOURCE"

run_policy() {
  local name="$1"
  local periodic_dump="$2"
  local jvm_options="$3"
  local prefix="$OUT/$name"
  local -a command=(java)
  if [[ -n "$jvm_options" ]]; then
    # This script owns these exact diagnostic options; word splitting is intentional.
    # shellcheck disable=SC2206
    local split_options=( $jvm_options )
    command+=("${split_options[@]}")
  fi
  command+=(
    -cp "$CLASSES"
    ExecutionSampleCoverageProbe
    --duration-seconds "$DURATION"
    --sample-period-ms "$PERIOD"
    --periodic-dump-seconds "$periodic_dump"
    --warmup-ms "$WARMUP"
    --policy "$name"
    --output "$prefix.jfr"
    --json-out "$prefix.json"
  )
  printf '%q ' "${command[@]}" | tee "$prefix.command.txt"
  printf '\n' | tee -a "$prefix.command.txt"
  "${command[@]}" | tee "$prefix.stdout.txt"
}

# Diagnostic controls. These isolate default recorder buffers, the large-buffer setting, and dump
# rotation before introducing the game's timestamp flag.
run_policy "single-default-clock-normal" 0 ""
run_policy "single-large-clock-normal" 0 \
  "-XX:FlightRecorderOptions=memorysize=256m,maxchunksize=256m"
run_policy "periodic-default-clock-normal" 60 ""

# Product-policy analogs. Starsector supplies UseFastUnorderedTimeStamps; Preflight either adds the
# large single-chunk recorder options with flush=0, or keeps the ordinary 60-second survivability
# dump policy. The probe preserves those two choices as separate runs.
FAST_CLOCK="-XX:+UnlockExperimentalVMOptions -XX:+UseFastUnorderedTimeStamps"
run_policy "single-large-fast-unordered" 0 \
  "$FAST_CLOCK -XX:FlightRecorderOptions=memorysize=256m,maxchunksize=256m"
run_policy "periodic-default-fast-unordered" 60 "$FAST_CLOCK"

cat \
  "$OUT/single-default-clock-normal.json" \
  "$OUT/single-large-clock-normal.json" \
  "$OUT/periodic-default-clock-normal.json" \
  "$OUT/single-large-fast-unordered.json" \
  "$OUT/periodic-default-fast-unordered.json" > "$OUT/results.ndjson"
