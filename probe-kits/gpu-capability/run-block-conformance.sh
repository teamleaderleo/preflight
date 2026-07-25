#!/bin/bash
# Generates the S3TC conformance vector and checks it against this machine's GPU driver.
#
# Unlike run-gpu-capability-probe.sh this needs no Starsector installation and no LWJGL: the vector
# is written by a plain Java program with no GPU involved, and checked by a small C program with no
# JVM involved. That split is what lets the same check run on a headless rented GPU, where LWJGL's
# Pbuffer cannot get a context at all -- see modal-block-conformance.py.
#
# Usage:
#     ./run-block-conformance.sh                 # generate the synthetic vector and check it
#     ./run-block-conformance.sh <vector.bin>    # check a vector somebody already wrote
#
# The second form is for a vector exported from a real baked cache by
# `preflight assets cache-conformance`. Same format, same probe -- the difference is whether the
# driver is arbitrating a test pattern or the profile's own art.
#
# Read-only: it uploads a texture and reads it back. Nothing on disk is modified outside this
# directory.
set -u
set -o pipefail

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO=$(CDPATH= cd -- "$HERE/../.." && pwd)
CORE_CLASSES="$REPO/preflight-core/target/classes"
BUILD="$HERE/.probe-build"
SUPPLIED_VECTOR=${1:-}
VECTOR=${SUPPLIED_VECTOR:-$HERE/block-conformance-vector.bin}

if [ -n "$SUPPLIED_VECTOR" ] && [ ! -f "$SUPPLIED_VECTOR" ]; then
    printf '%s\n' "No such vector: $SUPPLIED_VECTOR"
    exit 2
fi

# Only generating a vector needs Java at all. Checking one somebody else wrote needs a C compiler and
# a driver, which is the whole point of the split.
if [ -z "$SUPPLIED_VECTOR" ] && [ ! -d "$CORE_CLASSES" ]; then
    printf '%s\n' "preflight-core is not built. Run:"
    printf '%s\n' "    mvn -q -pl preflight-core -am install -DskipTests"
    exit 2
fi
if [ -n "$SUPPLIED_VECTOR" ]; then
    NEEDED_TOOLS=(cc)
else
    NEEDED_TOOLS=(javac java cc)
fi
for tool in "${NEEDED_TOOLS[@]}"; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        printf '%s\n' "$tool was not found; it is needed to build the probe."
        exit 2
    fi
done

mkdir -p "$BUILD"

if [ -n "$SUPPLIED_VECTOR" ]; then
    printf '%s\n' "Using the supplied vector: $VECTOR"
else
    if ! javac --release 17 -nowarn -cp "$CORE_CLASSES" -d "$BUILD" "$HERE/BlockConformanceVector.java"; then
        printf '%s\n' "Vector generator failed to build."
        exit 2
    fi
    if ! java -cp "$CORE_CLASSES:$BUILD" BlockConformanceVector "$VECTOR"; then
        printf '%s\n' "Vector generation failed."
        exit 2
    fi
fi

# The two backends are headless in different ways: CGL needs no drawable, EGL needs no display.
case "$(uname -s)" in
    Darwin)
        CC_ARGS=(-framework OpenGL -Wno-deprecated-declarations)
        ;;
    *)
        CC_ARGS=(-lEGL -lGL)
        ;;
esac

if ! cc -O2 -o "$BUILD/block-conformance-probe" "$HERE/block-conformance-probe.c" "${CC_ARGS[@]}"; then
    printf '%s\n' "Conformance probe failed to build."
    printf '%s\n' "On Linux this needs the EGL and GL development headers (libegl-dev, libgl-dev)."
    exit 2
fi

printf '\n'
"$BUILD/block-conformance-probe" "$VECTOR"
STATUS=$?

printf '\n%s\n' "Vector kept at: $VECTOR"
printf '%s\n' "To check the same vector on a rented NVIDIA GPU:"
printf '%s\n' "    modal run $HERE/modal-block-conformance.py"
if [ -z "$SUPPLIED_VECTOR" ]; then
    printf '%s\n' "To ask the same question about a real baked cache instead of this test pattern:"
    printf '%s\n' "    preflight assets cache-conformance --cache-dir <cache> --out /tmp/cache-vector.bin"
    printf '%s\n' "    $0 /tmp/cache-vector.bin"
fi
exit "$STATUS"
