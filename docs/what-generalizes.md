# What generalizes

A note on what this project is an instance of, written while it is still small enough to be honest
about. It is a thinking document, not a plan. Nothing here is a commitment, and the concrete work
stays in [the roadmap](roadmap.md).

## The question

Preflight looks specific to the point of eccentricity: one closed-source Java game, one person's
eighty-six mods, one macOS install. But almost none of the *work* has been Starsector-specific. It has
been: find where a closed system spends its startup, find what it recomputes every launch that could
have been computed once, find what the content ecosystem ships that the platform then has to pay for,
and — repeatedly — find out that the measurement was wrong before the conclusion was.

So: is this game engine work? Is it generalizable? Both partly, in ways worth separating, because they
generalize very differently.

## Three layers

**Layer 1 — the epistemics. Fully general, and not about games at all.**

Everything this repository has been forced to learn about not fooling itself applies to any
performance or data work:

- *A default threshold is a measurement trap.* JFR's `jdk.FileRead` drops reads under a millisecond,
  which is most of them once the page cache is warm. Identical work measured **36.5 MB and 0 bytes**
  depending on that one setting, and the 0 was not distinguishable from "the file was never read".
- *Negative evidence is stronger than positive.* "A file the process never opened cannot have been
  decoded" needs no inference. "Every file was opened, therefore they were decoded" is a plausible
  story about reads. The verdict says which it is rather than flattening them.
- *Evidence you cannot explain must be counted out loud.* The probe silently discarded every audio
  read it could not map — a third of them — and reported a confident finding built on the rest.
- *Do not redesign a metric to explain a number you have not explained.* A 62% figure looked like
  partial coverage and triggered a redesign of the verdict logic. It was a path-resolution bug. The
  redesign happened to be right for other reasons, which is luck, not method.
- *Identity joins are where silent wrongness lives.* Two published corrections came from one cause:
  the same file spelled two ways. Nothing crashed. The number was just wrong.
- *A same-process test cannot catch a cross-process assumption.* Resolving a relative path against
  "here" works perfectly whenever the recorder and the analyser share a working directory, and in
  production they never do.

None of this is about games. It is about the gap between an instrument and the thing it points at.

**Layer 2 — the seam. A real category, and underserved.**

The structural situation is: **a closed platform, an open content ecosystem, and a loading contract
between them that nobody owns.** Waste collects there for a reason that is not anyone's fault:

- the platform cannot know what content will do, so it must be general and lazy;
- content authors cannot see what the platform pays for their choices, and get no feedback;
- so the interface accumulates cost that is invisible from both sides.

That describes modded games. It also describes browser extensions and page load, DAW plugin scanning
at startup, IDE plugin ecosystems, package managers, container base images, and CI caches. The
specific artifacts differ; the shape does not. This is the part of the user's intuition that seems
most right to me.

**Layer 3 — the mechanics. Barely generalizes.**

Starsector on the JVM is an unusually generous target, and it is worth being blunt that a lot of what
made this tractable is luck:

| what made it easy | how common that is |
| --- | --- |
| `-javaagent` + JFR gives non-invasive, structured, production-grade instrumentation | rare; a C++ game gives you ETW/perf/dtrace or a code-injection plugin |
| Mods ship as loose files and readable-ish JSON | rare; most games ship packed, often encrypted archives |
| Mod code is JVM bytecode with a readable constant pool | rare; native mods are opaque |
| Modding culture tolerates a stranger's tool reading their files | not universal |

The Skyrim and Minecraft equivalents both exist and both required deeper hooks — a script extender
plugin and a mod loader hook respectively. The questions ported. The toolchain would not.

## Is it engine work?

Partly, but there is a distinction I would insist on, because it changes the design space more than it
first appears.

**An engine developer can change the engine. This project cannot change anything.**

Everything Preflight builds must be a *discardable cache beside a system that does not know it
exists*, must fail open, and must never modify the game, the mods, or a save. That is not a
stylistic preference — it is the only way a third party is allowed to operate. And it rules out most
of what an engine developer would do first: you cannot change the asset format, you cannot make the
loader lazy, you cannot add a manifest, you cannot ask content authors to do anything.

What remains is: *precompute exactly what the closed system would have computed, prove it is
equivalent, and hand it over in a form the system already accepts.* That is much closer to runtime and
systems work than to engine work — JIT and AOT caches, prefetchers, CDNs, package caches. The nearest
industrial neighbours are not engines at all:

- **Valve's Steam shader pre-caching** precompiles shader pipelines off-device and ships them so the
  game never compiles at play time. Same shape as prepared textures and prepared audio, at a scale
  that makes the analogy flattering rather than instructive.
- **Android Baseline Profiles** ship a profile so ART can AOT-compile the startup path, reportedly
  ~30% faster execution from first launch. That is the same idea as the Janino bytecode cache and the
  AppCDS work here, done by the platform owner instead of a bystander.

Both are the platform owner doing it with cooperation. Doing it *without* cooperation is the
interesting constraint, and the part with less prior art.

## Prior art, honestly

Most individual pieces here already exist somewhere. Worth writing down plainly, because it is easy to
mistake "I have not seen this" for "this does not exist".

