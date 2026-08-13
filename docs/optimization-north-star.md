# Optimization North Star

## Purpose

This document converts the July 2026 real-install evidence into the near-term execution plan for Preflight.

Discovery is complete for the three dominant repeat-launch CPU domains on the reviewed Starsector 0.98a-RC8 installation:

- texture and image preparation;
- Janino compilation and generated class definition;
- Jogg/JOrbis audio decoding and Starsector sound-wrapper construction.

The next phase is implementation, equivalence proof, and controlled OFF-versus-ENABLED measurement. Another broad discovery probe is not required before the first live texture pilot.

## Evidence threshold reached

Two unified read-only runs used the same profile fingerprint:

```text
63166a7e7360eb4c926d974bb9232f55a1024ad1c5b41ec8c60171157362c6f0
```

Both runs retained the same complete report set:

- exact `TextureLoader` contract captured;
- exact Janino complete-map contract captured;
- 32 audio-decoder identities retained;
- 7 Starsector sound-wrapper identities retained;
- 6 Janino/commons-compiler loader identities retained;
- no report truncation;
- no source archive hash failures;
- zero class transformations;
- zero contained or malformed-class failures.

### Repeatable CPU attribution

| Domain | Campaign-loading run | Campaign-and-combat run |
| --- | ---: | ---: |
| Audio decoding | 1,964 / 6,979 samples, 28.14% | 2,169 / 9,207 samples, 23.56% |
| Janino | 1,872 / 6,979 samples, 26.82% | 2,330 / 9,207 samples, 25.31% |
| Texture and image work | 1,443 / 6,979 samples, 20.68% | 1,722 / 9,207 samples, 18.70% |
| **Combined** | **75.64%** | **67.57%** |

Sampling identifies priorities; it does not convert directly into removable wall time. The consistency across a shorter campaign-loading trace and a longer combat trace is enough to justify an implementation program around these three domains.

### Profile census

The reviewed enabled profile contained:

- 77 enabled IDs, 75 resolved mod roots, and 2 unresolved IDs;
- 47,323 files / 1,799,634,328 bytes;
- 24,382 images / 1,117,167,000 bytes;
- 1,396 sounds / 485,036,607 bytes;
- 3,838 loose Java files / 24,987,975 bytes;
- 78 JARs / 47,989,974 bytes;
- 271 duplicate logical resource paths and 1,625 duplicate provider entries.

### Janino evidence

| Measurement | Campaign-loading run | Campaign-and-combat run |
| --- | ---: | ---: |
| Janino-defined unique classes | 588 | 594 |
| Janino compilation events | 515 | 516 |
| Aggregate Janino compilation duration | 5,462.42 ms | 5,867.12 ms |

Aggregate compilation duration can overlap other activity. It remains strong evidence for exact generated-bytecode reuse.

### Crash interpretation

Both runs collected complete evidence before ending in separate mod-side failures:

- one campaign script-list failure near Second-in-Command activity;
- one AI Tweaks and Advanced Gunnery Control target-selection failure in combat.

The probe registered no live transformation and applied zero transformations. These recordings are valid for prioritization and contract design. They are not suitable as final performance benchmarks. Real acceleration claims require a stable updated-mod profile, successful normal exit, and repeated scenario-controlled runs.

## North Star

> An unchanged Starsector profile should reuse deterministic work from prior launches, while every mismatch, stale artifact, corruption, unsupported case, ambiguity, or internal error transparently executes the original game path.

Preflight is a repeat-launch accelerator. It must remain an additional process-local wrapper and javaagent, not a replacement game loader and not a mutator of the installation.

## Intended runtime workflow

```text
scan and fingerprint the enabled profile
                 |
                 v
validate or prepare content-addressed artifacts
                 |
                 v
launch the existing Starsector launcher with exact identity gates
                 |
        +--------+---------+
        |                  |
        v                  v
validated hit       miss, mismatch, or error
        |                  |
        v                  v
reuse prepared      execute untouched original path
result                    |
                         v
                  capture repaired artifact
                         |
                         v
                 report hits and fallbacks
```

## Ordered execution program

### 0. Build the benchmark harness first

Automate one repeatable scenario with separate milestones:

1. process start to usable main menu;
2. main menu to representative campaign responsiveness;
3. campaign to first representative combat responsiveness.

Required modes:

- adapter OFF, cold-ish filesystem state;
- adapter OFF, warm filesystem state;
- adapter ENABLED, artifact-building miss;
- adapter ENABLED, validated warm hit;
- adapter ENABLED, deliberately corrupted artifact;
- adapter ENABLED after a source, mod, or order change.

Use at least five successful runs per mode on the same machine, JVM, profile, save action, and scenario. Report median, minimum, maximum, and all individual results. Add p90 when the sample count supports it.

