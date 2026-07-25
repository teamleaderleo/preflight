#!/bin/bash
# Generates the S3TC conformance vector and checks it against this machine's GPU driver.
#
# Unlike run-gpu-capability-probe.sh this needs no Starsector installation and no LWJGL: the vector
# is written by a plain Java program with no GPU involved, and checked by a small C program with no
# JVM involved. That split is what lets the same check run on a headless rented GPU, where LWJGL's
# Pbuffer cannot get a context at all -- see modal-block-conformance.py.
#
# Read-only: it uploads a texture and reads it back. Nothing on disk is modified outside this
# directory.
set -u
set -o pipefail

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO=$(CDPATH= cd -- "$HERE/../.." && pwd)
CORE_CLASSES="$REPO/preflight-core/target/classes"
BUILD="$HERE/.probe-build"
VECTOR="$HERE/block-conformance-vector.bin"

if [ ! -d "$CORE_CLASSES" ]; then
    printf '%s\n' "preflight-core is not built. Run:"
    printf '%s\n' "    mvn -q -pl preflight-core -am install -DskipTests"
    exit 2
fi
for tool in javac java cc; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        printf '%s\n' "$tool was not found; it is needed to build the probe."
        exit 2
    fi
done

mkdir -p "$BUILD"

if ! javac --release 17 -nowarn -cp "$CORE_CLASSES" -d "$BUILD" "$HERE/BlockConformanceVector.java"; then
    printf '%s\n' "Vector generator failed to build."
    exit 2
fi
if ! java -cp "$CORE_CLASSES:$BUILD" BlockConformanceVector "$VECTOR"; then
    printf '%s\n' "Vector generation failed."
    exit 2
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
exit "$STATUS"