| what we built | what already exists | how this differs |
| --- | --- | --- |
| Startup JFR attribution per mod/file | [Skyrim Load Time Profiler](https://www.nexusmods.com/skyrimspecialedition/mods/173928), [Minecraft Loading Profiler](https://www.curseforge.com/minecraft/mc-mods/loading-profiler-forge-neoforge) | those attribute *which mod is slow*; this asks *what work repeats every launch and could be precomputed* |
| Prepared textures / prepared audio | [Steam shader pre-caching](https://grokipedia.com/page/Steam_shader_cache) | same idea, done by the platform owner with cooperation; here without |
| Janino bytecode cache, AppCDS | [Android Baseline Profiles](https://developer.android.com/topic/performance/baselineprofiles/overview) | same idea, platform-owned and shipped with the app |
| Asset lint: NPOT padding, oversampled audio, long effects | [Unturned asset validation](https://docs.smartlydressedgames.com/en/stable/assets/asset-validation.html) has **Texture NPOT** and "long audio clips with high frequencies are found and logged" | first-party, pre-publication, for content authored against that engine; this is post-hoc, third-party, on already-shipped content |

That Unturned row deserves emphasis rather than burial. Our asset-lint rules independently rediscovered
checks a shipping engine already has. That is reassuring about the *rules* and deflating about the
*novelty*, and both are worth knowing. The rule Unturned's validator does not have is unreferenced-asset
detection — which is also the one this project has now confirmed against the game's own behaviour.

## What we found that does generalize

Three findings here are not really about Starsector.

**1. Lenient parsers silently swallow authoring errors, and nobody has a feedback loop.** This is the
strongest one. Starsector's JSON dialect accepts comments, trailing commas, unquoted keys, single
quotes and numeric suffixes — and a reader consumes exactly one top-level value and ignores everything
after it. So content that sits past the closing brace is *never applied and never complained about*.
In `expsp_beat_msl.proj`, a `PROXIMITY_FUSE` block sits past the top-level value: that missile has no
proximity fuse in game, the author does not know, and nothing anywhere would have told them.

That is a **correctness** defect found by a **performance** tool, and the pattern is everywhere lenient
parsing meets a content ecosystem: YAML with duplicate keys, JSON5, CI configuration, Kubernetes
manifests, game data files. The generalizable claim is not "check Starsector JSON". It is: *wherever a
permissive parser reads third-party content, some fraction of that content is silently dead, and
measuring that fraction is cheap and nobody does it.* Ours was 5 files in 15,353 — a low rate, and four
of them were real defects in popular, released mods.

**2. Per-launch recomputation of things that never change.** 1,169 MB of PCM decoded before the main
menu on every single launch, from files whose bytes have not changed since installation. The general
question — *what fraction of a program's startup is recomputing something it computed identically last
time?* — is answerable for almost any application, and is asked far less often than "what is slow".

**3. Asset conditioning waste in ecosystems nobody conditions.** 786 MB of VRAM in power-of-two
padding, 374 MB of decoded audio recorded above 96 kHz, 285 images stored progressively that decode
~8.75x slower for no benefit on local disk. Engines validate this for first-party content. Nobody
validates it for the ecosystem that grows around a game after release.

## Studies worth running

Written down because they are interesting, not because they are scheduled.

- **A cross-ecosystem redundancy census.** What share of startup is recomputation, in Minecraft, in
  Skyrim, in a modded Unity game? The method ports even where the tooling does not.
- **Dead-config prevalence across ecosystems.** Cheap to measure, never measured. We have a pilot
  number for one game and one profile.
- **Asset conditioning waste as an ecosystem property.** Is the padding-and-oversampling profile here
  typical of modding communities, or an artifact of this one?
- **How often do default profiler settings hide the thing being looked for?** We have one spectacular
  data point (36.5 MB → 0 bytes). The general version is a real question about tooling defaults.

## The case against generalizing

This section exists because the honest answer to "can this be generalized?" includes a reason not to
rush.

Whatever value this project has so far came from being ruthlessly specific and from correcting itself.
The published headline number has been wrong twice, in public, in this repository, and both times the
correction came from taking one concrete profile seriously. A generalized framework — a plugin
architecture, an abstraction over "games", a runtime-agnostic probe interface — would have made both
of those bugs *harder* to find, because the specific thing that exposed them was comparing a number
against one real installation somebody actually plays.

Generality also has a way of arriving before evidence. The right sequence is the one this repo already
committed to in [ADR 0001](adr/0001-measurement-first.md): measure, then build. The generalized version
of that is: **find a second real target that actually needs this, and let it force the abstraction.**
Do not build the abstraction and then look for a target.

## What that suggests

- **Generalize the writing, not the code.** The epistemics and the seam thesis are worth stating
  clearly and are useful to anyone. The code should stay specific until a second target demands
  otherwise.
- **The dead-config finding is the most portable thing here** and the one most worth writing up on its
  own terms.
- **The most defensible framing of this project is not "game optimizer".** It is: *optimizing and
  auditing a system you are not allowed to modify, from the outside, without breaking it.* That framing
  is what makes the fail-open rule, the read-only rule, and the equivalence proofs load-bearing rather
  than decorative — and it is a category with more prior art in systems than in games.
