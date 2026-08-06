# Starsector Preflight

**Make heavily modded Starsector start faster and run more smoothly—without permanently patching
the game, its mods, or your saves.**

> **Development preview.** Public binaries aren't available yet. Distribution, the product name,
> and the final disclaimer are pending written authorization from Fractal Softworks. The remaining
> release gates include signed updates, first-class removal and storage controls, consent-based
> diagnostics submission, and real Windows/Linux game testing. See
> [Release readiness](docs/release-readiness.md).

Preflight prepares work that Starsector and its mods would otherwise repeat on every launch, then starts the same game and mod profile using those prepared results.

The intended release experience is simple: install Preflight, choose **Recommended**, prepare the
current mod profile once, then launch Starsector normally through Preflight. The same engine powers
the desktop app and CLI. During development, the equivalent CLI setup is:

```bash
java -jar preflight.jar install --prepare
```

Preparation is content-addressed and safe to repeat. A game or mod update selects new identities;
unchanged artifacts are hits, interrupted work is resumed from completed blobs, and any missing or
invalid prepared result falls back to the original game path at launch. Constrained machines can
bound the preparation work explicitly, for example `--workers 2 --memory-mb 128`.

The install command creates a normal local launcher. For unattended launches and benchmark
automation, Preflight can also use Starsector's saved display and sound settings directly:

```bash
java -jar preflight.jar run --direct --optimization-preset recommended
```

## What has been demonstrated

On the development machine — Starsector 0.98a-RC8, 83 mods, M5 MacBook Air, the game's bundled
x86-64 Java runtime under Rosetta — the current **Recommended** path (`--fast` compatibility alias)
has reached the main menu in
**15.88 seconds**. The two preceding clean production gates were **16.66 seconds cold** and
**16.28 seconds warm**.

The 15.88-second result is the current warm record. It retained 42/42 transformed-class cache hits,
all 15,469 prepared-texture and
pixel-conversion hits, active adapter health, and zero adapter decline or failure. The exact run is
documented in [Codex fleet members are now created only when consumed](docs/evidence/2026-08-06-codex-lazy-fleet-members.md).

The project also retains its earlier controlled comparison: fifteen unattended launches, five per
condition, measured vanilla at **80.09 seconds** and the then-current complete Preflight stack at
**42.36 seconds**. Every round agreed to within 1.9 seconds on the 37-second effect. That historical
campaign and its full identity are in
[The whole stack, measured at once](docs/evidence/2026-08-03-the-whole-stack-measured-at-once.md).
Later work reduced the tracked 62.60-second prepared-texture waypoint through accepted 29-, 25-,
23-, 18-, 17-, and 16-second gates; the current milestone table is in the
[engineering handoff](docs/next-llm-handoff.md).

The next public performance claim will be a new controlled before/after cohort on the release
candidate. Until then, reproduce the existing measurements with:

```bash
scripts/run-startup-benchmark.sh --unattended --conditions vanilla,fast,full --rounds 5
```

Results depend on the game build, mod set, cache warmth, storage, CPU, translation layer, memory
pressure, and temperature. The harness lets beta users measure their own installations.

| Repeated work removed | Count |
| --- | ---: |
| Cache or memo hits in the measured launch | **64,739** |
| Counted operations removed or shortcut | **192,089** |
| Empty texture allocation removed | **1.22 GiB** |

