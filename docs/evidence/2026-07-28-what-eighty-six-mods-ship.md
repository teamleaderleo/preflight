# What eighty-six mods ship (2026-07-28)

The linter's thresholds were tuned against one 73-root profile. Eleven more mods were installed,
bringing the sample to **86 mod directories, 28,097 images, 1,489 Mpixel**, which is enough to ask
whether the calibration transfers and to state a few things about the ecosystem rather than about one
person's install.

## The calibration transferred

Each directory linted alone, so every mod is an independent sample:

| | 74 mods | 86 mods | 13 newly installed |
| --- | ---: | ---: | ---: |
| median findings | 0 | 0 | 4 |
| clean mods | 40/74 (54%) | 44/86 (51%) | 4/13 (31%) |
| `texture-npot-padding` | 32% of mods | 34% | 46% |
| `texture-progressive` | 23% | 22% | 23% |
| `audio-oversampled` | 15% | 15% | 15% |
| `asset-duplicate-content` | 15% | 13% | 0% |

Firing rates on mods the thresholds were never tuned against sit within a few points of the overall
rates. Half of all mods still produce nothing.

`asset-extension-mismatch` was the one rule that moved sharply, 4 findings to 28. Twenty-one of the
new ones are a single mod shipping files named `.png` whose first four bytes are `ff d8 ff e0`. The
detection is right; the ecosystem simply contains more of this than one profile suggested.

`sound-declared-missing` has now seen 83 enabled mods across two profiles and never fired once.

## Two ecosystem rates

**41% of all mod JPEGs are stored progressively.** 179 of 436, plus 55 interlaced PNGs. That is under
1% of images but **25.9% of every pixel these mods ship**, because the progressive ones are
backgrounds and illustrations rather than sprites. A quarter of mods that ship images ship at least
one.

Against the [measured 8.75× decode penalty](2026-07-28-progressive-jpeg-costs-nine-times-the-decode.md)
and ImageIO being 67–70% of a texture load, that makes progressive encoding the most concentrated
avoidable cost in mod art: a quarter of the pixels, at nine times the decode, fixable by re-saving.

**83.9% of mod images are not powers of two** — 23,571 files across 60 of the 75 mods that ship
images, and 2,251 MB of padding if all were resident at once.

That second number needed a wording change rather than a louder warning. Non-power-of-two is what
sprite art *is*: a ship is whatever size it is, and padding it to 512×512 would be worse in every way.
Reporting it as a defect would describe normal practice back at four fifths of all authors, which is
the failure this tool can least afford.

The 1 MB padding floor is what keeps that from happening. It admits 288 findings out of 23,571 NPOT
files — 1.2% — and the smallest edge among them is 176 px, so what survives is large illustrations
and backgrounds where rounding up to the next power of two costs megabytes and choosing different
dimensions is reasonable advice. The rule now says so out loud.

**This is the case for fixing NPOT padding at runtime rather than at the source.** Eighty percent of
mods would have to change art they were right to author that way. The
[padding removal work](2026-07-26-padding-removal-needs-no-instruction-surgery.md) is the correct home
for the other 2.2 GB.

## Limits

Eighty-six mods chosen by one person are not a random sample of the ecosystem, and popular mods are
probably better maintained than obscure ones. The rates above describe this sample. What they support
is narrower and still useful: the thresholds are not obviously wrong, and progressive encoding is
common enough to be worth telling people about.
