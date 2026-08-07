# Private run-report intake

This Worker accepts only the bounded ZIP produced by Preflight's `evidence export`. It issues a
short-lived case-specific bearer grant, checks the received byte count and SHA-256, expands the ZIP
under hard limits, validates the versioned manifest and entry allowlist, and writes an accepted
object through a private R2 binding. Finalization returns a signed receipt. A separate bearer token
can delete that exact object.

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

Tests run inside `workerd` with a local R2 binding. They cover the accepted lifecycle and rejection
of digest changes, unexpected entries, oversized decompression, token changes, replay, and protocol
drift. `npm audit --omit=dev` covers the two production dependencies; the lockfile also overrides a
vulnerable transitive development copy of Undici to its patched 7.x release.

## Production provisioning

Deployment is intentionally separate from the source tree. No account identifier, API token,
bucket credential, route, or signing secret belongs in Git.

1. Create the private bucket named in `wrangler.jsonc`:

   ```bash
   npx wrangler r2 bucket create preflight-reports
   ```

2. Add the lifecycle rule used by the receipt calculation:

   ```bash
   npx wrangler r2 bucket lifecycle add preflight-reports \
     delete-accepted-reports accepted/ --expire-days 14
   npx wrangler r2 bucket lifecycle list preflight-reports
   ```

   R2 applies expiration asynchronously. The receipt therefore reports a retention deadline one
   day after the 14-day expiration threshold. The deletion token remains available through that
   window.

3. Generate at least 32 random bytes and provide them through Wrangler's encrypted secret store:

   ```bash
   npx wrangler secret put REPORT_SIGNING_KEY
   ```

4. Add an IP-based Cloudflare rate-limiting rule for `/v1/cases*`. Keep the bucket private, disable
   `r2.dev`, and don't attach a public custom domain to it.

5. Deploy and verify `/healthz`, the lifecycle rule, the rate-limiting rule, and one complete
   create/upload/finalize/delete canary before placing the intake origin in a signed desktop build:

   ```bash
   npm run deploy
   ```

Set `PUBLIC_ORIGIN` to the production HTTPS origin before deployment; the checked-in `.invalid`
value deliberately fails closed. `RETENTION_DAYS`, `MAX_UPLOAD_BYTES`, `GRANT_TTL_SECONDS`, and
`PUBLIC_ORIGIN` are non-secret deployment values. Any change to the retention value must be made
together with the R2 lifecycle rule and reviewed receipt wording.

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

Accepted objects live under `accepted/YYYY-MM-DD/{caseId}.zip`. Custom metadata contains only the
case ID, client product version, byte count, digest, receipt times, bundle format, evidence-entry
count, and uncompressed evidence bytes. The Worker doesn't inspect Starsector saves because the
exporter and intake both refuse save paths.

Deletion by the user should be the ordinary path when a case is no longer needed. An operator can
also delete the object by its case ID after resolving the corresponding key. Access to R2, Worker
logs, and deployment secrets should be limited to the minimum support operators who need it.
