# Audio decode probe

`preflight audio decode-probe` answers one question, and the answer decides whether the prepared
audio milestone gets built at all:

> Does Starsector open every declared sound effect while it loads, or only when something first
> plays one?

[The audio census](audio-census.md) sized what that would cost — 1,803 declared effects expanding to
**1,172.6 MB** of PCM in the profile it was measured against — without establishing whether the game
pays it. Both answers are useful and only one of them is a project:

- **Eager.** Every effect is decoded during load. That is the largest unclaimed cost measured
  anywhere in this repository, and prepared audio is worth building.
- **Lazy.** Effects are decoded on first play. Prepared audio would move work out of moments nobody
  is timing and into a cache nobody needed. It should not be built.

## Running it

Two steps. The first needs a real launch; the second does not.

```bash
preflight run --trace-all-file-reads
```

Play until the main menu is up, then quit normally. The recording lands in
`~/.starsector-preflight/runs/<timestamp>/startup.jfr`.

```bash
preflight audio decode-probe ~/.starsector-preflight/runs/<timestamp>/startup.jfr
```

## Why the ordinary recording will not do

The startup recording enables `jdk.FileRead` with a **one-millisecond threshold**. That is the right
setting for finding what cost time and the wrong one for finding what was *opened*: a small file read
from a warm page cache finishes well inside a millisecond and produces no event.

This is not a theoretical concern. The same program reading the same 200 `.ogg` files, recorded both
ways:

| | file read bytes recorded |
| --- | ---: |
| `--trace-all-file-reads` | 36.5 MB |
| default threshold | **0** |

A probe run against the second recording would have found no sound reads at all and reported a
confident `LAZY` — the exact answer that cancels the milestone. So the agent now records whether the
threshold was lifted, and **a recording made without the flag is refused rather than interpreted.**

## What a verdict means

| Verdict | Basis |
| --- | --- |
| `EAGER` | ≥90% of declared effects were opened during the run |
| `LAZY` | ≤10% were opened |
| `INCONCLUSIVE` | neither; the numbers are reported without a conclusion |
| `UNUSABLE` | the recording cannot answer the question |

The two directions are not equally strong, and the tool says so rather than flattening them:

**`LAZY` is a proof.** A file the process never opened cannot have been decoded. Nothing is inferred.

**`EAGER` is strong evidence, not proof.** A read is not a decode. Every declared effect being opened
in one burst during load, off a shared calling frame, is consistent with eager decoding and hard to
explain otherwise — but it remains a statement about reads.

The report includes the calling frame for every audio read, which is most of what separates "the game
loaded these" from "something walked the directory".

## Output

```
Audio decode probe

  recording spans 61.2 s, 48219 file reads, 4747 of them audio

  effect          2050 declared    2050 opened       0 never opened
                 first opened at p0 8.1 s, p50 14.2 s, p90 19.8 s, p100 21.0 s
  music            156 declared       3 opened     153 never opened
  unreferenced     220 declared       0 opened     220 never opened

  read by
        4747  com.fs.starfarer.<...>

  EAGER
  ...
```

`--json` prints the machine-readable report; `--output <path>` writes it. The exit code is 0 for any
answer including `LAZY` — a measurement that says "do not build this" is a successful measurement —
and 4 only for `UNUSABLE`, so a script cannot mistake a refused recording for a result.

## Cost of running it

`--trace-all-file-reads` records every file read in the JVM with a stack trace, so the recording is
substantially larger than a normal startup trace and the run carries more profiling overhead. Neither
changes *which* files get opened, which is the only thing being measured.

## Verification

`AudioDecodeProbeTest` builds real Flight Recorder output rather than a stub, because the part worth
testing is the join between a `jdk.FileRead` path and a census logical path. That join is exactly
where a silent failure would produce a confident wrong answer, and it did during development: the
first version compared normalized paths, which do not resolve symlinks, so every file under a
symlinked directory looked unopened. Paths are canonicalized on both sides now, and the test that
caught it runs against a `@TempDir`, which on macOS is reached through one.
