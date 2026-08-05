# Campaign-rule token shapes persist across unchanged launches

Date: 2026-08-05

Profile: 83 enabled mods, Starsector 0.98a-RC8, macOS on Apple M5, bundled x86-64
Zulu 17 under Rosetta, `--fast`

## Result

Persisting the already-reviewed rule-token memo reduced the exact tokenizer seam from 600ms to
89ms and the complete campaign-rules loader from 1.688s to 1.262s. The warm launch served all
62,340 tokenizer calls from 31,614 prepared expression shapes with zero miss, decline, load
failure, or write failure.

Retained runs:

- learning: `~/.starsector-preflight/runs/rule-token-persist-cold-20260805-142620`
- warm: `~/.starsector-preflight/runs/rule-token-persist-warm-20260805-142718`

Both runs reached the main menu, reported active adapter health, completed with wrapper exit 0,
and left no Starsector process running. Their menu markers were 26.22s and 24.57s. The 1.65s
whole-launch difference includes ordinary launch noise; the exact 511ms tokenizer and 426ms rules
loader reductions are the attributable result.

## Change

The existing launch-local memo was already correctness-preserving but began empty on every JVM.
It scanned vanilla's synchronized `StringBuffer` tokenizer once for each of 31,614 distinct
expressions, then rebuilt fresh tokens for the remaining 30,726 repeated calls. The learning launch
now records each successful ordered `(token string, token enum name)` shape and publishes it only
after resource initialization returns successfully.

The following launch loads the shapes before the rules loader and reconstructs a fresh
`ArrayList` and fresh mutable `Misc.Token` objects on every call. Nothing mutable is shared across
expressions or callers. The same game constructor still derives variable memory keys and names on
every rebuilt token.

## Safety and lifecycle

- The artifact name uses the exact SHA-256 identity of the ordered merged `rules.csv` provider
  corpus. A changed mod set, provider order, or rules file selects a different artifact.
- The bytecode adapter remains bound to the exact installed game archive and expression class, so
  changed tokenizer/game code disables the reviewed transform.
- The format is size/count bounded, SHA-256 checksummed, and written transactionally through a
  forced temporary file and atomic replacement where supported.
- A missing, truncated, corrupt, wrong-profile, over-limit, duplicate-expression, or unknown-enum
  artifact is never served. The affected expression runs vanilla and may be relearned.
- Publication occurs at the reviewed resource-initialization completion marker. A startup that
  fails earlier cannot replace the last good artifact.
- Cache pruning recognizes `.sprt` and keeps/removes it by the same live rules identity as the
  adjacent prepared rules CSV.

Focused coverage includes byte-for-byte round trips, checksum/truncation rejection, fresh-object
semantics, mutation isolation, cold publication, cross-session prepared reuse, corrupt-artifact
fallback, and stale-artifact pruning. The full reactor is verified after the live gate.
