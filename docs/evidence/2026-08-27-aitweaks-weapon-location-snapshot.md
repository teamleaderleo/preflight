# AI Tweaks weapon-location snapshot candidate

**Status:** accepted with limit; exact structural, fixture, installed-JAR, and live-combat checks passed

The deterministic 30-second, 2× simulation profile recorded 464 combat main-thread execution
samples. AI Tweaks 2.2.10 `AutofireAI.advance` appeared on 49, or 10.56%, and its stacks carried
267.2 MiB of weighted JFR allocation samples. These are statistical samples rather than exact CPU
time or an allocation census.

Filtering allocation samples on vanilla weapon-slot `computePosition` attributed 139.3 MiB of
weighted `Vector2f` allocation to the method. Of that population, 63.1 MiB had
`AutofireAI.advance` above it and 49.1 MiB followed the short-lived
`AutofireAI.updateTarget -> SelectTarget.target` route. The exact installed `SelectTarget` class is
still the reviewed AI Tweaks 2.2.10 class with SHA-256
`a87ebfe62a5a36d0b507cfc66822d3bcccda66c9c10f8c313b8a7b21924e97d5` from archive SHA-256
`9f6179bcd2df2e3ce8cea2da79051c9f1be3c9b71712c6c28d7568b777ecf5b2`.

## Exact opportunity

One `SelectTarget` object is constructed and consumed synchronously inside one weapon-AI target
update. Its exact bytecode calls `WeaponHandle.getLocation-impl` six times. The game thread cannot
advance ship position or facing between those calls, and the selection object is not retained for a
later frame. Reusing the location inside that object therefore preserves movement between target
updates while avoiding five redundant weapon-slot position calculations per selection.

The then-existing range/boxing transform was extended to v3 with one private final synthetic
`Vector2f` field. Its constructor records the first exact weapon-location result, and all six
reviewed call sites load that same reference. Admission still requires the exact mod archive,
custom AI Tweaks loader, class hash, Java 17 bytecode, constructor, weapon field, five range calls,
three boxing sites, and now all six location calls with the reviewed receiver shape. Any drift or a
second rewrite retains the original class.

The executable woven fixture changed the backing location immediately after construction and proved
that all six later reads keep the per-selection snapshot while the next selection can observe a new
location. It also proves one underlying location call, one underlying engagement-range call, two
constructor boxes, and one telemetry snapshot. The installed-archive integration test transforms
the exact local AI Tweaks JAR and confirms the original class contains six location calls while the
candidate contains one.

The last accepted 2× route created 32,676 selection snapshots. Applied to that same workload, v3
would avoid up to 163,380 weapon-location computations and their returned vectors. JFR's sampled
weights put the allocation opportunity near five-sixths of the 63.1 MiB autofire-attributed
`computePosition` population, about 52.6 MiB, but that remains a prioritization estimate rather
than an allocation census.

## Live result

A Java 17, Preflight-only run of `campaign-simulation-combat-speedup.json` applied the exact v3
plan to the installed AI Tweaks class, completed every semantic step, and exited with both the
launcher and driver reporting `passed`. Runtime telemetry recorded 27,687 selection snapshots.
The previously observed Advanced Gunnery Control `ClassCastException` did not recur. The log kept
the known startup warnings and vanilla simulation-dialog warning; neither stopped or invalidated
the scenario, and no candidate-specific failure appeared.

In the same 30-second, 2× combat step, the `SelectTarget`-attributed `computePosition` allocation
sample fell from 49.1 MiB before v3 to 10.0 MiB with v3. The roughly 80% reduction is consistent
with retaining one of the original six calls. Total `SelectTarget`-attributed weighted allocation
also moved from 97.3 MiB to 83.2 MiB, though differing battle evolution prevents treating that
aggregate as a controlled delta.

The live frame window was likewise not lockstep: v3's run averaged 30.41 FPS with a 7.90 FPS 1%
low, versus 46.39 and 12.48 in the earlier run. Ship survival, weapon activity, temperature, and
other combat state were not held constant, so this pair cannot attribute the difference to the
small per-selection snapshot. The candidate is accepted for its exact structural removal and
successful repeated live execution, with no claim yet that it improves FPS or frame pacing.

The weapon-location snapshot remained in v4 after a heavy combat run exposed a null dereference in
v3's separate boxed target-search field. A later v4 run reproduced the null-receiver failure at the
original target-search field read, so Preflight retired the entire combined target rather than
continuing to ship the geometry subset without a trustworthy semantic boundary. See
[the correction and retirement report](2026-08-27-aitweaks-boxed-search-range-correction.md). The
historical "two constructor boxes" statement above describes v3, not a retained contract.

## 2026-08-28 successor experiment

A later design avoided `SelectTarget` fields entirely by bracketing the synchronous selection in
`AutofireAI` and intercepting the exact `WeaponHandle.getLocation-impl` helper. It safely removed the
reviewed allocation family and passed ordinary plus 1,040-DP combat, but the global getter wrapper
showed measurable sampled CPU weight and no useful player-visible improvement. It was therefore
preserved and reverted rather than reviving this historical acceptance. See the
[successor rejection record](2026-08-28-aitweaks-weapon-location-selection-rejected.md).
