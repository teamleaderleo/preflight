# Codex reuses one synthetic industry instead of applying it twice

Date: 2026-08-06

Install: Starsector 0.98a-RC8, current 83-mod profile, macOS on Apple M5, bundled x86-64
Zulu 17 under Rosetta, default balanced texture policy, `--fast`

## Finding

An exact opt-in breakdown of `CodexDataV2.init()` put 687ms in
`linkRelatedEntries()`. Its largest measured child was 338 adjacent
`SettingsAPI.getIndustryDemand()` / `getIndustrySupply()` calls taking 403ms.

The shipped implementations independently construct a fake planet, market, and faction, instantiate
the same industry plugin, and call `Industry.apply()`. Codex asks for demand and then supply for each
industry. The demand result discards the applied industry immediately; the supply call repeats the
whole construction and application before collecting the other half of the same state.

The exact adapter now retains the already-applied `Industry` object after a Codex demand call and
consumes it once in the immediately adjacent supply call. The supply collection and vanilla special
resource-deposit handling remain unchanged. Calls outside the exact Codex link pass remain vanilla.

## Exact measured result

| gate | demand/supply child | `linkRelatedEntries` | memo |
| --- | ---: | ---: | --- |
| observation-only baseline | 403ms | 687ms | disabled |
| first memo gate | **190ms** | **459ms** | 169/169 hits |
| final IndEvo correctness gate | 195ms | 464ms | 169/169 hits |
| final hardened warm gate | 198ms | 498ms | 169/169 hits |

The first adjacent gate removed **213ms (52.9%)** from the exact demand/supply child and **228ms
(33.2%)** from its containing link pass. Whole-launch samples remain noisier than this seam: the
final hardened cold/warm pair reached the main menu in **17.10s / 16.38s**. An earlier unchanged-
binary pair reached 17.37s / 16.30s. These support no regression; the instrumented child delta is
the causal result.

Every live gate recorded 169 demand candidates, 169 supply lookups, 169 hits, zero misses, and an
inactive empty scope at shutdown. The hardened rewrite installs a bytecode-level `finally`, so a
callback exception also clears the thread-local candidate before propagating normally.

Runs:

- `codex-breakdown-v2-20260806-075237`
- `codex-demand-supply-memo-v1-20260806-075852`
- `codex-indevo-null-world-v2-20260806-080609`
- `codex-indevo-null-world-v2-warm-20260806-080655`
- `codex-memo-finally-final-20260806-080944`
- `codex-memo-finally-final-warm-20260806-081030`

## The optimization exposed real IndEvo defects

Codex's market is deliberately synthetic and has no containing location or star system. IndEvo
4.1b treated it like a world market while `Industry.apply()` was being queried:

- `MilitaryRelay` attempted to create/remove a relay in a null location;
- `ArtilleryStation.apply()` attempted `StarSystemAPI.addTag()`;
- `ArtilleryStation.unapply()` attempted `StarSystemAPI.removeTag()`;
- `WorldWonder.apply()` attempted to install global listeners and mutate a null star system.

Before reuse, applying every industry twice emitted 28 caught stack traces. Reusing the demand
instance reduced that to 14 without hiding anything. The exact IndEvo guards then reduced 14 to
three, exposing the separate `unapply/removeTag` path; the final guard reduced the Codex/IndEvo
traces to zero.

Final telemetry recorded exactly 18 contained null-world side effects:

| site | bypasses |
| --- | ---: |
| artillery add tag | 3 |
| artillery remove tag | 3 |
| relay create | 1 |
| relay remove | 2 |
| world-wonder world effects | 9 |

Real markets still execute the original IndEvo branches. Artillery cleanup after the skipped tag
removal also continues; only the call whose receiver is null is bypassed. The game log's unrelated
mod asset/font diagnostics and the OpenAL thread interrupted by the probe's automatic shutdown are
not attributed to this adapter.

## Safety and update behavior

The memo binds both shipped core archive hashes, exact class hashes, Java class-file version, app
loader, required method descriptors, unique application sites, locals, and control-flow landmarks.
The IndEvo repair independently binds the exact 4.1b archive, three class hashes, URL class loader,
method descriptors, and unique world-mutation calls.

Any Starsector or IndEvo update, loader change, archive drift, method-shape ambiguity, or failed
composition declines the affected transform and preserves the original code. A missing memo entry
executes the complete vanilla supply path. An installation without IndEvo simply never loads those
three optional targets. Nothing is persisted across launches.

Verification included synthetic scope/one-shot/drift tests, transformations of the exact installed
`starfarer_obf.jar`, `starfarer.api.jar`, and `IndEvo.jar` classes, executable telemetry assertions,
two final live menu gates, and full `mvn verify`.

## Next measured seam

The same probe now places `CodexDataV2.populateShipsAndStations()` at roughly 139--231ms. The
remainder of `linkRelatedEntries()` outside industry demand/supply is roughly 0.25--0.30s. Those are
the next bounded Codex targets; neither should be changed without a more detailed exact breakdown.
