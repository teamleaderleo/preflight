# Package trust and private report boundaries are explicit

**Date:** 2026-08-08

## Decision

The first public beta doesn't wait for paid Apple Developer ID/notarization or Windows Authenticode
identities. Its macOS and Windows packages publish SHA-256 manifests and state the expected
Gatekeeper and SmartScreen warnings before download. Linux `.deb` and AppImage packages are built
and tested on Ubuntu 22.04; `.deb` describes the Debian-family package format rather than the CI
distribution.

Tauri's project-key updater signature remains mandatory. Tagged builds require the updater private
key, public key, and password; package collection requires the exact updater artifact/signature
pairs; and `latest.json` is assembled only after every supported platform is present. A failed
signature check leaves the installed version unchanged. This free update signature has a different
purpose from an operating-system publisher identity.

The macOS package verifier now distinguishes a paid Developer ID signature from Tauri's ad-hoc
bundle signature. An ad-hoc signature can prove package structure is internally coherent; it can't
claim an Apple-identified publisher. Windows verification likewise reports Authenticode status
without treating it as a first-beta build gate.

## Report intake

The report service doesn't trust a request because it appears to come from Preflight. A desktop
client can be imitated, and any embedded credential can be extracted. Every request is handled as
anonymous hostile input behind strict byte, entry, decompression, path, UTF-8, manifest, and hash
limits. JSON evidence must parse, and every nonblank JSON Lines record must be an object before the
archive reaches private R2 storage.

Accepted objects use `accepted/{caseId}.zip`, allowing a support operator to resolve the private
object directly from the user-visible case ID. The bucket remains private. Operators retrieve an
archive through authenticated R2 access, treat every value as inert text, and never execute or
publish its contents.

## Verification

- report-intake typecheck and nine Worker-runtime tests passed;
- desktop frontend tests passed;
- 21 desktop release-contract tests passed after refreshing the prepared engine;
- 15 Rust host tests passed; and
- the local `test:release` command now prepares the engine before checking its packaged legal and
  scenario boundary, preventing stale generated dependencies from producing a false failure.

No public package was published, no intake was deployed, and no game launch was needed for this
decision.