The per-change arithmetic, individual multipliers, and source links are in the
[accumulated scorecard](docs/evidence/2026-08-02-accumulated-startup-scorecard.md).
The chronological, publication-oriented account is
[From 88 seconds to 15.88: what changed in Starsector's loading path](docs/optimization-history.md).
The [experiment ledger](docs/experiment-ledger.md) includes the unsuccessful and deferred branches.
The [storage reference](docs/performance-storage-tradeoffs.md) collects the time-space choices.

## Why it is faster

The investigation traced startup, compared the game log with the visible loading screen, recorded
thread activity, added unattended direct launching, and inserted exact probes around increasingly
narrow pieces of the loader.

That analysis found three large concentrations of repeat work.

The texture path could leave the loading thread waiting roughly **27 seconds** on Starsector's one-thread prefetch queue. Once the queue returned, the same launch still hashed source files, decoded images, converted pixels, copied buffers, calculated colors, and padded uploads. Removing the wait, moving source validation off the hot path, and serving upload-ready pixels produced the accepted [29% startup campaign](docs/evidence/2026-08-01-twenty-nine-percent-when-they-compose.md).

The visible 0% pause was another large block of real work. Exact progress and method probes showed
that vanilla `SpecStore` spent roughly **18–19 seconds** rebuilding variants, weapons, projectiles,
hulls, campaign rules, and related registries. Further probes found that most of the useful time was
inside repeated JSON/CSV merge-and-parse operations. Tagged input trees created a narrow reusable
boundary, and Starsector continues to construct fresh live objects. The complete loader breakdown is
in [The 20-second 0% plateau is vanilla SpecStore](docs/evidence/2026-08-02-zero-percent-is-spec-store.md).

The final startup tail contained repeated work in mod callbacks. AshLib repeatedly resolved the same hull and variant JSON while constructing ship-render information. GraphicsLib repeated much of its automatic texture-map discovery even when its generated files were already valid. The resulting changes reduced the AshLib callback by **7.07–7.44 seconds** and the measured GraphicsLib sequence by **4.82 seconds**. See the [AshLib](docs/evidence/2026-08-02-ashlib-startup-json-cache.md) and [GraphicsLib](docs/evidence/2026-08-02-graphicslib-compact-autogen-replay.md) reports.

## Where the time went

| Change | Measured result | Evidence |
| --- | ---: | --- |
| Prepared textures and prefetch bypass | **25.53s saved; 1.41× overall** | [Accepted campaign](docs/evidence/2026-08-01-twenty-nine-percent-when-they-compose.md) |
| AshLib ship JSON memoization | **3.61–4.17× faster callback** | [Report](docs/evidence/2026-08-02-ashlib-startup-json-cache.md) |
| GraphicsLib compact replay | **1.56× faster callback** | [Report](docs/evidence/2026-08-02-graphicslib-compact-autogen-replay.md) |
| Merged variant JSON | **10.15× faster merge/parse; ~2.7s net** | [PR #275](https://github.com/teamleaderleo/starsector-preflight/pull/275) |
| Merged weapon JSON | **3.34× faster loader; ~2.0s net** | [PR #278](https://github.com/teamleaderleo/starsector-preflight/pull/278) |
| Merged projectile JSON | **2.34× faster loader; ~1.1s net** | [PR #281](https://github.com/teamleaderleo/starsector-preflight/pull/281) |
| Merged ship-hull JSON | **3.52× faster loader; ~1.7s net** | [PR #284](https://github.com/teamleaderleo/starsector-preflight/pull/284) |
| Rules CSV, duplicate checks, tokens, command packages | **~1.56s combined** | [#286](https://github.com/teamleaderleo/starsector-preflight/pull/286), [#288](https://github.com/teamleaderleo/starsector-preflight/pull/288), [#291](https://github.com/teamleaderleo/starsector-preflight/pull/291), [#298](https://github.com/teamleaderleo/starsector-preflight/pull/298) |
| Shared cache-profile identity | **1.613s → 0.452s; 3.57× faster** | [PR #300](https://github.com/teamleaderleo/starsector-preflight/pull/300) |
| **Historical composed campaign** | **80.09s → 42.36s; 1.89× overall** | [2026-08-03 campaign](docs/evidence/2026-08-03-the-whole-stack-measured-at-once.md) |
| **Current clean production gates** | **16.66s cold / 16.28s warm / 15.88s warm record** | [2026-08-06 gate](docs/evidence/2026-08-06-codex-lazy-fleet-members.md) |

The texture path also stopped allocating empty power-of-two padding. In a full load, texture uploads
fell from **3.65 GiB to 2.43 GiB**, removing **1.22 GiB** and serving more textures. See
[The texture padding is gone](docs/evidence/2026-08-02-the-padding-is-gone.md).

## Repeated work removed

The component runs represent **64,739 direct cache or memo hits**. Counting the queue, decode, conversion, scan, and validation stages that no longer execute brings the stacked total to **192,089 operations removed or shortcut**.

| Work avoided or replaced | Count |
| --- | ---: |
| Texture prefetch enqueues skipped | **50,879** |
| Image decodes, pixel conversions, and color calculations bypassed | **64,956** |
| Merged variant, weapon, projectile, hull, and rules values served | **11,690** |
| Repeated rule tokenizations memoized | **30,726** |
| Linear duplicate scans replaced by hash checks | **21,059** |
| Prepared command-package resolutions | **671** |
| Provider real-path resolutions avoided | **12,103** |

A particularly clear algorithmic change is the campaign-rule duplicate check. Vanilla performed a
trigger-local linear scan for each of 21,059 registrations. [PR #286](https://github.com/teamleaderleo/starsector-preflight/pull/286) replaces that repeated scan with an exact hash-set membership check—average **O(1)** lookup—and preserves the game's original insertion order and duplicate behavior.

The other caches use straightforward memoization and precomputation at larger boundaries: exact inputs become a key, the expensive deterministic result is prepared once, and later requests reuse it. A changed game JAR, mod file, or provider order produces a different key.

## How the investigation progressed

The most useful advances often began with a result that didn't make sense.

The first valid prepared-pixel campaign saved only [1.5%](docs/evidence/2026-08-01-the-first-valid-startup-number.md), despite profiles that made texture work look much larger. That discrepancy led to a critical-path probe and the discovery that the cache sat behind a [27-second prefetch wait](docs/evidence/2026-08-01-the-loading-thread-waits-on-a-one-thread-prefetcher.md). Fixing the placement of the cache turned the same body of work into the 29% campaign.

The rule-command package map was expected to remove most of a 641ms phase. It removed about 165ms. The successful class load was expensive; the failed probes were cheap. The same investigation exposed **1.613 seconds of repeated cache-profile construction before the game JVM even started**, which led to [PR #300](https://github.com/teamleaderleo/starsector-preflight/pull/300) and a larger saving than the feature that revealed it.

The [Fast Rendering prior-art review](docs/prior-art-starsector-render.md) corrected an earlier
conclusion about its texture prefetcher, documented where the two projects overlap, and highlighted
the untouched JSON/spec path that became the SpecStore campaign. The pattern throughout the project
has been the same: inspect the logs and code, ask a narrower question, build the probe that answers
it, and let the measurement choose the next change.

## Design foundations

The design uses familiar application and compiler techniques.

[React Query / TanStack Query](https://tanstack.com/query/latest/docs/framework/react/guides/query-keys) provides a useful mental model for stable cache keys, reusable answers, and invalidation when inputs change; its [prefetching model](https://tanstack.com/query/latest/docs/framework/react/guides/prefetching) is also close to Preflight's goal of moving known work ahead of demand. [SWC](https://swc.rs/) demonstrates the value of doing expensive transformation work ahead of use and handing the runtime a ready artifact.

Preflight applies those ideas alongside classic computer-science tools: memoization, hash maps,
precomputation, content-addressed artifacts, bounded concurrency, and replacing repeated linear work
with direct lookup. The difficult part is locating a reusable boundary inside an opaque, obfuscated,
mod-heavy Java application and keeping Starsector's original behavior available for every cache
miss or declined adapter.

## How Preflight works

Preflight prepares deterministic work outside the timed game launch. At startup it identifies the exact game build, enabled mod order, and resource providers that can affect each prepared result.

A matching artifact skips only the work that was already completed. Starsector still constructs its live objects, registers scripts, applies mod ordering, mutates its registries, creates textures, performs OpenGL uploads, and runs the remaining mod logic.

Changed game or mod files select different prepared data. A missing entry, unsupported class, corrupt
artifact, or runtime validation failure uses the original loader. Runtime acceleration transforms
exact reviewed classes only in the child JVM's memory; those changes disappear when the game exits.

Preflight writes its own caches and bounded reports. Two explicit features can update game-owned
preferences: a confirmed named-profile switch changes only `enabled_mods.json`, and the launch
settings screen changes only Starsector's existing display, sound, UI-scale, antialiasing, and
battle-size values. Both paths preview or constrain the change and save a backup. Preflight never
rewrites game or mod JARs, executables, assets, activation data, or saves. The complete boundary is
in the [product contract](docs/product-contract.md).

## Install and run

Build or download the self-contained `preflight.jar`, then install the local launcher:

```bash
java -jar preflight.jar install
```

Run through the ordinary launcher path:

```bash
java -jar preflight.jar run --optimization-preset recommended
```

Run unattended through Starsector's saved launcher settings:

```bash
java -jar preflight.jar run --direct --optimization-preset recommended
```

Inspect the detected installation and mod profile without launching:

```bash
java -jar preflight.jar doctor
```

Inspect or update the same resolution, fullscreen, sound, antialiasing, UI-scale, and battle-size
preferences used by Starsector's own UI:

```bash
java -jar preflight.jar launch-settings --game /path/to/Starsector --json
java -jar preflight.jar launch-settings set --game /path/to/Starsector \
  --resolution 1920x1080 --fullscreen false --sound true \
  --antialiasing 0 --ui-scale 1.0 --battle-size 400 --json
```

Prepare every reusable cache for the current profile:

```bash
java -jar preflight.jar prepare
```

Preparation overlaps its three independent opening scans by default. Use `--serial-stages` as a
diagnostic fallback; game launch and all in-game work remain unaffected.

Prepared textures have two exact, lossless storage policies. `balanced` is the default and
compresses them with LZ4 to use substantially less disk, except where compression saves under 23.1%
and raw storage is faster for nearly no space cost. It retains the same runtime pixels and fail-open
behavior; `fastest` keeps every upload-ready pixel array raw for minimum decode CPU:

```bash
java -jar preflight.jar prepare --texture-storage fastest
java -jar preflight.jar prepare --texture-storage balanced
```

On the 83-mod development profile, `balanced` reduced the texture pack from 5.34 GB to 2.26 GB.
Ten shuffled fresh-game-JVM replays measured the exact startup access order at 1,137ms balanced
versus 691ms fastest; fastest buys about 446ms for 3.08GB. A real learned-order fastest gate reached
the menu in 18.71s with zero cache or transform failure. Existing manifests remain active until
preparation runs again. After changing policies, `java -jar preflight.jar cache prune --json`
previews the superseded blobs and profile packs that can be reclaimed; add `--yes` only after
reviewing that plan. Add `--keep-named` to preserve the caches for every readable named profile.

Preparation also creates an indexed texture pack for each profile. The game opens that pack once and
falls back to the loose blobs on any problem. After a successful launch, the next preparation can
use its checksummed access-order hint to tune physical layout automatically; missing or corrupt
hints are ignored. The packed copy currently retains loose blobs for repair and fail-open safety,
so profile pruning is the supported way to reclaim obsolete versions.

Save and switch ordered mod sets without losing their exact caches, and manage diagnostic evidence
independently from acceleration data:

```bash
java -jar preflight.jar profile save "Heavy campaign"
java -jar preflight.jar profile list --json
java -jar preflight.jar profile activate "Heavy campaign"       # preview
java -jar preflight.jar profile activate "Heavy campaign" --yes

java -jar preflight.jar cache --json
java -jar preflight.jar evidence --json
java -jar preflight.jar evidence export --output preflight-diagnostics.zip
java -jar preflight.jar evidence prune --keep-runs 20 --keep-benchmarks 10
```

Both prune commands are preview-only unless `--yes` is present. Evidence retention never touches
acceleration caches and refuses sessions that change between planning and deletion. Diagnostics
export includes only bounded, allowlisted text metadata from the newest three runs and two
benchmarks by default. Its in-ZIP disclosure and manifest name everything included or skipped;
logs, crash dumps, JFR, screenshots, caches, game/mod assets, saves, symlinks, and unknown files are
never copied. See [Diagnostics export](docs/diagnostics.md).

Development smoke automation uses a platform-neutral semantic scenario. The first campaign-load/roam
scenario and evidence contract are documented in
[desktop smoke automation](docs/desktop-smoke-automation.md).

The native desktop host and its build instructions live in
[preflight-desktop](preflight-desktop/README.md). Launch settings, Prepare, Profiles, Storage, and tracked game launch
all use the same narrow engine contract as the CLI. The native packaging matrix is green for a
macOS arm64 DMG, Windows x64 NSIS installer, and Linux x64 Debian and AppImage packages. These are
currently private, unsigned development artifacts: public distribution authorization, Apple
signing/notarization, Windows signing, and a PID-safe automated game smoke remain release work. See
[Downloads and installation](docs/downloads.md) and the
[exact distribution matrix](docs/evidence/2026-08-06-desktop-distribution-matrix.md).

On macOS, `install` creates `~/Applications/Starsector Preflight.app`. Linux receives a command and desktop entry. Windows receives a local command launcher.

Remove the launcher integration with a preview before confirmation; add `--purge` to include
Preflight's caches and retained evidence. Neither form removes Starsector, mods, or saves:

```bash
java -jar preflight.jar uninstall
java -jar preflight.jar uninstall --purge
```

## Before public release

Preflight is fast enough for a beta; the remaining work is product trust and compatibility:

1. Obtain written authorization for distribution, integration approach, product name, and
   disclaimer from Fractal Softworks.
2. Finish the single-action desktop flow, visible disk use, preview-first cleanup/removal, signed
   updates, and an explicit **Send run report** consent flow.
3. Exercise clean licensed-game installs on Windows and Linux. CI verifies the portable engine and
   native packages. Licensed-game execution requires user installations.
4. Run a fresh controlled before/after cohort against the release candidate, then publish only the
   result that cohort supports.

The complete blocker list and publication checklist are in
[Release readiness](docs/release-readiness.md). The public trust work is also tracked in
[issue #294](https://github.com/teamleaderleo/starsector-preflight/issues/294).

## Analysis and mod tools

The same codebase contains the tools used to find the startup work: JFR recording, exact
startup-phase probes, loader-level attribution, profile comparison, crash detection, and unattended
benchmark campaigns. Profiling is optional; normal accelerated launches don't need to pay for a
recording.

Preflight also includes a read-only mod linter:

```bash
java -jar preflight.jar lint
java -jar preflight.jar lint --path ./MyMod
```

Across real mod profiles it has found progressive JPEGs that decode **8.75× slower** through the game's ImageIO path, gigabytes of avoidable texture and audio allocation, duplicate and shadowed resources, unused files, extension mismatches, and configuration left outside the top-level value where the game never reads it.

See [Asset Lint](docs/asset-lint.md) and the [evidence archive](docs/evidence/) for the individual investigations.

## Build

Preflight requires JDK 17 and Maven 3.9 or newer:

```bash
mvn verify
```

The runnable launcher and Java agent are produced together at:

```text
preflight-cli/target/preflight.jar
```

## Documentation

- [Documentation map](docs/README.md)
- [Release readiness](docs/release-readiness.md)
- [Optimization history](docs/optimization-history.md)
- [Experiment ledger](docs/experiment-ledger.md)
- [Performance and storage tradeoffs](docs/performance-storage-tradeoffs.md)
- [Accumulated startup scorecard](docs/evidence/2026-08-02-accumulated-startup-scorecard.md)
- [Product, compatibility, cache-control, and support-upload contract](docs/product-contract.md)
- [Automatic launch and discovery](docs/automatic-launch.md)
- [Vanilla runtime adapter](docs/vanilla-adapter.md)
- [Repeated startup benchmark](docs/startup-benchmark.md)
- [Asset lint](docs/asset-lint.md)
- [Prior-art review](docs/prior-art-starsector-render.md)
- [Roadmap](docs/roadmap.md)
- [Evidence archive](docs/evidence/)

## Status

Preflight is under active development. Public distribution awaits the release gates above. Runtime
acceleration is pinned to reviewed game classes and exact profile inputs, with the original game
path retained for unsupported or changed inputs. Future launcher and game updates may require a
Preflight update.

## License

[MIT](LICENSE). Starsector, Fast Rendering, and mod content remain the property of their respective
owners. The repository and release packages contain none of those assets. This is an independent
project. Any endorsement requires an explicit statement from Fractal Softworks.
