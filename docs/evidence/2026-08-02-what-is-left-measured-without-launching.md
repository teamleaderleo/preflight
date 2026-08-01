# What is left, measured without launching the game

**Date:** 2026-08-02
**Install:** Starsector 0.98a-RC8, 89 mods present / 77 enabled, macOS 15, M5 MacBook Air
**Method:** offline filesystem census, `preflight audio census`, and a byte scan of a real 42.6 MB save
**Status:** measured facts, no timings. The machine is thermally constrained today (no room cooling),
so nothing here required a launch and nothing here is a wall-clock claim.

The startup campaign settled what the texture path is worth. This measures every corpus we have
*not* optimized, so the next target is chosen from data instead of from the profile's top frame.

## The corpora

| group | files | MB | core files | core MB | mod files | mod MB |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| texture | 33,579 | 1,189.5 | 3,049 | 69.6 | 30,530 | 1,119.9 |
| audio | 2,595 | 568.3 | 829 | 27.3 | 1,766 | 541.0 |
| jar | 107 | 69.7 | 19 | 17.5 | 88 | 52.2 |
| **janino source** | **4,640** | 28.5 | 116 | 0.5 | 4,524 | 28.0 |
| **spec json** | **11,735** | 22.3 | 858 | 1.9 | 10,877 | 20.5 |
| **spec csv** | **1,334** | 18.6 | 36 | 4.5 | 1,298 | 14.1 |

Two readings of that table matter more than the totals.

**Mods are the workload.** Textures: 94% of bytes. Audio: 95%. Janino: 98% of files. Specs: 92%.
Anything measured on vanilla measures almost nothing, and any optimization that scales with mod
count is worth more than its vanilla profile suggests.

**The spec path is a file-count problem, not a byte problem.** 13,069 JSON and CSV files totalling
40.9 MB -- a corpus that fits in L3 cache twice over. Parsing 40 MB of JSON is not a 10-second
problem on any machine. **Opening 13,069 files across 84 resource roots is.** The seam is resolution
and syscalls, not `JSONTokener`, and that folds the "JSON path" item into the same `File.exists`
work as the resource index rather than making it a separate parser project.

That reframing is worth stating plainly because the roadmap has carried "the JSON/spec path is
comparable to the texture path in both time and allocation" since July. In *time*, plausibly. In
*bytes*, it is 40.9 MB against 1,189.5 MB -- 29 times smaller. Whatever the spec path costs, it is
not paying for volume.

## Audio: the eligible set is a third of what the directory tree suggests

`preflight audio census`, current profile, authority = `data/config/sounds.json` rather than paths:

| kind | files | encoded | decoded | seconds |
| --- | ---: | ---: | ---: | ---: |
| **effect** | **2,050** | 133.8 MB | **1,226.2 MB** | 7,744 |
| music (streamed, not eligible) | 156 | 390.4 MB | 3,030.5 MB | 16,698 |
| unreferenced | 220 | 20.6 MB | 230.9 MB | 1,049 |
| all | 2,426 | 544.8 MB | 4,487.5 MB | 25,491 |

**A path-based split gets this badly wrong, and I made that error before checking.** Walking
directories and calling anything under `music/` music gives 167 music files and 2,428 effects. The
declared truth is 156 and 2,050. The gap is mods that keep seven-minute themes in `sounds/` --
`nsp_rampager_theme.ogg` is 254 seconds and `nsp_templar_theme.ogg` is 440, both declared as
effects. This is the exact failure the July census already documented, so the tool exists precisely
because the intuitive method is wrong.

Two distributions inside the effect set decide what a prepared-audio policy should do:

| effect duration | files | decoded |
| --- | ---: | ---: |
| 0-1s | 493 | 31.1 MB |
| 1-5s | 1,243 | 400.7 MB |
| 5-15s | 256 | 354.0 MB |
| 15-60s | 41 | 127.3 MB |
| **60s+** | **17** | **313.0 MB** |

**17 files are 25.5% of all effect PCM**, and they are mislabelled music. A duration cap at 60
seconds removes a quarter of the cost and loses nothing a player would notice; a cap at 15 seconds
removes 36% for 58 files.

| effect sample rate | files | encoded | decoded |
| --- | ---: | ---: | ---: |
| **192,000 Hz** | **141** | 14.3 MB | **345.1 MB** |
| 96,000 Hz | 56 | 3.9 MB | 46.4 MB |
| 48,000 Hz | 132 | 8.3 MB | 54.1 MB |
| 44,100 Hz | 1,559 | 100.6 MB | 737.3 MB |
| <= 32,000 Hz | 161 | 6.2 MB | 43.3 MB |

**141 files ship at 192 kHz and cost 345 MB of PCM -- 28% of the entire effect corpus.** They are
almost all from one mod. 192 kHz carries no information a human can hear, OpenAL will resample it
anyway, and at 44.1 kHz those same files would occupy 79 MB. That is **266 MB of RAM and decode work
bought for nothing**, and it is a property of the content, not of the game.

The census also finds **2 genuinely broken files** in one mod: `melta_fire.ogg`, whose first Ogg
packet is not a Vorbis identification header, and a truncated `bt_holy_aura_charge.ogg`.

**So the answer to "is there much in audio" is: less than 568 MB suggests, more than intuition
suggests.** 1.23 GB of decodable effect PCM is real; but a third of it is misclassified music and
oversampled content that no cache should be storing in the first place. Fixing the *content* beats
caching it.

## The save: 90.8% of the reference table is never used

A real 42.6 MB campaign, byte-scanned:

```
campaign.xml            42,609,080 bytes
open tags                1,048,357
distinct tag names           2,335
z=  ids defined            440,117   (42.0% of all elements)
ref= uses                  178,491   (17.0% of all elements)
distinct ref targets        40,659
ids never referenced       399,458   -- 90.8%
embedded <j0> JSON blobs    16,736
```

