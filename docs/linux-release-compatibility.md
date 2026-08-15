# Linux release compatibility boundary

Preflight's Linux `.deb` and AppImage packages use an explicit binary-compatibility build boundary.
This boundary controls the GNU libc symbols the package may require; it does not by itself claim that
Starsector or Preflight has been exercised successfully on every distribution with that libc version.
Real-game Linux coverage remains a separate release-evidence gate.

## Reviewed builder

The Linux package jobs run on a maintained GitHub-hosted Linux machine, but the actual build executes
inside this pinned official Ubuntu Jammy image:

```text
ubuntu:jammy-20260627@sha256:0d7799fb1b4e200a24c11fc575a7e11ecb6501ff6eec649226fad40bd6501c64
```

The digest is part of the reviewed release workflow. Updating the image is a release-boundary change,
not a routine moving-runner update.

The selected package ABI ceiling for the first beta is:

```text
GLIBC <= 2.35
```

`ubuntu-latest` is only the outer GitHub runner. It does not define the Linux package's compatibility
floor.

## Package verification

After Tauri builds the `.deb` and AppImage, `scripts/verify_linux_glibc_floor.py` extracts both
packages, finds every ELF file, reads its versioned GLIBC requirements with `readelf`, and refuses the
job if any packaged ELF requires a GLIBC version newer than 2.35.

The same verifier runs in ordinary desktop package CI and in the tagged/private-candidate Distribution
workflow. A newer dependency, Rust target, Java runtime, native library, or packaging-tool change can
therefore raise the floor only by making the package job fail first.

## Provenance receipt

Every Linux package run writes two retained JSON files:

- `glibc-floor.json` — package names, ELF count, selected maximum, and maximum GLIBC requirement
  actually observed in the `.deb` and AppImage;
- `provenance.json` — the pinned builder image, `/etc/os-release`, installed libc package version,
  `ldd`, Java, Node, npm, Rust, and Cargo identities, plus the package-compatibility report.

CI uploads them as:

```text
preflight-linux-builder-provenance-<workflow run id>
```

Candidate review should retain that artifact beside the package/lifecycle evidence. If the artifact is
missing, the Linux candidate has lost its reviewed builder receipt and should be rebuilt instead of
having its compatibility claim reconstructed from memory.

## Observed candidate floor

The pinned-builder PR must complete a real Linux package build before this boundary is accepted. The
measured `maximumObservedGlibc` from that run is recorded here before merge.

**Observed package maximum:** pending the first pinned-builder package run.

This value describes the exact package binaries from that run. The policy ceiling remains 2.35 even
when the current binaries happen to require an older symbol version.

## Support statement

For the first beta, the packaging claim is limited to: **the Linux release artifacts are built and
checked so their versioned GLIBC requirements do not exceed 2.35.** Distribution support claims still
require installation and licensed Starsector launch evidence on the distributions being named.
