# The last text-backed spec cache now rehydrates a tagged tree

**Date:** 2026-08-05  
**Install:** Starsector 0.98a-RC8, 83 enabled mods, macOS on Apple M5 under Rosetta  
**Result:** exact warm rules-array reconstruction fell from 194ms to 6ms

## Finding

The prepared rules CSV was the last older spec cache still storing
`JSONArray.toString()` and rebuilding its one warm hit with `new JSONArray(text)`. A post-Janino
startup probe measured that single `rules-csv-merge-parse` call at **194ms**. The artifact was about
12MiB of JSON text.

The four per-spec JSON caches and the general merged-read cache already use the production
`GameJson`/`JsonTree` bridge. That format tags value types, length-prefixes containers and strings,
and interns repeated strings within a tree. It has already passed a real installed-`json.jar`
fidelity replay over 12,584 entries and 990,602 recursively compared values. Rules need exactly the
same contract: return a new mutable game `JSONArray`, not a shared object, while avoiding another
text tokenizer pass.

## Change and boundaries

SPRC format v2 stores the tagged tree produced directly from vanilla's completed rules array.
Every hit decodes into newly constructed game JSON containers through the existing direct sink. The
runtime additionally verifies that the decoded root is the installation's own `JSONArray`; a
malformed tree or wrong root shape marks the entry bad and executes the untouched vanilla loader.

The existing profile identity is unchanged and still content-binds all providing rules CSV files in
override order. The checksummed artifact remains bounded and atomically replaced only after the
complete rules loader returns normally. A v1 artifact is intentionally rejected as an unsupported
format, so the first updated launch runs vanilla, captures its authoritative result, and writes v2.
Rule-token and rule-command artifacts are separate siblings and remain warm during that migration.

Tests cover deterministic persistence, defensive byte-array ownership, corruption/truncation,
foreign artifact rejection, malformed tree fallback, wrong-root fallback, first-launch capture,
fresh reconstruction, and independence between returned arrays. Full `mvn verify` passes, including
39 failsafe integration tests and the synthetic cross-process suite.

## Live migration and warm gate

Migration:

- `~/.starsector-preflight/runs/rules-tagged-populate-20260805-214747`
- v1 rejected explicitly: `Unsupported prepared rules cache version: 1`
- one miss, one capture, one atomic write
- vanilla rules merge/parse: 968ms
- 38/38 exact transforms, zero decline/failure; normal automatic shutdown

Warm:

- `~/.starsector-preflight/runs/rules-tagged-warm-20260805-214851`
- one hit, zero miss/capture/write
- exact `GameJson.decode`: **6ms**
- exact containing `rules-csv-merge-parse`: **6ms**
- artifact: 8,810,607 bytes (8.4MiB)
- 38/38 exact transforms, zero decline/failure; normal automatic shutdown

The immediately preceding old-format warm probe measured 194ms at the same containing seam, so the
supported exact reduction is **188ms (96.9%)**. Its whole menu time was 26.06s versus 25.81s for the
tagged warm probe, a directional 0.25s difference consistent with the seam. Whole launches remain
noisy and the population launch sat between them, so the exact seam—not that pair—is the claim.

The coolest ordinary run before this change was 25.08s. Subtracting the exact 188ms yields a
theoretical 24.89s edge. A subsequent three-minute-cooled, non-probed launch reached **25.092602s**
at `~/.starsector-preflight/benchmarks/20260805-215211`: one rules-tree hit in 9ms, all 228 Janino
pack hits in 33ms, exit 0, and zero transform decline/failure. That is only 93ms over the threshold,
but it is still not sub-25 and one launch would not establish a repeatable result anyway.
