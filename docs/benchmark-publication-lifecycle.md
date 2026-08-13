# Community benchmark publication lifecycle

The private report intake and a future public benchmark leaderboard have different retention and identity needs. A raw report can expire from private R2 after its bounded retention window while a sanitized benchmark row may be intentionally durable. That split requires an explicit publication lifecycle before any leaderboard is enabled.

## Required private linkage

A public `preflight-community-benchmark-v1` row must never contain the Cloudflare case ID, object key, upload/delete grant, archive filename, installation path, or raw benchmark identity.

The operator side still needs a private mapping from the accepted report to the sanitized row so these actions are possible without guessing:

- suppress duplicate publication of the same accepted contribution;
- remove or retract a published row when the contributor exercises the supported deletion path;
- replace a row after a schema or normalization correction without publishing a second copy;
- audit which accepted report produced a disputed aggregate without exposing that linkage publicly.

A candidate private mapping is:

```text
case ID / accepted object key -> sanitized submission ID + publication state
```

It belongs in private operator storage, not in the public dataset or repository.

## Consent boundary

Private report consent and public leaderboard consent must remain separate. Sending a bounded support or benchmark ZIP to the private intake does not itself authorize durable public publication.

Before a row can be public, the contribution contract needs an explicit versioned consent value that states at least:

- whether sanitized benchmark publication is allowed;
- whether a display name is allowed and its exact value, if any;
- which optional hardware/profile summary fields are allowed;
- what deletion or withdrawal does to a previously published row;
- whether aggregates may retain a historical contribution after the public row is withdrawn.

The default for every optional public field is absent/off.

## Deletion semantics

The current private report receipt already supports scoped deletion of the accepted R2 object. A public leaderboard needs one documented rule for what happens next. The conservative first-beta rule should be:

1. delete or expire the private report normally;
2. use the private linkage to identify any published sanitized row;
3. remove that row from the next published dataset when deletion is contributor-requested;
4. recompute aggregates from the remaining rows;
5. retain no public tombstone that exposes the private case identity.

Automatic R2 retention expiry is different from a contributor-requested withdrawal: expiry can remove the raw evidence while a separately consented sanitized row remains public, provided that distinction is disclosed before contribution.

## Publication gate

Do not enable a public leaderboard until all of these exist:

- explicit public-contribution consent distinct from private report consent;
- private accepted-report -> submission linkage;
- duplicate/replacement policy;
- contributor-requested withdrawal behavior;
- minimum benchmark-quality filters;
- version/cohort filtering for aggregates;
- a deterministic publish input/output contract suitable for review;
- tests proving case IDs, object keys, grants and private paths cannot enter public output.

The current `npm run benchmark:dataset` command is an operator normalization tool and intentionally stops short of this publication state machine.
