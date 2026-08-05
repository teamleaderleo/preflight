# Nexerelin's startup callback implicitly constructs the campaign engine

Nexerelin 0.12.2b is consistently one of the largest remaining application-load callbacks on the
current 83-mod profile, at roughly 0.6--0.9 seconds. An exact, opt-in startup probe now separates
its top-level config read, mod-faction list, 75 `NexFactionConfig` constructions, direct JSON field
access, helper methods, starting-fleet validation, and variant-existence checks. The probe changes
no return value or control flow and is registered only with `--startup-phase-probe`.

Three clean self-terminating probes localized the callback:

- 75 faction-config loads consumed 0.56--0.64 seconds;
- their 75 cached merged JSON reads consumed 0.11--0.15 seconds;
- 6,655 direct JSON accesses consumed 0.06--0.09 seconds;
- repeated localization defaults, relationship maps, conversions, dispositions, stations,
  vengeance names, and special items were individually small;
- `loadStartShips` consumed about 0.31--0.32 seconds, almost entirely in one call.

The deeper run recorded 428 fleet validations and 1,150
`SettingsAPI.doesVariantExist(String)` calls. One existence check took about 300ms; all other checks
combined took roughly 10ms. Exact shipped bytecode explains the discontinuity. A missing static
variant falls through `SpecStore` to `CampaignEngine.getInstance().getSavedVariant(id)`. If no
campaign engine exists, `getInstance()` constructs the complete engine, publishes it through
`Global.setSector()` and `Global.setFactory()`, and initializes the combat engine.

This looked removable but is not redundant in this mod stack. A temporary exact-build experiment
fed the existing null path directly when no campaign engine existed. The rewrite matched and
installed, then Nexerelin immediately failed in `fillRelationshipMap()` and
`NexUtilsFaction.doesFactionExist()` because both dereference `Global.getSector()`. In other words,
Nexerelin accidentally uses the first missing start-ship variant check as its campaign-engine
bootstrap. The experiment was deleted and is not part of Preflight.

There is no honest 300ms optimization at this boundary. Avoiding the lookup without moving the
engine initialization breaks Nexerelin; moving the same initialization earlier only relabels the
cost. A future improvement would need to change Nexerelin's initialization model or make the
campaign-engine constructor itself cheaper while preserving all of its global publication side
effects.

One attempted detailed probe hit the known bundled x86 JVM SIGSEGV at 0.835 seconds, before resource
initialization, and is excluded. Its exact retry reached the main menu and produced the detailed
attribution, but forced harness shutdown emitted OpenAL teardown errors; it is useful as timing
evidence, not as a clean compatibility gate. The three preceding probes exited cleanly.
After deleting the rejected guard, a final clean launch reached the menu in 25.04 seconds, completed
all 76 callbacks, exited zero, and reproduced the 330ms single-call bootstrap. Full `mvn verify`
passes.

Relevant runs:

- `~/.starsector-preflight/runs/nex-faction-breakdown-v1-20260805-152809`
- `~/.starsector-preflight/runs/nex-faction-breakdown-v2-20260805-152956`
- `~/.starsector-preflight/runs/nex-faction-breakdown-v3-20260805-153104`
- `~/.starsector-preflight/runs/nex-start-ships-breakdown-v1-retry-20260805-153534`
- `~/.starsector-preflight/runs/nex-diagnostic-final-clean-20260805-154719`
- rejected experiment: `~/.starsector-preflight/runs/saved-variant-guard-v2-20260805-154242`
