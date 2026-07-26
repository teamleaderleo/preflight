# The first run against a real profile, and what it cost the plan (2026-07-26)

The VRAM census has run against the installed game for a while — the 6.91 GB in the footprint table
comes from it. The *block-cache* pipeline never had. `bake-blocks`, `cache-conformance` and
`contact-sheet` had only ever seen synthetic fixtures.

This is their first run against `/Applications/Starsector.app`: 72 enabled mods, 23,738 profile
textures, 6.91 GB of resident VRAM.

Three things came out of it. One is a measurement bug. One reverses a roadmap priority. One is the
first good news the contact sheet has produced.

## The block cache caches four percent of the profile

`assets bake-blocks --dry-run` over the whole profile, 422 seconds:

| outcome | textures | share |
|---|---|---|
| skipped as shader maps | 11,000 | 46.3% |
| over the fidelity gate | 11,786 | 49.7% |
| **cached** | **952** | **4.0%** |

The cached set compresses 315.6 MB of decoded pixels into 70.0 MB of blocks — a 4.51× ratio, saving
**245 MB**. Against a 6.91 GB working set that is **3.6%**.

The median cached texture has `meanDeltaE 0.0` and `p99DeltaE 0.0`. Not "low" — *zero*. Half of
everything that clears the gate is flat or blank art that block compression reproduces exactly. The
`closestToTheGate` list is almost entirely `graphics/fx/`: auras, shockwaves, engine flames, soft
glows. Smooth gradients survive S3TC. Ship hulls, portraits, icons and weapons do not.

This was not visible in synthetic testing, and not for the reason we assumed. The recorded finding
was that *synthetic gradients are harder for BC1 than real art*. On this profile the opposite is
true, because real Starsector art is small, hard-edged, high-contrast sprite work with alpha
fringes — the worst possible input for a codec that stores two endpoints per 4×4 block.

### The comparison that matters

The same `doctor` run reports **1.86 GB** of power-of-two padding: memory allocated and never
sampled. Removing it is **lossless**.

| approach | saves | fidelity cost | status |
|---|---|---|---|
| block cache, whole profile | 245 MB | lossy, gated | built, inert |
| padding removal | **1.86 GB** | **none** | not started |

Padding removal is **7.6× the benefit at zero fidelity cost**, and it is the one that has not been
built. The asset track has had these in the wrong order. This does not make the block cache
worthless — it makes it the second thing, not the first.

## meanDeltaE and p99DeltaE are on different scales

The dry run surfaced rows that cannot be true:

| texture | mean ΔE | p99 ΔE |
|---|---|---|
| `blinker_amber_01.png` | 1.9698 | 0.95 |
| `tahlan_shellshield.png` | 1.7925 | 1.00 |
| `bt_vortex_swirl.png` | 1.5695 | 1.00 |

A 99th percentile below the mean is essentially impossible for a non-negative distribution. All three
**passed the gate** while reporting a mean above it.

The cause is in `TextureFidelity.compare`. `weightedDeltaE` returns ΔE already multiplied by
`alpha/255`, and that attenuated value is what feeds the histogram — so `p99DeltaE`, `maxDeltaE`,
`imperceptibleFraction` and `obviousFraction` are all on the **coverage-attenuated** scale. The mean,
however, is `weightedSum / weightTotal` — dividing by `Σw` cancels the attenuation and returns the
mean to the **raw** scale.

Confirmed directly. 400 pixels, all at alpha 26, all with a large colour error:

```
mean   = 92.88
p99    =  9.45
max    =  9.47
```

`92.88 × (26/255) = 9.47`, exactly the reported max. The tail statistics are attenuated by coverage;
the headline mean is not. They are printed side by side, in the same units, under names that imply
the same scale.

**The gate runs on p99**, so the gate runs on the attenuated scale. That is arguably the right
choice — attenuating by alpha models what compositing actually puts on screen — but it is not the
scale the mean is quoted in, and the two are not comparable. Every published fidelity figure pairing
a mean with a p99 has this ambiguity in it, including the ones in
[the driver-agreement evidence](2026-07-26-encoder-driver-byte-agreement.md).

