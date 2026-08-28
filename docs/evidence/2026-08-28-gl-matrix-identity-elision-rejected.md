# GL matrix identity elision rejected

Date: 2026-08-28

Install: Starsector 0.98a-RC8, current 83-mod profile, macOS on Apple M5, shipped x86-64
Java 17 game runtime under Rosetta, Preflight Recommended preset

Status: **rejected after exact-call discovery, ordinary-combat correctness, and a thin interleaved
1,040-DP cohort**. The candidate removed a median 2,526,231 exact identity transforms per measured
run, but did not produce a reproducible player-visible smoothness improvement. Its implementation is
preserved in Git history beginning at `9e263701`, with activation hardening at `ff0da811`; it is not a
shipping optimization.

## Decision

An intrusive discovery census observed 29,179,941 legacy matrix operations over 500 combat frames,
or 58,359.9 calls/frame. Of those, 2,387,807 (8.18%) were exact identity operations. The strongest
single family was `glRotatef`: 2,100,158 of 5,958,583 calls (35.25%) had zero angle. This volume
justified a narrow experiment, but the census FPS is not used as performance evidence.

The candidate suppressed only operations mathematically equivalent to multiplying the current matrix
by the identity matrix: `(0,0,0)` translations, `(1,1,1)` scales, and finite-axis rotations with angle
zero. It retained original LWJGL behavior inside `glBegin`/`glEnd`, on non-identity inputs, on the wrong
thread, when disabled, and whenever the exact target did not install. It was gated to the reviewed
LWJGL 2 `GL11` class and exposed the kill switch
`PREFLIGHT_GL_MATRIX_IDENTITY_ELISION=1` / `preflight.framePacing.matrixIdentityElision`.

The candidate worked structurally. The two B runs suppressed 2,700,905 and 2,351,558 operations,
13.59% and 13.20% of the measured transform families, with no primitive-scope decline, wrong-thread
call, unbalanced scope, or runtime disable. It did not meet the smoothness contract. Across arm
medians, B versus A changed p99 frame time by **-1.4%**, 1% low FPS by **+1.5%**, >50 ms frames per
active minute by **+2.6%**, >100 ms frames per active minute by **+0.1%**, stutter burden by
**-1.2%**, and average FPS by **+0.8%**. Maximum frame time was worse in the candidate arm median.
These movements are small, mixed, and well inside run-to-run variation.

The causal chain therefore stops after its first link:

`2.53M fewer exact matrix calls/run -> no reproducible p99/1%-low/severe-frame win`

Do not generalize this result into a claim that all legacy GL traffic is harmless. It rejects this
specific exact-identity wrapper seam. Neighboring texture, blend, color, enable/disable, viewport,
upload, and synchronization families remain independent count-first hypotheses.

## Ordinary-combat correctness

The final ordinary sanity run passed the complete semantic route: main menu, campaign load, fleet
construction, deployment, autopilot, command-map dismissal, exact 4x viewport, 60 seconds of active
combat, evidence capture, and controlled shutdown. The exact target installed once with all eight
expected methods; the candidate remained active with no disable reason, wrong-thread call, or fatal.

The retained screenshot shows coherent ship positions and rotations, selection bounds, shields,
projectiles, explosions, status text, and combat UI at the verified 5,760x3,728 world viewport. No
zoom-dependent displacement or cross-pass matrix leakage is visible. This is one visual sanity sample,
not proof of every nested mod render callback. The screenshot is retained at
[`images/2026-08-28-gl-matrix-identity-ordinary.png`](images/2026-08-28-gl-matrix-identity-ordinary.png).

The sanity scenario intentionally did not open the performance measurement window, so its causal
counters are zero; installation and active/fallback health are read from session telemetry. No save or
serialized game data was changed. Candidate state was process-local and discarded at shutdown.

An earlier apparent sanity pass is excluded: telemetry showed zero installed targets because the new
plan had been omitted from the plan registry. The harness was hardened to require a nonzero exact
installed target/method count before accepting a candidate run. Other excluded attempts failed before
combat measurement because of a locked console, an overshot viewport, or use of a stress-only receipt
in the ordinary scenario. None contributes FPS evidence.

