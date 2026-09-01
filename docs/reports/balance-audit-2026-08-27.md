# Installed-profile paper balance audit — 2026-08-27

Status: point-in-time v2 audit of the exact enabled profile; simulator nominations, not universal
balance claims

Commit context: analyzer format `starsector-paper-balance-v2`; 83 enabled mods plus core; 1,417
ranked player hulls after materializing 368 hull skins; 3,052 weapons; 735 ship systems; 5,550
variants; 2,705 high-confidence fitted loadouts. Metadata, hull, skin, weapon, system, and variant parse
failures were zero.
The complete derived CSV/JSON snapshot remains ignored under `benchmark-results/`; this bounded report
is intentionally committed for review and historical comparison.

## Stable hull nominations

“Stable” sorts the mean rank across balanced, mobility, durability, and firepower lenses, then rank
spread. A low spread means the hull is not winning only one chosen weighting. Ship systems and
scripted built-ins are named but not numerically valued.

| # | Hull | Provider | Size / role | DP | Balanced | Mean rank | Spread |
|---:|---|---|---|---:|---:|---:|---:|
| 1 | Exeter | CFT | CAPITAL_SHIP / carrier | 23 | 90.698 | 2.5 | 6 |
| 2 | Anubis | core | CRUISER / combat | 18 | 86.639 | 5.25 | 7 |
| 3 | Quietus | TTE | CRUISER / carrier | 12 | 84.744 | 11.25 | 19 |
| 4 | Arethusa | CFT | CAPITAL_SHIP / carrier | 28 | 83.941 | 17.25 | 53 |
| 5 | Circe | swp | CRUISER / combat | 12 | 81.232 | 26.25 | 33 |
| 6 | Commandant | BSC | CRUISER / combat | 16 | 83.570 | 29.25 | 98 |
| 7 | Aphelion | hte | CRUISER / combat | 15 | 81.219 | 33 | 61 |
| 8 | Muscular | JYD | CAPITAL_SHIP / carrier | 12 | 81.889 | 34.75 | 100 |
| 9 | Olmedreca | TTE | CAPITAL_SHIP / combat | 12 | 80.904 | 36.5 | 70 |
| 10 | Stratus (P) | diableavionics | DESTROYER / combat | 5 | 82.007 | 36.75 | 88 |
| 11 | Nimbyx | OcuA | CRUISER / combat | 9 | 81.795 | 37 | 101 |
| 12 | Cog PD Platform | CFT | CRUISER / carrier | 10 | 81.814 | 40.5 | 147 |
| 13 | Deshret | jaydeepiracy | CRUISER / combat | 18 | 79.055 | 41.5 | 61 |
| 14 | Kaolinite (Litho) | XLU | CAPITAL_SHIP / combat | 45 | 78.797 | 41.75 | 61 |
| 15 | Hardy | JYD | DESTROYER / carrier | 8 | 79.739 | 42.25 | 50 |

Anubis is the strongest currently credible vanilla signal: balanced hull rank 2, mean four-lens rank
5.25, spread 7, no paper dominator, and Temporal Shell exposed as an unpriced special mechanic. That
converges with player reports that the hull is unusually strong for 18 DP.

## Fitted-loadout nominations

| # | Hull — variant | Provider | Size / role | DP | Score | Anti-ship DPS proxy | PD DPS proxy |
|---:|---|---|---|---:|---:|---:|---:|
| 1 | Vestale — Standard | CFT | FRIGATE / combat | 4 | 92.202 | 761.111 | 0.000 |
| 2 | Lector — Outdated | luddenhance | DESTROYER / phase | 7 | 91.875 | 2133.333 | 0.000 |
| 3 | Mongrel — Strike | underworld | CRUISER / combat | 12 | 90.779 | 2395.833 | 2000.000 |
| 4 | Stratus (P) — Outdated | diableavionics | DESTROYER / combat | 5 | 90.413 | 931.479 | 1500.000 |
| 5 | Kite (P) — Missile | PMMMVanillaEdits | FRIGATE / combat | 2 | 90.409 | 500.000 | 0.000 |
| 6 | Tarsus (BRV) — Standard | HMI_brighton | DESTROYER / combat | 3 | 89.513 | 1000.000 | 0.000 |
| 7 | Rime — Outdated | diableavionics | CRUISER / carrier | 16 | 89.092 | 1626.595 | 260.000 |
| 8 | Odam — Standard | CFT | CRUISER / carrier | 18 | 89.053 | 1460.833 | 0.000 |
| 9 | Short — Standard | JYD | FRIGATE / carrier | 4 | 89.000 | 155.000 | 500.000 |
| 10 | Albatross — Anti-Fighter | swp | DESTROYER / carrier | 7 | 87.419 | 1026.667 | 0.000 |
| 11 | Mongrel — Junker | underworld | CRUISER / combat | 12 | 87.136 | 1955.833 | 1050.000 |
| 12 | Creep — Close Support | HMI | DESTROYER / combat | 8 | 87.131 | 2749.333 | 1020.000 |
| 13 | Amalgam — Junker | underworld | CAPITAL_SHIP / combat | 24 | 87.077 | 3220.667 | 3700.000 |
| 14 | Stratus (P) — Support | diableavionics | DESTROYER / combat | 5 | 87.017 | 590.948 | 350.000 |
| 15 | Dunnock (P) — Strike | A_S-F | FRIGATE / combat | 4 | 87.011 | 944.792 | 312.500 |

