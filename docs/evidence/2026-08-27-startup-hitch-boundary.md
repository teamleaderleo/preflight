# Startup hitch attribution and the interactive frame boundary

Date: 2026-08-27

Install: Starsector 0.98a-RC8, current heavily modded profile, macOS on Apple M5, bundled x86-64
Zulu 17 under Rosetta, Preflight fast preset

## Finding

Startup hitching is real, but it is not gameplay frame pacing. Three adjacent 1,040-DP combat
launches reached the interactive menu in 30.807--31.995 seconds and each contained a single
6.101--6.155-second presentation gap. The clean combat measurement windows excluded it, but the
general `postStartupActive` distribution did not: the resource-complete marker fired inside that
long display interval, and the following boundary attributed the entire preceding interval to the
post-startup population.

A retained profiled launch reproduced the same shape at 6,358.476ms. Phase timestamps attributed
6,357.666ms to pre-swap game work, 0.541ms to native swap, and 0.266ms to message processing. This
was synchronous initialization, not a vsync stall and not a dropped combat frame.

## Exact-window profile

The gameplay hotspot tool can now accept `runtime-frame-report.json` and map the exact worst-frame
wall-clock interval onto JFR's Rosetta-skewed recording clock:

```text
python3 scripts/starsector_gameplay_hotspots.py startup.jfr \
  --frame-report runtime-frame-report.json --include-other
```

The retained recording calibrated at 2.493665x wall time per recorded second. Its exact hitch
window contained 285 execution samples, including 133 on the main thread. Of those main-thread
samples, 126 were startup/title/unclassified work and seven were the title screen's decorative
combat engine. The leading inclusive startup owners were:

| owner | main-thread execution samples | share of startup/title samples |
| --- | ---: | ---: |
| `ResourceLoaderState.init` | 81 | 64.29% |
| `TitleScreenState.prepare` | 30 | 23.81% |
| GraphicsLib `ShaderModPlugin.onApplicationLoad` | 21 | 16.67% |
| save/Continue initialization through `CampaignGameManager` | 16 | 12.70% |

These owners overlap because the table is inclusive. GraphicsLib's 21 samples were already on the
accepted compact/lazy-normal path; 17 included missing-normal-map traversal. The save path was also
already on the accepted same-JVM descriptor memo. This slice does not justify another unsafe
cross-launch save shortcut or a universal startup percentage claim.

The same window contained 339 main-thread JFR allocation samples with 1,204,330,984 weighted bytes.
Of those, 1,201,764,912 bytes belonged to startup/title work. JFR allocation weights are estimates,
not an exact byte census. Prepared-texture reads/decompression, game resource copies, and adapter
transformation inspection were the largest broad groups. Profiling intentionally disables the
adapter transformation cache, so this recording is attribution evidence rather than a production
startup-time claim.

## Telemetry correction

`postStartupActive` remains the legacy post-resource-initialization distribution, but it now
conservatively excludes the display interval that crosses the resource-complete marker. A new
`postInteractiveActive` distribution begins only after the exact, already-reviewed title-screen
seam removes the final `Preloading...` label and accepts input. It also excludes its crossing
interval. Reports expose both transition-exclusion counters.

The measurement contract is now:

- time-to-interactive measures launch completion;
- the worst pre-interactive frame measures startup stall severity;
- `postInteractiveActive` is the broad aggregate after the player can act;
- paused/unpaused campaign and clean combat windows remain the authoritative gameplay populations;
- startup frames never support a gameplay FPS or low-percentile claim.

The change is telemetry-only. It does not alter game state, caches, simulation, input, saves, or the
existing exact bytecode seam.

## Verification

- ten Python hotspot-analyzer tests pass;
- seventeen focused Java 17 frame/semantic-state tests pass;
- exact retained JFR and frame-report hashes and measurements are recorded in
  `data/2026-08-27-startup-hitch-boundary.json`;
- full Java 17 `mvn verify` passes: 2,182 tests, zero failures, zero errors, and nine
  environment-gated skips;
- source-boundary and benchmark-claim provenance gates pass.
