# Technical writeup draft

Blog-length piece on how Preflight works. Every number here traces to a retained report under
[evidence/](evidence/); check them before publishing, and mark the condition each one came from.

---

## Making a heavily modded Starsector start in 16 seconds instead of 88

Starsector with 83 mods took about a minute and a half to reach the main menu on my machine. The
five-run median was 88.13 seconds, and the worst run was around 101. It now reaches the menu in
15.88 seconds warm.

Almost none of that came from clever code. It came from noticing that the game does the same
expensive work every single launch, and that nearly all of it is work whose inputs haven't changed
since last time.

### First: most of my early measurements were wrong

The thing I'd tell anyone starting this: you will measure the wrong thing, confidently, for a
while.

An early profile showed a 4.8–5.9 second gap before resource loading that I spent real time trying
to explain. It turned out to be a clock-origin artifact — two timestamps from different origins
being subtracted. There was no gap. A later pass found the harness itself was contributing an
18-second error that had gone unnoticed for a month, because the measurement was self-consistent:
every run agreed with every other run, and all of them were wrong together.

Self-consistency is not correctness. What fixed it was checking the harness against the game's own
artifacts — its logs, its bytecode — instead of against my own model of what it must be doing.

So the ordering that worked was: measure, distrust the measurement, verify it against something
external, *then* optimize. Every number below is from a retained report with its exact conditions,
because I no longer believe numbers that aren't.

### What startup actually spends its time on

Once the clock was trustworthy, the profile was unglamorous. In a heavily modded install, startup
is dominated by:

- **Decoding textures.** Thousands of PNGs decoded to raw pixels, every launch.
- **Parsing and merging mod data.** Ship, weapon, projectile, hull and variant JSON, plus campaign
  rules CSV — read, parsed and merged from scratch each time.
- **Generating bytecode.** Mods that compile classes at runtime do it again on every start.
- **Decoding audio.** Same files, same result, every time.

The pattern is the same in every case. The game has no reason to assume any of it stayed the same
between runs, so it redoes all of it. But on a machine where you haven't touched your mods since
yesterday, all of it *did* stay the same.

### The fix is caching, and the interesting part isn't the cache

Preflight does the work once, ahead of launch, and stores the result keyed by a fingerprint of the
exact inputs: the game build, the ordered mod profile, and the content of the files themselves. On
the next launch it hands the game the stored answer.

Where it actually got hard was the other half: **being correct when the assumption breaks.**

A cache that's wrong 1% of the time isn't 99% good, it's unusable — a stale texture or a mismatched
merged JSON is a corrupted game that fails somewhere far away from the cause, and the player has no
way to connect the two. So every shortcut is gated on an exact identity check: the class it's
about, the source it was loaded from, the loader that loaded it. If any of that doesn't match what
the shortcut was reviewed against, the shortcut declines and the game's own code runs.

That's why an unknown Starsector version degrades instead of breaking. It's also why "we don't
support that mod" means "no speedup claimed", not "it'll crash".

Concretely, the containment rules are:

- Nothing is permanently modified — not the game, not mod JARs, not assets, not saves. Runtime
  changes exist only in the launched JVM and vanish when it exits.
- Every optimization has an independent kill switch, plus one global switch.
- On any uncertainty — identity mismatch, validation failure, a cache that doesn't verify — the
  original path runs.
- Prepared data is content-addressed and validated on read, so a truncated or drifted cache is
  rejected rather than used.

### Where the time went

Measured contributions on the 83-mod development profile:

| Change | Measured saving |
| --- | ---: |
| Prepared textures and prefetch bypass | 25.53s |
| AshLib repeated ship JSON | 7.07–7.44s |
| GraphicsLib compact auto-generation replay | 4.82s |
| Merged weapon, projectile and ship-hull JSON | ~4.8s combined |
| Merged variant JSON | ~2.7s |
| Cache-profile identity pass | 1.613s → 0.452s |

Some of the per-boundary ratios are more striking than the totals:

| Boundary | Before | After | Speedup |
| --- | ---: | ---: | ---: |
| Variant merge/parse | 3.289s | 0.324s | 10.15× |
| AshLib callback | 9.778s | 2.712–2.343s | 3.61–4.17× |
| GraphicsLib callback | 8.503s | 5.465s | 1.56× |

Textures dominate, which is unsurprising in hindsight and wasn't obvious at the start — I expected
JSON parsing to be the story, and spent a while there before the profile pointed elsewhere.

### The unglamorous bugs were the expensive ones

A sample, because these are the ones that actually cost time:

- A stop-acknowledgement was written with a plain file write, which creates and truncates before
  writing. A reader polling for the file could catch it existing and empty, and conclude the
  operation had failed. The fix is the standard one — write to a temporary sibling, then rename.
  A test that polls tightly while publishing 2,000 acknowledgements saw about 140 empty reads
  before the change and none after.
- Launcher discovery resolved a filename candidate that, on a case-insensitive filesystem, matched
  a *directory* of the same name, and the discovered path came back with the wrong spelling.
- A test server set its listener non-blocking to poll for a deadline; on BSD-derived systems the
  accepted socket inherits that flag, so the server reset connections the client was still writing
  into. It looked exactly like the client misbehaving.

None of these are interesting computer science. All of them are the difference between a tool
people trust and one they uninstall.

### What I'd tell someone doing this

1. **Your measurement is wrong until something external agrees with it.** Self-consistent numbers
   are the dangerous kind.
2. **Write down the condition, not just the number.** "16.9s" is useless in a month. "One run,
   uncooled, cold cache, 83-mod profile, macOS" survives.
3. **Decide what happens when you're wrong before you decide how fast you'll be.** The fallback
   design is what made the aggressive parts safe to attempt.
4. **The boring wins are the big ones.** The single largest contribution here is "don't decode the
   same PNG twice."

---

**Numbers to verify before publishing:** the 101 / 88.13 / 15.88 progression and its conditions,
every row of both tables, the 2,000-publication flake figure, and the "18-second error" claim —
that last one is from working notes rather than a retained report, so it needs a citation or it
should come out.