Track:

- wall time for each milestone;
- domain execution samples;
- hits, misses, fallbacks, and invalidation reasons;
- expensive original calls avoided;
- bytes prepared and served;
- cache size;
- peak heap and direct-buffer use;
- circuit-breaker activation;
- successful normal-exit rate.

### 1. Ship texture reuse first

Texture work is the closest domain to a safe live experiment because the repository already has prepared upload-ready blobs and the exact real-install contract is captured.

Pinned reviewed identity:

```text
class:       com/fs/graphics/TextureLoader
class SHA:   d8fcb4cb90d457fc3075e711b6293940774dcf990ea66a7584c231bd96898b50
archive SHA: 10d89e113f6d1627cc7bc90b692e8a7f450fdd820c5a4ac5edaecd6710afe708
source:      fs.common_obf.jar
loader:      jdk/internal/loader/ClassLoaders$AppClassLoader, name=app
```

Important captured methods include:

```text
o00000(BufferedImage, com.fs.graphics.Object) -> ByteBuffer
o00000(BufferedImage, int, int, int, int) -> com.fs.graphics.Object
o00000(com.fs.graphics.Object, String, int, int, int, int, boolean) -> com.fs.graphics.Object
```

#### Stage 1: compatibility pilot

Intercept the exact decoded-image seam and return a verified cache-backed `BufferedImage` while preserving the rest of the original texture path. This proves exact targeting, lookup, invalidation, fallback, corruption repair, and visual equivalence with limited behavioral change.

#### Stage 2: prepared-pixel path

Use the captured lower conversion seam to supply the existing upload-ready payload and derived colors. A valid hit should bypass:

- PNG/ImageIO decompression;
- raster access and repeated `getPixel()` calls;
- row reversal and RGB/RGBA conversion;
- repeated derived-color calculation.

Keep Starsector's original texture object, OpenGL upload, cleanup, and lifetime behavior.

Prepare the observed working set first rather than all 24,382 images. Prioritize main-menu, campaign-start, common UI, representative combat, and requested GraphicsLib-generated maps. Deduplicate by encoded-content hash while preserving logical path and provider-order semantics in manifests.

### 2. Add prepared audio reuse second

Audio remained a first-tier CPU cost in both runs. The heavy path includes Jogg packet handling, JOrbis codebooks/mapping/MDCT, decoded-buffer growth, and the `sound/J` wrapper.

Pinned decoder archives:

```text
jogg-0.0.7.jar   ed7946260897d97c468a4749b3d9d5e436a268fa948bc32e75a7487130e89379
jorbis-0.0.15.jar d049b2a1c6ddefde3a5cbff320c96fdd5aefa09b0d3bbea3fe44839f7e6713f9
```

Pinned primary wrapper seam:

```text
sound/J.o00000(InputStream) -> sound/F
```

The next gate is installed-decoder equivalence. Decode deterministic fixtures through the exact installed identities and compare:

**Through the call sequence `sound/void` actually uses.** Its disassembly shows only the low-level
`SyncState`/`StreamState`/`Page`/`Packet` and `Info`/`DspState`/`Block`/`Comment` API; no JAR in the
installation references `com.jcraft.jorbis.VorbisFile`, which is what the existing harness decodes
through. The two paths do not agree on the same bytes, so the choice of API decides the answer before
any comparison happens. Details in
[the evidence](evidence/2026-07-26-the-audio-gate-decodes-an-api-the-game-never-calls.md).

- PCM bytes;
- byte order and encoding;
- channel count and sample rate;
- frame count and all `sound/F` metadata;
- stream consumption and close behavior;
- malformed and unsupported-input behavior.

Begin live reuse with short fully decoded effects. Keep streaming music on the original path until its policy and lifecycle are proven equivalent. Key artifacts by encoded source hash, decoder and wrapper identities, decode policy, format behavior, and Starsector build identity.

### 3. Bind the Janino bytecode cache third

Pinned reviewed identity:

```text
class:       org/codehaus/janino/JavaSourceClassLoader
class SHA:   6b0eea7994ab4c314f1bc7cdefaa99b66897d500c2cad6fd2d97cd08b134c4b8
archive SHA: 60f05562c22b6de06641a1f76148692ef336ad1f6712fe6a76f9e2611f766344
source:      janino.jar
loader:      jdk/internal/loader/ClassLoaders$AppClassLoader, name=app
```

Captured complete-map methods:

```text
generateBytecodes(String) -> Map
defineBytecode(String, byte[]) -> Class
findClass(String) -> Class
```

The remaining problem is dependency completeness. Capture every source and resource lookup performed while generating one complete map, including:

