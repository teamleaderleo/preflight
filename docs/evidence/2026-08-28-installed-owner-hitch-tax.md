# Installed campaign owner and hitch tax

Date: 2026-08-28

Issues: #1158, #449. Branch: `codex/1158-physical`.

Install: Starsector 0.98a-RC8, current 83-mod profile, macOS 26.6.2 on Apple M5,
shipped x86-64 Zulu 17.0.10 runtime under Rosetta, Preflight Recommended path.

Status: **successful intrusive discovery; no FPS optimization claim**.

One unattended installed-game route now joins runtime class ownership, separate recurring frame tax
and retained hitch tax, enabled-mod bytecode leads, presentation phases, and SAMPLE JFR evidence.
The run identifies real targets and rules out GC as the dominant hitch family, but its callback
timers are intentionally intrusive and the current Continue save is not hashed. Its frame rates are
diagnostic context, not a baseline or candidate result.

## What the run explained

The settled paused and exact unpaused windows were qualitatively different:

| phase | active frames/time | p50 | p95 | p99 | 1% low | >50 ms | >100 ms | repeated >33 ms clusters |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| paused after campaign warmup | 3,214 / 57.486 s | 17.8 ms | 21.4 ms | 24.1 ms | 41.49 FPS | 0 | 0 | 0 |
| exact unpaused measurement window | 1,708 / 32.592 s | 17.3 ms | 31.6 ms | 59.6 ms | 16.78 FPS | 35 | 2 | 18 |

The driver requested a 45-second unpaused hold. The pause-state identity gate retained only 32.592
seconds before later game evolution became paused, rather than silently mixing the two states. That
is useful discovery behavior, but it is another reason not to promote these FPS values as a cohort.

Presentation timing points away from Java swap wait as the primary bad-frame owner. Across campaign
frames after the first 30 seconds, 116 of 122 frames over 33.33 ms were pre-swap dominated and only
six were swap dominated. Pre-swap work excluding the known limiter had p99 49.6 ms; native swap had
p99 14.2 ms, of which render-thread CPU was only 0.5 ms p99. The recurring roughly one-refresh
off-CPU swap family still exists, but the large active hitches are overwhelmingly before swap.

## Ranked runtime owners

The largest exact hitch owner was Starsector's own `ModularFleetAI`, not a mod callback:

- 30,787 measured calls, 1,401.361 ms total, 45.52 microseconds average;
- one 113.618 ms call and two more near or above 50 ms;
- nine retained calls overlapped >50 ms frames and five overlapped >100 ms frames;
- 234.436 ms of exact callback span overlapped retained hitch frames.

Its reviewed bytecode already divides `advance(float)` into assignment, strategic, tactical,
navigation, and per-ability plugin calls. A bounded timer around those existing seams is therefore
the highest-information exact successor: it can distinguish one pathological module/fleet from a
diffuse AI update without changing gameplay.

The strongest exact mod hitch leads were:

| owner / callback | observed work | retained hitch evidence | interpretation |
| --- | ---: | ---: | --- |
| Nexerelin `EconomyInfoHelper$1` | 10 calls / 46.171 ms; max 26.959 ms | both slow calls overlapped >100 ms frames | compact severe-frame lead; needs semantic reason/frequency before optimization |
| Bultach Coalition `GestaltSeededFleetManager` | 31 calls / 83.124 ms; max 47.374 ms | 47.374 ms inside a 68.329 ms frame | real isolated fleet-spawn hitch; bytecode shows inherited manager work reaching fleet creation, so do not remove it as “redundant” |
| IndEvo `DerelictInfrastructureCondition` | 32 sampled calls; max 27.429 ms | one call associated with two >50 ms frames | sampled market-condition lead; repeat before a candidate |
| Csp hullmods | 40 sampled calls / estimated 569.905 ms; max 17.110 ms | one >50 ms association | recurring sampled lead, weak hitch evidence |

Recurring frame tax and hitch tax are deliberately separate. More Planetary Conditions and IndEvo
market conditions each estimated about one second of aggregate work, while QoL Pack, Stellar
Networks, and Nexerelin engine scripts measured 1,032.751, 558.402, and 552.774 ms respectively.
Those totals are meaningful paper-cut leads but none individually explains the unpaused tail.