This needs a decision before any further gate tuning, because tuning a threshold against an
incoherent pair of statistics cannot converge:

1. **Make the mean attenuated too** (`weightedSum / visible`). Consistent with p99, max and both
   fractions. Changes every published mean.
2. **Make the histogram raw.** Consistent with the current mean, and makes the gate stricter — soft
   alpha would no longer buy a pass. Changes every published p99 and the cache membership.

Option 1 is the smaller change and matches the documented intent ("the measurement tracks what
reaches the screen"). It is not obviously correct, and it is not mine to take unilaterally, because
it moves the gate.

### Resolved: option 1, same day

`meanDeltaE` is now `weightedSum / visible`. Two properties made this safe to verify rather than
argue about:

- **For fully opaque images it is a no-op.** At alpha 255 the weight is 1, so `Σ(ΔE·w)/Σw` and
  `Σ(ΔE·w)/N` are the same number. Only partial-alpha content moves at all.
- **Cache membership is unchanged.** Re-running the full dry run gives 952 cached, 11,000 shader
  maps, 11,786 over gate and 70,012,680 block bytes — identical in every figure, because the gate
  reads p99 and p99 did not change.

The near-gate table afterwards:

| texture | mean before | mean after | p99 |
|---|---|---|---|
| `blinker_amber_01.png` | 1.9698 | 0.9888 | 0.95 |
| `bt_vortex_swirl.png` | 1.5695 | 0.2366 | 1.00 |
| `tahlan_shellshield.png` | 1.7925 | 0.2005 | 1.00 |

Two rows still report a mean fractionally above p99, both by **0.0388**. That is smaller than one
histogram bin (`BIN_WIDTH = 0.05`) and it is not the old defect: `percentile()` returns the *lower
edge* of the bin it lands in, so p99 is systematically under-reported by up to 0.05. The regression
test allows exactly one bin of slack for this reason.

## The classifier survives contact with real art

The synthetic fixture's headline failure was a planted false positive: a ship hull named
`engine_glow.png`, skipped on its filename alone. That was the whole argument for the contact sheet.

On 24 real textures evenly sampled from the profile, **the filename classifier was right every
time.** Every cell labelled *shader map* visibly is one — the purple-blue normal maps are
unmistakable at a glance, and the material and surface maps are equally clear. Nothing labelled
*cached* was misfiled.

That is a real result and it argues *against* one of the open questions in
[the verification strategy](../verification-strategy.md): moving the classifier to content-based
detection now would be solving a problem this profile does not have. The convention holds because
GraphicsLib enforces it — 4,926 of the 11,000 shader maps are machine-generated files in
`zz GraphicsLib-1.12.1/cache/`, named by the generator rather than by a human.

The sheet remains worth having. It just reported a pass, and a pass that was measured is worth more
than a pass that was assumed.

## Two small things

- **Duplicate launcher candidates on macOS.** `doctor` reports a scoring tie between
  `/Applications/Starsector.app/...` and `/Applications/starsector.app/...` and warns that it picked
  lexicographically. Those are the *same directory* — macOS is case-insensitive, and
  `StarsectorDiscovery.addStandardRoots` adds both spellings. Confirmed with `test -ef`. The warning
  is noise and the tie is an artefact; roots should be de-duplicated by real path.
- **The census walks non-asset directories.** `.github/workflows/teaser.png`, a mod's repository
  screenshot, was sampled as profile art. Only one file on this profile, so it is cosmetic, but it
  means the sample space is not strictly game assets.

## Reproducing

```
java -jar preflight-cli/target/preflight.jar doctor
java -jar preflight-cli/target/preflight.jar assets bake-blocks --out-dir <dir> --dry-run
java -jar preflight-cli/target/preflight.jar assets contact-sheet --out <sheet.png> --samples 24
```

No flags needed: `StarsectorDiscovery` already resolves the macOS app bundle. Profile fingerprint for
this run: `8abef77fded75bde3da5f425e166c53cf0c8a935f44b32c96cf493b1b3f8a0c1`.
