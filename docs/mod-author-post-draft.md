# Mod-author public writing draft

This is source copy for a Starsector forum post, Patreon update, README section, mod-author outreach,
or a longer development post about the tooling that fell out of Preflight's performance work. It is
not a new product contract. Keep the stable read-only tools separate from the exploratory asset lab
at the bottom.

## Title options

- **Preflight for mod authors: I pointed it at 86 Starsector mods. Most were clean.**
- **A Starsector mod linter that does not grade your mod**
- **What 86 Starsector mods actually ship**
- **The performance launcher grew a mod-analysis toolkit too**
- **Find expensive assets and broken setup links without touching the mod**

## Short deck

Preflight can lint one mod by itself, inspect a complete resolved profile, inventory a giant mod
stack, and catch deterministic dependency/reference problems without launching Starsector or
rewriting anybody's files. The linter has no score, ranking, or automatic fixer. Across 86 installed
mod directories, the median result was zero findings and 44 were completely clean.

## Long post

Preflight started because my heavily modded Starsector installation took an absurd amount of time to
reach the main menu. I started profiling the game to find out where that time was actually going.

Eventually that raised another question: if the profiler can tell me which kinds of files and config
cost the game real time or memory, can any of that information be useful to the people making the
mods themselves?

The answer is yes, but only if the tool can resist the temptation to turn every unusual thing into a
warning.

So Preflight has a mod linter now.

```bash
java -jar preflight.jar lint --path ./MyMod
java -jar preflight.jar lint --game "/path/to/Starsector"
java -jar preflight.jar lint --game "/path/to/Starsector" --mod my_mod
java -jar preflight.jar lint --game "/path/to/Starsector" --json --output lint.json
```

`--path` checks one mod directory on its own. It does not need the rest of the profile around it. The
whole-profile form resolves the actual provider order and can answer cross-mod questions that a
single folder cannot.

It never edits, moves, re-encodes, deletes, or rewrites a mod. It always exits zero. A finding is
something offered to the author, not a grade and not a gate imposed on somebody else's work.

There is no score. There is no tier list. There is no ranking of mods. There is no automatic button
that rewrites somebody's art because a tool decided it knew better.

A rule has to say what it measured and why the player pays for it.

### The first result I wanted: most mods are fine

I calibrated the rules by running `--path` over 86 installed mod directories as 86 separate samples.
The **median was zero findings**, and **44 of the 86 mods were completely clean**. No rule fired on
more than a third of the sample.

That is not a weakness in the linter. That is the target.

A tool aimed at other people's work pays a much higher price for a false positive than it does for
missing a clever new rule. If a check cannot distinguish measurable cost from artistic intent, it
does not belong in the default report.

The thresholds were also checked against 13 mods installed after the original calibration. Their
firing rates stayed within a few points of the larger sample rather than exploding as soon as the
sample changed.

### Progressive images are an easy win

One of the clearest findings is progressive image encoding.

Through the ImageIO path the game uses, equivalent progressive JPEGs measured about **8.75 times
slower to decode** than baseline JPEGs. The fix does not require changing the picture. It is the same
pixels stored in a representation that is useful for progressive web display and expensive for a
game that wants the whole image now.

In the reviewed corpus, 41% of mod JPEGs were progressive and they carried 26% of all mod image
pixels. That makes this one of the nicest possible lint rules: measurable, actionable, and almost
entirely disconnected from artistic judgment.

### Non-power-of-two textures are normal, so the linter mostly leaves them alone

83.9% of the reviewed mod images were not powers of two.

A naive linter could turn that into thousands of warnings and tell practically the whole modding
scene that its art is wrong. Preflight does not do that.

The stock texture path can allocate the next power-of-two upload buffer, so sufficiently large NPOT
art can waste meaningful VRAM. The lint rule therefore asks a narrower question: **how many bytes of
empty padding does this particular texture cost?** It reports only when that waste exceeds 1 MB.

Out of 23,571 NPOT files in the reviewed profile, only 288 crossed that threshold.

That is a better answer than “NPOT bad.” Sprite art should be the size the sprite needs to be. The
linter is interested in the handful of cases where rounding the allocation upward costs megabytes,
not in making four fifths of mod authors resize their artwork to satisfy a style rule.

### Audio can be expensive before the player hears any of it

Starsector bulk-decodes declared effects before the main menu. The linter can therefore point out
things that have a direct load-time/memory consequence:

- effects stored at 96 kHz or above;
- long non-music effects that are decoded in full rather than streamed;
- files named by no `sounds.json` in the resolved profile;
- declared sounds that no provider actually supplies;
- files that are named like one format but contain another;
- audio that is not decodable as the Vorbis-in-Ogg format the game expects.

