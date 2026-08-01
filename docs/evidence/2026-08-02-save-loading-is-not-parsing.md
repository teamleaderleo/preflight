# Save loading is not parsing, and it is not reference tracking

**Date:** 2026-08-02
**Corpus:** 100 real campaigns, 8.9 MB to 103.3 MB, from the operator's own save history
**Method:** offline scans and microbenchmarks on the game's own x86_64 JVM. No launch.
**Status:** two of the three candidates are measured and both are small. The third is dominant by
elimination and has not been measured directly.

## A real corpus, not one early-game save

Earlier save work used the five campaigns in the live install, all 32-43 MB and all early-game --
a limitation worth naming, because it is the operator who named it. The archived history provides
**100 campaigns spanning 8.9 MB to 103.3 MB**, and the largest is 2.4x anything measured before.

| | small | large | ratio |
| --- | ---: | ---: | ---: |
| bytes | 9.5 MB | 103.3 MB | 10.8x |
| elements | 232,738 | **2,783,874** | 12.0x |
| distinct tag names | 1,295 | 3,557 | 2.7x |
| `z=` ids defined | ~124,000 | **1,180,319** | 9.5x |
| embedded `<j0>` JSON blobs | 5,634 | 72,922 | 12.9x |

What grows is content, not structure. `Astrd` goes from **0 to 57,079** elements; `set` from 1 to
70,407. A late campaign is the same shapes, many more times.

**The reference redundancy holds across the whole corpus: 89.2% to 92.4%, mean 91.5%.** The 103 MB
save is 89.3% dead. So [the rejection of the pre-scan](2026-08-02-what-is-left-measured-without-launching.md)
survives scaling -- 1.05M dead registrations is about 42 ms of `HashMap.put` against a ~67 ms scan
of a 103 MB file. Still net negative, and now tested at 2.4x the size.

## Which parser the game actually uses

XStream's no-arg constructor needs `xmlpull`, which is **not present anywhere in the install** --
`new XStream()` throws `NoClassDefFoundError` on the full game classpath. So Starsector must pass a
driver explicitly, and it does.

Searching the jars' class bytes in memory (extracting them to disk silently fails -- obfuscated
names such as `void`, `new` and `Ö00000` collide on a case-insensitive filesystem, and only 14 of
2,818 classes survive, which is worth knowing before trusting any grep over an extracted copy):

```
starfarer_obf.jar   2,818 classes, 13 referencing XStream
    com/thoughtworks/xstream/io/xml/StaxDriver        1
    com/thoughtworks/xstream/io/xml/QNameMap          2
    com/thoughtworks/xstream/io/HierarchicalStreamReader  19
    ...
  owners: com/fs/starfarer/campaign/save/C, CampaignGameManager$5, $6, $6$1
```

**`StaxDriver`** -- the JDK's built-in StAX pull parser. So a StAX measurement is the right one, not
an approximation. (Also worth filing: `CampaignGameManager` is *not* obfuscated.)

## The parse floor

StAX over the 103.3 MB save, reading every element name and every attribute name and value,
constructing nothing, on the game's own JVM:

| | |
| --- | ---: |
| elements | 2,783,874 |
| text characters | 24,722,224 |
| **best of 3** | **685 ms** |
| throughput | 150.8 MB/s |
| per element | 0.25 us |

**Parsing the largest save in the corpus takes under a second.**

## The decomposition

Three candidates were named when save loading was first raised. Two now have numbers:

| candidate | 103 MB save | status |
| --- | ---: | --- |
| XML pull-parsing | **685 ms** | measured |
| reference tracking | **~42 ms** | measured |
| **reflective object construction** | **everything else** | **by elimination** |

A Starsector campaign of this size does not load in 0.7 seconds. Whatever the true figure is,
parsing and reference bookkeeping together are a small fraction of it, and **the cost is building
roughly 2.8 million objects through XStream's converters and reflection.**

This kills a whole family of ideas before any of them is built:

