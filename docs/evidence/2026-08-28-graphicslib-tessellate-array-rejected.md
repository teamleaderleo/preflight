# GraphicsLib cached-tessellation array candidate rejected

Date: 2026-08-28

Install: Starsector 0.98a-RC8, current 83-mod profile, macOS 26.6.2 on Apple M5,
shipped x86-64 Zulu 17.0.10 runtime under Rosetta, Preflight Recommended/Fast path

Status: **rejected for the current profile and routes; the exact candidate installed but its
optimized path executed zero times**.

This experiment replaced the reviewed GraphicsLib 1.12.1 cached-tessellation immediate replay with
a reusable client-side vertex array. The code is an opt-in experiment, not a default Preflight
behavior. It retains an exact archive/class identity gate, original-bytecode fallback, structural
declines for unexpected bytecode, and the normal adapter kill switch.

## Result

The exact installed archive integration gate passed. The live candidate then completed the ordinary
combat route and the symmetric 1,040-DP fixture. Both sessions reported the transform installed,
ACTIVE adapter health, zero declines, zero source-binding rejections, zero contained failures, and
zero runtime-integrity failures. The ordinary captured combat frame showed no obvious sprite
position, rotation, scale, beam/effect attachment, zoom, UI, or GL-state leakage.

The causal telemetry was nevertheless zero in both routes:

| route | installed | batches | vertices | buffer grows | immediate vertex calls avoided |
| --- | ---: | ---: | ---: | ---: | ---: |
| ordinary combat | yes | 0 | 0 | 0 | 0 |
| symmetric 1,040 DP | yes | 0 | 0 | 0 | 0 |

The stress fixture retained 24 mirrored 520-DP primary fleets per side, no non-fighter losses, and
56.175 elapsed game seconds. It ended with 65 missiles and 84 projectiles. GraphicsLib texture and
shader activity was visible in the same process logs, so the zero counter does not mean the mod was
missing. It means the reviewed cache-hit branch was not exercised by either route.

## Why there is no FPS claim

The one ordinary baseline/candidate pair recorded the following thin frame context:

| arm | frames | p50 ms | p95 ms | p99 ms | 1% low FPS | max ms | >50 ms | >100 ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| baseline | 2,226 | 17.3 | 34.3 | 78.9 | 12.67 | 322.952 | 54 | 10 |
| candidate | 2,242 | 17.2 | 33.7 | 70.7 | 14.14 | 327.394 | 48 | 11 |

Those differences are run noise with respect to this candidate: it submitted no arrays and avoided
no immediate calls. The candidate-only 1,040-DP materiality check likewise recorded zero candidate
work (p50 25.5 ms, p95 42.4 ms, p99 78.9 ms, 1% low 12.67 FPS, 37 frames over 50 ms, and eight over
100 ms across 1,166 frames). A stress baseline and repeated interleaved cohort were deliberately not
run. Once the direct counter proved the optimization absent in both ordinary and exact stress
workloads, more frame-time arms could not establish its causal chain.

This is a useful rejection rather than an optimization failure hidden by averaging:

`exact transform installed -> zero cached replay batches -> zero GL work removed -> no defensible smoothness claim`

## Identity and probe cost

All three observations used source commit `ffb1c2dfbe046fd32a012b2180e356f55e1817b5`, Graphics.jar
SHA-256 `832064013fe853731941e547842884ba121fb8b20eff08d24137f7a2c916903a`, and target class SHA-256
`0e25f52eb84a184bd426afaa69372a49d57befe672bda88a71691e09facfeacf`. Presentation policy remained
the current profile state: swap interval one and the game's unchanged 60 FPS cap. The display-boundary
probe averaged 29.79, 36.48, and 30.15 microseconds in the baseline ordinary, candidate ordinary,
and candidate stress processes respectively. No broad GL-command or JFR discovery probe was active.

The candidate is disabled unless
`-Dpreflight.graphicsLibTessellateArray=true` is present. The unattended runner keeps normal Preflight
launches unchanged and performs archive/class identity validation before launch.

## Decision and next question

Do not promote this candidate and do not spend a repeated cohort on the same zero-work routes. Keep
the branch and negative evidence visible because another scenario could exercise the cache.

For #1153, the higher-information render-side successor is the downstream vanilla particle
submission census identified by the separate `DynamicParticleGroup.render` probe. For the broader
#449 program, runtime owner-tax attribution may rank above another speculative GraphicsLib rewrite.
Only revive this tessellation candidate after a thin discovery run reports nonzero cached replay
batches in a named workload.

## Reproduction and retained data

```bash
bash scripts/run-1153-tess-array-pilot.sh \
  --variant baseline \
  --route ordinary \
  --workload-id tess-ordinary-correctness-r1 \
  --label issue-1153-tess-array-A-ordinary-r1

bash scripts/run-1153-tess-array-pilot.sh \
  --variant candidate \
  --route ordinary \
  --workload-id tess-ordinary-correctness-r1 \
  --label issue-1153-tess-array-B-ordinary-r1

bash scripts/run-1153-tess-array-pilot.sh \
  --variant candidate \
  --route symmetric-1040 \
  --workload-id tess-1040-materiality-r1 \
  --label issue-1153-tess-array-B-1040-materiality-r1
```

The compact machine-readable record is in
[`data/2026-08-28-graphicslib-tessellate-array-rejected.json`](data/2026-08-28-graphicslib-tessellate-array-rejected.json).
Raw logs, screenshots, generated targets, and run directories are disposable local evidence and are
not committed.
