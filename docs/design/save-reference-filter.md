# Design: filtering XStream's reference map during a save load

**Date:** 2026-08-02
**Status: rejected on measurement. Do not build this.** The seam analysis below is correct and worth
keeping -- it is the cleanest interception point this project has found, and some other idea may want
it -- but the optimization it was written for does not pay. The scan costs **26 ms** to avoid
**18 ms** of registrations, on both the native and the game JVM. See
[the correction](../evidence/2026-08-02-what-is-left-measured-without-launching.md) for the table.
`SaveReferenceScan` remains in `preflight-core` as a measurement instrument, not as a load-time
component.
**Depends on:** `SaveReferenceScan` (landed), the adapter's pinned-target machinery (exists)

The measurement is in
[what is left, measured without launching](../evidence/2026-08-02-what-is-left-measured-without-launching.md):
across all five saves on this install, **90.7%-91.0% of the ids XStream registers are never looked
up.** This is how that redundancy could be removed, and what makes it safe.

## Why this seam is unusually friendly

Every other seam this project has touched lives in `starfarer_obf.jar` or `fs.common_obf.jar`:
obfuscated, renamed per platform (58% of symbols diverge across Windows/macOS/Linux, per the
[macOS fork's mapping tables](../prior-art-starsector-render.md)), and pinned by class hash because
names cannot be trusted.

The reference map does not live there. It lives in a stock third-party library:

```
/Applications/Starsector.app/Contents/Resources/Java/xstream-1.4.10.jar
  sha256 a1587f35fa617513607c86ec9e6e4de5eb8acdf9a3a6d7f7458f8a8c40b00858

com/thoughtworks/xstream/core/AbstractReferenceUnmarshaller.class
  sha256 5a42cb859c35396da6229f36e8d46d2b7d694b21a1729ae7b179b4c832aab776
com/thoughtworks/xstream/core/ReferenceByIdUnmarshaller.class
  sha256 df021584e3f34d6e16da16de21b8ba5d35ae2375604ede54e00640f2133a533d
```

XStream 1.4.10 is public, unobfuscated, identically named on every platform, and released in 2017 --
so the class hashes are stable across installs in a way no Starsector class is. A target pinned here
is pinned once, not per platform.

## Where the registrations happen

`AbstractReferenceUnmarshaller.convert(Object, Class, Converter)`, disassembled. Two sites write to
the `values` map, and **both are gated on the reference key**:

```
  // site 1 -- registers the parent under construction, so a child can point back at it
  0:  parentStack.size()  ifle -> 51
  14: parentStack.peek()  -> key
  21: ifnull -> 51                     <-- a null parent key skips this site
  30: values.containsKey(key) ifne -> 51
  44: values.put(key, result)

  // site 2 -- registers the object just built
  216: getCurrentReferenceKey() -> key
  228: parentStack.push(key)
  236: TreeUnmarshaller.convert(...)   -> the real work
  243: ifnull -> 271                   <-- a null key skips this site
  265: values.put(key, converted)
  275: parentStack.popSilently()
```

So **a null reference key skips both registrations**, and it does so through branches XStream
already takes for unreferenceable objects. Nothing new has to be understood about the map, the
stack discipline, or the exception paths.

The concrete key producer is 30 bytes:

```java
// com.thoughtworks.xstream.core.ReferenceByIdUnmarshaller
protected Object getCurrentReferenceKey() {
    String alias = getMapper().aliasForSystemAttribute("id");   // "z" in Starsector's config
    return alias == null ? null : reader.getAttribute(alias);
}
```

## The change

Splice that one method to consult the scan:

```java
protected Object getCurrentReferenceKey() {
    Object id = /* original body */;
    return SaveReferenceFilter.keep(id) ? id : null;
}
```

`getReferenceKey(String)` -- the *lookup* side, called when a `ref=` is resolved -- is untouched, so
every reference that is actually followed still resolves exactly as before.

## Why it is safe, and where it is not

**The asymmetry is the whole design.** Registering an id nobody asks for wastes a map entry.
Failing to register one that *is* asked for makes `values.get()` return null and XStream throw. So
the filter may only ever over-approximate, and `SaveReferenceScan` is built to that rule: any
ambiguity includes more ids, and anything it cannot parse marks the result unusable, after which
`mustRegister()` returns true for everything and behaviour is bit-for-bit what it is today.

Three things still have to be true, and only the first is proven:

1. **A null key skips both sites.** Proven from the bytecode above.
2. **The scanned file is the file being unmarshalled.** Not yet solved -- see below.
3. **Nothing else reads `values` by iteration.** `values` is private, and the only reads are the two
   `containsKey`/`put` sites and the `get` on the lookup path. Confirmed for 1.4.10 by
   disassembly; would need re-confirming if the bundled version ever changes, which the pinned
   class hash forces.

### The open problem: binding a scan to a load

`getCurrentReferenceKey()` has no idea which file it is reading. Something has to run the scan and
publish the resulting bitset before unmarshalling starts, and clear it afterwards. Options, in
increasing order of intrusiveness:

- **Hook the save-stream open.** Cleanest if a single obfuscated method opens `campaign.xml`; costs
  a per-platform pinned target in `starfarer_obf.jar`, which is what we were trying to avoid.
- **Hook XStream's own entry point** (`XStream.unmarshal` / `fromXML`), which is again unobfuscated,
  and derive the path from the reader if it exposes one. Needs checking -- a `Reader` usually does
  not carry its origin.
- **Scan on first use.** The filter starts inert, and the first `getCurrentReferenceKey()` of a load
  triggers a scan of the newest `campaign.xml` under the saves directory. Cheap to build, but it
  guesses which save is being loaded, and guessing wrong must fall back to registering everything
  rather than filtering by the wrong bitset.

The first option is most likely correct; discovering the method requires running the game with the
adapter probe, which is a launch but not a timing measurement.

## What this is worth: nothing, measured

The question this section originally left open -- "if the registrations cost less than the scan,
this is not worth shipping" -- was answered by a microbenchmark on the real 40.6 MB save, 7 trials,
medians:

| | native arm64 JDK 21 | game JVM (x86_64, Rosetta 2) |
| --- | ---: | ---: |
| scan for `ref="` | 26.6 ms | 26.2 ms |
| register 440,117 ids | 16.2 ms | 19.2 ms |
| register 40,659 ids | 0.8 ms | 1.0 ms |
| **net** | **-11.2 ms** | **-8.0 ms** |

A `HashMap.put` with a short String key costs about 40 ns. Four hundred thousand of them is 18 ms --
a real number attached to a big-sounding count, and noise inside a load measured in seconds. The
benchmark is also generous to the idea: it calls `containsKey` on every key, where XStream only does
so at one of its two sites.

**The general lesson is the durable part.** The 90.8% figure was ranked first because it was the
largest measured redundancy in the profile, and redundancy was quietly treated as a proxy for cost.
It is not. A count is worth measuring into milliseconds before anything is designed around it, and
here that took a single-file benchmark and about five minutes -- against building a save-open seam,
a target pin, an ASM splice and a campaign to find the same thing out.

## Sequence

1. ~~Scan, with the over-approximation rule and tests~~ -- landed as `SaveReferenceScan`, now a
   measurement instrument.
2. ~~Discover the save-open seam~~ -- unnecessary; step 3 came first and closed the question.
3. ~~Measure what the registrations cost~~ -- **done, and the answer is "less than the scan".**
4. ~~The splice, the filter, and a campaign~~ -- cancelled.

What remains open on save loading is where the time *does* go. The three candidates named in the
evidence document -- XML pull-parsing, reflective object construction, reference resolution -- are
still unsplit, and this result removes only a small part of the third. Profiling one real save load
is the next step, and it needs a launch rather than another byte count.
