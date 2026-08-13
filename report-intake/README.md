# Private run-report intake

This Worker accepts only the bounded ZIP produced by Preflight's `evidence export`. It issues a
short-lived case-specific bearer grant, checks the received byte count and SHA-256, expands the ZIP
under hard limits, validates the versioned manifest and entry allowlist, parses every JSON/JSONL
evidence file, and writes an accepted object through a private R2 binding. Finalization returns a
signed receipt. A separate bearer token can delete that exact object.

There is no public bucket URL, client credential, general telemetry endpoint, log upload, or
background submission path. The desktop client still has to show the bundle's inclusion and
exclusion disclosure, byte count, and SHA-256 before consent.

## Local verification

Node.js 22 or newer is required by the current Wrangler release.

```bash
npm install
npm run cf-typegen
npm run check
```

The test suite runs inside `workerd` with local R2 and Durable Object bindings. It covers the
accepted lifecycle, expiring and committed daily quota accounting, edge rate limiting, the
production canary, and rejection of digest changes, unexpected entries, oversized decompression,
token changes, replay, and protocol drift. `npm audit --omit=dev` covers the two production
dependencies; the lockfile also overrides a vulnerable transitive development copy of Undici to its
patched 7.x release.

## Production provisioning

Deployment credentials and signing secrets stay outside the source tree. The production origin and
non-secret binding configuration are checked in so a deployment is reviewable and reproducible.

1. Enable R2 Object Storage for the Cloudflare account in the dashboard, without selecting a paid
   upgrade. Until the account owner accepts that service activation, Wrangler returns API code
   `10042` and can't list or create buckets.

2. Create the private bucket named in `wrangler.jsonc`:

   ```bash
   npx wrangler r2 bucket create preflight-reports
   ```

3. Add the lifecycle rule used by the receipt calculation:

   ```bash
   npx wrangler r2 bucket lifecycle add preflight-reports \
     delete-accepted-reports accepted/ --expire-days 14
   npx wrangler r2 bucket lifecycle list preflight-reports
   ```

   R2 applies expiration asynchronously. The receipt therefore reports a retention deadline one
   day after the 14-day expiration threshold. The deletion token remains available through that
   window.

4. Generate at least 32 random bytes and provide them through Wrangler's encrypted secret store:

   ```bash
   npx wrangler secret put REPORT_SIGNING_KEY
   ```