XStream is serialising with **abbreviated id references** (`z=` defines, `ref=` points). Its
unmarshaller registers every `z=` it encounters into a reference map so that a later `ref=` can find
it. On this save that is **440,117 registrations to service 40,659 lookups.**

**Nine out of ten entries in that map are never read.**

The fix is a pre-pass, and it is cheap in a way that matters: finding the set of referenced ids is a
linear scan for one byte pattern over 42 MB. The scan above took 0.08 seconds in Python; in Java
over a mapped buffer it is milliseconds, and unlike SHA-256 it has no intrinsic to lose under
Rosetta. Feed that set to the unmarshaller and it registers 40,659 objects instead of 440,117 --
**a 90.8% cut in reference-tracking work, with the answer computed from the same file being loaded,
requiring no cache, no prepare step, and no persisted artifact.**

It fails open trivially: if the pre-scan does not run or the format is unrecognised, register
everything, which is current behaviour.

> **Correction, measured the same day. This does not work, and the paragraph above oversold it.**
>
> A large *count* is not a large *cost*. Both halves of the trade, measured on the real 40.6 MB
> save, 7 trials, medians:
>
> | | native arm64 JDK 21 | game JVM (x86_64, Rosetta 2) |
> | --- | ---: | ---: |
> | scan the file for `ref="` | 26.6 ms | 26.2 ms |
> | register 440,117 ids | 16.2 ms | 19.2 ms |
> | register 40,659 ids | 0.8 ms | 1.0 ms |
> | **registration saved** | **15.4 ms** | **18.2 ms** |
> | **net** | **-11.2 ms** | **-8.0 ms** |
>
> **The scan costs more than the registrations it avoids, on both JVMs.** 400,000 `HashMap.put`
> calls are about 40 ns each; the whole redundancy is worth roughly 18 ms inside a load measured in
> seconds. And the benchmark is generous to the idea -- it pays `containsKey` on every key, where
> XStream only does that at one of its two registration sites, so the real saving is smaller still.
>
> A second thing worth keeping: **Rosetta barely penalises either operation** (26.6 vs 26.2 ms, 16.2
> vs 19.2 ms). Both are memory-bound rather than instruction-bound, which is the opposite of the
> 11.4x penalty SHA-256 pays. "Runs under Rosetta" is not a blanket multiplier -- it hits code that
> leans on x86 extensions, and barely touches pointer-chasing and byte scanning.
>
> What this does **not** close is the memory argument: 440,117 retained map entries plus their key
> strings is tens of megabytes held for the duration of a load, and Fast Rendering's XStream work
> was a GC-pressure fix on this exact subsystem. That is a different claim needing a different
> measurement, and it argues for reducing retention rather than for this pre-scan.
>
> `SaveReferenceScan` stays in the tree -- it is correct, tested, and it is the instrument that
> produced this table -- but it should not be wired into a load as a speedup.

What this does **not** establish is how much of save-load wall time is reference tracking versus XML
pull-parsing versus reflective object construction. Those have completely different fixes and the
split is unmeasured. What it does establish is that one of the three has a large, exactly-quantified
redundancy in it.

Two other structural facts, for whoever measures it:

- **One tag is 20% of the file.** `<st>` appears 209,270 times, and the sample shows it is a short
  string element (`<st>$nex_alignments</st>`) -- a key or type token, not a stat object. The top 25
  tags are 60.5% of all elements, so the parser's work is concentrated in a very small alphabet.
- **16,736 embedded JSON blobs** sit inside `<j0>` elements, so a save load also runs the JSON
  parser 16,736 times on top of the XML parse.

### Where Fast Rendering already is

They replaced `com/thoughtworks/xstream/io/path/Path` with their own, and `PathNode.attachNode`
interns path chunk arrays into a shared trie so that many `Path` objects share structure. Their
changelog calls it "memory-optimized" and pairs it with fixes for freezes during saving/loading.

**That is a GC-pressure fix, not a work-elimination fix**, and it is orthogonal to the finding above:
they made each tracked path cheaper; the pre-scan stops tracking 90.8% of them. Both can apply.

## What falls out of this

Ranked by measured redundancy rather than by guess:

1. ~~**Save-load reference pre-scan.** 90.8% of a 440,117-entry map is dead.~~ **Measured and
   rejected the same day** -- the scan costs 26 ms to avoid 18 ms of registrations. See the
   correction above. The lesson is worth more than the idea was: this document ranked seams by
   *redundancy* on the assumption that redundancy implies cost, and for this one it did not. The
   remaining items below inherit that doubt until each is measured, and the microbenchmark that
   settled it took minutes.
2. **Resource resolution from the index.** 13,069 spec files plus 4,640 script files plus 33,579
   textures all resolve through the same 84-root probe. This is one fix serving every corpus, and it
   subsumes what was filed as a separate "JSON path" project.
3. **Persisted Janino bytecode.** 4,640 source files, 98% from mods. Fast Rendering parallelises the
   compile and memoizes it in a `ConcurrentHashMap` that dies with the process; persisting that map
   is the whole idea.
4. **A profile linter, not a cache, for audio.** 266 MB of the effect corpus is oversampling and
   313 MB is mislabelled music. Telling a user "this mod ships 141 files at 192 kHz" is worth more
   than caching the decode of them, costs nothing to compute, and needs no game integration.
5. **Prepared audio for what remains.** After a duration cap and excluding oversampled content, the
   eligible set is well under half of 1.23 GB.

## Method note

Every number here came from reading files, not from running the game. That is deliberate: the
machine is thermally constrained today and any wall-clock figure taken now would be unreportable.
Counts, byte totals and structural ratios are not affected by temperature.
