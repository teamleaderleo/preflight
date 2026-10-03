# First balance seed observations

This note uses the small `vanilla-0.98a-seed.json` corpus only. It is deliberately pre-model: no eDP estimate is claimed yet.

## Why this seed is useful

The first ten ships include several kinds of calibration case:

- a community-defined pound-for-pound anchor: Onslaught;
- a near-direct same-cost upgrade: Onslaught (XIV) versus Onslaught;
- a suspected low-cost overperformer: Anubis;
- two extreme player-control-premium cases: Doom and Afflictor;
- a high-performing destroyer with known AI fitting requirements: Medusa;
- a same-cost capital comparison with similar base durability but very different community results: Executor versus Pegasus.

That is enough to test whether a candidate metric learns useful distinctions before expanding the corpus.

## Naive static ratios

These are descriptive ratios only. They deliberately omit mount quality, system mechanics, range, fighter contribution, damage types, built-in modifiers, AI behavior, and loadouts.

| Ship | DP | OP/DP | Hull/DP | Armor/DP | Flux/DP | Dissipation/DP | Speed/DP |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Anubis | 18 | 9.44 | 388.89 | 38.89 | 400.00 | 23.33 | 4.44 |
| Onslaught | 40 | 9.00 | 500.00 | 43.75 | 425.00 | 15.00 | 0.62 |
| Onslaught (XIV) | 40 | 9.25 | 500.00 | 46.25 | 446.25 | 15.75 | 0.57 |
| Paragon | 60 | 6.17 | 300.00 | 25.00 | 416.67 | 20.83 | 0.50 |
| Executor | 50 | 7.00 | 340.00 | 30.00 | 280.00 | 9.50 | 1.00 |
| Pegasus | 50 | 7.30 | 340.00 | 30.00 | 280.00 | 10.00 | 0.70 |
| Doom | 35 | 4.14 | 228.57 | 35.71 | 285.71 | 25.71 | 2.14 |
| Aurora | 30 | 5.50 | 266.67 | 26.67 | 366.67 | 26.67 | 2.67 |
| Medusa | 12 | 8.33 | 250.00 | 25.00 | 500.00 | 33.33 | 8.33 |
| Afflictor | 12 | 4.58 | 125.00 | 33.33 | 250.00 | 33.33 | 13.75 |

For shielded ships, a second naive proxy divides capacity/dissipation by shield damage-to-flux efficiency before dividing by DP:

| Ship | Shield-adjusted capacity/DP | Shield-adjusted dissipation/DP |
| --- | ---: | ---: |
| Anubis | 666.67 | 38.89 |
| Onslaught | 425.00 | 15.00 |
| Onslaught (XIV) | 446.25 | 15.75 |
| Paragon | 694.44 | 34.72 |
| Executor | 466.67 | 15.83 |
| Pegasus | 350.00 | 12.50 |
| Aurora | 611.11 | 44.44 |
| Medusa | 833.33 | 55.56 |

Again, this is a diagnostic ingredient, not a durability score. Fortress Shield, armor, phase, shield arc, upkeep, hard-flux behavior, mobility, and AI use all change the downstream result.

## Observation 1: Onslaught is a useful calibration anchor

Grievous69 explicitly calls Onslaught the gold-standard battleship and says anything stronger pound-for-pound should be considered for a nerf. It is 40 DP in 0.98a.

That gives the future eDP fit a human-defined reference interpretation:

```text
A broadly strong ordinary 40-DP capital should land around the Onslaught neighborhood.
```

This is better than inventing a numeric center from scratch. The model should still be free to find that the listed 40 DP is slightly high or low under a chosen scenario policy.

## Observation 2: Onslaught (XIV) is a clean sanity test

At the same 40 DP, the XIV variant has:

- +10 OP;
- +100 armor;
- +850 flux capacity;
- +30 flux dissipation;
- -2 top speed;
- otherwise the same broad mount pattern and system family.

The community observation is correspondingly higher: `A+ / S` versus `A / S-`.

A model that ranks base Onslaught above XIV under ordinary combat policy needs a strong, inspectable explanation. This is an excellent early unit test for the feature pipeline and fitted model.

## Observation 3: raw ratios can see Medusa, but only partially

Medusa is 12 DP and has very high flux/dissipation/mobility per DP. Its `A+ / S` human rating therefore has visible static support.

