# Community benchmark contributions

Preflight keeps benchmark contribution separate from public leaderboard publication.

The private intake remains the trust boundary for user-submitted evidence. A contribution is a bounded diagnostics ZIP that the existing Cloudflare Worker validates and stores privately. Public or aggregate benchmark data must be derived from that accepted evidence and must not expose the raw ZIP, case ID, deletion grant, installation paths, launcher paths, or the benchmark's sealed private identity.

## Current operator flow

A paired desktop benchmark writes its sealed result as `benchmark-result.json` inside the benchmark run session. That file is allowlisted in diagnostics exports and accepted by the report-intake validator.

For an exact local export, list evidence sessions with:

```bash
preflight evidence --json
```

Then export only the desired sessions by their top-level names:

```bash
preflight evidence export \
  --run-session desktop-benchmark-20260812T100000Z \
  --output contribution.zip
```

`--run-session` and `--benchmark-session` are repeatable. Exact-session selection is mutually exclusive with `--runs` and `--benchmarks`; a missing or duplicate session name fails instead of silently selecting another session.

Accepted or locally exported ZIPs can be normalized without manually unpacking them:

```bash
cd report-intake
npm run benchmark:dataset -- \
  --output community-benchmarks.json \
  accepted-report-1.zip accepted-report-2.zip
```

The dataset builder performs bounded ZIP extraction, reconciles every evidence entry against the diagnostics manifest by byte count and SHA-256, rejects duplicate or missing inventory entries, requires at least one completed passing paired benchmark, verifies the startup improvement calculation, strips private identity/path fields, and emits:

- sanitized contribution records;
- overall median normal and optimized startup times;
- median improvement percentage;
- the same aggregates by operating system;
- an improvement-first leaderboard ordering.

The generated dataset is an operator artifact. It is not automatically published. The Cloudflare intake remains the authoritative acceptance boundary for remotely submitted reports; the operator builder's extra inventory checks make direct local ZIP ingestion fail closed on inconsistent evidence rather than silently accepting a partial bundle.

## Public record boundary

The current `preflight-community-benchmark-v1` record intentionally contains only data suitable for later aggregate/public use:

- Preflight product version;
- operating system family;
- evidence timestamp;
- measurement-only startup milliseconds;
- optimized startup milliseconds;
- calculated improvement percentage;
- bounded aggregate cache/fallback/failure counters when present;
- aggregate prepared-data bytes/files when present;
- benchmark probe-overhead percentages and budget verdicts when present.

It deliberately omits the raw benchmark identity, profile fingerprint, install root, launcher, selected save, category paths, case ID, upload/deletion grants, and every other raw evidence entry.

Hardware summary, mod-count/profile summary, optional public display name, and explicit leaderboard consent should be added as separate versioned contribution fields rather than inferred from private diagnostics.

## Intended desktop flow

The product-facing path should become distinct from the existing Support ZIP flow:

1. Run the paired startup benchmark.
2. Show the result immediately.
3. Offer **Contribute this benchmark** for that exact benchmark session.
4. Review required benchmark fields and separately optional context categories.
5. Keep public-leaderboard consent separate from private contribution consent.
6. Submit through the existing private Cloudflare case/upload/finalize protocol.
7. Retain the existing signed receipt and deletion control.
8. Derive a sanitized community record only after intake validation.

The existing Support ZIP remains broader and troubleshooting-oriented. It should not become the public leaderboard schema.

## Publishing direction

Cloudflare should remain the anonymous/private ingress layer. GitHub Actions can be useful downstream for validating and publishing a sanitized generated dataset or static leaderboard, but raw reports should not be accepted through public issues, pull requests, or workflow artifacts.

Before a leaderboard is public, add explicit contribution consent, deduplication policy, minimum benchmark-quality checks, version/cohort filtering, and a documented process for removing a published record when the corresponding private report is deleted.