- requested and transitive loose sources;
- ordered resource and classpath providers;
- parent loader and protection domain;
- compiler settings and character encoding;
- duplicate and cross-mod class-name behavior;
- all inner and anonymous generated classes.

A dependency context that cannot be proven complete is a miss.

On a valid hit, verify the entire class map atomically and define every class through Janino's original `defineBytecode()` path. Never serve a partial generated set. On any miss, ambiguity, checksum failure, linkage problem, duplicate definition, or internal error, call original `generateBytecodes()`, then capture the complete successful result for the next launch.

### 4. Use one shared artifact runtime

Texture, audio, and Janino adapters should share:

- content-addressed blobs;
- atomic manifests;
- exact profile and target identity keys;
- payload checksums;
- corruption quarantine;
- bounded entry and byte counts;
- cleanup policy;
- per-session error budgets;
- hit, miss, fallback, and invalidation telemetry;
- manual inspect, validate, and purge commands.

Domain-specific payloads remain separate:

```text
texture: upload-ready pixels, dimensions, colors, and conversion metadata
audio:   PCM/sample payload plus the complete sound/F contract
Janino:  complete generated class map plus dependency closure
```

### 5. Connect the provider index and add profile hygiene diagnostics

After the big three consumers prove real gains, connect the persistent provider index to measured lookup seams without changing mod override order.

Report, but do not silently modify:

- missing enabled mod IDs;
- duplicate logical paths and winning providers;
- case collisions;
- embedded source-control directories;
- backup and archive files;
- stale settings-schema entries;
- startup version-check network activity;
- unusually large unused assets.

### 6. Add bounded preparation and prioritization

Only after live hits are correct:

- prepare main-menu and campaign-start content before combat-only content;
- validate artifacts in bounded worker pools;
- keep game-object and OpenGL operations on their required original threads;
- cap workers, heap, direct buffers, and disk writes;
- stop background preparation when it delays the active launch.

Concurrency follows correctness and measurement.

## Non-negotiable release gates

A domain moves from experiment to enabled only when all of the following pass:

1. exact class, archive, source, method, and loader identity;
2. deterministic semantic equivalence;
3. cold miss executes the original path;
4. warm hit avoids the intended expensive work;
5. corrupt artifact triggers one original fallback and repair;
6. source, mod, and order changes invalidate correctly;
7. unknown builds remain untouched;
8. memory and storage stay bounded;
9. packaged child-JVM tests pass;
10. real five-run OFF-versus-ENABLED comparison passes;
11. startup, campaign, combat, save, and normal exit remain correct;
12. the immediate kill switch remains effective.

## Near-term pull-request sequence

1. Add the benchmark scenario runner and metrics schema.
2. Implement the exact-gated texture compatibility pilot.
3. Implement the upload-ready prepared-pixel texture path.
4. Complete the installed-JOrbis equivalence harness.
5. Implement short-effect prepared-audio reuse.
6. Add Janino source/resource dependency tracing.
7. Bind the production SPJB wrapper to the exact complete-map target.
8. Connect provider-index lookups and add hygiene diagnostics.
9. Add bounded preparation scheduling.
10. Run the stable-profile A/B campaign and publish results.

## The launch cost that precedes all of this

The launcher sets `-Xms6144m -Xmx6144m -XX:+AlwaysPreTouch`, so the JVM writes every heap page before
`main()` runs. Measured on the reviewed installation that is **~950 ms**, scaling at roughly
**150 ms per GB** of heap — spent before any resource preparation starts, and not something preflight
can cache away. It belongs in any baseline this project publishes, because a repeat-launch
improvement measured without accounting for it is measuring a smaller denominator than the operator
experiences.

The same pass established that the heap setting has **nothing to do** with the 6.91 GB texture
working set, which is driver-side memory outside the heap; and that Java 17 is held in place by the
launcher's architecture-specific and vendor-specific flags rather than by anything about the version.
Full method and numbers:
[2026-07-26-jvm-configuration-of-the-installed-game.md](evidence/2026-07-26-jvm-configuration-of-the-installed-game.md).

## Performance target

Initial engineering gate:

- at least 25% lower median repeat-launch time to campaign readiness;
- zero correctness regressions;
- a low and explained fallback rate.

Stretch target after all three domains are warm: 40% or better median improvement.

These are targets, not forecasts. Final claims depend on measured serialization, thread overlap, OpenGL work, mod initialization, cache state, and successful normal exits.

## Issue map

- #48 — umbrella evidence-driven startup roadmap
- #54 — exact-key Janino generated bytecode
- #59 — prepared decoded audio
- #75 — installed-JOrbis and Starsector sound-wrapper equivalence
- #77 — complete Janino dependency binding
- #78 — exact TextureLoader contract, now captured by merged PR #84
