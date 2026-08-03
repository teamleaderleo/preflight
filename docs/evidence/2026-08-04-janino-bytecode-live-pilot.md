# Janino complete-map cache live pilot

**Date:** 2026-08-04
**Install:** Starsector 0.98a-RC8, 89 enabled mods, bundled Janino 2.7.8
**Commit:** `176f609`
**Protocol:** direct `--fast` launch with `--janino-bytecode-cache`,
`--graphicslib-compact-replay`, `--startup-phase-probe`, no JFR recording, automatic SIGTERM after
the ordinary main-menu marker

## Result

The cold and cooled-warm pair both reached the main menu, shut down through the JVM hooks, reported
adapter health `ACTIVE`, and had no contained adapter failure or fatal lifecycle evidence.

| exact Janino telemetry | cold learning | warm hit | change |
| --- | ---: | ---: | ---: |
| calls | 228 | 228 | — |
| misses / stores | 228 / 228 | 0 / 0 | all removed |
| hits | 0 | 228 | all served |
| corruptions / errors / policy declines | 0 / 0 / 0 | 0 / 0 / 0 | clean |
| time inside the woven seam | 18.014s | 2.364s | **-15.650s (-86.9%)** |
| game-log start to main menu | 34.83s | **29.46s** | **-5.37s** |

The direct time is aggregate work inside 228 calls. Some calls overlap other loading activity, so it
is not a claim that all 15.65 seconds lay on the main-menu critical path. The whole-launch pair says
5.37 seconds moved on this run; one pair remains subject to the installation's roughly ±1.4 second
launch noise.

The startup phase probe independently observed the ScriptStore call site fall from 1.281s to 64ms
and the SpecStore call site fall from 7.418s to 6.417s. Those phase deltas are corroboration, not a
sum: they include their whole call sites and share background work.

The cold run wrote 228 checksummed complete-map bundles totaling 152,606,335 bytes. The warm run
used those same 228 exact context/request keys. The context binds the game JAR, Janino archive,
ordered mod classpath, loose Java/class source graph, bundled JVM modules, launcher/JVM policy,
debug flags, parent-loader policy, and protection-domain policy. Any difference selects another
content-addressed context or leaves compilation vanilla.

## GraphicsLib compatibility in the same launches

The first live attempt exposed a compatibility defect in the archive-identity gate rather than the
replacement: Starsector's mod URLClassLoader supplied a local `file:` URL containing the unescaped
space in `zz GraphicsLib-1.12.1`. `URL.toURI()` rejected it, so the adapter correctly retained the
original class. Commit `fbc186b` rebuilds only malformed local file URLs from escaped URI
components, then hashes the resolved archive exactly as before.

Both retained pilot runs matched Graphics.jar SHA-256
`832064013fe853731941e547842884ba121fb8b20eff08d24137f7a2c916903a`, matched the exact
`org.dark.shaders.util.TextureData` class and URLClassLoader, and applied the compact replay once.
Adapter health reported no mismatch or fallback. This pair proves live applicability and startup
compatibility; it does not re-price GraphicsLib because the replacement was enabled in both halves.

## Excluded rapid-relaunch failures

Two attempted warm runs were excluded after the known Starsector fast-relaunch resource-resolution
fatal selected two different `mission_text.txt` files. Both files existed and were unchanged on
disk, the printed search roots were complete, Janino had only clean cache hits, and GraphicsLib had
not loaded yet. Pull request #170 records the same failure reproduced with the ordinary launcher and
no Preflight. After a 90-second cooldown, the identical warm command completed normally.

The probe harness stopped every process cleanly. Automated launch campaigns must retain their
existing inter-launch settling interval; a fast-relaunch resource fatal is not cache-corruption
evidence.

## Retained evidence

- cold: `~/.starsector-preflight/runs/janino-graphics-timed-cold-20260804-025551`
- cooled warm: `~/.starsector-preflight/runs/janino-graphics-timed-warm-cooled-20260804-030033`
- excluded fast relaunches:
  `janino-graphics-timed-warm-20260804-025649` and
  `janino-graphics-timed-warm-settled-20260804-025756`

Full `mvn verify` passed before the instrumented launches. The installed-Janino integration replay
still verifies outer/nested complete-map persistence, a fresh-loader hit, class definition, and
execution through the game's own Janino archive.
