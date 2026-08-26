# Installed-profile paper balance analysis

Status: local analysis tool; rankings are hypotheses for simulator calibration, not release claims

`scripts/starsector_balance_analysis.py` builds a derived database from the exact enabled Starsector
profile. It exists to find interesting candidates, strict-looking upgrades, weak ships, and workload
recipes without redistributing game or mod data. Full outputs belong under ignored
`benchmark-results/`; only the reusable analyzer, tests, and bounded conclusions belong in Git.

## Source and precedence

The analyzer reads `mods/enabled_mods.json`, resolves each enabled id through its `mod_info.json`, and
uses that enabled order after core. CSV rows use last-provider-wins id precedence. JSON-shaped hull,
weapon, and variant resources first deep-merge providers at the same logical path, matching mods that
ship a partial overlay such as a one-field weapon effect replacement, and only then resolve ids.

The dialect reader handles the syntax observed in the installed profile: line and block comments,
trailing commas, single-quoted strings, unquoted keys and enum values, numeric suffixes, leading or
trailing decimal points, and leading-zero numbers. Every run reports unresolved providers, parse
failures, overridden ids and logical paths, and missing references before emitting a ranking.

## Hull model

Default rankings contain only frigates, destroyers, cruisers, and capitals that are not marked
unboardable, unavailable, hidden, or modular/station hulls. Fighters and special content remain in
the source census. Rare or limited acquisition is a separate flag, so it can be filtered without
silently treating rarity as combat weakness.

The balanced lens combines within-hull-size-and-role percentile scores:

- 20% mobility: speed, acceleration, turn rate, and campaign burn proxy;
- 25% durability per DP: hull, armor, and a conservative shield-flux-EHP contribution;
- 20% flux per DP: dissipation plus capacity amortized over a 20-second window;
- 25% firepower capacity per DP: OP, weighted weapon-slot envelope, and fighter bays;
- 10% logistics efficiency: DP relative to monthly supplies.

Mobility-, durability-, and firepower-heavy lenses rerank the same rows. `rankSpread` is the distance
between a hull's best and worst rank across those lenses; a strong mean rank with a small spread is a
more robust paper candidate than a hull that wins only one weighting.

Pareto dominance is stricter than the composite score. Hull A dominates B only inside the same hull
size, broad role, and shield type when A costs no more DP, is no worse on every modeled favorable
stat, and is strictly better on at least one. This deliberately avoids calling a phase ship, carrier,
or qualitatively different defense a strict upgrade based on one spreadsheet column. Ship systems,
geometry, built-ins, and AI can still overturn a paper dominance result.

## Weapons and fitted variants

Weapons compare only inside size, mount type, and damage-type peers. Declared beam DPS is used when
present. Projectile weapons use an explicitly labeled cycle proxy from shot damage and timing fields;
ammo, reload mechanics, burst behavior, scripted effects, damage-type matchups, accuracy, and target
access can invalidate that proxy. Range, DPS per OP, and flux efficiency form the initial score.

Variant analysis crosses fitted slot ids with merged hull and weapon specs, sums the available weapon
proxies, hullmod OP columns, vents, and capacitors, and records remaining OP and compatibility warnings.
Its loadout score combines hull paper strength, realized DPS per DP, range, flux headroom, and mean
weapon score. The current fit check is intentionally conservative. Stock and mod variants may receive
warnings because built-ins, scripted discounts, special slots, or runtime rules are absent from the
CSV arithmetic; warnings are data-quality leads, not proof that a shipped variant is invalid.

## What the numbers do not prove

No static score proves the best ship in Starsector. The model does not yet know armor layout,
time-to-contact, weapon arcs and convergence, missile conservation, fighter replacement, officer and
skill interactions, ship-system scripts, AI personality, phase-time behavior, objectives, retreat,
terrain, or acquisition constraints. It also cannot infer arbitrary hullmod script effects from their
OP table.

The intended loop is:

1. use stable ranks, Pareto results, and robust median/MAD outliers to nominate candidates;
2. construct mirrored, role-appropriate fleets at equal DP;
3. run repeated simulations with side and spawn-order swaps;
4. record win rate, survival, time to resolution, damage/DP, and frame cost;
5. compare empirical results to paper predictions and revise weights or missing features.

The existing 1,040-DP high-tech fixture is a performance stress workload, not the optimizer's answer.
Midline ballistic/projectile, carrier/fighter, missile-density, and normal-size control fixtures should
remain separate so an optimization that helps one scaling regime is not assumed to help every battle.

## Run locally

```bash
python3 scripts/starsector_balance_analysis.py \
  --game /Applications/Starsector.app \
  --output benchmark-results/balance/installed-profile
```

The output directory contains `summary.json`, `hulls.csv`, `weapons.csv`, and `variants.csv`. Re-run
after the enabled profile changes; do not treat an old ranking as portable to another mod set.
