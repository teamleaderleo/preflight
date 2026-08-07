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

The updater key is now provisioned. Its encrypted recovery copy is owner-readable outside the
repository, its password is held separately in macOS Keychain, its private values are GitHub
Actions secrets, and its public key is the only repository-level variable. Neither private value is
present in Git or the desktop client.

The distribution workflow now has a separate manual signed-candidate mode. It builds the ordinary
native installers plus all updater artifacts and signatures, includes the reviewed report-intake
origin, and assembles a complete candidate with inert `.invalid` feed URLs. GitHub permits signed-in
readers of a public repository to download its workflow artifacts, so candidate files use a
separate authenticated AES-256-GCM envelope before every upload. Plaintext exists only on ephemeral
build runners and after an authorized local download. Repository write permission and both
`gh release` commands remain in the tag-only publish job.

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

- report-intake typecheck and thirteen Worker-runtime tests passed, including rate limiting, exact
  daily byte reservations, and the production canary client against the local Worker/R2 runtime;
- desktop frontend tests passed;
- 21 desktop release-contract tests passed after refreshing the prepared engine;
- 15 Rust host tests passed; and
- the local `test:release` command now prepares the engine before checking its packaged legal and
  scenario boundary, preventing stale generated dependencies from producing a false failure.

The Cloudflare account's free R2 service is active. Production provisioning created the private
`preflight-reports` bucket, a 14-day `accepted/` expiration rule, an encrypted signing secret, and
the `preflight-report-intake` Worker. The Worker combines per-client edge brakes with a globally
coordinated 500 MiB grant limit for each UTC day. Each day's exact counter lives in its own SQLite
Durable Object, keeping unrelated days out of the same coordination point.

Live canary case `2555abea-efda-4cd0-be94-fe23d95e18cd` completed create, upload, finalize, and
authenticated deletion against deployed version `5a9c4e0d-d740-4271-af65-f5b98da850d9`. The
synthetic archive was 902 bytes, finalization stopped seeing it after deletion, and the bucket
subsequently reported zero objects and zero stored bytes. No public package or paid service was
created, the desktop release build still omits the intake origin, and no game launch was needed.
