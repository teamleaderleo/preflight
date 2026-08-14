# Vanilla and `--fast` on the same profile, in the same session

**Date:** 2026-08-15
**Session:** `20260815-022348`, `benchmark-summary.json` retained
**Repository head:** `f6c525741d4149cebe3d9171a23975716af8cd39`
**Preflight jar:** `9825f90b044d38cfde06e9176aa3fb06139eabac89a50d5b756c7d567383cdd9`
**Status:** first controlled pair; one machine, one profile, one session

Every published before/after so far has been a chronological progression: the 88.13s baseline was
measured on a 77-mod profile at the start of the work, and the 15.88s result on the 83-mod profile
the project has run since. That is two conditions separated by both the optimisation work *and* six
mods, which is why the writeup has had to say the two ends are not a controlled comparison.

This campaign runs both conditions against the same profile, interleaved, in one sitting.

## Result

| condition | n | median | min | max | range |
| --- | --- | --- | --- | --- | --- |
| `vanilla` — the game's own launcher, no Preflight | 5 | **89.00s** | 87.29s | 90.22s | 2.93s |
| `fast` — the `--fast` preset an installed launcher runs | 5 | **15.53s** | 15.25s | 15.85s | 0.60s |

**73.47s apart, 82.55%.** Permutation p = 0.048, which is the smallest value this design can
produce — the two conditions do not overlap, so every relabelling but one is less extreme. Treat it
as descriptive. It says the separation is not sampling noise on this machine; it does not
generalise to other hardware or other mod lists.

Individual runs, in the order they were launched:

- `vanilla`: 89.00, 88.86, 90.22, 89.91, 87.29
- `fast`: 15.58, 15.25, 15.85, 15.53, 15.36

## Conditions

Both conditions came from the same table and the same clock: `--unattended`, protocol `direct`,
measured from the game's own start-of-log marker to its graphics preload. No launcher was clicked
and no run was excluded.

- **Profile:** 83 enabled mods, fingerprint `2995668308ac3d31d645ccac30fb1a7e644e64fce5609050a1488df4cadc5af6`,
  read from the run's own `profile-after.json`.
- **Game:** Starsector 0.98a-RC8, from the game's own `Starting Starsector 0.98a-RC8 launcher` line.
- **Machine:** Apple M5, macOS 26.6.1, 1440x932 windowed with sound on — the launcher's own saved
  settings.
- **Order:** conditions shuffled inside each round, seed 28583, so thermal drift cannot line up with
  one condition.
- **Cooldown:** 240s before every launch, including the first.
- **Settling launch:** one `fast` launch discarded before counting, so GraphicsLib's one-time normal
  map generation could not land inside a measured run. It took 20.9s.

Drift across the whole campaign was **-0.46s**, 0.051s per launch, explaining 3.9% of variance.
The harness reports `driftDominatesConditions: false`. For contrast, the 2026-08-01 campaign drifted
+19.6s across fifteen launches; the cooldown is what removed that.

## What this does not say

**The `fast` figure is a warm launch.** `preparationMillis` is 0 — the caches already matched this
profile, so the run reuses prepared data and does not include the cost of building it. `preflight
prepare` is a separate one-off command and is not in either number. A first-ever run on a new
profile pays that cost once and is not described here.

**One machine, one profile, one session.** Five runs per condition is the campaign threshold for a
reportable claim, not evidence about anyone else's hardware or mod list.

**This is the checkout, not a release candidate.** The jar under test is
`preflight-cli/target/preflight.jar` at the head above. `run-startup-benchmark.sh` has no packaged
mode — it hardcodes that path — so the release-readiness item asking for the benchmark on the exact
release candidate is not closed by this campaign, and closing it needs harness work rather than
another run.

**The adapters were live.** Each `fast` run served 15,469 prepared textures (2,116,422,119 bytes,
3 fallbacks) and bypassed 15,469 pixel conversions with 0 fallbacks.
