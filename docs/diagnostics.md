# Diagnostics export

## TL;DR

Preflight can make a bounded support ZIP without copying caches, game/mod assets, saves, screenshots, recordings, or arbitrary logs.

Desktop: **Settings → Save diagnostics bundle**

CLI:

```bash
java -jar preflight.jar evidence export --output preflight-diagnostics.zip
```

You can inspect the ZIP before sharing it. Sending is a separate action; ordinary launches don't upload it automatically.

That's the useful part for most people. The rest of this page defines the exact export boundary.

## Default export

The default selection is the newest three launch runs and two benchmark sessions. CLI callers can change those counts, up to 20 per category:

```bash
java -jar preflight.jar evidence export \
  --output preflight-diagnostics.zip \
  --runs 3 \
  --benchmarks 2 \
  --json
```

If one exact run/benchmark should be shared, use `preflight evidence --json` to get its top-level session name, then select it explicitly:

```bash
java -jar preflight.jar evidence export \
  --run-session desktop-benchmark-20260812T100000Z \
  --output benchmark-contribution.zip \
  --json
```

`--run-session` and `--benchmark-session` are repeatable. Path-like, duplicate, missing, `.` and `..` names are refused. Exact selectors can't be mixed with recency counts, so an explicit selection can't quietly turn back into “newest N.”

An existing destination is refused unless `--overwrite` is explicit. The desktop only passes that after the native save dialog handles replacement confirmation.

## What's allowed into the ZIP

The exporter considers a fixed set of JSON/JSONL files used for things such as:

- launch outcome/runtime identity;
- enabled-mod metadata;
- adapter health/timing;
- benchmark identity/settings/results.

Resource names and aggregate file/size/hash metadata can appear. Source assets can't.

Every source has to be regular, non-symlink UTF-8 text. A source is skipped if it's too large, changes while being read, can't be read, or would cross the bundle's total source-content limit.

The exporter writes through a sibling temporary file and uses atomic replacement when the filesystem supports it.

## What's always excluded

The exporter doesn't include:

- prepared texture/audio/JSON/bytecode caches;
- Starsector files, mod files, saves, decoded assets, or compiled class bodies;
- console, wrapper, or game logs and crash dumps;
- JFR recordings, screenshots, and audio captures;
- symbolic links or unknown filenames.

Occurrences of the current user home are replaced with `<home>`, including common escaped/slash-normalized forms.

The bundle deliberately retains compatibility-relevant information such as enabled mod IDs, platform/runtime detail, adapter targets, counters, hashes, resource names, and bounded failure metadata. If any of that is sensitive in your situation, inspect `README.txt` and `manifest.json` inside the ZIP before sharing it.

## Manifest and limits

`manifest.json` uses the `starsector-preflight-diagnostics-v1` format and records the enforced limits, selected sessions, redactions/exclusions, and byte count + SHA-256 of each included entry.

The command receipt also reports the finished ZIP's SHA-256.

## Optional send flow

A configured release can review and send the exact saved ZIP.

Before consent, the UI shows the path, byte count, SHA-256, retention, included entries, skipped-source count, and fixed exclusions. The native host reopens the exact file, rechecks its identity/size/hash, and streams only to the compile-time HTTPS intake origin.

The send flow supports progress and cancellation. An accepted report returns a signed case receipt with matching digest/size, retention deadline, and case-specific early-deletion authorization.

Development/source builds don't contain a production intake origin, so local export still works while sending stays unavailable.

The server-side hostile-ZIP validation and deployment details live under [`report-intake`](../report-intake/README.md). Those are release/operations details, not something a normal user needs to understand before creating a ZIP.

The first beta sends a report only after the user reviews the bounded ZIP and chooses Send. Automatic failed-run reporting stays unavailable.

For the exact product/privacy contract, see [Product contract](product-contract.md) and [Privacy](privacy.md).
