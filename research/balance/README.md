# Balance research corpus

This directory is the first data-bearing research slice for #1148 and #1149.

It keeps three layers separate:

1. **facts**: exact per-version game/content fields such as DP, OP, armor, flux, speed, mounts, built-ins, and ship system;
2. **observations**: human ratings, comparisons, reasoning tags, and context from Reddit/video/tournament sources;
3. **analysis**: derived hypotheses and calibration notes. These are research outputs, never game truth.

The checked-in corpus stays deliberately small. Preflight should eventually read the same fields from the selected Starsector installation and enabled mod profile, using the installed content as authority. The repository corpus exists to develop the model, reproduce examples, and retain public calibration sources without becoming a mirror of Starsector's data files.

## Canonical reference versus local meta

A content/version pair may receive one canonical reference estimate under a named policy, for example:

```text
Anubis / Starsector 0.98a-RC8
General-AI reference eDP: 21.3
Listed DP: 18
```

A modded installation can change peer percentiles and matchup exposure without rewriting that canonical reference estimate. Profile-specific eDP, when computed, must be named separately with its own policy/profile identity.

## Seed sources

Game facts are cross-checked against Starsector Wiki pages marked current for 0.98a. Public human observations currently come from Grievous69's 0.98a Reddit tier-list series.

Primary references:

- Starsector 0.98a / RC8 notes: https://starsector.wiki.gg/wiki/Starsector_0.98a
- Capital tier list: https://www.reddit.com/r/starsector/comments/1k6ydy5/
- Cruiser tier list: https://www.reddit.com/r/starsector/comments/1k9569u/
- Destroyer tier list: https://www.reddit.com/r/starsector/comments/1kca3jd/
- Frigate tier list: https://www.reddit.com/r/starsector/comments/1kh6jan/

Per-item wiki references live in the seed JSON.

## Tier semantics

For Grievous69's series:

- first tier = AI control;
- second tier, when present = player control;
- one tier means the author sees little meaningful AI/player difference;
- campaign usefulness contributes to the rating;
- tiers are ordinal and relatively compressed; A versus C does not mean an A ship trivially defeats a C ship;
- ultra-specific fleet plans are intentionally de-emphasized.

Do not convert tier letters directly into evenly spaced numeric targets.

## Next research slices

- ingest the full four ordinary ship lists;
- add ballistic, energy, missile, and fighter observations;
- encode major dissent comments as conditional/pairwise observations;
- add BigBrainEnergy cross-patch observations;
- add one tournament fleet/loadout/result under its exact event policy;
- build an installed-game extractor that emits the same fact record contract;
- begin fitting pairwise priors only after holdout cells are marked.