But the source also says AI value falls sharply without specific shield/system support. So the item needs at least two states:

```text
Medusa / competent general AI fitting
Medusa / weak or missing system-enabling fitting
```

This is exactly what Build Sensitivity and Synergy Debt are meant to represent.

## Observation 4: Doom and Afflictor prove systems/control cannot be optional features

Doom looks mediocre in several naive per-DP fields yet receives `B / S+`.

Afflictor has tiny hull/OP totals yet receives `B / S` even after a DP increase.

The written reasons are system- and control-heavy:

- Mine Strike changes positioning, escape routes, fighter survival, and kill setup;
- Entropy Amplifier increases allied damage on a target;
- phase movement changes target access and survival;
- player timing unlocks much more value than ordinary AI use.

Any model that tries to score hulls primarily from CSV fields will systematically underrate these designs. Ship-system experiments and control mode must be first-class inputs to eDP.

## Observation 5: Executor versus Pegasus is a natural matched-pair experiment

Both are 50 DP and share several headline values:

```text
Hull:   17,000
Armor:   1,500
Flux:   14,000
```

Yet the observed AI ratings are far apart:

```text
Executor: A+
Pegasus:  C
```

Important differences include:

- shield efficiency: 0.6 Executor versus 0.8 Pegasus;
- top speed: 50 versus 35;
- High Energy Focus versus Fast Missile Racks;
- radically different large-slot emphasis;
- Executor built-ins and associated penalties;
- Pegasus ammunition/endurance and AI missile-spending behavior.

This pair should help separate several latent factors:

```text
persistent pressure
burst conversion
ammo endurance
AI target selection
mobility
shield quality
mount/role mix
```

The pair is not a pure ship-system experiment, but it is far more controlled than comparing arbitrary capitals.

## Observation 6: Pegasus is the first Build Sensitivity stress case

The headline AI tier is C, while multiple commenters argue that a four-Hydra fit is dramatically stronger and reliable enough to deserve a much higher rating.

Do not average those into one vague opinion. Encode:

```text
Pegasus / ordinary fitting prior
Pegasus / four-Hydra conditional prior
```

Then ask the combat/model layer whether the Hydra configuration produces a real distribution shift, and what it pays in Synergy Debt or endurance.

## Observation 7: Anubis is a good first suspected eDP outlier

Anubis combines:

- 18 listed DP;
- 80 speed;
- 0.6 omni shield;
- three large energy mounts;
- a fighter bay;
- Temporal Shell;
- explicit anti-missile / anti-fighter specialization;
- an unusual built-in flux penalty that complicates ordinary fitting.

The community rating is A and the author explicitly says it would still be attractive at 20 DP. That makes `eDP > 18` a useful hypothesis to test, while the fitting penalty gives the model a chance to learn that raw slot count is not free value.

## First modeling recommendations

1. **Fit pairwise before absolute.** Begin by asking the model to order well-supported comparisons such as XIV > base Onslaught.
2. **Keep AI/player outputs separate.** Doom, Afflictor, Aurora, Pegasus, and Medusa already demonstrate large control interactions.
3. **Represent fitting as a distribution.** One best-known build should not define the entire hull; retain both competent-general and specialized configurations.
4. **Treat systems as marginal interventions.** Build the system laboratory early instead of letting system value hide inside the hull residual forever.
5. **Use downstream battle outcomes to learn weights.** Static ratios are candidate explanatory features, not a handcrafted final score.
6. **Preserve a canonical policy.** Installed mods can change peer percentiles and matchup exposure without rewriting canonical reference eDP.
7. **Use disagreements as experiment selection.** Pegasus is valuable precisely because knowledgeable players disagree for concrete build/endurance reasons.

## Next data pass

The highest-value expansion is not indiscriminately adding every hull. Add deliberately contrasting anchors:

- low-cost durable distraction ships such as Monitor/Centurion;
- strong carrier examples;
- bad/weak examples from each hull size;
- ordinary high-breadth A/B ships;
- systems with obvious marginal effects such as Fortress Shield, Entropy Amplifier, Mine Strike, Phase Skimmer, Plasma Jets, and Accelerated Ammo Feeder;
- weapon examples where armor hit strength, range, ammo, tracking, and PD create known disagreements.

That gives the first fit variation in every important latent dimension instead of only more rows.
