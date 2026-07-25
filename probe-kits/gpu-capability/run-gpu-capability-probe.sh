#!/bin/bash
# Builds and runs the portable GPU texture-capability probe against a Starsector installation,
# using the game's own LWJGL and its own bundled JVM so the context measured is the one the game
# gets. Works on macOS and Linux; see run-gpu-capability-probe.bat for Windows.
#
# Read-only: the game is never launched and nothing in the installation is modified.
set -u
set -o pipefail

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$HERE" || exit 2

usage() {
    printf '%s\n' "usage: $(basename "$0") [/path/to/starsector]"
    printf '%s\n' ""
    printf '%s\n' "If no path is given, the usual install locations are tried."
}

# Resolve the installation: an explicit argument wins, otherwise try the usual places.
CANDIDATES=()
if [ "$#" -gt 0 ]; then
    case "$1" in
        -h|--help) usage; exit 0 ;;
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
    usage
    exit 2
fi

# The jars and natives live in different places on macOS and Linux.
if [ -d "$INSTALL/Contents/Resources/Java" ]; then
    JARS="$INSTALL/Contents/Resources/Java"
    NATIVES="$JARS/native/macosx"
    BUNDLED_JAVA="$INSTALL/Contents/Home/bin/java"
elif [ -d "$INSTALL/starsector-core" ]; then
    JARS="$INSTALL/starsector-core"
    NATIVES="$JARS/native/linux"
    BUNDLED_JAVA="$INSTALL/jre_linux/bin/java"
else
    printf '%s\n' "Not a Starsector installation (no Contents/Resources/Java or starsector-core): $INSTALL"
    exit 2
fi

LWJGL="$JARS/lwjgl.jar"
if [ ! -f "$LWJGL" ]; then
    printf '%s\n' "Missing $LWJGL"
    exit 2
fi
if [ ! -d "$NATIVES" ]; then
    printf '%s\n' "Missing native library directory: $NATIVES"
    exit 2
fi

# The probe must run on the game's own JVM: on Apple Silicon the bundled runtime and the LWJGL
# natives are x86_64, so an arm64 JVM cannot load them at all.
if [ ! -x "$BUNDLED_JAVA" ]; then
    printf '%s\n' "Bundled JVM not found at $BUNDLED_JAVA; falling back to java on PATH."
    printf '%s\n' "If this fails with UnsatisfiedLinkError, the architectures do not match."
    BUNDLED_JAVA="java"
fi
if ! command -v javac >/dev/null 2>&1; then
    printf '%s\n' "javac was not found. A JDK 17 or newer is needed to build the probe."
    exit 2
fi

STAMP=$(date +%Y%m%d-%H%M%S)
BUILD="$HERE/.probe-build"
REPORT="$HERE/gpu-capability-report-$STAMP.txt"
mkdir -p "$BUILD"

if ! javac --release 17 -nowarn -cp "$LWJGL" -d "$BUILD" "$HERE/GpuCapabilityProbe.java"; then
    printf '%s\n' "Probe failed to build."
    exit 2
fi

# The block-upload probe additionally needs preflight's own encoder, since the whole point is to
# compare its output against the driver's decoder. It is skipped rather than fatal when the module
# has not been built, so the capability probe still runs on a machine with only a JDK and the game.
CORE_CLASSES="$HERE/../../preflight-core/target/classes"
BLOCK_PROBE="no"
if [ -d "$CORE_CLASSES" ]; then
    if javac --release 17 -nowarn -cp "$LWJGL:$CORE_CLASSES" -d "$BUILD" "$HERE/BlockUploadProbe.java"; then
        BLOCK_PROBE="yes"
    else
        printf '%s\n' "Block-upload probe failed to build; continuing with the capability probe only."
    fi
fi

{
    printf '%s\n' "Starsector Preflight — GPU texture capability probe"
    printf '%s\n' "captured:     $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf '%s\n' "installation: $INSTALL"
    printf '%s\n' "probe JVM:    $BUNDLED_JAVA"
    printf '\n'
    "$BUNDLED_JAVA" -cp "$LWJGL:$BUILD" -Djava.library.path="$NATIVES" GpuCapabilityProbe
    if [ "$BLOCK_PROBE" = "yes" ]; then
        printf '\n%s\n' "== encoder vs driver: does the hardware read the blocks we write?"
        "$BUNDLED_JAVA" -cp "$LWJGL:$CORE_CLASSES:$BUILD" -Djava.library.path="$NATIVES" BlockUploadProbe
    else
        printf '\n%s\n' "== encoder vs driver: skipped (build preflight-core first: mvn -q -pl preflight-core -am install -DskipTests)"
    fi
} | tee "$REPORT"

printf '\n%s\n' "Report written to: $REPORT"
