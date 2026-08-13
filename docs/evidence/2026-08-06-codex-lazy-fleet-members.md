# Codex fleet members are now created only when consumed

Date: 2026-08-06

Install: Starsector 0.98a-RC8, current 83-mod profile, macOS on Apple M5, bundled x86-64
Zulu 17 under Rosetta, default balanced texture policy, `--fast`

## Finding

The detailed follow-up to the Codex industry memo put 178ms in
`CodexDataV2.populateShipsAndStations()`. Of that, 151ms was only 416 calls to
`SettingsAPI.createFleetMember()`:

| site | calls | time |
| --- | ---: | ---: |
| top-level ship entries | 116 | 62ms |
| station-module entries | 300 | 89ms |

The relationship work surrounding those objects was already cheap: 5,766 `makeRelated` calls
took 7ms, 15,751 entry additions took 2ms, and 9,416 variant relations took 4ms. The object
construction, not the graph, was the bounded seam.

The only retained use of those members is `CodexEntryV2.param2`. The entry already stores the exact
`ShipVariantAPI`, and the shared `getParam2()` accessor is the consumer boundary. Population now
stores that variant instead of constructing a fleet member. The first accessor call constructs the
same vanilla member through `SettingsAPI.createFleetMember(SHIP, exactVariant)`, replaces `param2`,
and returns it. Later reads return the retained member directly.

The public `addModulesForVariant(..., false, ...)` path is untouched. Only the one-shot initial
population call with `firstTime=true` uses the lazy module helper.

## Live gate

The cold diagnostic gate and its transformed-class-cache warm repeat both reported:

- both exact halves installed and the combined gate active;
- **416 deferred**, zero materialized before the main menu;
- the adjacent industry memo still at 169/169 hits;
- 48 exact transformations, zero decline and zero contained failure;
- normal exit and ACTIVE adapter health.

The cold diagnostic reached the ordinary main-menu marker in 17.103s. The warm diagnostic reached
`resource-init-complete` in 17.308s; its quiet-log shutdown did not retain the later GraphicsLib
marker, so it is correctness evidence rather than a menu-time result. A production `fast` benchmark
without the deep call-site instrumentation reached the menu in 16.66s cold and 16.28s warm. A
subsequent fresh warm benchmark reached **15.88s**, the best validated result in the accepted
16-second gate series. It used the direct unattended protocol and the launcher's normal saved
1440x932 windowed settings. That gate remained ACTIVE with 42 exact transformations, 42/42
transformed-class cache hits, zero decline/failure, 416 deferred members, and zero startup
materializations. All 15,469 prepared textures and pixel-conversion bypasses hit. Current
whole-launch run-to-run spread on the reviewed machine is roughly ±0.6s; the removed 151ms object
seam is causal, but no whole-launch delta is claimed from it.

Runs:

- `codex-deep-breakdown-v1-20260806-081525`
- `codex-lazy-fleet-v1-20260806-082846`
- `codex-lazy-fleet-v1-warm-20260806-082950`
- benchmark `20260806-083303`, `fast-1`
- benchmark `20260806-083842`, `fast-1`
- benchmark `20260806-084118`, `fast-1`

## Safety and update behavior

Both halves pin the exact shipped `starfarer.api.jar`, class hashes, class-file version, app loader,
method descriptors, and unique bytecode shapes. Population calls a runtime `active()` gate at both
replacement sites. If the accessor target was changed, shadowed, declined, or simply did not
install, those branches execute the original `createFleetMember()` calls. There is no partially
lazy state for an unmodified consumer to encounter.

The transformed-class cache replays the two installation effects from referenced runtime owners,
so warm launches retain the same gate. Nothing is persisted across launches. An executable
synthetic accessor test proves exact variant identity, one construction on first read, member
retention on the second read, and materialization telemetry. The exact installed Codex pair accepts
the composed rewrites with expanded frames. Full `mvn verify` is green.

Computer Use could list the live LWJGL process as `com.azul.zulu.java` but refused that nonstandard
bundle identifier when attaching, so an automated visual Codex click-through was not claimed. The
next ordinary human gameplay smoke should open one ship or station Codex detail and confirm the
materialized counter increments; startup behavior and executable accessor semantics are already
verified.
