# Portable play-history export

`preflight evidence history-export` is the first read-only portability slice from #807.

```text
preflight evidence history-export --output preflight-play-history.json [--csv preflight-play-history.csv] [--json]
```

The export is derived from the existing valid launch-ledger rows. It does not backfill old run directories, import anything, change playtime, or modify the local ledger.

The versioned JSON document contains a deterministic list of observed launch events plus a derived playtime summary. It deliberately omits run-directory paths, logs, command lines, credentials, usernames, and arbitrary diagnostic text. The optional CSV contains the same portable event projection and neutralizes spreadsheet formula/control prefixes.

Outputs are create-new: an existing destination is never overwritten. Choose a new path or remove/rename the old export explicitly.

This is not yet a sync or archival durability promise. Import/merge/manual historical adjustments remain later #807 work and must use the user-state durability contract from #584 before becoming authoritative portable state.
