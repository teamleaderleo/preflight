# A run report now has a private, bounded intake

**Date:** 2026-08-07
**Scope:** local Worker runtime, local R2 simulation, and desktop client; no production deployment

The support bundle already had a narrow local boundary: fixed JSON and JSONL names, UTF-8 text,
512 KiB per source, 5 MiB across source content, home-directory redaction, and a manifest containing
the byte count and SHA-256 of every included entry. The missing half was the receiving side. Saving a
safe ZIP locally doesn't establish that a server will reject a broader or changed ZIP.

`report-intake` adds that receiving boundary as a small Cloudflare Worker. A create request contains
only the protocol version, Preflight version, final ZIP size, and final ZIP SHA-256. The Worker issues
case-specific HMAC grants for upload and deletion. The token stays in the authorization header. The
upload is immutable and a repeated PUT is rejected.

Before writing through the private R2 binding, the Worker independently verifies the outer size and
digest, ZIP decompression bounds, UTF-8, entry count, path structure, filename allowlists, session
ranks, exact manifest schema, format limits, and each evidence entry's manifest size and digest. It
doesn't trust the desktop's disclosure or the ZIP's declared decompressed sizes. The streaming ZIP
reader stops output at the same 512 KiB per-entry and 5 MiB evidence boundaries enforced by the Java
exporter.

The Worker-runtime suite covers the accepted create/upload/finalize/delete lifecycle and rejects a
changed outer digest, an added save path, a highly compressible file that expands past 512 KiB, a
changed bearer token, a replayed upload, and a mismatched protocol version. Type checking, six tests,
the Wrangler production bundle dry run, and the complete dependency audit passed.

One cross-boundary check used the current Java CLI rather than a test-created ZIP. The CLI exported an
empty but fully disclosed `starsector-preflight-diagnostics-v1` archive:

| Field | Value |
| --- | --- |
| ZIP bytes | 1,291 |
| ZIP SHA-256 | `13c15d384ae154265a4d0e08d04aadbb18f65402eb96bd473f9ac8487013d359` |
| Included evidence entries | 0 |

The local Worker accepted those exact bytes, repeated the digest and size in its signed receipt, and
returned `204` when the receipt's deletion grant was used. The simulated bucket was empty afterward.

Production remains deliberately unavailable. The checked-in origin ends in `.invalid`, the signing
key exists only in the Worker secret binding, and the deployment checklist requires a private bucket,
14-day lifecycle rule, rate limit, canary upload/deletion, and an explicit production origin. R2's
lifecycle processing can trail its expiration threshold, so the receipt's retention deadline includes
the documented one-day processing window.

The desktop now completes the client half of the boundary. Its review shows the exact path, byte
count, full digest, included entries, skipped-source count, exclusions, network-metadata notice,
and retention before consent. The Rust host reopens only an absolute regular non-symlink `.zip`,
rechecks size, modification state, and SHA-256, refuses redirects and cross-origin endpoints, and
streams at most 6 MiB with progress and cancellation. Once the archive is accepted, it completes
finalization so a user isn't left with an inaccessible object. It validates the returned case,
object key, digest, size, product version, times, signature shape, and deletion grant before
displaying the receipt. The receipt can be copied or used to delete the object early.

Rust unit tests pin the fail-closed origin and endpoint rules, changed-file refusal, and coordinated
shutdown cancellation. Frontend tests cover explicit consent through receipt and deletion, plus the
unconfigured-build path where local export remains available. Production is still disabled: the
client origin is compile-time only and absent from ordinary builds. Provisioning, rate limiting,
public operator details, and a live packaged release-candidate canary remain release gates.
