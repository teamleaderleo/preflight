# Campaign radar's seven-class set is now constructed once

**Date:** 2026-08-05  
**Target:** Starsector 0.98a-RC8 `com/fs/starfarer/coreui/A/oOoO.class`  
**Class SHA-256:** `d946e2eddc6ecfca0b56e178928c349aa17ec178888e78701373679e818c0260`  
**Status:** implementation and offline verification complete; live campaign gate pending

## Why this seam

The latest mixed campaign profile contained only 174 campaign main-thread samples, but 22 of them
(12.64%) ended in `oOoO.renderStuff`. That is enough to inspect the renderer, not enough to claim a
precise saving.

The exact installed bytecode begins every render by copying the location's full `CampaignEntity`
repository into a new `ArrayList`. It then constructs a new `HashSet` and adds the same seven exact
classes:

- `CampaignPlanet`
- `CustomCampaignEntity`
- `CampaignOrbitalStation`
- `JumpPoint`
- `CampaignFleet`
- `CampaignTerrain`
- `NascentGravityWell`

The set is never mutated after those additions. It is used only by `contains(entity.getClass())`
inside the same invocation. By contrast, the following entity loop updates visibility, contact
replacement, range, icon maps, and faders; caching that loop would be incorrect. The initial entity
copy also protects iteration from structural changes and is therefore left for a separate proof.

## What changed

`campaign-radar-type-set-v1` adds one private static final synthetic set to the exact reviewed
renderer and initializes it from the same seven class literals in the original order. At the local
construction site:

- when the existing exact `preflight.campaign.entityIndex` gameplay gate is fully installed and
  enabled, the renderer loads the static set;
- otherwise it executes the retained original `new HashSet` and seven additions unchanged.

The transform refuses any different class hash, Java class version, method descriptor, archive,
loader, missing static initializer, second rewrite, additional matching construction block, or
change to any class literal/order/instruction shape. A partial installation of the location,
repository-list, or entity-id mutation seams leaves the shared gameplay gate shut.

The adapter report exposes:

```json
"campaignRadarRender": {
  "planId": "campaign-radar-type-set-v1",
  "installed": true,
  "enabled": true,
  "cachedFrames": 1234
}
```

`cachedFrames` is deliberately the only runtime counter. It proves the branch ran without timing or
changing any visibility, position, icon, or fader operation.

## Verification

- Synthetic exact-shape tests prove the cached branch, retained vanilla branch, single static set,
  seven initializer additions, fail-closed hash/type drift, second-rewrite refusal, and dependency
  on all three existing exact gameplay seams.
- The opt-in installed-class test read the shipped archive directly, confirmed the class hash,
  transformed the real renderer, and found exactly one cached read plus seven initializer additions.
- Full `mvn verify` passed: core 195, agent 390 with one expected skip, CLI unit 375, CLI integration
  38 with one expected skip, and synthetic 22 with one expected skip.

No game was launched for this implementation gate. The next run should load a campaign, move through
normal and hyperspace map views, open/close the map, and then inspect `cachedFrames` plus adapter
health. Frame-time attribution requires a longer controlled A/B; disappearance of one allocation
site alone is not a whole-FPS claim.