## Thin 1,040-DP cohort

The retained order was B1/A1/A2/B2. Every run used Preflight, JAR
`1c2f47ad8dce69dc6a2735345fdfbe90b62716679db1ae3ed7c90def728e3736`, the same game/profile/save,
the `campaign-simulation-combat-1000dp-thin` scenario, 1440x932 windowed display, VSync interval one,
unchanged game cap, Recommended/full adapter policy, and 24 mirrored ships totaling 520 DP per side.
The harness froze combat during camera setup and verified center `(0,0)`, zoom `4.000`, and viewport
5,760x3,728 before each 30-second window.

| Run | Arm | Avg FPS | p50 ms | p95 ms | p99 ms | 1% low | Max ms | >50 ms/min | >100 ms/min | Sim s |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| B1 | candidate | 18.25 | 50.9 | 90.4 | 145.8 | 6.86 | 230.9 | 605.4 | 50.0 | 36.41 |
| A1 | baseline | 18.76 | 49.1 | 86.6 | 151.1 | 6.62 | 200.3 | 506.9 | 47.9 | 37.44 |
| A2 | baseline | 17.50 | 53.1 | 95.4 | 153.9 | 6.50 | 166.2 | 666.7 | 51.9 | 35.04 |
| B2 | candidate | 18.31 | 50.8 | 92.4 | 154.8 | 6.46 | 198.4 | 599.3 | 49.9 | 36.55 |

All four runs passed identity, adapter-health, and bounded-workload gates. The fingerprint requires the
same 32 versus 50 non-fighter combatants at the window boundary, while allowing at most eight transient
fighter entities because fighters can launch during the sub-second receipt handshake. Begin fighter
timing varied by four entities; projectiles and missiles varied by one. Simulated progress spanned
35.04–37.44 seconds, end ship entities spanned 122–136, and no run ended combat. That is a genuinely
comparable workload class without pretending the AI simulation is lockstep.

## Presentation, CPU, allocation, and probe cost

The explicit measurement window retained a complete presentation split for every frame. Candidate
versus baseline arm medians changed pre-swap p99 from 151.2 ms to 149.0 ms and average pre-swap from
54.66 ms to 54.14 ms. Native swap remained tiny: average 0.317 ms baseline versus 0.311 ms candidate,
with inferred off-CPU swap wait around 0.09 ms. In every run, pre-swap work was the largest phase for
all measured slow frames; presentation wait was not masking a matrix win.

These thin runs did not include JFR or an intrusive CPU profiler, so no standalone CPU attribution or
allocation delta is claimed. The candidate uses primitive comparisons/counters and introduces no
intentional per-call allocation; allocation was not its target. The measured outcome already includes
the wrapper's branch/counter cost and the avoided native calls. Display-boundary measurement overhead
averaged 35.3–40.0 microseconds across runs. The current-thread swap clock was calibrated separately and
had zero read failures. No asynchronous GPU timer was enabled.

The compact machine-readable metrics and artifact hashes are in
[`data/2026-08-28-gl-matrix-identity-elision-rejected.json`](data/2026-08-28-gl-matrix-identity-elision-rejected.json).
Raw logs and run directories are disposable and are not committed.

## Consequence for issue #449

This closes the exact matrix-identity branch as a useful rejection. It also validates several pieces of
the broader gameplay-performance program already present on current main: semantic combat automation,
fixed stress viewport, explicit frame windows, presentation-phase timing, direct causal counters,
adapter/install health, and a bounded workload fingerprint. The strongest remaining fact is that the
1,040-DP tail is overwhelmingly pre-swap work, while this high-volume identity family was not a large
enough contributor to matter.

The next high-information slice should therefore return to attribution, not another speculative state
cache: enrich a bounded hitch packet for the repeated >33 ms combat clusters with thin workload state
and targeted sampled CPU/bytecode evidence, then choose one concrete family from those bad frames. Any
new intrusive probe remains discovery-only; its FPS cannot support the eventual optimization claim.
