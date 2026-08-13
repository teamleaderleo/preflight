# The fast preset now installs both rule-expression caches

**Date:** 2026-08-05  
**Install:** Starsector 0.98a-RC8, 83 enabled mods, macOS on Apple M5 under Rosetta  
**Result:** repaired a shared-class dispatch bug; exact adjacent control recovered 148--180ms

## The defect

`--fast` enabled both the persisted rule-token cache and the persisted rule-command-class cache.
Both exact targets rewrite the same obfuscated rule-expression class. Target selection normally
reached the token plan first, and its production branch returned immediately after installing only
that rewrite. The startup-probe branch already composed both plans, which hid the defect during the
original optimization work.

The failure was visible in every ordinary fast report: the command artifact was a valid `hit` with
671 prepared winners, but `packagesDeclared`, `hits`, and `misses` were all zero. The product said
the cache was loaded without ever invoking it.

The token-target branch now applies the token memo first and then applies the independent command
shortcut to the rewritten bytes. If either plan declines, the other valid rewrite remains. The
existing command runtime still compares the complete ordered live package list, instantiates the
winning command class exactly as vanilla does, and falls back to the untouched package walk on any
mismatch or disagreement.

## Verification

A regression fixture now puts the token constructor and command resolver on one class, applies the
plans in production order, and requires both runtime call sets to remain. Focused tests and full
`mvn verify` pass.

The repaired unattended gate is retained at:

- `~/.starsector-preflight/runs/rule-command-compose-20260805-211926`

It reached the ordinary main-menu marker, shut down automatically, and reported ACTIVE adapter
health with 38 transformations and zero decline or contained failure. The token cache retained all
62,340 hits. The command cache matched all 47 live packages and served all 671 prepared winners,
with zero misses, disagreement, or fallback.

An immediately adjacent startup-probe control used the identical fast stack except for the command
cache:

- repaired composition: command phase **376ms**, rules loader **1,122ms**
- control: command phase **524ms**, rules loader **1,302ms**

That is 148ms at the exact command seam and 180ms at its containing loader. The complete SpecStore
interval differed by 95ms because unrelated work and thermals remain noisy. Whole-launch times are
not used for the claim. This result also agrees with the older isolated warm-cache result of about
154--165ms.

## Baseline context

Before the repair, a five-launch unattended cohort of the actual non-probed `--fast` preset measured
25.71s, 25.72s, 26.57s, 26.06s, and 26.77s (median 26.06s). The monotonic rise on the fanless machine
is thermal drift, not a stable one-second code regression. The first cool pair is the useful local
baseline; restoring roughly 0.15--0.18s puts the same condition around 25.5--25.6s, still short of a
repeatable sub-25 claim.
