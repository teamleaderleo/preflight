#!/usr/bin/env bash
#
# Inspect the reviewed vanilla DynamicParticleGroup.render(FF)V bytecode without launching Starsector.
#
# Usage:
#   scripts/inspect-1153-particle-bytecode.sh [--game DIR] [--common-jar FILE] [--output FILE]
set -euo pipefail

GAME="${STARSECTOR_HOME:-/Applications/Starsector.app}"
COMMON_JAR="${STARSECTOR_COMMON_JAR:-}"
OUTPUT=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --game) GAME="$2"; shift 2 ;;
        --common-jar) COMMON_JAR="$2"; shift 2 ;;
        --output) OUTPUT="$2"; shift 2 ;;
        -h|--help)
            sed -n '2,7p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *) echo "Unknown option: $1" >&2; exit 2 ;;
    esac
done

command -v javap >/dev/null 2>&1 || { echo "javap is required (use a JDK, not a JRE)." >&2; exit 1; }
command -v python3 >/dev/null 2>&1 || { echo "python3 is required." >&2; exit 1; }

hash_file() {
    local file="$1"
    if command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$file" | awk '{print $1}'
    elif command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$file" | awk '{print $1}'
    elif command -v openssl >/dev/null 2>&1; then
        openssl dgst -sha256 "$file" | awk '{print $NF}'
    else
        echo "Need shasum, sha256sum, or openssl." >&2
        return 1
    fi
}

if [[ -z "$COMMON_JAR" ]]; then
    [[ -d "$GAME" ]] || { echo "Starsector installation not found: $GAME" >&2; exit 1; }
    matches="$(find "$GAME" -type f -name fs.common_obf.jar -print 2>/dev/null | head -2)"
    [[ "$(printf '%s\n' "$matches" | sed '/^$/d' | wc -l | tr -d '[:space:]')" == 1 ]] || {
        echo "Could not resolve exactly one fs.common_obf.jar under $GAME; pass --common-jar." >&2
        exit 1
    }
    COMMON_JAR="$matches"
fi
[[ -f "$COMMON_JAR" ]] || { echo "Common archive not found: $COMMON_JAR" >&2; exit 1; }
COMMON_JAR="$(cd "$(dirname "$COMMON_JAR")" && printf '%s/%s\n' "$(pwd -P)" "$(basename "$COMMON_JAR")")"

EXPECTED="10d89e113f6d1627cc7bc90b692e8a7f450fdd820c5a4ac5edaecd6710afe708"
ACTUAL="$(hash_file "$COMMON_JAR")"
[[ "$ACTUAL" == "$EXPECTED" ]] || {
    echo "Common archive differs from reviewed Starsector 0.98a-RC8." >&2
    echo "Expected: $EXPECTED" >&2
    echo "Actual:   $ACTUAL" >&2
    exit 1
}

TMP="$(mktemp -d "${TMPDIR:-/tmp}/preflight-1153-particle-inspect.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT
DISASM="$TMP/dynamic-particle-group.javap"
javap -classpath "$COMMON_JAR" -p -c -s com.fs.graphics.particle.DynamicParticleGroup >"$DISASM"

python3 - "$DISASM" "$COMMON_JAR" "$ACTUAL" "$OUTPUT" <<'PY'
import json
import pathlib
import re
import sys

path = pathlib.Path(sys.argv[1])
archive = sys.argv[2]
archive_sha = sys.argv[3]
output = sys.argv[4]
lines = path.read_text(encoding="utf-8", errors="replace").splitlines()

# javap prints a member header, then its descriptor, then Code. Find the exact (FF)V descriptor
# attached to render and stop when the next member descriptor begins.
start = None
header = ""
for i, line in enumerate(lines):
    if line.strip() == "descriptor: (FF)V":
        candidate_header = ""
        for j in range(i - 1, max(-1, i - 4), -1):
            text = lines[j].strip()
            if text and not text.startswith("descriptor:") and not text.startswith("flags:"):
                candidate_header = text
                break
        if " render(" in f" {candidate_header}" or candidate_header.startswith("void render("):
            for j in range(i + 1, min(len(lines), i + 8)):
                if lines[j].strip() == "Code:":
                    start = j + 1
                    header = candidate_header
                    break
    if start is not None:
        break

if start is None:
    raise SystemExit("Exact render(FF)V bytecode was not found by javap")

body = []
for i in range(start, len(lines)):
    stripped = lines[i].strip()
    if stripped.startswith("descriptor:"):
        break
    if re.match(r"^(public|protected|private|static|final|synchronized|native|abstract).*[);]$", stripped):
        break
    body.append(lines[i])

text = "\n".join(body)
patterns = {
    "glBeginSites": r"org/lwjgl/opengl/GL11\.glBegin:",
    "glEndSites": r"org/lwjgl/opengl/GL11\.glEnd:",
    "vertexSites": r"org/lwjgl/opengl/GL11\.glVertex[^:]*:",
    "texCoordSites": r"org/lwjgl/opengl/GL11\.glTexCoord[^:]*:",
    "colorSites": r"org/lwjgl/opengl/GL11\.glColor[^:]*:",
    "bindTextureSites": r"org/lwjgl/opengl/GL11\.glBindTexture:",
    "blendFuncSites": r"org/lwjgl/opengl/GL11\.glBlendFunc[^:]*:",
    "drawArraysSites": r"org/lwjgl/opengl/GL11\.glDrawArrays:",
}
counts = {name: len(re.findall(pattern, text)) for name, pattern in patterns.items()}

invokes = []
for line in body:
    if "// Method " not in line and "// InterfaceMethod " not in line:
        continue
    target = line.split("// ", 1)[1].strip()
    if target.startswith("Method "):
        target = target[len("Method "):]
    elif target.startswith("InterfaceMethod "):
        target = target[len("InterfaceMethod "):]
    if target.startswith("org/lwjgl/") or target.startswith("com/fs/graphics/"):
        invokes.append(target)

result = {
    "issue": 1153,
    "class": "com/fs/graphics/particle/DynamicParticleGroup",
    "method": "render(FF)V",
    "javapHeader": header,
    "sourceArchive": archive,
    "sourceArchiveSha256": archive_sha,
    **counts,
    "selectedInvocations": sorted(set(invokes)),
}
rendered = json.dumps(result, indent=2, sort_keys=True) + "\n"
if output:
    destination = pathlib.Path(output).expanduser().resolve()
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(rendered, encoding="utf-8")
print(rendered, end="")
PY
