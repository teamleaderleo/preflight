# Findings and outreach

## TL;DR

Preflight finds a lot of weird things. **Most of them do not create an obligation to contact anybody.**

Default disposition:

```text
finding
  ↓
Can Preflight safely handle it for users?
  ├─ yes → handle it, document it, stop
  └─ no / source fix is clearly better
          ↓
Is there one clear owner + a small durable fix + strong evidence?
  ├─ no → archive it, stop
  └─ yes → candidate to share someday
```

Outreach should be the exception. A good external report saves the recipient work. It should not hand them a new subsystem, a vague performance theory, or responsibility for Preflight's architecture.

This page is a triage ledger, not a contact queue.

## The test for whether a finding should leave the repository

A finding becomes a strong outreach candidate when most of these are true:

1. **The evidence is direct.** The bytes, trace, reproduction, or user-visible behavior establishes the problem without a long chain of guesses.
2. **There is a clear owner.** One mod or the base game can actually change the thing.
3. **The fix is local.** Re-export an asset, move a key inside a config object, correct a container, avoid one repeated calculation.
4. **The benefit survives without Preflight.** Every user benefits even if they never install this project.
5. **The advice is stable.** It is unlikely to become wrong when a different mod list or machine is used.
6. **The recipient does not inherit a new maintenance burden.** A one-time asset/config fix is much better outreach than “please adopt this cache and keep it coherent forever.”
7. **The likely value clears the social cost.** A silent correctness bug clears that bar easily. A 2 ms micro-optimization usually does not.

If several of those are missing, keeping the result in Preflight is a perfectly good ending.

## Five buckets

### A. Clear correctness defects

These are the strongest things to tell an author because the report is about behavior they already intended to ship.

Current measured examples are in [Config the game silently never reads](evidence/2026-07-28-config-the-game-silently-never-reads.md). Four released-mod findings were checked by hand:

| Mod | Finding | Why it is worth sharing |
| --- | --- | --- |
| exshippack | `PROXIMITY_FUSE` sits after the top-level projectile object | The intended fuse block is outside the value the loader consumes. |
| eusan_nation | `fireSoundTwo` sits after the weapon object | The second fire sound is stranded outside the loaded value. |
| ORK | `pirates.faction` closes early | `priorityWeapons` and later content sit after the early close. |
| Mayasuran Navy | config begins `0{` | The file does not begin with a valid top-level object/array. |

The MagicLib sample fragment found by the same rule is intentionally **not** in this list. It appears to be documentation/sample material rather than a file meant to load. That is exactly the kind of ambiguous finding that should stay out of somebody's inbox.

**Disposition:** worth reporting eventually, individually or in a small batch, with the exact file and a minimal description. No lecture about Preflight is required.

### B. Mechanical source-side performance fixes

These are good optional outreach candidates because an author can fix the source once and every user benefits.

#### Oversampled effects

The reviewed audio census found **195 declared effects at 96 kHz or above**, holding **391.0 MB** of decoded PCM for 992 seconds of audio. **124 belong to UAF and 38 to ORK.** At 44.1 kHz the same duration/channel counts would occupy about 100.3 MB of PCM. See [What prepared audio would have to hold](evidence/2026-07-26-what-prepared-audio-would-have-to-hold.md).

A useful correction to the easy-to-remember version: the UAF finding is **not one giant music file**. In the measured profile it is 124 high-sample-rate effects.

This is a particularly clean source-side optimization because the author does not need to adopt a cache, hook, API, or runtime dependency. They can re-export/resample the audio at an ordinary production rate and ship the smaller decode workload to everyone.

**Disposition:** good optional note to the author, especially if a per-mod lint report can supply exact paths. Keep the tone as “these files are exported at 96/192 kHz and the game fully decodes effects” rather than “your mod is bad.”

#### Progressive images

In the 86-mod sample, **41% of mod JPEGs were progressive**, carrying 25.9% of all image pixels, and the measured ImageIO path decoded progressive images about **8.75× slower** than equivalent baseline images. See [What eighty-six mods ship](evidence/2026-07-28-what-eighty-six-mods-ship.md).

The fix can be a lossless-in-pixels re-save, so this is actionable. It is also common enough that messaging every author individually would be noisy.

**Disposition:** better as linter output, documentation, or one general modding note. Contact a specific author only when their own report shows a concentrated cost and there is a natural reason to talk to them.

### C. Preflight should absorb it

Some findings are real and large, while still being poor author-facing reports.

#### Non-power-of-two texture padding

**83.9% of mod images** in the sampled ecosystem are non-power-of-two. Most are perfectly normal sprite art. Asking authors to redraw or pad four fifths of the ecosystem would make the source files worse and create needless coordination.

The linter therefore flags only unusually expensive cases, while Preflight's runtime path removes the broader padding cost when the live graphics context proves the dimensions are supported. See [What eighty-six mods ship](evidence/2026-07-28-what-eighty-six-mods-ship.md).

**Disposition:** Preflight/runtime problem first. No mass author outreach.

#### Repeated parsing, indexing, compilation, and runtime lookups

