# Mod-author public writing draft

This is source copy for a Starsector forum post, Patreon update, README section, mod-author outreach,
or a longer development post about the tooling that fell out of Preflight's performance work. Read
[Public writing style](public-writing-style.md) for voice; keep the stable read-only tools separate
from the exploratory asset lab near the bottom.

## Title ideas

- **Preflight for mod authors: I pointed it at 86 Starsector mods. Most were clean.**
- **A Starsector mod linter that does not grade your mod**
- **What 86 Starsector mods actually ship**
- **The performance launcher grew a mod-analysis toolkit too**
- **Find expensive assets and broken setup links without touching the mod**

## Short deck

Preflight can lint one mod by itself, inspect a complete resolved profile, inventory a giant mod
stack, and catch deterministic dependency/reference problems without launching Starsector or
rewriting anybody's files. The linter has no score, ranking, or automatic fixer, and when I
calibrated it across 86 installed mod directories the median result was zero findings, with 44 mods
completely clean.

## Long post

Preflight started because my heavily modded Starsector installation took an absurd amount of time to
reach the main menu, which meant I spent a lot of time profiling the game and the mods around it,
and eventually the obvious adjacent question arrived: if the profiler can tell me which files and
configuration choices cost real decode time, memory, or loader work, can any of that be turned into
something useful to the people making the mods themselves without producing one of those linters
whose principal accomplishment is discovering that an ecosystem has conventions?

That qualification turned out to be the important part, so Preflight has a mod linter now.

```bash
java -jar preflight.jar lint --path ./MyMod
java -jar preflight.jar lint --game "/path/to/Starsector"
java -jar preflight.jar lint --game "/path/to/Starsector" --mod my_mod
java -jar preflight.jar lint --game "/path/to/Starsector" --json --output lint.json
```

`--path` checks one mod directory on its own and does not require the rest of the profile around it;
the whole-profile form resolves actual provider order and can answer the cross-mod questions that a
single folder simply lacks enough information to answer.

The linter is read-only. It does not grade mods, rank authors, or quietly smuggle a rewriting tool
into a diagnostics command, because a finding should be something an author can evaluate rather
than a little act of jurisdiction over somebody else's work. The useful test for a rule is whether
it can say what it measured, why the player pays for it, and where its knowledge ends.

### The first result I wanted was that most mods were fine

I calibrated the rules by running `--path` over 86 installed mod directories as 86 separate samples.
The **median was zero findings**, **44 of the 86 mods were completely clean**, and no rule fired on
more than a third of the sample.

That is the target. A tool pointed at other people's work pays an enormous reputational tax for false
positives, and a rule that cannot distinguish measurable cost from artistic intent has very little
business appearing in the default report merely because it can be implemented. I also checked the
thresholds against 13 mods installed after the original calibration; the firing rates stayed within
a few points of the larger sample instead of exploding as soon as the corpus changed.

### Progressive images are the delightfully uncomplicated case

Through the ImageIO path Starsector uses, equivalent progressive JPEGs measured about **8.75 times
slower to decode** than baseline JPEGs. The picture itself does not have to change; progressive
encoding is useful for a web page that wants to reveal an image gradually and rather less useful for
a game asking for the whole thing immediately.

In the reviewed corpus, 41% of mod JPEGs were progressive and they carried 26% of all mod image
pixels, which makes this a lovely lint rule because the observation is measurable, the action is
obvious, and artistic judgment barely enters the room.

### NPOT textures are normal, so the question has to be narrower

**83.9%** of the reviewed mod images were not powers of two. Treat that statistic as a verdict and
you have just invented thousands of warnings whose primary informational content is that Starsector
mod art tends to be sprite-shaped.

The stock texture path can allocate the next power-of-two upload buffer, though, so very large NPOT
art can carry a real VRAM cost. Preflight therefore asks how many bytes of empty padding a particular
texture creates and reports only when that waste crosses 1 MB. Out of 23,571 NPOT files in the
reviewed profile, 288 crossed the threshold.