The wording is deliberately careful. `audio-unreferenced` does **not** mean “dead file.” A mod can
still open a sound from code. It means no `sounds.json` in the resolved profile names it, which is the
boundary the bulk startup loader uses.

A six-minute live session checked that premise: Starsector opened every one of the 2,050 sound
effects declared by `sounds.json`, and none of the 220 undeclared files during the observed run. That
supports the narrow loader claim without pretending the linter can prove a mod's code never uses one
later.

### It understands Starsector config instead of yelling “invalid JSON” at the ecosystem

The config checks cover `.json`, `.variant`, `.wpn`, `.ship`, `.proj`, `.system`, `.skin`, `.faction`
and `.skill`. The reviewed profile contained **15,353** such files.

Starsector's real configuration dialect is not strict JSON. Shipping mods rely on `#` and `//`
comments, trailing commas, unquoted keys, and numeric suffixes such as `0.1f`. Point a strict JSON
checker at the ecosystem and it will manufacture a spectacular pile of nonsense.

Preflight accepts those forms and checks two much narrower failure modes:

- a structure that can never finish parsing, such as an unterminated bracket/string/comment;
- meaningful configuration placed after the top-level value has already closed, where a reader that
  consumes one value never applies it.

It also ignores harmless trailing punctuation. The reviewed set contained 27 files that ended with
one brace too many and still worked. The rule requires actual unread content beyond the completed
value, not merely an extra bracket.

The result was **five config findings out of 15,353 files**. Four were real defects in released mods:
a missile with a `PROXIMITY_FUSE` block outside the top-level object, a weapon whose `fireSoundTwo`
never applies, a faction file that closes early and drops everything from `priorityWeapons` onward,
and a config beginning `0{`.

That signal-to-noise ratio is what I want from this tool.

### Whole-profile mode knows which file actually wins

For costs tied to bytes the game loads, Preflight examines the winning provider at a logical path.
If three mods provide the same resource and the third one wins, charging the first two for decode or
VRAM cost would be misleading.

Shadowed files are their own rule because *not* loading is exactly the point of that finding.
Duplicate-content detection likewise avoids double-counting a shadowed path as both “shadowed” and
“duplicate.”

Standalone `--path` mode is explicit about the questions it cannot answer. It suppresses rules that
need a whole profile, such as shadowing and cross-mod sound declarations, and reports which rules
were unavailable. A clean single-mod result should not quietly pretend to prove something that needs
provider context.

One real example is `knights_of_ludd`: linting it alone reports sixteen sounds as unreferenced; in the
complete profile, a companion mod declares them and those findings disappear. The tool is supposed to
change its answer when it receives the missing context.

### The byte totals stay separate because they are different problems

The reviewed 84-root profile produced 1,392 findings. The cost-bearing findings represented:

- **771.9 MB of VRAM padding**;
- **687.9 MB of decoded-at-load audio**;
- **100.8 MB of disk findings**.

Those numbers are intentionally never summed.

A megabyte of VRAM, a megabyte of decoded PCM, and a megabyte sitting on disk are not one resource.
Adding them together would produce a bigger headline and a worse report. The code and tests keep the
three cost kinds separate.

The six error-severity findings in that profile did not need a byte total at all. Several were more
important than the hundreds of megabytes because they described configuration or audio the game
could not use as intended.

### A rule is allowed to find nothing

`sound-declared-missing` found zero problems in the reviewed profile and has seen 83 enabled mods
across two profile sizes without firing once.

Good.

Every declared sound was supplied by some provider. The rule stays because the failure is
deterministic and useful when it happens, not because every rule needs to justify its existence with
a scary number on every scan.

## The mod-author toolkit is bigger than `lint`

The performance work produced two other read-only tools that are useful when a mod stack gets large.
They answer different questions.

### `preflight scan`: what is actually in this profile?

`scan` inventories the enabled setup rather than judging it. It can report enabled and missing IDs,
file and byte totals, images, sounds, JARs, loose Java, extension/mod breakdowns, largest assets and
mods, duplicate logical resource paths, and which provider wins each duplicate.

It can also take a VRAM budget and a proposed maximum texture size to project what capping oversized
textures would save:

```bash
java -jar preflight.jar scan --game "/path/to/Starsector" --vram-budget 4G --max-texture-size 2048
```

That is useful before making a change because it answers “what would this policy actually buy on
this profile?” instead of assuming a texture cap is worthwhile everywhere.

### `preflight analyze setup`: is this resolved mod stack internally coherent?

