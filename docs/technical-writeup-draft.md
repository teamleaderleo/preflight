# Technical writeup draft

Blog-length piece on how Preflight works. Every number here traces to a retained report under
[evidence/](evidence/); check them before publishing, and mark the condition each one came from.

---

## Making a heavily modded Starsector start in 13.69 seconds instead of 101

At its worst, Starsector took about 101 seconds to reach the main menu on my machine. The current
retained development endpoint is **13.69 seconds**. The mod list grew while I was working on it, from
77 mods to 83, so the full 101 → 13.69 span is a development chronology.

One historical A/B campaign also measured both conditions on the same 83-mod profile: five ordinary
launches had an 89.00-second median and five Preflight launches had a 15.53-second median. I shuffled
the order inside every round and let the machine cool for 240 seconds before each launch. None of the
ten runs were excluded. That arrangement makes the pair useful for the before/after question it was
designed to answer; it does not make those elapsed times a more authoritative kind of startup
measurement than other runs recorded with the same game-log clock.

Most of the result came from noticing that the game does the same work every single launch, even
when the inputs haven't changed since last time.

### First: most of my early measurements were wrong

The thing I'd tell anyone starting this: you will measure the wrong thing, confidently, for a
while.

An early profile showed a 4.8–5.9 second gap before resource loading that I spent real time trying
to explain. It turned out to be a clock-origin artifact. Two timestamps from different origins
being subtracted. There was no gap.

The more expensive one was the benchmark harness. It started its clock on the first timestamped log
line that appeared after it took its snapshot, and the game's launcher writes into the same log the
game does. Whether the launcher's lines had been flushed by that moment decided whether the first
fifth of loading landed inside the measured interval or outside it. So the results split into two
modes about 18 seconds apart, with nothing about the run predicting which mode it landed in, and I
recorded that split as an unexplained property of the game for a couple of weeks. It was a property
of my stopwatch. Recovering each launch's interval from the game's own log, with no harness involved,
showed the low mode understating by 14–18 seconds: startup was ~92s, not the ~75s I had been
quoting.

What fixed it was checking the harness against the game's own artifacts, including its logs and
bytecode, instead of against my own model of what it must be doing. The lesson generalizes past this bug: a
measurement that only ever agrees with itself has told you nothing.

So the ordering that worked was: measure, distrust the measurement, verify it against something
external, *then* optimize. Every number below is from a retained report with its exact conditions,
because I no longer believe numbers that aren't.

### What startup actually spends its time on

Once the clock was trustworthy, the profile was unglamorous. In a heavily modded install, startup
is dominated by:

- **Decoding textures.** Thousands of PNGs decoded to raw pixels, every launch.
- **Parsing and merging mod data.** Ship, weapon, projectile, hull and variant JSON, plus campaign
  rules CSV, read, parsed and merged from scratch each time.
- **Generating bytecode.** Mods that compile classes at runtime do it again on every start.
- **Decoding audio.** Same files, same result, every time.

The pattern is the same in every case. The game has no reason to assume any of it stayed the same
between runs, so it redoes all of it. But on a machine where you haven't touched your mods since
yesterday, all of it *did* stay the same.

### The cache had to know when it was wrong

Preflight does the work once, ahead of launch, and stores the result keyed by a fingerprint of the
exact inputs: the game build, the ordered mod profile, and the content of the files themselves. On
the next launch it hands the game the stored answer.

The difficult part was deciding what should happen when that assumption breaks.

A cache that's wrong 1% of the time isn't 99% good, it's unusable. A stale texture or a mismatched
merged JSON is a corrupted game that fails somewhere far away from the cause, and the player has no
way to connect the two. So every shortcut is gated on an exact identity check: the class it's
about, the source it was loaded from, the loader that loaded it. If any of that doesn't match what
the shortcut was reviewed against, the shortcut declines and the game's own code runs.

That's why an unknown Starsector version degrades instead of breaking. It's also why "we don't
support that mod" means "no speedup claimed", not "it'll crash".

Concretely, the containment rules are:

- Nothing is permanently modified: not the game, mod JARs, assets, or saves. Runtime
  changes exist only in the launched JVM and vanish when it exits.
- Every optimization has an independent kill switch, plus one global switch.
- On any uncertainty, including an identity mismatch, validation failure, or cache that doesn't
  verify, the original path runs.
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

Textures dominate, which is unsurprising in hindsight and wasn't obvious at the start. I expected
JSON parsing to be the story, and spent a while there before the profile pointed elsewhere.

### The unglamorous bugs were the expensive ones

A sample, because these are the ones that actually cost time:

- A stop-acknowledgement was written with a plain file write, which creates and truncates before
  writing. A reader polling for the file could catch it existing and empty, and conclude the
  operation had failed. The fix is the standard one: write to a temporary sibling, then rename.
  A test that polls tightly while publishing the acknowledgement 2,000 times sees hundreds of empty
  reads against the plain write and none against the rename. The count moves with the machine and
  the run; the zero doesn't.
- Launcher discovery resolved a filename candidate that, on a case-insensitive filesystem, matched
  a *directory* of the same name, and the discovered path came back with the wrong spelling.
- A test server set its listener non-blocking to poll for a deadline; on BSD-derived systems the
  accepted socket inherits that flag, so the server reset connections the client was still writing
  into. It looked exactly like the client misbehaving.

None of these are interesting computer science. All of them are the difference between a tool
people trust and one they uninstall.

### What I learned from getting it wrong

I no longer trust a measurement until something external agrees with it. Self-consistent numbers
can still be wrong in exactly the same way every time. I also write down the conditions now. A
number such as “16.9 seconds” becomes useless surprisingly quickly; the machine, profile, cache
state, temperature, and measurement boundary are what let it survive.

The fallback behavior had to be decided before the optimization was allowed to matter. That made it
possible to attempt more aggressive changes without asking an unknown installation to share my
assumptions. The largest improvement still came from a simple observation: the same PNG did not
need to be decoded again.

---

**Where each number came from**

| Claim | Source |
| --- | --- |
| 101s historical high and earlier startup chronology | [Scorecard](evidence/2026-08-02-accumulated-startup-scorecard.md), [claims.json](claims.json) |
| 13.69s retained current development endpoint | [Storage/startup record](evidence/2026-08-23-storage-to-fourteen-seconds.md), [claims.json](claims.json) |
| 88.13s was measured on 77 mods; 15.88s on 83 | [29% campaign](evidence/2026-08-01-twenty-nine-percent-when-they-compose.md), [lazy fleet members](evidence/2026-08-06-codex-lazy-fleet-members.md) |
| 89.00s vanilla and 15.53s `fast`, same 83-mod profile, one interleaved A/B session | [Historical A/B campaign](evidence/2026-08-15-controlled-vanilla-fast-campaign.md) |
| 4.8–5.9s pre-resource gap was a clock-origin artifact | [Save-descriptor memo](evidence/2026-08-06-main-menu-save-descriptor-memo.md) |
| Two modes ~18s apart; low mode understated by 14–18s; ~92s not ~75s | [The bimodality was the anchor](evidence/2026-08-01-the-bimodality-was-the-anchor.md) |
| Every row of both component tables | [Scorecard](evidence/2026-08-02-accumulated-startup-scorecard.md) |
| 2,000 publications, zero torn reads after the rename | `RecordingStopControllerTest.everyObservedAcknowledgementIsComplete` |

Still to fill before publishing: the release URL and a link target for the writeup itself. The
per-boundary tables are development-profile results on one machine, and the piece should keep
saying so wherever it quotes them.