The broad core phases remain larger than most single mods: location advancement occupied 9,149.521
ms and economy advancement 5,056.966 ms inclusive. The sampled market drill-down still saw
156,157,056 commodity-stat callbacks and 39,039,264 event-mod callbacks, but their estimated total
costs were 3,071.410 and 809.194 ms. Enormous volume remains a legitimate optimization lead; it is
not, on its own, proof that another memo is safe or player-visible.

## JVM, native, and Rosetta result

SAMPLE JFR retained 46 hitch frames, eight severe. Only one retained hitch overlapped a GC pause:
5.789 ms of a 166.545 ms frame. Safepoints and VM operations likewise accounted for only a few
milliseconds. This route does **not** justify changing G1, heap sizing, or pre-touch policy for
gameplay tails.

Main-thread `Thread.sleep` overlapped 27 retained frames, but every retained stack was the known
`BaseGameState.traverse` frame limiter. It is not evidence of an unrelated lock or scheduler stall.
Deoptimizations and execution/native samples occurred in many hitch windows, but they are point
associations, not elapsed time. Native samples most often landed in swap, display lists, and legacy
`glBegin`/`glEnd`; they keep rendering in the next-level investigation but do not assign the hitch
duration to OpenGL.

The JFR-to-wall calibration factor was 2.4925 on the x86-64 game JVM under Rosetta, and JFR reports
the translated CPU as `VirtualApple @ 2.50GHz`. This is important clock-domain calibration evidence,
not a claim that Rosetta alone costs 2.49x performance. A native-architecture or JDK comparison
must preserve and independently measure the LWJGL/native stack before drawing that conclusion.

## Correctness, attribution, and observer health

The semantic driver passed all steps: main menu, internal Continue, initial pause observation,
verified pause, warmup, settled pause, verified unpause, transition exclusion, and exact unpaused
frame-window start. It owned and stopped the exact process. Adapter health remained `ACTIVE` with
74 reviewed transforms, zero source-binding rejects, unavailable plans, declines, contained
failures, or runtime-integrity failures; the kill switch remained off.

Ownership resolved 53 runtime mods across ten callback families. Two rows remained explicitly
`DYNAMIC_JANINO` rather than being guessed. Family totals are inclusive and sampled totals are
estimates, so they must not be summed as independent CPU time.

The display-boundary hook averaged 30.41 microseconds. Owner timing and hitch joining are intrusive;
their per-callback cost is not independently subtracted, and JFR sampled Preflight reporting code.
That observer presence is retained as a limitation instead of laundering this discovery run into an
FPS claim.

The initial postprocessor also exposed a real portability bug: this host's `jfr print --json`
represents event `type` as a string, while the synthetic fixture used a nested name object. Commit
`3eaa8798` accepts both shapes, adds the real-shape regression, preserves successful game evidence
when a postprocessor fails, and fixes the runner's owner-family summary paths.

## Decision and next experiment

Do not run FULL JFR or change collectors yet. GC/JIT evidence does not warrant another whole-game
launch. Do not optimize the isolated Bultach fleet spawn merely because it is easy to name; its
bytecode performs real fleet creation.

The next narrow discovery slice is exact `ModularFleetAI.advance` decomposition:

1. time assignment, strategic, tactical, navigation, and ability-AI calls separately;
2. retain bounded slow spans and the concrete fleet/module class identity only at report time;
3. preserve exact archive/class hashes, original calls, and a disable path;
4. run one ordinary unpaused campaign route with the same semantic pause gate;
5. promote an optimization only if one module or repeated operation owns material tail time.

If the AI work remains diffuse, stop instrumenting that seam. The next physical-host calibration is
thin async GPU timing plus bounded legacy-GL synchronization/command counts on the stable route,
because the current pre-swap result cannot distinguish Java/game CPU from GL driver work.

## Reproduction and retained data

```bash
./mvnw verify

scripts/run-1158-owner-tax-discovery.sh \
  --label issue-1158-owner-tax-r1
```

The installed run used source commit `6f90a3905bf12f85dfebccbfb7ae4355518ab672` and profile
fingerprint `2995668308ac3d31d645ccac30fb1a7e644e64fce5609050a1488df4cadc5af6`, at 1440x932
windowed, 60 FPS cap, VSync requested on, Apple OpenGL 2.1-over-Metal. The compact retained record is
[`data/2026-08-28-installed-owner-hitch-tax.json`](data/2026-08-28-installed-owner-hitch-tax.json).
Raw logs, JFR, generated target files, and run directories are disposable local evidence and are not
committed.