The deep setup check is separate from asset linting. It does not launch or modify Starsector.

```bash
java -jar preflight.jar analyze setup --game "/path/to/Starsector"
java -jar preflight.jar analyze setup --game "/path/to/Starsector" --json
```

It can deterministically report things such as:

- an enabled mod whose metadata is missing or unreadable;
- two installed mods declaring the same mod ID;
- a required dependency that is missing;
- a required dependency that is installed but disabled;
- malformed dependency or total-conversion metadata;
- a winning `.variant` whose declared hull does not exist in the resolved hull/skin set.

That last category is deliberately based on the **resolved** profile. It is not just grepping one
folder for strings and hoping the provider order does not matter.

So the three tools line up like this:

| Question | Tool |
| --- | --- |
| What does this mod ship that has a measurable cost or deterministic config problem? | `preflight lint` |
| What is actually in this enormous enabled profile, and who wins duplicate resources? | `preflight scan` |
| Are the active mods/dependencies/static links internally coherent before I launch? | `preflight analyze setup` |

All three can be useful to a player debugging a giant stack. `lint --path` is specifically designed
to also be useful while an author is working on one mod by itself.

## What the linter refuses to become

It does not apply fixes.

That is deliberate. Re-encoding a JPEG is relatively straightforward; resizing art, deleting files,
resampling audio, or rewriting somebody's config is a different product boundary. The read-only
linter does not smuggle that risk in behind a “Fix all” button.

It does not call an unreferenced sound dead when mod code may still load it. It does not call ordinary
NPOT art a mistake. It does not report strict-JSON violations that Starsector accepts. It does not
score authors against each other.

If a finding cannot say what it knows, what it does **not** know, and why the reported thing costs
something, I would rather not have the rule.

## Short mod-author pitch

> Preflight has a read-only Starsector mod linter. `lint --path ./MyMod` checks one mod on its own;
> whole-profile mode understands provider order and cross-mod relationships. It reports measured
> costs and deterministic config problems, never edits files, and has no score, ranking, or automatic
> fixer. I calibrated it over 86 installed mods: the median was zero findings and 44 were completely
> clean. The same CLI also has a profile census and a deep setup check for missing/disabled
> dependencies, duplicate IDs, and resolved variants pointing at absent hulls.

## Good screenshots / examples for a public post

Do not make the first image a terminal wall. Good visual candidates:

1. one clean single-mod lint result;
2. one progressive-image finding with the measured decode explanation;
3. one large NPOT finding showing the actual padding cost rather than “NPOT bad”;
4. the profile summary with separate disk/decoded/VRAM totals;
5. a deep-setup finding for an installed-but-disabled dependency or missing resolved hull;
6. the 44/86 clean calibration result as a simple chart if a chart is useful.

A clean screenshot is important. The thesis is not “I can make every mod look broken.”

## Exploratory asset lab: interesting, but do not sell this as the first-beta contract yet

The repository also contains asset experiments and local generator tools that came out of the same
research. The current asset-quality track explicitly labels itself **exploratory / not yet
evidence-gated**, so these are good future-post material rather than promises about the first beta.

Interesting examples include:

- `preflight font generate` and `font generate-pack`, which can generate BMFont atlases and a local
  drop-in font mod from an operator-supplied font without Preflight redistributing commercial game
  fonts;
- `preflight assets shrink`, which can generate a separate override mod containing capped copies of
  oversized textures after using `scan` to estimate the tradeoff;
- block-compression probes and conformance vectors for checking experimental GPU-ready texture data
  against a real driver;
- contact sheets that place original art, reconstructed art, error maps, and the experiment's
  decision beside each other so a fidelity number is not the only thing deciding whether a texture
  category was classified correctly.

There is a very good later story here: the performance investigation produced enough tooling to
start asking not only “how can Preflight route around expensive inputs?” but “what can an author
change once so every player of the mod benefits?”

That story is worth telling. It just needs to stay visibly separate from the stable read-only linter,
profile census, and setup analyzer until the experimental tools have their own accepted product
boundary.

## Call to action

For a single mod:

```bash
java -jar preflight.jar lint --path ./MyMod
```

For the complete installed profile:

```bash
java -jar preflight.jar lint --game "/path/to/Starsector"
java -jar preflight.jar scan --game "/path/to/Starsector"
java -jar preflight.jar analyze setup --game "/path/to/Starsector"
```

If the linter says zero findings, excellent. If it reports something, the finding should tell you the
measured cost and enough context to decide whether you care. If it gets that judgment wrong, that is
something I want reported too. A mod-author tool that people cannot trust to stay quiet when nothing
is wrong is not useful.
