#!/bin/bash
# Measures where texture load time actually goes, using the game's own LWJGL and bundled JVM.
# Read-only: the game is never launched and nothing in the installation is modified.
#
# The work runs as two processes on purpose. On macOS, ImageIO initialises CoreFoundation and a GL
# context created afterwards segfaults inside it, so decoding and uploading cannot share a JVM.
set -u
set -o pipefail

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$HERE" || exit 2

SAMPLES=${PREFLIGHT_PROBE_SAMPLES:-300}

CANDIDATES=()
if [ "$#" -gt 0 ]; then
    case "$1" in
        -h|--help)
            printf '%s\n' "usage: $(basename "$0") [/path/to/starsector]"
            printf '%s\n' "       PREFLIGHT_PROBE_SAMPLES=<n> to change the sample size (default 300)"
            exit 0
            ;;
    esac
    CANDIDATES+=("$1")
else
    CANDIDATES+=(
        "/Applications/Starsector.app"
        "$HOME/Applications/Starsector.app"
        "$HOME/starsector"
        "$HOME/.local/share/starsector"
        "/opt/starsector"
    )
fi

INSTALL=""
for candidate in "${CANDIDATES[@]}"; do
    if [ -d "$candidate" ]; then
        INSTALL="$candidate"
        break
    fi
done
if [ -z "$INSTALL" ]; then
    printf '%s\n' "No Starsector installation found. Pass its path as the first argument."
    exit 2
fi

if [ -d "$INSTALL/Contents/Resources/Java" ]; then
    JARS="$INSTALL/Contents/Resources/Java"
    NATIVES="$JARS/native/macosx"
    BUNDLED_JAVA="$INSTALL/Contents/Home/bin/java"
    MODS="$INSTALL/mods"
elif [ -d "$INSTALL/starsector-core" ]; then
    JARS="$INSTALL/starsector-core"
    NATIVES="$JARS/native/linux"
    BUNDLED_JAVA="$INSTALL/jre_linux/bin/java"
    MODS="$INSTALL/mods"
else
    printf '%s\n' "Not a Starsector installation: $INSTALL"
    exit 2
fi

[ -d "$MODS" ] || MODS="$JARS/graphics"
if [ ! -d "$MODS" ]; then
    printf '%s\n' "No mods or graphics directory to sample under $INSTALL"
    exit 2
fi
if [ ! -x "$BUNDLED_JAVA" ]; then
    printf '%s\n' "Bundled JVM not found at $BUNDLED_JAVA; falling back to java on PATH."
    BUNDLED_JAVA="java"
fi
if ! command -v javac >/dev/null 2>&1; then
    printf '%s\n' "javac was not found. A JDK 17 or newer is needed to build the probe."
    exit 2
fi

# preflight-core supplies the block encoder and the power-of-two footprint arithmetic.
CORE="$HERE/../../preflight-core/target/classes"
if [ ! -d "$CORE" ]; then
    printf '%s\n' "preflight-core is not built. Run: mvn -q -pl preflight-core -am install -DskipTests"
    exit 2
fi

STAMP=$(date +%Y%m%d-%H%M%S)
BUILD="$HERE/.probe-build"
REPORT="$HERE/texture-pipeline-report-$STAMP.txt"
HANDOFF="$BUILD/handoff.txt"
mkdir -p "$BUILD"

if ! javac --release 17 -nowarn -cp "$CORE:$JARS/lwjgl.jar" -d "$BUILD" \
        "$HERE/TextureLoadPipelineProbe.java" "$HERE/StorageProbe.java"; then
    printf '%s\n' "Probe failed to build."
    exit 2
fi

CP="$CORE:$JARS/lwjgl.jar:$BUILD"
{
    printf '%s\n' "Starsector Preflight — texture load pipeline probe"
    printf '%s\n' "captured:     $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf '%s\n' "installation: $INSTALL"
    printf '%s\n' "sampled from: $MODS"
    printf '\n'
    # The pipeline probe runs first. StorageProbe writes and reads a 256 MiB file, which
    # evicts the page cache and roughly doubled the measured decode time when it ran first.
    "$BUNDLED_JAVA" -Xmx4g -cp "$CP" -Djava.awt.headless=true \
        TextureLoadPipelineProbe decode "$HANDOFF" "$MODS" "$SAMPLES" >/dev/null
    "$BUNDLED_JAVA" -cp "$CP" -Djava.library.path="$NATIVES" \
        TextureLoadPipelineProbe upload "$HANDOFF"
    printf '\n%s\n' "-- storage characteristics of this machine"
    "$BUNDLED_JAVA" -Xmx2g -cp "$CP" StorageProbe "$BUILD/iotest"
    rm -rf "$BUILD/iotest"
} | tee "$REPORT"

printf '\n%s\n' "Report written to: $REPORT"
