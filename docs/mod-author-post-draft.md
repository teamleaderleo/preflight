# Mod-author public writing draft

Source copy for a Starsector forum post, Patreon update, README section, or mod-author outreach.

## Title options

- **Preflight has a Starsector mod linter now**
- **I pointed a profiler at 86 Starsector mods. Most were clean.**
- **A Starsector mod linter that does not grade your mod**

## Post

Preflight has a mod linter now.

For one mod:

```bash
java -jar preflight.jar lint --path ./MyMod
```

For the full installed profile:

```bash
java -jar preflight.jar lint --game "/path/to/Starsector"
```

I calibrated it across **86 installed mod directories**. The median was **zero findings**, and **44 of 86 were completely clean**.

That is about where I want it.

Some examples of what it does report:

- Progressive JPEGs measured about **8.75× slower to decode** through the ImageIO path Starsector uses.
- Large non-power-of-two textures can waste VRAM through upload padding. The rule only reports cases over **1 MB** of padding; **288 of 23,571** NPOT files crossed that line in the reviewed profile.
- Starsector bulk-decodes declared sound effects before the main menu, so the linter can flag high-rate audio, long decoded-at-load effects, missing declared sounds, extension/content mismatches, and files the game cannot decode as expected.
- Starsector config is not strict JSON. The checker understands comments, trailing commas, unquoted keys, numeric suffixes, and the other forms real mods use. Across **15,353** reviewed config files it produced **five findings**; four were defects in released mods.
- Whole-profile mode understands provider order, so it can distinguish the file that actually wins from shadowed copies and can resolve cross-mod relationships that one folder alone cannot.

On the reviewed 84-root profile, the cost-bearing findings represented **771.9 MB of VRAM padding**, **687.9 MB decoded at load**, and **100.8 MB of disk findings**. Those stay separate because they are different resources.

There is no score or ranking. The linter edits nothing.

Preflight also has two adjacent tools:

```bash
java -jar preflight.jar scan --game "/path/to/Starsector"
java -jar preflight.jar analyze setup --game "/path/to/Starsector"
```

`scan` inventories the enabled profile: file/byte totals, largest assets and mods, duplicate logical paths, provider winners, and related profile information.

`analyze setup` checks the resolved active stack for things like missing or disabled required dependencies, duplicate mod IDs, malformed metadata, and selected variants that point at hulls missing from the resolved profile.

So, roughly:

| Question | Tool |
| --- | --- |
| Does this mod ship a measurable asset/config problem? | `lint` |
| What is actually in this huge enabled profile? | `scan` |
| Is the active stack internally coherent? | `analyze setup` |

If you make a Starsector mod, please try `lint --path` on it. If it says zero findings, great. If it reports something wrong or useless, tell me that too.

## Useful supporting facts

Keep these for replies or a longer technical post rather than forcing them all into the first announcement:

- 41% of reviewed mod JPEGs were progressive and carried 26% of all reviewed mod image pixels.
- A six-minute live session opened all **2,050** effects declared through `sounds.json` and none of the **220** undeclared files during the observed run, supporting the linter's narrow startup-loader classification.
- One standalone-mod example (`knights_of_ludd`) reports sixteen sounds as unreferenced by itself; those findings disappear in the complete profile because a companion mod declares them.
- The reviewed config findings included a missile `PROXIMITY_FUSE` block outside the top-level object, a weapon whose `fireSoundTwo` never applies, a faction file that closes early, and a config beginning `0{`.
- `sound-declared-missing` produced zero findings across the reviewed profiles. It remains useful because the failure is deterministic when it occurs.

## Exploratory tools

Keep experimental asset-generation/rewrite work separate from the first-beta linter pitch. The stable public story is `lint`, `scan`, and `analyze setup`; source-side asset experiments can get their own post when they have their own accepted product boundary.
