#!/usr/bin/env bash
# The public startup benchmark entry point.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
cd "$ROOT"

case "${1:-}" in
    --campaign)
        shift
        exec env PREFLIGHT_BENCHMARK_CONCISE=false scripts/run-startup-benchmark.sh "$@"
        ;;
    --details)
        shift
        exec scripts/probe-launch.sh "$@"
        ;;
    -h|--help)
        cat <<'EOF'
Usage:
  scripts/benchmark-startup.sh [OPTIONS]
      One automatic --fast launch. Prints its exact main-menu time and stops the game.

  scripts/benchmark-startup.sh --details [OPTIONS]
      One launch plus startup phases and mod callback timings.

  scripts/benchmark-startup.sh --campaign [OPTIONS]
      A repeated, resumable comparison campaign.

One-launch options such as --game, --engine, --cache, and --texture-storage pass through.
Without an explicit storage option, the benchmark follows Preflight's normal lifecycle: Compact
for a learned profile, or Balanced for a new profile's first complete preparation.
EOF
        exit 0
        ;;
esac

exec env PREFLIGHT_BENCHMARK_CONCISE=true \
    scripts/run-startup-benchmark.sh \
    --unattended \
    --conditions fast \
    --rounds 1 \
    --skip-warmup \
    --cooldown-seconds 0 \
    "$@"