- A faster XML parser buys at most 685 ms, and only a fraction of that.
- A binary intermediate format that skips XML parsing buys the same 685 ms, while costing a cache,
  a prepare step, and an invalidation story. **This was the shape the original save-caching idea
  assumed, and it is the wrong shape.**
- Reference-map filtering buys 42 ms and costs more than that.

## What is left, and what would have to be true

The remaining cost is object construction, and Preflight's usual move -- precompute it and reuse it
-- does not obviously apply, because the output is a live object graph that cannot be serialised
back into anything cheaper than the save file already is. That is the honest difficulty.

Routes that are not obviously dead, none measured:

1. **Converter dispatch and reflection.** XStream resolves a converter per element and sets fields
   reflectively. 2.8 million times, that is where the time should be. Generated accessors or
   converter caching are the standard fixes and would be a change inside XStream, which is
   unobfuscated and stable -- the friendliest seam
   [already documented](../design/save-reference-filter.md).
2. **The 72,922 embedded JSON blobs**, parsed by a second parser inside the first. Unmeasured and
   easy to measure.
3. **Allocation and GC**, which is what Fast Rendering's XStream `Path` replacement actually
   addressed. Their fix targets memory pressure, and this analysis says memory pressure is where
   the remaining cost lives, so their instinct was right even though their mechanism was narrow.

**The next measurement is a profile of a real save load**, and unlike everything in this document it
needs the game running and a save being loaded through the UI. Sample *proportions* survive thermal
throttling; the wall clock does not.

## Addendum: a synthetic graph says XStream is not the bottleneck either

The 103 MB save cannot be loaded in the live install -- it needs a different mod set -- so the real
load could not be profiled. A synthetic graph answers the same question without the game: build an
object graph, let XStream serialise it, then time XStream unmarshalling it back against a bare StAX
pass over the identical bytes. Same library, same `StaxDriver`, same `ID_REFERENCES` mode, on the
game's own JVM.

174,762 objects, 13.4 MB, 821,218 elements:

| | ms | us/element |
| --- | ---: | ---: |
| StAX parse only | 78 | 0.095 |
| **XStream unmarshal (builds objects)** | **374** | **0.455** |

**Construction is 4.8x the parse, and parse is 21% of the total.** Scaled to the real save's
2,783,874 elements: **0.26 s parsing, 1.27 s unmarshalling.**

Even allowing that real elements are richer -- the real save parses at 0.25 us/element against this
graph's 0.095, so call it 2.6x heavier and put unmarshalling near 3.3 s -- **generic XStream work
does not explain a slow save load.**

Which leaves an uncomfortable and honest conclusion: **every number in this document is unanchored,
because nobody has measured how long a real Starsector save actually takes to load.** The analysis
assumed "tens of seconds" and never checked. If a 100 MB campaign really does load in a handful of
seconds, there is no save-loading problem to solve and this whole line of work is closed. If it
takes thirty, then roughly 90% of that time is something other than parsing, reference tracking, or
XStream reflection -- post-load fixup, sector regeneration, script initialisation -- and none of the
seams examined here touch it.

**The missing measurement is cheap and available.** The live install has five 32-43 MB saves that
load in the current mod configuration. Timing one, with a profile attached, anchors everything above
and decides whether this line continues. It needs the game running and a save loaded through the UI.

> **Answered the same day: [a save load is 96 seconds](2026-08-02-a-save-load-is-ninety-six-seconds.md).**
> A 42.7 MB save took **96.0 s** -- longer than the whole optimized startup. XStream is **13.3 s
> (14%)**, so this document's estimate of ~3.3 s was low by about 4x, but its conclusion was right
> and is now measured: **86% of a save load is not XStream.** The largest item is intel/mission
> regeneration at 30%, and the JSON/spec path costs another 18% -- meaning that corpus is paid at
> launch *and* on every save load.

Caveats on the synthetic: the node type carries six scalar fields, a nested object, a child list and
one cross-link. Starsector's classes are richer and some have custom converters, so this is a lower
bound on per-element construction cost. It is not a lower bound on total load time, because it
models only the XStream portion.
