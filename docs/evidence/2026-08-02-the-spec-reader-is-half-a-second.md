# The spec reader is worth half a second, and that is all

**Date:** 2026-08-02
**Install:** Starsector 0.98a-RC8, 89 mods installed, macOS 15, M5 MacBook Air
**Benchmark:** the game's own JVM (Zulu 17.0.10 **x86_64**, under Rosetta), page cache warmed
**Status:** measured. **Sized and shelved, not rejected** -- the win is real, small, and safe.

The question was whether the JSON/CSV loading path has the same shape as
[the sector-scanning lookup](2026-08-02-a-failed-lookup-scans-the-sector.md). It does not. It is a
different mechanism, and it is much cheaper than the profile made it look.

## What the reader does

`LoadingUtils.super(InputStream)` is the reader every spec file passes through:

```java
byte[] buf = new byte[1048576];              // 1 MiB, per call, whatever the file size
StringBuffer sb = new StringBuffer();        // synchronised, unpresized
while ((n = in.read(buf)) != -1)
    sb.append(new String(buf, 0, n, "UTF-8"));   // a String per chunk
return sb.toString().replaceAll("\\r", "");      // full copy, then a regex over the whole file
```

A 1 MiB allocation per call for a file averaging 2,690 bytes; a `StringBuffer` that grows by
doubling; `toString()`, which copies everything again; and a `Pattern` compiled per call to delete
one character. Four full copies of every file.

## The corpus

Every extension the game parses as JSON, across the install and all 89 mods:

| | files | bytes |
| --- | ---: | ---: |
| `.json` + `.csv` | 2,586 | 23.0 MiB |
| plus `.ship` `.variant` `.wpn` `.faction` `.system` `.skin` `.proj` | **17,666** | **45.3 MiB** |

## What it costs

| | whole corpus | per file |
| --- | ---: | ---: |
| vanilla (1 MiB buffer, `StringBuffer`, `replaceAll`) | **0.767 s** | 43.4 us |
| same, but `String.replace` instead of the regex | 1.519 s | 86.0 us |
| `Files.readAllBytes`, strip `\r` in place, decode once | **0.292 s** | 16.5 us |

**The whole reader is 0.767 s of a 62.6 s startup.** Reading it optimally saves **0.475 s** -- 0.76%.

Real, and nowhere near the 12.3% of `main` execution samples that had `LoadingUtils` on the stack.
That share is the *parsing* underneath it -- `JSONTokener`, `JSONObject`, `JSONObject.stringToValue`
-- plus 76 samples inside `java.util.regex` reached from `LoadingUtils`. The reading is not the cost;
the tokenising is, and that is a separate question with a much less obvious seam.

### Do not swap the regex for `String.replace`

`replaceAll("\\r", "")` is **twice as fast** as `replace("\r", "")` here. `String.replace(CharSequence,
CharSequence)` builds its result through a `StringBuilder` with bounds work per match, while
`replaceAll` on a one-character pattern gets a fast path through `Matcher.appendReplacement`. The
obvious cleanup makes it slower. The win is in not making four copies, not in avoiding the regex.

## What is not established

- **No claim that 0.475 s appears at startup.** The benchmark reads every spec file once with the
  page cache warm; the game reads a subset, cold, interleaved with parsing and with the root probing
  measured [separately](2026-08-02-what-a-root-probe-costs.md). This is an upper bound on the
  reader's share, not a prediction.
- **The parse cost was not measured**, only observed in the profile.
- **Nothing was built.** `LoadingUtils` is obfuscated (`super(InputStream)`), so a splice would need
  a pinned digest like every other target, and 0.475 s does not currently justify one.
