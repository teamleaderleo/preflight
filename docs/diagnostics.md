# Diagnostics export

Create an attachable support bundle without copying acceleration caches or game data:

```bash
java -jar preflight.jar evidence export --output preflight-diagnostics.zip
```

The desktop application exposes the same engine contract under **Settings → Save diagnostics
bundle** and asks where to save the ZIP. The default selection is the newest three launch runs and
two benchmark sessions. CLI callers can lower or raise those session counts explicitly, up to 20
per category:

```bash
java -jar preflight.jar evidence export \
  --output preflight-diagnostics.zip \
  --runs 3 \
  --benchmarks 2 \
  --json
```

An existing destination is refused unless `--overwrite` is explicit. The desktop host passes that
flag only after the native save dialog handles replacement confirmation.

## Fixed boundary

The exporter does not recursively archive an evidence directory. It considers only fixed JSON and
JSONL filenames used for run outcome, runtime identity, enabled-mod metadata, adapter health and
timing, and benchmark identity/settings/results. Resource names and aggregate file/size/hash
metadata can be present; source assets cannot.

Every source must be regular, non-symlink UTF-8 text. A source is skipped if it is larger than 512
KiB, changes while being read, cannot be read, or would cross the 5 MiB total source-content limit.
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

## Planned send flow

The current development build saves the bundle locally; it does not upload it. The planned **Send
run report** action will transmit this exact bounded ZIP only after showing the disclosure, byte
count, and SHA-256 and receiving explicit consent. It will return a case ID and retention deadline.
It is not a general telemetry channel, and automatic crash upload—if ever added—will be a separate,
default-off choice. See the [product contract](product-contract.md) for the service boundary.