That is a much better answer than a taxonomic rule about what dimensions a texture ought to have.
Sprite art should be the size the sprite needs; the linter cares about the small subset where the
allocation consequence becomes large enough to discuss.

### Audio can be expensive long before anyone hears it

Starsector bulk-decodes declared effects before the main menu, so the linter can report things with a
direct load-time or memory consequence: effects at 96 kHz or above, long non-music effects that are
decoded in full, files named by no `sounds.json` in the resolved profile, declared sounds no provider
supplies, extension/content mismatches, and audio the game's Vorbis-in-Ogg path cannot decode.

The wording stays narrow on purpose. `audio-unreferenced` means no `sounds.json` in the resolved
profile names the file; it does not mean mod code can never open it later. A six-minute live session
checked the loader premise: Starsector opened all 2,050 effects declared by `sounds.json` and none of
the 220 undeclared files during that observed run, which supports the startup-loader claim without
pretending the linter has omniscience about arbitrary code paths.

### Starsector config deserves a Starsector parser, not a strict-JSON scolding

The config checks cover `.json`, `.variant`, `.wpn`, `.ship`, `.proj`, `.system`, `.skin`, `.faction`
and `.skill`; the reviewed profile contained **15,353** such files. Starsector's real dialect admits
`#` and `//` comments, trailing commas, unquoted keys, numeric suffixes such as `0.1f`, and other
forms that would make a generic strict-JSON checker produce a magnificent heap of nonsense.

Preflight accepts those conventions and asks narrower questions: can the value finish parsing, and
is meaningful configuration sitting after the top-level value has already closed where a reader
that consumes one value will never apply it? Harmless trailing punctuation is ignored; the reviewed
set included 27 files ending with one brace too many that still worked.

The result was **five config findings out of 15,353 files**. Four were real defects in released mods:
a missile with a `PROXIMITY_FUSE` block outside the top-level object, a weapon whose `fireSoundTwo`
never applies, a faction file that closes early and drops everything from `priorityWeapons` onward,
and a config beginning `0{`.

That is the signal-to-noise ratio I want.

### Whole-profile mode knows which file actually wins

For costs tied to bytes the game loads, Preflight examines the winning provider at a logical path. If
three mods provide the same resource and the third one wins, charging the first two for decode or
VRAM cost would make the report larger while making it less true.

Shadowed files are therefore their own rule, and duplicate-content detection avoids counting the
same logical situation twice. Standalone `--path` mode is equally explicit about what it cannot
know: rules that require provider context disappear from that mode and the report says which ones
were unavailable.

`knights_of_ludd` is a useful real example. Lint it alone and sixteen sounds appear unreferenced; lint
the complete profile and a companion mod supplies the declarations, so those findings vanish. The
tool is supposed to change its answer when you give it the context it was missing.

### Different bytes live in different universes

The reviewed 84-root profile produced 1,392 findings whose cost-bearing subset represented **771.9
MB of VRAM padding**, **687.9 MB of decoded-at-load audio**, and **100.8 MB of disk findings**. Those
numbers stay separate because adding a megabyte of VRAM, a megabyte of decoded PCM, and a megabyte on
disk produces a larger integer and no coherent resource quantity.

Several of the six error-severity findings in that profile were more important than hundreds of
megabytes anyway, because they described configuration or audio the game could not use as intended.

### A rule is allowed to come home empty-handed

`sound-declared-missing` found zero problems in the reviewed profile and has seen 83 enabled mods
across two profile sizes without firing once, which is an excellent outcome: every declared sound was
supplied by some provider. The rule stays because the failure would be deterministic and useful if it
appeared, not because every rule owes the dashboard a scary number.

## The tooling around `lint`

The performance investigation produced a profile census and a deeper setup checker as well, and the
three commands answer different questions without needing to be presented as a holy trinity.

`preflight scan` inventories the enabled setup: enabled and missing IDs, file and byte totals,
images, sounds, JARs, loose Java, extension/mod breakdowns, largest assets and mods, duplicate logical
paths, and provider winners. It can also model a VRAM budget and proposed texture-size cap before
anything is changed.