These are data-audit targets, not fleet advice. Very low-DP fits remain prominent because the model
measures marginal paper efficiency. Use hull size, role, special-mechanic flags, ammunition burden,
and empirical simulation before interpreting one global ordinal rank.

## Corrections prompted by known ships

- Grendel Support was fitted rank 6 in v1. Its three Harpoons were priced at instantaneous cycle DPS
  and six Vulcans at full anti-ship DPS. The v2 60-second ammunition window and separate PD weighting
  move it to fitted rank 64. The base Grendel remains strong and non-dominated: balanced hull rank 41,
  durability rank 50. “Strong, underrated cruiser” survives; “literal #6 fit” does not.
- Brawler (LP) was absent in v1 because it is a `.skin`, not a standalone `.ship`. V2 materializes it
  from the base Brawler and correctly exposes Ammo Feeder plus built-in Safety Overrides. The static
  score still cannot price those scripts, so its fitted Raider rank 354 is a known underprediction and
  a high-value simulator calibration case.
- Monitor and Tempest remain static underpredictions. Fortress Shield and Terminator Drones explain
  why ordinary hull/slot arithmetic cannot reproduce their specialist value.

## Ship-system evidence and doctrine axes

The local system catalog resolves 735 system rows and inspects 89 locally available Java sources.
Nine CSV ids have no matching `.system` definition; they remain data-quality leads. Group labels are
capabilities, not strength scores.

| System | Capability groups | Structured/source evidence | Tactical interpretation to test |
|---|---|---|---|
| Accelerated Ammo Feeder | offense | 5 active seconds in a 17-second cycle; source exposes +100% ballistic ROF and -50% ballistic flux use | concentrated punch-up, especially when the fitted ballistic package crosses shield/armor breakpoints |
| Fortress Shield | defense, commitment | toggle; cannot fire; source exposes 90% shield-damage reduction and zero shield upkeep at full effect | extreme denial/survival bought with lost damage output |
| Termination Sequence | offense, control, support | 600 flux/use; AI hints expose 1,500 threat range and 1,200 threat damage; implementation is compiled | Tempest dueling/finishing and disabling value; EMP behavior needs direct code/runtime confirmation |
| Temporal Shell | mobility | 10 active seconds in a 26-second cycle; AI hints expose +100 active speed and 1.46 average-speed multiplier; implementation is compiled | Anubis/Scarab action-economy and disengagement multiplier, not priced in the static hull score |
| Burn Drive | mobility, commitment | 5 active seconds in an 18-second cycle; +100 speed hints; disables turning, strafing, acceleration control, and shields while committed | rapid time-to-contact and anchor pressure with a flank/isolation liability |

Fleet evaluation therefore keeps **punch-up**, **punch-down**, **independent action**, **formation
contribution**, and **commitment/recovery** as separate axes. An Onslaught-like all-in-one anchor can
be extremely effective under simple advance orders because it bundles shield pressure, armor damage,
durability, and forward commitment. The same package can lose marginal value when the fleet needs
fast independent responders or when an anchor is isolated and unable to reposition. Tempest,
Monitor, and Brawler (LP) exercise the opposite specialist breakpoints.

## Methodology

The balanced hull score is 15% mobility, 25% durability per DP, 15% absolute effective durability,
20% flux per DP, 20% firepower envelope per DP, and 5% logistics. Except for absolute survival,
components are percentiles within hull-size-and-role peers. Alternate mobility, durability, and
firepower lenses produce sensitivity ranks. Pareto dominance requires equal hull size, role, and
shield type, no higher DP, no worse modeled favorable stats, and one strict improvement.

Weapon cycle DPS is capped by ammunition available over 60 seconds, including declared regeneration.
Point-defense DPS is reported separately and contributes 25% to the anti-ship proxy. Fitted scoring
combines hull score, anti-ship DPS per DP, range, flux headroom, and mean weapon score. `.skin`
overlays inherit base hull data, apply slot and built-in changes, and preserve exact system/hullmod
identifiers without inventing a numeric value for arbitrary scripts.

## Interpretation and next experiment

The v2 static audit is useful for nominations, regression detection, obvious outliers, and workload
design. It does not solve ship systems, AI, armor geometry, missile timing, damage-type breakpoints,
fighter replacement, objectives, or matchup dependence. The next trustworthy step is mirrored,
side-swapped simulation across distinct fixture families: high-tech mobility, midline ballistics,
carrier/fighter density, missile density, and normal-DP control. Anubis, Grendel, Monitor, Tempest,
and Brawler (LP) form a useful vanilla calibration set because their community reputations exercise
both model successes and explicit blind spots.
