# Linting one mod alone, and what that broke (2026-07-28)

The linter could only run against a resolved profile: an install root, `enabled_mods.json`, and every
enabled mod indexed together. That is the wrong shape for its audience. A mod author has their work in
a directory, not installed into somebody else's seventy-mod profile, and analysis that only runs
against a resolved profile is analysis they cannot run at all.

`preflight lint --path <directory>` indexes one directory as a lone resource root. It also turns the
72 mods already installed here into 72 independent samples, which is the calibration this needed —
every floor and threshold so far was tuned against one profile, and thresholds tuned on one sample
tend not to transfer.

## What the samples say

74 directories, each linted alone:

```
median 0 findings   mean 12.2   max 250   clean 40/74
```

| rule | findings | mods | share of mods |
| --- | ---: | ---: | ---: |
| `texture-npot-padding` | 265 | 24 | 32% |
| `audio-oversampled` | 250 | 11 | 15% |
| `texture-progressive` | 226 | 17 | 23% |
| `asset-editor-source` | 94 | 10 | 14% |
| `asset-duplicate-content` | 44 | 11 | 15% |
| `audio-long-effect` | 17 | 6 | 8% |
| `asset-extension-mismatch` | 4 | 3 | 4% |
| `audio-undecodable` | 2 | 1 | 1% |

**Most mods are clean.** 40 of 74 produce nothing at all and the median is zero, which is the result
to want from a tool that reports on other people's work. No rule fires on more than a third of mods,
so none of them is describing normal practice back at people as though it were a defect. Every rule
fired somewhere, so none is dead. Findings concentrate: one mod accounts for 250.

## Cross-checking the two modes found a false positive

Running both modes and comparing per-mod counts was meant to catch bugs in the new path. It caught
something better. Most mods differed by a few findings — expected, since profile mode adds
`asset-shadowed` and attributes only override winners. Two differed the wrong way:

| mod | standalone | profile | delta |
| --- | ---: | ---: | ---: |
| `knights_of_ludd` | 29 | 16 | **-13** |
| `eusan_nation` | 20 | 15 | **-5** |

Fewer findings *with more context* is the interesting direction. `knights_of_ludd` reports sixteen
`audio-unreferenced` sounds alone and **none** in the profile.

The cause is that `sounds.json` declarations are merged across the whole profile rather than each mod
being read on its own. A companion mod declares those sixteen sounds. Alone, the directory cannot see
who declares its files, so every one of those sixteen was a false positive — shown to an author who
had done nothing wrong, about files that work correctly.

`audio-unreferenced` is therefore suppressed in standalone mode, joining two rules already suppressed
for the same class of reason:

| rule | why a lone directory cannot answer it |
| --- | --- |
| `asset-shadowed` | compares providers; there is only one |
| `sound-declared-missing` | would fire on every core sound a mod legitimately reuses |
| `audio-unreferenced` | another mod's `sounds.json` may declare these files |

The standalone report names these in a `rulesRequiringAProfile` field, so a clean result does not read
as a stronger claim than it is.

## What this does not settle

These are 74 samples from one person's profile, chosen by one person's taste. They are not a random
sample of the mod ecosystem, and a rule that never misfires here could still misfire elsewhere. What
they do establish is that the thresholds are not obviously wrong: the common case is silence, and no
rule is firing on a majority.
