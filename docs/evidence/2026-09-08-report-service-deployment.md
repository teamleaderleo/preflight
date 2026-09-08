# Report service recovery deployment — 2026-09-08

The production report service now runs the transaction-replay and quota-reservation
recovery changes from #1289/#1290. This is service-level evidence; it does not
qualify a desktop package or replace the packaged cancel/retry/restart/delete flow.

## Identity

- Reviewed source: `54072f2a6c7b858a9139abf4dc55fc37267dd7fb`.
- Origin: `https://preflight-report-intake.leoli-082000.workers.dev`.
- Previous Worker version: `5a9c4e0d-d740-4271-af65-f5b98da850d9`.
- Deployed Worker version: `5752d7c6-e927-4c8a-85f3-fc5a8ef7f99e`.
- Normal `npm run deploy`, using the locked Wrangler 4.123.0 and Node 24.20.0.
- Existing bucket, secret, bindings, retention, and access configuration retained.

## Verification

Before deployment, `npm run check` passed typechecking, 28 Worker tests, and
7 operator tests. The deployed bindings include both rate limiters, the private
report bucket, and the daily-quota Durable Object. The existing `accepted/`
lifecycle rule remains enabled with 14-day expiration.

The repository's production canary ran with a bounded wrapper that adds a fresh
`preflight-report-transaction` UUID and repeats the same create request before
returning the second grant to the ordinary upload/finalize/delete sequence.
Both creates returned HTTP 201 and the same case ID. This exercises recovery
from an unavailable first create response; it does not simulate transport loss
or wait for grant expiry. Local Worker tests cover the reservation-expiry edge.

The live check completed at `2026-09-08T21:39:54.176Z`:

- Health/protocol check passed.
- Case: `d051562a-474b-833e-bb70-f30164d032cf`.
- Synthetic archive: 969 bytes; no game or personal data.
- SHA-256: `c05521ce8eb066ee2601859c72177f68168b4be466a6c8996de38ee55af7b626`.
- Upload and final receipt matched the case, byte count, and digest.
- Deletion returned HTTP 204; a subsequent finalization returned HTTP 409,
  confirming that the accepted archive was gone.

The synthetic case was deleted. Grants and deletion tokens were not retained in
the evidence. The wrapper and sanitized JSON receipt are retained locally under
`benchmark-results/release-acceptance-20260908/report-replay-canary.*` in the Mac
canonical checkout.

## Remaining boundary

Select one release source after the current product fixes settle, then obtain
authorization for its exact tag. Native real-game acceptance, package-bound
startup/lifecycle/update receipts, and the hands-on packaged report flow must
use that package generation. This deployment does not authorize publication.