The profiling work found large repeated-work seams: shared JSON reads, runtime compilation, list validation, unchanged commodity calculations, texture queueing, and similar paths. [Engineering overview](engineering-overview.md) records the current readable summary.

Even when one mod amplifies one of these costs, asking every author to independently invent memoization or maintain another cache can create more failure points than it removes. Preflight already has identity, invalidation, fallback, and evidence machinery designed for that job.

**Disposition:** keep the optimization inside Preflight unless a particular mod has a tiny, obviously local source fix that can be demonstrated independently.

### D. Base-game / vanilla candidates

A profiler result against a heavily modded installation is **not automatically a Starsector bug report**.

Potentially interesting base-game observations include:

- the texture prefetch path can put a long single-threaded wait ahead of useful prepared-data decisions;
- the stock texture path can allocate large power-of-two upload padding;
- declared effects are eagerly opened in one pre-menu burst rather than on first use;
- some campaign paths repeatedly validate large collections or recompute unchanged state.

These are technically interesting and Preflight has strong evidence for the observed profile. They become good upstream reports only after one more step:

```text
large modded-profile observation
        ↓
small reproduction with clear inputs
        ↓
show that the behavior belongs to vanilla/API semantics
        ↓
measure impact or correctness consequence
        ↓
then consider telling Fractal
```

For example, the audio evidence shows all 2,050 declared effects in the reviewed profile were opened inside a 1.5-second window, but the loading thread does not wait for that pool. That is a useful engine observation, while “this makes startup 1.2 GB slower” would be wrong. See [The game builds 1.2 GB of PCM before the main menu](evidence/2026-07-29-the-game-builds-1-2-gb-of-pcm-before-the-main-menu.md) and its linked follow-up.

**Disposition:** keep a shortlist. Do not contact upstream from the giant-profile trace alone. Create a small reproduction first, and only for the few observations whose impact remains interesting after isolation.

### E. Interesting evidence with no recipient

Some findings are valuable because they explain Preflight, rule out a design, or teach something about the ecosystem. They do not need an external owner.

Examples:

- a cache hit occurring after the real bottleneck;
- an optimization that becomes slower on another mod/path;
- a stale timing anchor;
- a probe whose path resolution was wrong;
- a candidate technique such as AppCDS that failed to establish a useful enough win;
- an unusual asset that is harmless or clearly intentional.

**Disposition:** evidence archive. Done.

## Current shortlist

This is deliberately small. It should stay small.

### Strongest author-facing correctness reports

1. exshippack projectile config with the stranded `PROXIMITY_FUSE` block.
2. eusan_nation weapon config with stranded `fireSoundTwo`.
3. ORK faction config that closes before `priorityWeapons` and later content.
4. Mayasuran Navy config beginning `0{`.

These are the cleanest “you probably want to know this exists” reports because they are specific, checked, and about intended behavior.

### Strongest optional performance note

**High-sample-rate effects**, especially the concentrated UAF/ORK sets. This is source-side, mechanical, and useful without Preflight.

### Needs one more verification before author outreach

**ORK `melta_fire.ogg`**, a declared effect whose Ogg container contains FLAC rather than Vorbis. The census proves the container/codec mismatch and that the game declares the file. Before contacting the author, capture the exact player-visible/log behavior on the current mod version so the report can say what fails rather than merely that Preflight's Vorbis reader rejects it.

### Better as ecosystem guidance than direct messages

**Progressive JPEG/Adam7 PNG encoding.** The measured cost is large and the source fix is simple, but the pattern is common enough that the linter/documentation can do the communication without dozens of individual conversations.

### Keep inside Preflight

- broad NPOT texture padding;
- shared JSON/data-read memoization;
- generic generated-code deduplication;
- broad campaign lookup/index memoization;
- cache/storage layout work;
- compatibility/fallback machinery.

These are exactly the kinds of cross-cutting problems Preflight is equipped to own.

## What a good report should look like

The ideal report is boring and short:

```text
What I found
Exact file/path/version
What the game appears to do
Small reproduction or measurement
Why the proposed fix is local
Evidence link, if useful
```

Avoid sending a full Preflight engineering history. Avoid ranking mods. Avoid a pile of unrelated findings in one message unless the recipient explicitly wants a full lint report.

For performance findings, phrase the measured cost and let the author decide whether it is worth changing. For correctness defects, describe the observed bytes/behavior and the smallest likely repair.

## The zero-outreach workflow

It is valid for the project to do this:

1. keep the finding in machine-readable lint/evidence output;
2. classify it in this ledger;
3. fix around it in Preflight when that is the best user outcome;
4. publish general documentation that authors can discover on their own;
5. contact nobody unless a finding clears the high bar above.

The project can be useful to the ecosystem without turning its maintainer into the ecosystem's unpaid QA coordinator.

## When this page should change

Add an item here only when it answers a real disposition question. The full linter already owns thousands of individual findings; duplicating them here would recreate the tracking problem this page is meant to solve.

Keep this page to:

- categories;
- a small current shortlist;
- cases whose disposition is genuinely debatable;
- links to the evidence that owns the details.

When a report is sent and resolved, record the result in dated evidence or an issue and remove it from the active shortlist.