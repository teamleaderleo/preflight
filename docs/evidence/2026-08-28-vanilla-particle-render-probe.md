# Vanilla particle-render cost probe

Date: 2026-08-28

Install: Starsector 0.98a-RC8, current 83-mod profile, macOS 26.6.2 on Apple M5,
shipped x86-64 Zulu 17.0.10 runtime under Rosetta, Preflight Recommended/Fast path

Status: **material discovery lead; no FPS claim and no batching candidate yet**.

`com.fs.graphics.particle.DynamicParticleGroup.render(float,float)` is called often enough to
justify one tighter downstream investigation. In the exact symmetric 1,040-DP window it ran
44,992 times, exactly 38 calls per measured frame, and occupied 586.879 ms inclusive: 0.496 ms per
frame and 1.95% of the 30.027-second active window. The ordinary-combat correctness route showed
the same call density and a higher inclusive share.

This does not establish that all of that time can be removed. The discovery probe adds two
`System.nanoTime()` reads and primitive bookkeeping per call, and `DynamicParticleGroup.render`
delegates the actual particle rendering. Its exact bytecode contains zero direct `glBegin`,
`glEnd`, vertex, texture-coordinate, color, texture-bind, or blend-function calls. The next useful
question is therefore the cost and draw-context distribution in the downstream `BaseParticle`
render path, not a speculative rewrite of the group wrapper.

## Live results

The ordinary route was a correctness and semantic-combat sanity check. It completed unattended,
all route steps passed, the exact particle transform installed, adapter health remained `ACTIVE`,
and the captured combat frame showed no obvious position, scale, attachment, state-leak, or UI
corruption.

| route | scope | frames | calls | inclusive time | time/frame | active-wall share |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| ordinary combat | semantic combat | 2,184 | 82,574 | 1,548.283 ms | 0.709 ms | 3.47% |
| symmetric 1,040 DP | sealed 30 s window | 1,184 | 44,992 | 586.879 ms | 0.496 ms | 1.95% |

The sealed stress window retained 24 mirrored 520-DP fleets per side, no non-fighter losses, a
fixed 4.0 view multiplier centered at `(0,0)`, autopilot, and two-times simulation speed. It ended
with 70 missiles and 94 projectiles. The thin frame probe recorded p50 24.2 ms, p95 40.2 ms, p99
82.1 ms, 1% low 12.18 FPS, 33 frames over 50 ms, and three over 100 ms. Those FPS numbers describe
an intrusive discovery run and are **not** a baseline/candidate performance comparison.

Both accepted runs used the same profile fingerprint
`2995668308ac3d31d645ccac30fb1a7e644e64fce5609050a1488df4cadc5af6`, 1440x932 window,
VSync-off current profile state, 60 FPS game cap, Apple OpenGL 2.1-over-Metal adapter, and exact
reviewed `fs.common_obf.jar` / class hashes. Both applied 70 reviewed transformations with zero
declines, contained failures, source-binding rejections, unavailable plans, or runtime-integrity
failures. The frame-boundary probe averaged 28.96 microseconds across the stress process; particle
probe overhead is separately classified as unmeasured intrusive overhead.

## Decision

Do not claim a gameplay improvement and do not port Fast Rendering's broad command queue from this
result. The wrapper seam is material enough to survive triage, but its 1.95% stress-window upper
bound cannot by itself explain the large combat deficit.

The next #1153 slice should count and time the downstream particle submission seam while retaining
draw mode, texture, blend, layer, and owner context. Only if that tighter census demonstrates many
compatible immediate-mode batches should an exact primitive-buffer/array batching candidate be
built and judged with repeated interleaved baseline/candidate 1,040-DP cohorts. If the downstream
work is diffuse or incompatible, retain this result and close the batching branch.

## Reproduction and retained data

```bash
bash scripts/run-1153-particle-probe.sh \
  --route ordinary \
  --workload-id ordinary-correctness-r3 \
  --label issue-1153-particle-ordinary-r3

bash scripts/run-1153-particle-probe.sh \
  --route symmetric-1040 \
  --workload-id symmetric-1040-r1 \
  --label issue-1153-particle-1040-r1
```

The compact machine-readable record is in
[`data/2026-08-28-vanilla-particle-render-probe.json`](data/2026-08-28-vanilla-particle-render-probe.json).
Raw logs, screenshots, generated target files, and run directories are disposable local evidence
and are not committed.
