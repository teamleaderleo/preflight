# Diagnostics export

Create an attachable support bundle without copying acceleration caches or game data:

```bash
java -jar preflight.jar evidence export --output preflight-diagnostics.zip
```

The desktop application exposes the same engine contract under **Help → Make a support file** and
asks where to save the ZIP. The default selection is the newest three launch runs and two benchmark
sessions. CLI callers can lower or raise those session counts explicitly, up to 20 per category:

```bash
java -jar preflight.jar evidence export \
  --output preflight-diagnostics.zip \
  --runs 3 \
  --benchmarks 2 \
  --json
```

The recency counts are only the default selection policy. When one exact run or benchmark should be
shared, first use `preflight evidence --json` to read the top-level session names, then select those
names explicitly:

```bash
java -jar preflight.jar evidence export \
  --run-session desktop-benchmark-20260812T100000Z \
  --output benchmark-contribution.zip \
  --json
```

`--run-session` and `--benchmark-session` are repeatable and use only top-level session names from
the measured evidence inventory. Duplicate, missing, path-like, `.` and `..` names are refused.
Exact-session selectors cannot be combined with `--runs` or `--benchmarks`, so an explicit choice
cannot silently fall back to a recency count.

An existing destination is refused unless `--overwrite` is explicit. The desktop host passes that
flag only after the native save dialog handles replacement confirmation.

## Fixed boundary

The exporter doesn't recursively archive an evidence directory. It considers only fixed JSON and
JSONL filenames used for run outcome, runtime identity, enabled-mod metadata, adapter health and
timing, and benchmark identity/settings/results. Resource names and aggregate file/size/hash
metadata can be present; source assets can't.

Every source must be regular, non-symlink UTF-8 text. A source is skipped if it is larger than 512
KiB, changes while being read, can't be read, or would cross the 5 MiB total source-content limit.
The exporter writes through a sibling temporary file and atomically replaces the selected ZIP when
the filesystem supports it.

The following categories are never considered:

- prepared texture, audio, JSON, bytecode, and other acceleration caches;
- Starsector files, mod files, saves, decoded assets, or compiled class bodies;
- console, wrapper, or game logs and crash dumps;
- JFR recordings, screenshots, and audio captures;
- symbolic links and unknown filenames.

Text occurrences of the current user home are replaced with `<home>`, including JSON-escaped and
slash-normalized forms. The bundle deliberately retains enabled mod IDs, platform/runtime details,
adapter targets, counters, hashes, resource names, and bounded failure metadata because those
establish the compatibility state. Read `README.txt` and `manifest.json` inside the ZIP before
sharing if that metadata is sensitive.

`manifest.json` uses the `starsector-preflight-diagnostics-v1` format and records enforced limits,
selected-session ranks/timestamps, redactions, exclusions, and the byte count and SHA-256 of every
included entry. The command receipt separately reports the finished ZIP's SHA-256.

## First-beta local-only flow

The first beta can **create and save** this disclosed ZIP, but it does not send the file to a
Preflight service. Help exposes no remote review/send/delete action and Settings exposes no
automatic failed-run reporting control in the packaged beta. The trusted Distribution workflow does
not compile a report-intake origin into the native application.

After creating a ZIP, inspect `README.txt` and `manifest.json`, keep the file on your computer, and
share it only through a private support path you choose if you want someone else to inspect it. Do
not attach private diagnostics to a public issue merely because the archive is bounded and redacted.
The **Copy setup** action remains the smaller public-safe text path for an ordinary issue.

The repository still contains the private Worker, transport implementation, and historical hosted
canary evidence from pre-release remote-report experiments. They are retained for future engineering
work, not enabled by the first-beta package. A later remote-capable release must re-establish the
consent, retention, deletion, migration, package, and privacy contracts before that capability is
advertised or compiled into a release.

Automatic failed-run upload is likewise absent from the first beta. A stale development-era
preference cannot turn it on or cause a failed run to be inspected/exported/sent once authoritative
local-only status is known. See the [product contract](product-contract.md) and
[Privacy](privacy.md).
