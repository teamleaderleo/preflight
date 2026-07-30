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
java -jar preflight-cli/target/preflight.jar run --trace-all-file-reads
```

Play until the main menu is up, then quit normally. The recording lands in
`~/.starsector-preflight/runs/<timestamp>/startup.jfr`, and the run prints that directory when it
starts.

```bash
java -jar preflight-cli/target/preflight.jar audio decode-probe \
  "$(command ls -td ~/.starsector-preflight/runs/*/ | head -1)startup.jfr"
```

A campaign run measures more than a menus-only run: the effects a menus-only session never opens are
campaign sounds. Both are useful, and the report says which kind it read.

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
| `EAGER` | the opened effects were opened in a burst — at least 20 files, in a first-open window under 3 seconds or under 5% of the session |
| `LAZY` | none were opened, or they were opened spread across the session |
| `INCONCLUSIVE` | neither shape; the numbers are reported without a conclusion |
| `UNUSABLE` | the recording cannot answer the question |

**The verdict keys on timing, not on how many files were opened.** The first version compared the
opened fraction against thresholds of 90% and 10%, and the first real run appeared to land at 62% —
reported `INCONCLUSIVE` about data that was not remotely ambiguous, since every one of those files was
opened inside 1.5 seconds of a six-minute session. *Lazy* means **at the time of use**, so when the
opens happen is the direct evidence and how many happen is a proxy. A partial fraction with a burst
shape means the session did not reach a later loading phase, and the detail text says so rather than
claiming full coverage.

That 62% was itself a measurement error — see the path resolution note below — and the same run reads
100% now. The timing test stands on its own reasoning rather than on that example.

The window is judged both absolutely and against the session, because session length is an artifact
of how long the player stayed rather than of how the game loads. Comparing only against the session
lets someone who quits ten seconds after the menu appears flip the verdict on a load that behaved
exactly like a two-hour session's. CI found that one, on a runner slow enough that a test's forty
reads were a large share of its short recording.

The two directions are not equally strong, and the tool says so rather than flattening them:

**`LAZY` is a proof.** A file the process never opened cannot have been decoded. Nothing is inferred.

**`EAGER` is strong evidence, not proof.** A read is not a decode. Every declared effect being opened
in one burst during load, off a shared calling frame, is consistent with eager decoding and hard to
explain otherwise — but it remains a statement about reads.

The report includes the calling frame for every audio read, which is most of what separates "the game
loaded these" from "something walked the directory".

**Audio the census cannot account for is counted and shown.** A recording holds reads the census has
no entry for — vanilla music is a single `sounds/music/music.bin` container rather than separate
files. Those reads are not evidence about declared files, but discarding them silently is how a report
comes to say "no music was opened" about a run that read a music container 1,806 times. The count, its
share of all audio reads, and a sample of the paths are printed above the findings whenever it is
non-zero.

**Relative reads are resolved against the directory the game ran in.** Flight Recorder stores the path
the JVM passed to the OS, and Starsector opens its own resources by relative path —
`sounds/sfx_impacts/shield_hit_heavy_01.ogg` — because its launcher changes into the core resource
directory before starting the JVM. Resolving those against Preflight's working directory instead made
every core resource look unopened: 7,309 audio reads on the reviewed profile, a third of the
recording's audio, and a published figure 20% too low.

The agent records the game's own `user.dir`, so the base is a fact the recording carries rather than
something the analysis reconstructs. The report states both the directory and where it came from:

| `gameWorkingDirectorySource` | meaning |
| --- | --- |
| `recording` | the agent stated it; nothing was inferred |
| `core-root` | the recording predates the field, so the core resource root was used |

`relativeFileReadEvents` reports how many reads needed it. A relative path that does not resolve to an
indexed file stays unmatched and is counted, so a base that is wrong shows up as unmatched reads
rather than as silence.

## Output

The reviewed profile, 2026-07-29:

```
Audio decode probe

  recording spans 359.6 s, 418588 file reads, 20182 of them audio the census accounts for

  1806 further audio reads matched no declared file (8% of audio reads). Nothing below describes them.
    sounds/music/music.bin

  effect          2050 declared    2050 opened       0 never opened
                 1169.4 MB of PCM behind what was opened, 0.0 MB behind what was not
                 first opened at p0 23.2 s, p50 23.9 s, p90 24.6 s, p100 24.7 s
  music            156 declared       0 opened     156 never opened
                 0.0 MB of PCM behind what was opened, 2890.1 MB behind what was not
  unreferenced     220 declared       0 opened     220 never opened
                 0.0 MB of PCM behind what was opened, 220.2 MB behind what was not

  read by
       20182  com.fs.graphics.L.Ô00000

  EAGER
  The run opened 2050 of 2050 declared effects, and opened them inside 1.5 s of a 359.6
  s session. ...
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
