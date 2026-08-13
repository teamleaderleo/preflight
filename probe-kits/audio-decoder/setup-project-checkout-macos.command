#!/bin/bash
set -euo pipefail

DEST="$HOME/Projects/preflight"
URL="https://github.com/teamleaderleo/preflight.git"

if ! command -v git >/dev/null 2>&1; then
    printf '%s\n' "Git was not found. Install the Xcode command-line tools or Git first."
    exit 2
fi

mkdir -p "$HOME/Projects"

if [ ! -e "$DEST" ]; then
    printf '%s\n' "Cloning Preflight into $DEST"
    git clone "$URL" "$DEST"
elif [ -d "$DEST/.git" ]; then
    if ! git -C "$DEST" diff --quiet || ! git -C "$DEST" diff --cached --quiet; then
        printf '%s\n' "The checkout has uncommitted changes. They were preserved and no update was attempted."
        printf '%s\n' "$DEST"
        exit 3
    fi
    printf '%s\n' "Updating the existing checkout at $DEST"
    git -C "$DEST" fetch origin
    git -C "$DEST" checkout main
    git -C "$DEST" pull --ff-only origin main
else
    printf '%s\n' "$DEST exists but is not a Git checkout. Nothing was changed."
    exit 3
fi

printf '\n%s\n' "Checkout ready: $DEST"
if command -v open >/dev/null 2>&1; then
    open "$DEST" >/dev/null 2>&1 || true
fi
