# Prepared-pixel acceptance: archived operator handoff

**Status:** historical evidence only — no operator action is authorized by this document.

This file records the July 2026 prepared-pixel acceptance sequence and the failed/repaired comparison harness that followed it. The product contract has moved on: the reviewed prepared-pixel path passed live lifecycle and controlled startup gates, and current product presets own the supported activation policy. Do **not** use the old one-shot comparison/restore procedure as a current runbook.

For the current runtime and safety contract, use:

- [Prepared texture blobs](prepared-textures.md)
- [Automatic launch and discovery](automatic-launch.md)
- [Vanilla runtime adapter](vanilla-adapter.md)
- [Prepared-pixel gameplay smoke](evidence/2026-07-23-prepared-pixel-gameplay-smoke-pass.md)
- [Controlled composed startup campaign](evidence/2026-08-01-twenty-nine-percent-when-they-compose.md)

## Accepted lifecycle result

The corrected prepared-pixel path passed the exact reviewed macOS/Starsector 0.98a-RC8 lifecycle:

```text
launcher → main menu → campaign → combat → save → clean exit
```

Retained evidence:

```text
archiveSha256: cbc9f5884d89f69e93f6b0ca882c911fdb0cb43397932b77b191920ded0a11bf
runtimeSeconds: 624.640
prepared hits: 5015
coherent-direct NPOT hits: 4450
fallbacks/internal errors: 0
active direct bytes at shutdown: 0
active/pending buffers at shutdown: 0
operatorAccepted: true
automatedAccepted: true
```

The operator reported normal launcher, main-menu, campaign, and combat visuals; save and clean exit completed; direct-buffer ownership returned to zero.

## Historical comparison work

The July comparison work created several useful safeguards:

```text
PR #152 — comparison runner and contract
PR #154 — automatic Starsector-log readiness detection
PR #156 — profile stability and launcher-settling repair
PR #158 — installed core mission resource discovery
PR #160 — bounded GraphicsLib mutable-cache control
```

Those changes were needed because early comparison attempts were invalidated by mutable GraphicsLib generated-normal state and, separately, a wrong hardcoded macOS mission-resource path. They are retained as engineering history, not as current product setup instructions.

### Invalid first comparison

The first automatic pair is invalid:

```text
archiveSha256: 2530de69d2251319422b3224a0d8430e5537f77a667fd69a9a726996785fdd08
order: prepared,compatibility
```

Prepared reached the main menu. The compatibility half later stopped while resolving `data/missions/afistfulofcredits/descriptor.json`, and the profile had already changed between halves because GraphicsLib generated normal-map cache files. Its timing is not evidence.

### Invalid mutable-cache attempt

The replacement attempt is also invalid:

```text
archiveSha256: 6c3c4f2d1220ce5e11f73649b5c9e1f11b30f3bf115c48fffdccc10733ed4729
before fingerprint: 3c1fc13ee4b47a93d36122ee2804070dbacf43523a3d38df5cc531e35e4513fe
after fingerprint: 5bf805bc6c8898c0f3c9eefb8808783cc405938286e00d44a349953046d9b1a1
```

The old drift classifier reported two added images, but later inspection found 27 GraphicsLib cache files written or replaced during the prepared half. Most were identical 68-byte, 1×1 PNGs after normal-map buffer failures. Because GraphicsLib consumes this state on later starts, treating the cache as harmless drift would have contaminated the second condition.

### Guard-path stop

A later attempt stopped before launch because the comparison runner initially assumed:

```text
Contents/Resources/starfarer.res/res/data/missions
```

The reviewed installation stores the mission data under:

```text
Contents/Resources/Java/data/missions
```

PR #158 replaced the hardcoded location with bounded discovery and exact mission-file checks. That stop contains no game, mod, texture, or timing evidence.

## What replaced the one-shot timing idea

The single-pair harness was explicitly preliminary (`samplesPerMode=1`, `benchmarkAccepted=false`). The later startup campaign used repeated, interleaved conditions with settling intervals and stable-input checks. On that development profile it measured:

```text
vanilla median:   88.13s
fast median:      72.25s
prepared median:  62.60s
```

The prepared condition beat the non-pixel fast condition in all five paired rounds, isolating roughly 9.65 seconds for the prepared-pixel contribution in that campaign. These are retained development-profile measurements; they are not a release-wide or cross-hardware claim.

## Current activation policy

Current product presets own activation:

- **Recommended / `--fast`** selects prepared pixels and requests true-size uploads. The exact fold bypass and live OpenGL capability probe must both pass before NPOT allocation becomes unpadded; otherwise the original padded behavior remains reachable.
- **Conservative** keeps the prepared conversion path while retaining power-of-two padding.
- Changed target identity, stale or incompatible prepared data, missing runtime capability, memory pressure, or contained bridge failure declines to preserved Starsector behavior.

Compatibility mode and adapter/plan kill switches remain available for isolation and rollback.

## No current operator action

Do not follow historical instructions from old issue comments that say to:

```text
RESTORE GRAPHICSLIB CACHE
COMPARE
```

Do not manually reset GraphicsLib state merely to reproduce the July experiment. If current diagnostics suggest a GraphicsLib interaction, create a new comparison contract against the current Recommended/Conservative paths and current installation identity rather than reviving the old one-shot harness.
