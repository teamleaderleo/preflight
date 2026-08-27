# GL texture-bind deduplication rejected

Date: 2026-08-28

Install: Starsector 0.98a-RC8, current 83-mod profile, macOS on Apple M5, shipped x86-64
Java 17 game runtime under Rosetta, Preflight Recommended preset

Status: **rejected after ordinary-combat validation and a thin interleaved 1,040-DP cohort**. The
candidate suppressed a median 38.36% of observed `GL_TEXTURE_2D` binds, but it did not improve tail
smoothness or simulated throughput. The active candidate is preserved by commits `9886e4b3` through
`316f1923`; it is not a gameplay-performance win.

## Decision

The preceding exact state census observed 1,442,464 `glBindTexture` calls in one settled campaign
window, of which 774,305 (53.68%) repeated the modeled current binding. That justified one narrow,
opt-in suppression candidate rather than a combined fixed-function state cache.

The candidate tracked only exact LWJGL `GL_TEXTURE_2D` bindings on the render thread. It declined or
invalidated its state at context, active-texture, texture-deletion, attribute-stack, display-list,
direct-state-access, and unexpected-thread boundaries. Its property and environment kill switches
were `preflight.framePacing.textureBindDedup` and `PREFLIGHT_GL_TEXTURE_BIND_DEDUP`. Original calls
remained the fallback whenever the model was not certain.

That model worked structurally: the final two B runs suppressed 1,774,758 and 1,743,344 binds with no
wrong-thread observations or runtime disable. It did not produce the required player-visible result.
Across the two-run arm medians, B versus A changed p99 frame time by **+1.6%**, 1% low FPS by
**-0.9%**, >50 ms frames per active minute by **+41.5%**, stutter burden by **+8.4%**, and average
FPS by **-4.3%**. The >100 ms rate improved 19.5%, but that isolated movement does not outweigh the
broader tail and throughput regression. The candidate is rejected rather than expanded to blend or
enable/disable state.

## Correctness and recovery work

The first live candidate build exposed an activation bug: a runtime class could load before the plan
installed and permanently remain inert. That was fixed by making installation visible after the
exact transform completed. Later attempts exposed display-list boundaries where a strict unmatched
end disabled the candidate even though the safest behavior was to invalidate and resume only after
returning to a known state. The final implementation recovered conservatively and retained original
calls across every uncertain boundary.

The final ordinary-combat run passed all 33 semantic steps in an 8-allied-versus-25-opposing fixture,
including deployment, autopilot, verified zoom, a 60-second active window, and controlled shutdown.
It retained 3,263 frames at 53.9 average FPS, 17.21 FPS 1% low, 58.1 ms p99, 99.733 ms maximum, 41
frames over 50 ms, and zero over 100 ms. It observed 8,991,678 binds and suppressed 3,155,871
(35.10%), with no wrong-thread or runtime-disable event. This is a B-only correctness and sanity
observation, not an uplift claim.

No save or serialized game data was changed. The runtime state was process-local, bounded, primitive,
and discarded at shutdown. Exact class/method/hash gates, original fallback, and the ordinary plan
kill switch remained in force.

## Thin 1,040-DP cohort

The retained order was B1/A1/A2/B2. Every run used Preflight, the same JAR
`97e2f0ae25c2632116b920edbe42cd5163838d73b9d544056118d0314bb369e2`, game/profile/save/scenario,
1440x932 windowed display, VSync interval one, unchanged game cap, Recommended/full adapter policy,
and the same 24-ship-per-side, 520-DP-per-side fixture. The harness froze combat during camera setup,
then verified center `(0,0)`, zoom `4.000`, and a 5,760x3,728 viewport before starting the 30-second
window.

The thin workload fingerprint retained begin/end simulation time, ships, fighters, hulks,
projectiles, missiles, aggregate hull and flux, plus combat-ended state. All four runs passed the
identity, adapter, and bounded workload gates. Begin ship count was exactly 102; end ship count was
122–133; simulated time advanced 36.60–39.68 seconds; none ended combat. This is not lockstep battle
evolution, but no arm had a material workload-class mismatch that could support a positive claim.

| Run | Arm | Avg FPS | p50 ms | p95 ms | p99 ms | 1% low | Max ms | >50 ms/min | >100 ms/min | Sim s |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| B1 | candidate | 19.48 | 49.2 | 75.9 | 133.7 | 7.48 | 176.9 | 509.4 | 32.0 | 38.67 |
| A1 | baseline | 19.90 | 45.5 | 82.7 | 143.5 | 6.97 | 162.8 | 403.3 | 39.9 | 39.68 |
| A2 | baseline | 19.62 | 46.0 | 84.0 | 143.3 | 6.98 | 199.7 | 407.7 | 42.0 | 39.09 |
| B2 | candidate | 18.36 | 52.2 | 81.4 | 157.8 | 6.34 | 238.4 | 637.7 | 34.0 | 36.60 |

The candidate arm's median simulated progress was also lower, consistent with the frame result rather
than a hidden throughput win. The direct causal counter proves that calls disappeared; it does not
prove that the driver, Rosetta boundary, or GPU saved useful time.

## Measurement limits and rejected attempts

These were thin measurement runs: no JFR and no asynchronous GPU timer. The frame hook retained a
thin current-thread CPU clock with zero read failures. Its average reporting overhead was 34–47
microseconds per frame across the cohort; maximum observations ranged from 2.682 to 23.350 ms.
The explicit 30-second window did not yet retain its own presentation-phase split, so this experiment
cannot claim a presentation-phase movement. Adding that exact-window split is required before the
matrix-tail acceptance contract is measured.

Earlier runs are intentionally excluded from the cohort:

- the initial candidate activation and display-list recovery attempts did not keep the candidate
  active for the whole workload;
- cursor-only camera control was restored by macOS and allowed zoom to drift from 4.000 to 1.250;
- setting the view once was overwritten by combat updates;
- an early workload receipt was misclassified, and a smoke-time report raced the final shutdown
  report;
- an unnormalized cohort began with different ship counts.

The final route corrected each harness issue: it pins the full combat viewport externally, freezes
combat while preparing the camera, emits begin/end fingerprints, flushes the end receipt, and reads
the final runtime report. These failures remain useful evidence for why the final cohort is the only
one used in the decision.

## Consequence

Do not revisit this candidate because its counter is large. It already answered the relevant
question: removing repeated texture binds at this wrapper seam does not yield a reproducible
smoothness benefit on the retained workload. Neighboring GL families remain separate hypotheses.
They must be counted and given their own correctness and tail-frame acceptance contract before any
suppression attempt.

Compact metrics and artifact hashes are in
[`data/2026-08-28-gl-texture-bind-dedup-rejected.json`](data/2026-08-28-gl-texture-bind-dedup-rejected.json).
Raw logs and run directories are disposable and are not committed.
