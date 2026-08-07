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

The first local update-signed macOS candidate exposed one packaging detail before the hosted
matrix ran: a DMG target alone doesn't make Tauri's updater archive. The signed build now requests
both `dmg` and `app`; it produced the DMG, `.app.tar.gz`, and updater signature. The verifier mounted
the DMG, compared its application tree byte-for-byte with the updater archive, checked the 106-file
bundled runtime, and completed its packaged smoke test.

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
- 28 desktop release/prepared-contract tests passed after refreshing the prepared engine;
- 16 Rust host tests passed; and
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

A local update-signed macOS package then exercised the real desktop disclosure and consent flow
against production. Its first upload found a stale client-side receipt assumption: the service and
operator contract use `accepted/{caseId}.zip`, while the desktop still expected a dated key. The
desktop rejected the inconsistent receipt and deleted the incomplete server case automatically.
After aligning the validator and preview fixture with the operator-resolvable key, the rebuilt
package accepted case `ba8dc755-b956-4568-92f1-fdbc9f162a9b`. The disclosed ZIP was 197,368 bytes
with SHA-256 `558766c179e293418d406b525613af435129673f519d9c26a093fa71f5d12260`; an authenticated R2
download matched both values exactly. The case remains private under its 2026-08-23 automatic
expiration until deliberate deletion was authorized. The authenticated operator DELETE returned
HTTP 200 and a cache-busted authenticated GET returned HTTP 404, confirming that the exact object
was gone. Wrangler's ordinary object GET replayed the previous body during this check, so it wasn't
used as deletion evidence.

Rebuilding the app while its receipt screen was open also showed that the deletion authorization
had lived only in React memory. The desktop now retains an exact, structurally checked, unexpired
receipt in app-local storage. It removes the local authorization after deletion, explicit dismissal,
or expiry. The native host still validates the configured origin, deletion URL, method, and token
before sending any request, so modified local storage can't redirect the bearer credential.

The native transport now also runs against a bounded local HTTP intake during ordinary Rust tests.
One scenario creates a case, streams the exact disclosed bytes, finalizes it, and validates the
returned receipt. A second cancels as upload begins and requires the authorized cleanup DELETE before
accepting cancellation. A third replays the deletion grant from a receipt and verifies its method,
path, and bearer token. The cancellation scenario re-reads the local ZIP afterward and requires it
to remain byte-identical. HTTPS origin validation remains a separate fail-closed test; the loopback
server exists only to exercise request sequencing and payloads without touching production.

That package also exposed a first-run discovery boundary before any game launch. A macOS app can
inherit `/` as its working directory, and the engine had treated it as an implicit recursive search
root. A protected `/Library/Trial` descendant escaped the lazy walk as an unchecked I/O failure.
Implicit filesystem roots are now skipped, unreadable descendants are contained, and a regression
test covers the packaged working-directory condition. Running the rebuilt engine from `/` selected
`/Applications/Starsector.app` directly and reported the skipped implicit root as a diagnostic.
