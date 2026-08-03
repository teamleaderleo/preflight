# Stale mod simulation opponents reach vanilla as invalid fleet members

**Date:** 2026-08-04

**Input:** gameplay pilots `gameplay-pilot-20260804-033528` and
`gameplay-pilot-20260804-042009`, installed Starsector 0.98a-RC8 mod data and bytecode

**Status:** exact consumption-site guard live-validated; roster expansion remains separate work

The follow-up gameplay pilot appeared to have no possible simulation opponents. Its console contains
25 distinct `is not a valid ship variant id` reports from
`FleetMember.updateVariantIfNeeded`, reached through `OO0O.addToFleet` and the refit simulator's
private mission builder. The immediately preceding pilot, recorded before the deployment icon cache
existed, contains the exact same 25 ids. This is not a cache regression.

The ids are stale rows in enabled mods' own merged inputs:

- United Aurora Federation 0.8.4c contributes 22 invalid rows from
  `data/campaign/sim_opponents.csv`.
- PMMM 1.7.7 contributes three: `wolf_d_pirates_Raider`, `falcon_p_Strikee`, and
  `pmm_sunder_p_Assault`.

Several are plainly renamed loadouts. For example, UAF's CSV still names
`uaf_tsutsumu_l_standard` while the installed variant directory contains
`uaf_tsutsumu_l_efficient` and `uaf_tsutsumu_l_expanded`. Vanilla copies every merged row into a
shared `SpecStore` list and the refit simulator later passes every id to `addToFleet`. The invalid
member path prints a full runtime exception and can leave simulator construction incomplete.

The guard is deliberately narrower than changing `FleetMember` or swallowing arbitrary variant
errors. The exact reviewed `com.fs.starfarer.coreui.refit.OOOo` class contains two calls that consume
the simulation-opponent list. Immediately after each call, the adapter validates ship ids through
`SpecStore.new(HullVariantSpec.class, id)` and `_wing` ids through the corresponding
`FighterWingSpec` registry. Valid rows retain order and duplicates. If every row is valid, the exact
original list object is returned. If a row is authoritatively absent, only that simulator invocation
receives a filtered copy; Preflight never edits the mod or the shared merged list.

The transformation requires the exact class hash, archive hash, Java 17 class version, private
method descriptor, app classloader, and exactly two reviewed consumption sites. Any drift declines
the transformation. Any runtime lookup, reflection, list, or registry failure returns the entire
original list. A bounded adapter report records calls, candidates, removed rows, fail-open events,
and invalid ids; `preflight.simOpponentSafety.disabled=true` disables only this guard.

Focused tests cover ship-versus-wing registry selection, valid-list identity preservation,
copy-on-invalid behavior, shared-list non-mutation, reflection fail-open, the kill switch, changed
bytecode shape, wrong hashes, and duplicate transformation. An opt-in installed-archive test also
transformed the actual reviewed `starfarer_obf.jar` class and found exactly the two expected sites;
the transformed class then linked successfully on Starsector's own Java 17 with its production
verification setting.

## Live result and remaining roster gap

The `sim-opponent-safety-20260804-044915` pilot applied 16 reviewed transformations with no decline,
contained failure, or health mismatch. The simulator guard received 535 configured rows, removed the
25 known-invalid ids, and returned 510 valid rows. `failOpen` remained zero and the console contained
no invalid-variant errors. This establishes the guard's intended behavior.

The pilot still did not present what the user considered the full enemy roster. That is a different
boundary: the variant cache rehydrated 5,573 variant definitions, while merged
`sim_opponents.csv` files opted in only 535 rows. The guard repairs safety at the curated list's
consumption seam; it deliberately does not add unlisted variants. Blindly supplying every loaded
definition would also include alternate loadouts, hidden/dev variants, armor and station modules,
and other specs that are not standalone simulator opponents. Any roster expansion therefore needs
an explicit deployability classifier and its own compatibility evidence.
