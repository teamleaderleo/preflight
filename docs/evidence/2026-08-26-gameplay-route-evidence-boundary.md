# Gameplay route evidence now proves measured duration

**Date:** 2026-08-26
**Status:** implementation and repository verification complete; a representative human pilot remains required

## The previous receipt admitted too little route evidence

The gameplay-pilot attestation bound the selected disposable save, exact before/after content,
engine JAR, source state, run/profile/adapter reports, adapter health, process outcome, and the
operator's save/reload confirmation. With the adapter enabled, it also required nonzero frame counts
for campaign warm-up, settled campaign, and combat.

Nonzero was not a sufficient route contract. One frame in each distribution could satisfy it. The
combat check also read `combatActive`, which includes Starsector's animated title-screen battle.
That was enough to show that each telemetry bucket existed, but not enough to substantiate the
requested campaign route or a three-to-five-minute combat exercise.

## Version 5 binds duration, ordering, and the human observation

Every frame distribution now publishes its exact accumulated active nanoseconds. A complete v5
operator receipt requires all of the following:

| Route phase | Distribution | Minimum active time | Minimum frames |
| --- | --- | ---: | ---: |
| Campaign warm-up | `campaignFirst30SecondsActive` | 20 seconds | 100 |
| Settled campaign | `campaignAfter30SecondsActive` | 30 seconds | 100 |
| Combat after campaign play | `combatAfterCampaignActive` | 3 minutes | 100 |

The time requirement prevents a handful of frames from standing in for the route. The frame floor
keeps a single long stall from standing in for a usable distribution. The combat distribution begins
only after campaign play has been observed, so the title-screen battle before loading a save cannot
satisfy it.

Frame classification alone cannot prove that later combat frames came from the operator's intended
battle rather than a title-screen return after campaign play. The create-once human statement now
therefore covers the whole route: campaign warm-up, settled campaign play, a three-to-five-minute
combat simulation, save, title-screen return, reload, resumed play, and normal exit. The typed phrase
changed with the attestation format so an older reload-only confirmation cannot silently acquire the
broader meaning.

A receipt also remains incomplete when the source tree was dirty, the run report does not say
`COMPLETED`, or the adapter reports contained runtime failures. The raw evidence files remain
size-bounded, identity-stable, and hashed into the receipt. Previously complete v4 lifecycle
receipts remain eligible for bounded retention; only new v5 receipts claim the stronger route
contract.

## What this does and does not prove

A complete receipt proves that one exact engine/profile/save combination exercised the measured
route and completed the guarded save lifecycle. It does not turn a human-driven route into a
controlled implementation A/B, and it does not generalize one mod profile to every save. The
comparison runner's two conditions both launch through Preflight: reviewed fixes off, then on. A
fresh complete pair still needs the operator to repeat a representative route under both conditions
before publishing a campaign-performance claim from that runner.

The exact retained source for the existing **9.15 → 20.45 FPS one-percent-low** values records them
as the first 30 seconds and later 4,091 frames of `campaign-engine-times-v1-20260805-073428`, not as
the runner's two conditions. This change strengthens the next evidence collection without changing
that older report's labels.

## Verification

- `bash -n scripts/run-gameplay-pilot.sh`
- `python3 scripts/test_save_state_guard.py` — 31 tests
- `mvn verify` — all modules passed
- `python3 scripts/test_verify_claims.py` and `python3 scripts/verify_claims.py`
- `python3 scripts/test_verify_source_boundary.py` and `python3 scripts/verify_source_boundary.py`