```bash
java -jar preflight.jar scan --game "/path/to/Starsector" --vram-budget 4G --max-texture-size 2048
```

`preflight analyze setup` asks whether the resolved mod stack is internally coherent. It can report
an enabled mod whose metadata is missing or unreadable, duplicate declared mod IDs, required
dependencies that are missing or installed-but-disabled, malformed dependency or total-conversion
metadata, and a winning `.variant` whose declared hull does not exist in the resolved hull/skin set.
It exits after analysis and can emit JSON for tooling.

```bash
java -jar preflight.jar analyze setup --game "/path/to/Starsector"
java -jar preflight.jar analyze setup --game "/path/to/Starsector" --json
```

So `lint` is about measurable asset/configuration costs, `scan` is about what the enormous profile
actually contains, and `analyze setup` is about whether the active dependency and selected-reference
picture makes sense before launch. All are useful to players debugging a giant stack; `lint --path`
is deliberately useful to an author with one mod on the desk and no complete profile available.

## Why there is no Fix all button

Re-encoding a progressive JPEG is straightforward. Resizing art, deleting files, resampling audio,
or rewriting somebody's configuration is a different class of tool with a different risk budget,
and the read-only linter does not get to acquire that power merely because a fix looks obvious in a
few easy cases.

The same restraint applies to language. An unreferenced sound may still be opened from code. Ordinary
NPOT art is ordinary. Starsector accepts configuration syntax that strict JSON rejects. If a finding
cannot say what it knows, what remains outside its knowledge, and why the reported thing costs
something, I would rather leave the rule out.

## Short mod-author pitch

> Preflight has a read-only Starsector mod linter. `lint --path ./MyMod` checks one mod on its own,
> while whole-profile mode understands provider order and cross-mod relationships; it reports
> measured costs and deterministic config problems, edits nothing, and has no score, ranking, or
> automatic fixer. I calibrated it over 86 installed mods, where the median was zero findings and 44
> were completely clean. The same CLI also has a profile census and a deeper setup check for things
> like disabled required dependencies, duplicate IDs, and selected variants pointing at absent
> hulls.

## Good screenshots and examples

The first image should show the thesis rather than a terminal wall. A clean single-mod result is a
great opener; after that, a progressive-image finding with its measured decode explanation, a large
NPOT finding with the actual padding cost, the profile summary with separate disk/decoded/VRAM
numbers, or a setup finding such as an installed-but-disabled dependency all tell a more legible
story than a giant dump of findings.

A simple 44/86 clean calibration chart could also work. The point is that the tool can discriminate,
not that it can make every mod look pathological.

## Exploratory asset lab

The repository also contains asset experiments and local generator tools that grew out of the same
research. The current asset-quality track labels itself exploratory, so this is future-post material
instead of first-beta product copy.

Interesting examples include `preflight font generate` / `font generate-pack` for local BMFont
atlases and font mods from operator-supplied fonts; `preflight assets shrink` for generating a
separate override mod containing capped copies of oversized textures after `scan` estimates the
trade; block-compression probes and conformance vectors; and contact sheets that put originals,
reconstructions, error maps, and the experiment's decision beside one another so a fidelity number
does not become the sole aesthetic sovereign.

There is a good later story here about moving from "how can Preflight route around expensive input?"
to "what can an author change once so every player of the mod benefits?" It simply belongs after the
experimental tools earn their own product contract.

## Call to action

For one mod:

```bash
java -jar preflight.jar lint --path ./MyMod
```

For the complete installed profile:

```bash
java -jar preflight.jar lint --game "/path/to/Starsector"
java -jar preflight.jar scan --game "/path/to/Starsector"
java -jar preflight.jar analyze setup --game "/path/to/Starsector"
```

If the linter says zero findings, excellent. If it reports something, the finding should give enough
measurement and context for the author to decide whether they care; if it gets that judgment wrong,
that is worth reporting too, because a mod-author tool that cannot learn to stay quiet when nothing
useful is wrong will eventually become noise.