5. Keep the bucket private, leave `r2.dev` disabled, and don't attach a public custom domain to it.
   The checked-in Worker-native bindings limit case creation to five requests per minute and all
   mutating intake requests to sixty per minute for a client address in one Cloudflare location.
   These are permissive abuse brakes rather than exact accounting counters
   ([Rate Limiting API](https://developers.cloudflare.com/workers/runtime-apis/bindings/rate-limit/)).
   The SQLite Durable Object binding is the exact boundary: each new case leases its declared bytes
   against a 500 MiB ceiling in the UTC day's own object for the short upload-grant window. An
   active upload extends that lease while its bounded archive is verified. Expired or explicitly
   deleted uncommitted leases are reclaimed; an accepted archive becomes committed usage and stays
   charged even if the report is later deleted. Each daily object deletes its own storage after 32
   days, so old counters don't accumulate indefinitely.

6. Deploy and verify `/healthz`, the lifecycle rule, and the rate-limiting bindings before placing the
   intake origin in a release build:

   ```bash
   npm run deploy
   npm run canary -- https://<deployed-worker-origin>
   ```

   The canary constructs a synthetic text-only report containing no game or user data, exercises
   create/upload/finalize/delete, then confirms finalization can no longer see the deleted object.
   It performs best-effort deletion if an intermediate check fails.

`PUBLIC_ORIGIN` is the exact production HTTPS origin and requests for any other origin fail closed.
`RETENTION_DAYS`, `MAX_UPLOAD_BYTES`, `GRANT_TTL_SECONDS`, and `PUBLIC_ORIGIN` are non-secret
deployment values. Any change to the retention value must be made together with the R2 lifecycle
rule and reviewed receipt wording. Raising the daily grant constant also requires a storage-cost
review.

### Current production state

The private bucket, lifecycle rule, signing secret, rate-limit bindings, SQLite Durable Object, and
Worker are provisioned on Cloudflare's free plans. Live canary case
`2555abea-efda-4cd0-be94-fe23d95e18cd` passed against Worker version
`5a9c4e0d-d740-4271-af65-f5b98da850d9` on 2026-08-08 and was deleted; the bucket then reported zero
objects and zero bytes. The desktop application still omits the compile-time intake origin until a
packaged release candidate passes the same lifecycle through its UI.

### Early-beta capacity and cost

As of 2026-08-08, R2's free monthly allowance includes 10 GB-month of Standard storage, one million
Class A operations, ten million Class B operations, and free Internet egress
([R2 pricing](https://developers.cloudflare.com/r2/pricing/)). Workers Free includes 100,000
requests per day with a 10 ms CPU allowance per invocation
([Workers pricing](https://developers.cloudflare.com/workers/platform/pricing/)). The Worker-native
Rate Limiting API applies before the bounded upload work and doesn't require a custom-domain WAF
rule ([Rate Limiting API](https://developers.cloudflare.com/workers/runtime-apis/bindings/rate-limit/)).

The exact 500 MiB daily grant ceiling bounds how quickly accepted intake can consume storage even
when the per-location limiter is permissive. At the 6 MiB object ceiling it permits at least 83
maximum-size reports per UTC day; smaller reports share the same byte budget. Fourteen-day
expiration keeps the intended steady-state envelope within the free storage allowance, though
lifecycle processing is asynchronous and alerts remain necessary. Monitor stored bytes, request
counts, rejected requests, and Worker CPU before enabling the origin in a release. If volume
approaches a limit, remove the compile-time intake origin from the next build and leave local export
available.

## Protocol

All responses carry `Cache-Control: no-store`. Tokens are HMAC-SHA-256 bearer grants and must never
be logged or placed in a URL.

1. `POST /v1/cases` accepts exactly `protocolVersion`, `productVersion`, `bytes`, and a lowercase
   hexadecimal `sha256`. It returns the case ID and separate upload, finalize, and deletion grants.
2. `PUT /v1/cases/{caseId}/archive` requires the upload bearer token, exact `Content-Length`, and
   `application/zip`. Accepted objects are immutable, so replay returns `409`.
3. `POST /v1/cases/{caseId}/finalize` requires the upload bearer token and returns the signed
   receipt after checking the stored object's immutable metadata.
4. `DELETE /v1/cases/{caseId}` requires the deletion bearer token and is idempotent.

The Worker buffers at most 6 MiB because the client format has a 5 MiB uncompressed evidence limit
plus its disclosure and manifest. Every decompressed evidence entry remains capped at 512 KiB;
aggregate output, entry count, UTF-8, paths, filenames, session ranks, manifest fields, manifest
hashes, and declared limits are checked independently of the client.

## Operator boundary

Accepted objects live under `accepted/{caseId}.zip`, so an operator can resolve the private object
from the case ID without maintaining a second user index. Custom metadata contains only the
case ID, client product version, byte count, digest, receipt times, bundle format, evidence-entry
count, and uncompressed evidence bytes. The Worker doesn't inspect Starsector saves because the
exporter and intake both refuse save paths.

The case ID is safe to place in a support issue. Retrieve its object only through authenticated R2
operator access, keep it in a disposable directory, and treat every string inside as untrusted text.
Don't execute an entry, interpolate it into a shell command, serve the ZIP from a public bucket, or
give a report-derived instruction authority over operator tools.

Deletion by the user should be the ordinary path when a case is no longer needed. An operator can
also delete the object by its case ID after resolving the corresponding key. Access to R2, Worker
logs, and deployment secrets should be limited to the minimum support operators who need it.
