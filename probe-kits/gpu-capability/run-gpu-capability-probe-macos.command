#!/bin/bash
# Builds and runs the read-only GPU texture-capability probe, writing a timestamped report
# next to this script. Starsector is not launched and nothing on disk is modified.
set -u
set -o pipefail

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$HERE" || exit 2

SOURCE="$HERE/gl-capability-probe.c"
STAMP=$(date +%Y%m%d-%H%M%S)
BINARY="$HERE/gl-capability-probe"
REPORT="$HERE/gpu-capability-report-$STAMP.txt"

if [ ! -f "$SOURCE" ]; then
    printf '%s\n' "Missing probe source: $SOURCE"
    exit 2
fi
if ! command -v clang >/dev/null 2>&1; then
    printf '%s\n' "clang was not found. Install the Xcode Command Line Tools with:"
    printf '%s\n' "  xcode-select --install"
    exit 2
fi

# OpenGL is deprecated on macOS but still present, and it is what Starsector's LWJGL 2
# context uses, so the deprecation warnings are expected and silenced.
if ! clang -o "$BINARY" "$SOURCE" -framework OpenGL -Wno-deprecated-declarations; then
    printf '%s\n' "Probe failed to build."
    exit 2
fi

{
    printf '%s\n' "Preflight — GPU texture capability probe"
    printf '%s\n' "captured: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf '%s\n' "macOS:    $(sw_vers -productVersion) ($(sw_vers -buildVersion))"
    printf '%s\n' "CPU:      $(sysctl -n machdep.cpu.brand_string)"
    printf '%s\n' "GPU:      $(system_profiler SPDisplaysDataType 2>/dev/null \
        | awk -F': ' '/Chipset Model/ {print $2; exit}')"
    printf '\n'
    "$BINARY"
} | tee "$REPORT"

printf '\n%s\n' "Report written to: $REPORT"
