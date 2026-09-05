# Windows upload guard cost and repeatability

Objective: retain the RGB alignment repair while reducing ordinary startup cost and variance.
Owner: current Codex task. Phase: baseline repeats and helper candidate verification.
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
