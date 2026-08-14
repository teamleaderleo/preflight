# Package lifecycle rehearsal

**Status:** implemented; no hosted run recorded yet

Desktop CI installs each platform's package, verifies the engine inside it, and removes it. That is
a player's first install. This rehearsal covers their second one — the upgrade written over a tree
already in use, and the rollback that puts the earlier version back.

Before this existed, the only evidence for that path was the
[signed macOS update and rollback rehearsal](evidence/2026-08-08-signed-update-rollback-rehearsal.md),
driven by hand on the development Mac. Windows and Linux had none.

## What it runs

`.github/workflows/package-lifecycle.yml`, dispatch-only, on `ubuntu-latest`, `windows-latest`, and
`macos-latest`. Each runner:

1. builds the desktop package at the checkout's version,
2. sets the tree to `0.1.1-rehearsal` through `set-release-version.mjs` and builds again,
3. runs `preflight-desktop/scripts/exercise-package-lifecycle.mjs` against the two bundle
   directories.

It builds twice per runner, so it is not part of ordinary CI. Dispatch it for a release candidate.

## What it checks

| Property | Why it is checked |
| --- | --- |
| The upgrade's version differs from the first install's | An upgrade to the same version installs nothing, and every later assertion would pass while rehearsing nothing |
| The rollback restores the earlier tree byte for byte | An installer that leaves one file from the newer version behind produces a state nobody can reason about afterwards; a file count or a size would not catch it |
| Caches, saves, mods, and game files are untouched after every step | This is the one defect a player cannot recover from, and the removal at the end is where an over-eager uninstaller would reach into them |
| Removal leaves none of the package's own files | Same check Desktop CI already makes, repeated here because it now runs after two writes rather than one |

The version comes from the engine manifest each package carries, not from Debian's database, a
Windows file resource, and an Info.plist. Three sources would be three ways for the comparison to be
subtly wrong; the manifest is written by the build and is already verified as part of the payload.

Per platform the install steps are `dpkg --install` with `--force-downgrade` for the rollback, the
NSIS installer under `/S` into a fixed directory, and a `ditto` of the mounted DMG's app.

## What it does not cover

The signed updater itself. Applying a signed update is driven from the packaged UI — the player
presses **Install and restart** — and this rehearsal never launches the app. What it exercises is
the package-manager path, which is what the `.deb` uses in production anyway, and the file-level
outcome the updater has to produce. The macOS rehearsal linked above remains the only evidence for
signature verification, rejected-signature recovery, and the in-app update flow.

The AppImage is not rehearsed here either; it updates through the updater payload rather than a
package manager.
