# Severe-frame attribution in the 1,040-DP combat fixture

Date: 2026-08-28

Install: Starsector 0.98a-RC8, current 83-mod profile, macOS on Apple M5, shipped x86-64
Java 17 game runtime under Rosetta, Preflight Recommended preset

Status: **diagnostic pass completed; no narrow optimization candidate promoted**. The exact retained
`>=100 ms` combat-frame population did not contain a broadly recurring CPU or allocation family with
enough excess presence to justify another speculative wrapper.

## Why the selector changed

The profiled symmetric 1,040-DP fixture averaged 50.889 ms/frame. Its old `>33.33 ms`
repeated-cluster view consequently covered 29.372 of the 30-second measurement step and left only 19
background samples. That does not distinguish hitches; it merely rediscovers that this stress battle
runs below 30 FPS.

`starsector_gameplay_hotspots.py` now supports `--hitch-frame-millis MS`. It reads bounded hitch
packets, rejects incomplete packet populations, deduplicates overlapping frame history by sequence,
checks exact 50/100 ms populations against the recorder counters, and groups only consecutive
qualifying frames. It can then compare those windows with the non-window remainder of the same exact
scenario step:

`python3 scripts/starsector_gameplay_hotspots.py RUN/startup.jfr --scenario-evidence RUN/smoke-evidence.json --step combat-sample-1040dp --frame-report RUN/runtime-frame-report.json --hitch-frame-millis 100 --cluster-enrichment`

The JFR clock required the established Rosetta calibration: 2.494 wall seconds per recorded second.
This remains intrusive discovery instrumentation. Its FPS numbers are context, not an optimization
claim.

## Exact workload and frame context

The Preflight-only scenario passed from menu through controlled shutdown. It used the fixed 4x
viewport at center `(0,0)`, 5,760x3,728 visible world units, and 24 mirrored ships totaling 520 DP per
side. The frame window began with 102 entities: 32/50 non-fighters and 10/10 fighters on sides zero
and one. It ended after 39.174 simulated seconds with 121 entities, 12 hulks, 228 projectiles, and 106
missiles; combat had not ended. Adapter health and the exact vanilla combat-runtime integrity gate
passed.

The 589-frame, 29.974-second window measured 19.65 average FPS, 46.8 ms median, 81.7 ms p95, 145.8
ms p99, 6.86 FPS 1% low, and 170.677 ms maximum. It contained 211 frames over 50 ms and 20 frames over
100 ms. Every one of its 571 frames over 33.33 ms was pre-swap dominated. Pre-swap p99 was 144.9 ms;
native swap averaged 0.304 ms and had a 0.6 ms p99. The asynchronous GPU timer was not enabled.
Display-boundary measurement overhead averaged 39.915 microseconds over 2,159 samples, with a 9.897
ms maximum.

## CPU result

The recorder retained all 29 session-wide severe triggers with zero dropped packets. Intersecting
them with `combat-sample-1040dp` produced 18 groups covering 2.657 wall seconds. Those windows
contained 51 combat execution samples; the remainder of the same step contained 607.

No narrow method recurred broadly enough to support an intervention. The strongest mod-side lift was
Advanced Gunnery Control's `TagBasedAI.advance` chain: 4/51 severe samples (7.84%) versus 13/607
background samples (2.14%), a 3.66x lift, but it appeared in only 2 of 18 groups. The broadest weak
lead was vanilla `WeaponGroup.advance`: 13/51 (25.49%) versus 124/607 (20.43%), only 1.25x, across
10/18 groups. AI Tweaks ship/autofire work was common in both populations rather than a coherent
severe-frame discriminator.

The responsible conclusion is not “nothing costs CPU.” Whole-step sampling still placed
`CombatEngine.advance` in 574/658 combat execution samples, AI Tweaks `ExtendedShipAI.advance` in
219/658, vanilla `BasicShipAI.advance` in 201/658, `Ship.advance` in 172/658, and
`WeaponGroup.advance` in 137/658. It means this one sparse severe population cannot choose a safe,
narrow optimization among those broad subsystems.

## Allocation result

Whole-step JFR allocation sampling retained 2,804 combat samples and approximately 4.0 GiB of
weighted allocation. These are JFR estimates, not an exact census. `Vector2f` represented 30.77%.
The leading owners were vanilla `computePosition` at 469.6 MiB, Preflight's already accepted-with-limit
`CollisionQuerySet.initialize` path at 331.3 MiB, and AI Tweaks `SelectTarget.selectTarget` at 218.7
MiB.

The severe windows retained 246 combat allocation samples and 342.7 MiB weighted. Their composition
was similar rather than hitch-specific: `Vector2f` was 27.74%, `CollisionQuerySet.initialize` 40.0
MiB, and vanilla `computePosition` 30.0 MiB. This does not justify reviving either the rejected compact
collision index or the rejected `WeaponHandle.getLocation` successor. Large allocation volume remains
a legitimate lead, but the next candidate needs a sharper ownership or lifecycle boundary.

## Decision and next handoff

This pass produced a useful measurement correction and a negative target-selection result. Do not
manufacture an optimization from two Advanced Gunnery Control hitch groups or from large allocation
families already tested at broader seams.

The live coordination map in #1152 and child lanes #1153–#1158 now supplies higher-information work
than another bespoke probe here. The installed machine should next exercise an existing exact
candidate—first the #1157 precision limiter/VSync interaction after its carrier is clean—or consume
the #1154/#1158 owner/JVM attribution only after those branches pass their integration gates. Scaling
coefficients (#1155), bounded GPU/resource diagnostics (#1156), and render-sync candidates (#1153)
remain separate physical-machine jobs. Synthetic concurrency/topology sweeps should stay outside the
licensed-game run budget.

Machine-readable metrics and hashes are retained in
[`data/2026-08-28-combat-severe-frame-attribution.json`](data/2026-08-28-combat-severe-frame-attribution.json).
The raw JFR, reports, logs, and run directory are disposable and are not committed.
