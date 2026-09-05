# Windows upload guard cost and repeatability

Objective: retain the RGB alignment repair while reducing ordinary startup cost and variance.
Owner: current Codex task. Phase: accepted helper implementation; integration and cleanup.
Finish: validate the candidate on the native Windows game, retain only a justified change, integrate
main, restore the ordinary task and retire disposable builds. Do not infer reliability from a best run.

Baseline main c2d5bf6f, installed JAR f9866880854c8c6cc50f0a1c2f07c8e88ab022a9f8b49b98c5e3242c6ae9dfdd.
The inline alignment guard adds argument spills and exception handlers to every original upload
site. Candidate: retain the same exact gate, buffer ownership checks, alignment query/change/restore
and original GL call, but place the guarded native call in a private static helper in TextureLoader.
The loader keeps a single helper invocation, with no new exception region or argument spill locals.
Diagnostic path metadata travels as the helper's final parameter. This is a code-shape hypothesis;
there is no measured performance win yet. Worker count, scheduling, GL thread, 1024 ceiling,
converter semantics, sampler/mipmap/reload policy, packed-raster path and late resources are unchanged.

Fresh baseline cohort `20260906-052328`: menus 19.949 / 20.530 s; graphics preload 18.171 / 18.904 s.
Both native Recommended runs completed. Final candidate source `2a6d2282918026c48af8bdcb0aafda9b7c70c97f`,
JAR `3aebbe8dc19a60c1221be69cb2943cce2d8c4a8a45ef18806865db56ddf1f6ef`; full verify passed in
47.857 s, all 23 installed-loader tests and 8 probe tests passed. Four ordinary native repeats are running.

The prior 28.067 s outlier also had large post-texture main-thread log gaps: cleanup-to-save-read
1.230 s versus 0.407 s in the fresh baseline; codex-to-next-CSV 1.178 s versus 0.374 s. Logs alone do
not attribute these gaps, but the slowdown extends beyond native texture upload. Both guest starts
had about 15.3 GiB free physical memory, the same 14 vCPUs, and host performance power mode.
There is no evidence supporting another RAM increase in this slice.

Candidate cohort `20260906-052800` completed four ordinary native launches:

| Run | Interactive menu (s) | Graphics preload (s) |
| --- | ---: | ---: |
| 1 | 20.096 | 18.364 |
| 2 | 17.809 | 16.151 |
| 3 | 18.171 | 16.488 |
| 4 | 19.069 | 17.183 |

All four had 168 alignment changes/restores, 15,002 commits, 44 packed converter images,
102 late Kaleidoscope resources consumed, and zero active/pending buffers. Three-platform CI
`33993116844` passed on executable source `2a6d2282`. The baseline artifact has been restored with
hash/source checks for an intervening two-run comparison, after which the unchanged candidate
will be repeated. No timing attribution is accepted from the first block alone.

Intervening baseline cohort `20260906-053237`: menus 19.681 / 18.488 s, graphics preload
17.884 / 16.755 s. Both completed. This overlaps the candidate range, so a stable speed advantage
is not established by the first four candidate runs. The same candidate bytes are now undergoing
four further ordinary native launches, preserving the two artifact identities throughout.

Unchanged candidate cohort `20260906-053532` completed all four launches:

| Run | Interactive menu (s) | Graphics preload (s) |
| --- | ---: | ---: |
| 1 | 22.717 | 21.052 |
| 2 | 19.515 | 17.737 |
| 3 | 18.803 | 17.145 |
| 4 | 17.756 | 16.075 |

All eight candidate runs completed without stalls, balanced all 168 alignment changes/restores,
committed 15,002 resources, used 44 packed converter images, consumed all 102 late Kaleidoscope
resources and ended with zero active/pending buffers. Candidate median across eight runs: 18.936 s;
baseline median across the four intervening/before runs: 19.815 s. Candidate range: 17.756–22.717 s,
with six of eight under 20 s. The sequential A2/B4/A2/B4 comparison is exploratory, not a shuffled
campaign or proof of a stable 0.879-second improvement. In particular, the candidate still has a slow
outlier and does not establish consistent sub-18 startup or lower tail latency than the baseline.

Retain the smaller ordinary loader control flow: the safety policy is unchanged, installed/native
contracts pass, and the observed median supports further work on this implementation. No additional
RAM or CPU topology changes were made. The VM still maps its 14 active vCPUs to six P-cores and
eight E-cores; the previously rejected six-core-only experiment is documented in
`2026-09-01-windows-vm-startup-tuning.md` and was not repeated. CPU placement remains a hypothesis,
not an attributed cause of these results. Keep the measured candidate JAR installed after integration;
only evidence changes follow its verified executable source.
