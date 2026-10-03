# Wide community ordinal corpus observations

This note summarizes Grievous69's four ordinary 0.98a ship lists after normalizing the headline tiers into `grievous69-0.98a-ordinary-ship-ratings.tsv`.

It deliberately avoids assigning numeric distance to tier letters.

## Corpus size

The normalized table currently contains **89 ship/variant rating rows**:

| Category | Rating rows | Explicit AI/player split | Split rate |
| --- | ---: | ---: | ---: |
| Capitals | 14 | 8 | 57.1% |
| Cruisers | 24 | 6 | 25.0% |
| Destroyers | 17 | 5 | 29.4% |
| Frigates | 34 | 5 | 14.7% |
| **Total** | **89** | **24** | **27.0%** |

Per the author's methodology, a single tier means the author does not see a meaningful enough AI/player difference to list separately.

So the corpus already provides two useful labels:

```text
explicit control-sensitive item
control difference not considered headline-worthy by this source
```

The second label is weaker than `AI == player`; it means only that this source chose one headline tier.

## Important source bias: the explicit control split only points one way

Every one of the 24 explicit dual ratings gives the player the higher tier.

That is useful evidence for **positive Control Premium**, but it makes this source unsuitable for learning the complete distribution of control effects on its own.

In particular, it cannot teach the model strong examples of:

```text
AI Power > Player Power
```

Even if those cases exist. The source's convention and purpose naturally emphasize ships where human timing/mobility/target choice unlocks extra value.

Therefore negative/AI-favoring control examples need other evidence:

- tournament AI performance;
- explicit community comments saying AI executes a mechanic unusually well;
- automated AI-versus-scripted/player-policy counterfactuals;
- other reviewers whose rubric allows AI to outrank player use.

The Grendel discussion is already a useful qualitative lead because some players argue the AI's phase behavior can be unusually good, even though the headline rating remains `B+ / A`.

## Control sensitivity appears more often in capitals

57% of capital rows receive a separate AI/player rating in this source, versus 15–29% in the smaller classes.

Do not treat that as a universal law yet. Plausible explanations include:

- capital/battlecruiser systems reward target choice and positioning more strongly;
- the player's single flagship slot makes capital piloting more salient to reviewers;
- poor decisions on a 35–60 DP ship have much larger visible opportunity cost;
- the capital set contains several deliberate high-risk flagship designs such as Odyssey and Retribution;
- the small-ship list contains many straightforward support/distraction/logistics-adjacent entries.

Still, this is enough to justify keeping **control mode as a model dimension from the beginning** instead of bolting it on later.

## Tier distribution itself is category-dependent

The AI headline tiers are distributed very differently by hull size.

### Capitals

Most sit in the A/B neighborhood, with only a few large control-sensitive outliers such as Pegasus (`C / A-`) and Retribution (`D+ / A+`).

### Cruisers

The list is centered more heavily around A/B, with role obsolescence and fleet-context arguments moving several otherwise functional hulls downward.

### Destroyers

The center shifts lower: several older or fragile hulls fall into C, while Medusa/Manticore/Sunder/Drover remain high.

### Frigates

The range becomes enormous: `SS` Monitor and `S` Omen coexist with multiple D/F civilian/pirate/cannon-fodder entries.

This means an absolute `tier -> eDP ratio` mapping would be especially misleading. Tier semantics are conditioned on category, role, campaign usefulness, and the author's expectations.

Use the ratings as **ordinal/conditional constraints**, not evenly spaced targets.

## Variant pairs are an unusually valuable subcorpus

The source includes many near-related variants:

- Onslaught versus Onslaught (XIV)
- Legion versus Legion (XIV)
- Dominator versus Dominator (XIV)
- Eagle versus LG/XIV
- Eradicator versus Pirate
- Falcon versus LG/Pirate/XIV
- Venture versus LP/Pirate
- Enforcer versus XIV
- Hammerhead versus LG
- Manticore versus LP
- Shrike versus Pirate
- Sunder versus LG
- Afflictor versus Pirate
- four Brawler families
- Centurion versus LG
- several Hound/Kite/Wolf/Gremlin variants

These are more valuable for early model calibration than arbitrary cross-hull comparisons because much of the hull identity is shared.

They create natural questions such as:

```text
Does +armor/+flux/+OP compensate for -speed?
What is the value of replacing one system with another?
How much does an OP tax hurt a small hull?
How much value does free Safety Overrides create?
How much does a degraded mobility system cost?
```

The first fitted model should have an explicit variant-pair validation report.

## The strongest qualitative anchors in the wide set

A few rows are especially useful because the prose makes a direct balance claim:

### Monitor: `SS`

The only SS rating in the ordinary lists. The author calls the 6-DP Flux Shunt + Fortress Shield combination fundamentally game-breaking and points to tournament behavior as supporting evidence.

This is a natural high-end outlier test and a system-interaction case. A model that considers Monitor weak because it deals no damage is missing tanking/distraction/target-attention value.

### Onslaught: `A / S-`

Explicitly described as the pound-for-pound gold-standard battleship. Useful central reference anchor.

### Onslaught (XIV): `A+ / S`

Called directly better at the same 40 DP apart from a tiny speed loss. Useful same-cost monotonic sanity check.

### Retribution: `D+ / A+`

One of the largest stated control gaps. Useful Control Premium stress test.

### Doom: `B / S+`

Another extreme system/timing control case.

### Medusa: `A+ / S`

Source explicitly says AI Medusa without enabling shield/system support could fall to roughly B. Useful Build Sensitivity/Synergy Debt case.

### Cerberus / Hound: `F`

Low-end reference examples. Useful for preventing a fit trained mainly on strong ships from compressing the weak tail.

## What to hold out

Do not feed all 89 rows into the first model fit.

A useful first split would reserve entire families before fitting, for example:

```text
hold out Monitor as an extreme zero-damage support/tank test
hold out Retribution as an extreme Control Premium test
hold out one variant family such as Falcon or Brawler
hold out one whole hull-size slice for rank-order validation
```

That forces the model to demonstrate some transfer instead of memorizing the ordinal table.

## Next source-quality work

The TSV is deliberately a source-normalization layer. The next tasks are:

1. resolve every source label to exact vanilla hull ID/version;
2. mark non-combat/campaign-heavy rows such as Shepherd so combat-only fits do not misuse them;
3. attach reason tags to the most informative rows rather than all 89 immediately;
4. encode dissent comments separately;
5. add another reviewer/source before interpreting source agreement as community consensus;
6. add tournament results to supply AI-only evidence under explicit event policies.
