# Verification strategy: what can be proved without the game, and with what

Surveyed 2026-07-26.

## Why this doc exists

The project has been moving fast enough that several findings were established in conversation, acted
on, and never written down. This is the case file: what was checked, what was found, what was decided,
and what the alternatives were. It is deliberately more than a decision record, because the reasoning
behind a rejected option is the part that gets re-litigated when nobody wrote it down.

It covers the verification boundary — which claims can be proved by machine, which need the reviewed
installation, and which need a person looking at something — plus a survey of the tooling that might
have moved that boundary and mostly does not.

## The tier map

Three tiers, ordered by what they need to run. The boundary between B and C is the only interesting
one, and it is not where it looks.

| tier | needs | covers | where it runs | status |
|---|---|---|---|---|
| A | JVM only | agent injection, bytecode transformation, cache hit/miss, fail-open fallback, index and lookup, all IO formats | anywhere, including the Linode VPS | automated |
| B | a real GPU, no display, no game | whether a driver reads the blocks preflight writes | Modal; not Lima, not the VPS | automated |
| C | the reviewed installation | the real `TextureLoader`, preloader handoff timing, "no visible corruption" | the operator's machine | partly manual |

**Tier A** is `preflight-synthetic-startup` plus the synthetic Starsector in the test tree —
`com.fs.graphics.TextureLoader` carrying the real obfuscated method names (`Ô00000`, `o00000`), along
with `com.fs.graphics.L` and `com.fs.starfarer.loading.A`, driven by `SyntheticTextureLauncher` **in a
child JVM** so the agent is genuinely injected rather than simulated.

**Tier B** exists because every Tier-A harness stops at the byte level. The stub loader counts calls
and returns arrays; it never calls `glCompressedTexImage2D`. For the prepared-pixel cache that was the
whole contract — the promise was bytes. For the block cache it is not, and a wrong internal-format
constant, a wrong mip-level order or a wrong row order would pass every test in the repo and fail only
on a player's machine. See [asset-quality-track.md](asset-quality-track.md) §"What can be tested
without launching Starsector" for how that gap was closed.

**Tier C** is smaller than it looks, and shrinking. The
[main-menu comparison contract](evidence/2026-07-23-prepared-pixel-main-menu-comparison-contract.md)
already automates readiness detection, phase timing, profile-fingerprint guarding and log
classification. What still requires a person is two acceptance lines:
`automaticDetectionVisuallyAccepted` and *"no visible corruption."*

What structurally cannot move out of Tier C: `starfarer_obf.jar` is not redistributable, so it cannot
be shipped to a VPS or baked into a container image. The synthetic is a model of the engine, not the
engine. Two known risks it cannot reproduce are the real `TextureLoader`'s two independent
power-of-two implementations (already the blocker on padding removal) and the asynchronous
image-preloader handoff timing.

Note the qualifier though: Tier C needs the *installation*, not necessarily a *launched game*. The
offline installed-class contract checker from PR #119 reads real class bytes and verifies the
transformation without starting anything. That pattern should extend to the block path.

## Infrastructure topology, and why Modal specifically

Three machines are available and they are not interchangeable.

- **Linode VPS** — no GPU. Tier A only. Already wired: [vps-verify.yml](../.github/workflows/vps-verify.yml)
  registers a self-hosted runner (`runs-on: [self-hosted, linux, starsector-preflight]`), runs the suite
  in rootless Podman under 768 MiB / 0.85 CPU / 512 PIDs, and is triggered by a `/vps verify` PR comment
  gated to repo-owner comments on same-repository heads. Setup lives in
  [bootstrap-vps-runner.sh](../scripts/bootstrap-vps-runner.sh) and
  [configure-vps-runner-service.sh](../scripts/configure-vps-runner-service.sh).
- **Lima VM** — no real GPU. A GL context there resolves to Mesa llvmpipe, which the conformance probe
  correctly refuses to classify as hardware. Useful for Linux-behaviour checks, useless for driver
  conformance.
- **Modal** — the Tier-B host. The block conformance probe has run on a Tesla T4 for a fraction of a
  cent per run.

The Modal choice deserves recording because it looked arbitrary and is not. A
[2026 survey of agent sandbox providers](https://www.startuphub.ai/ai-news/artificial-intelligence/2026/daytona-vs-e2b-vs-modal-vs-vercel-sandbox-2026)
puts Daytona ahead on cold start (~90 ms p99) with E2B close behind (~150 ms, Firecracker microVMs),
and Daytona and E2B at rough price parity around $0.0504/vCPU-hour. On the axis that matters here they
are all equivalent, because **Modal is the only one of them that exposes a GPU inside the sandbox.**
Cold-start latency is irrelevant to a job that compiles a C file and uploads four textures; GPU access
is the entire requirement. Any future migration has to re-check that one property first.

## Cross-repo findings

Surveyed `teamleaderleo/renderprove` and `teamleaderleo/smolrunner` for reusable parts.

### SmolRunner: the problem is already hand-solved here

SmolRunner is pre-alpha — `doctor`, `plan`, `host plan`, all read-only, with runner installation and
reconciliation still roadmap work. Its problem domain (self-hosted GitHub Actions runners on ordinary
Linux boxes, rootless Podman, cgroup limits, disposable execution) is precisely what `vps-verify.yml`
and the two bootstrap scripts already do by hand for this repo. Adopting SmolRunner would be about
*maintaining* that setup, not extending its reach. No verification capability is gated on it.

### RenderProve: convergent evolution on renderer identity

RenderProve's repeatability probe launches five fresh worker containers, digests a `worker.json`
recording image ID, Chromium version, OS, kernel, architecture, Node version, locale, timezone and a
digest of the sorted font inventory — and **fails if the renderer identity changes between runs, as a
separate assertion from whether the pixels changed.**

The GPU capability probe here prints `preflight-renderer-class: hardware|software` and exits 3 rather
than 0 on a software run, because the first hosted run reached a healthy T4 and silently rendered on
Mesa llvmpipe: libglvnd finds drivers through ICD manifests that the NVIDIA installer normally writes
and a container never runs.

Two projects, no shared code, same conclusion: *the identity of the thing that produced the image is
evidence, and asserting it has to be separate from asserting the image.* Both arrived there by being
burned, which is the usual way.

**What transfers:** the receipt shape. RenderProve has `schema/receipt-v1.schema.json`, a documented
compatibility rule ("consumers must reject unsupported receipt versions; adding or removing fields
requires a new version rather than silently widening v1"), and canonical-JSON digests. Preflight writes
`gpu-capability-report-<timestamp>.txt` with a JSON line at the end and prose in `docs/evidence/`. The
prose is good and should stay. The machine-readable half should be a versioned schema that a consumer
rejects on mismatch, matching the framing discipline already used for `SPFT`/`SPFM`/`SPFI`/`SPFB`/
`SPFC`/`SPFV`.

**What does not transfer:** everything else. RenderProve is Chromium-and-DOM-shaped — its manifest is
routes × viewports, its evidence is navigation status, console messages and page facts. Starsector is
not a page. Modelling a GL window as a route would be a worse fit than writing the small thing
directly.

### The ΔE question: which way does the code flow?

RenderProve's own "current limits" list includes:

> screenshot equality is byte-exact, with no perceptual tolerance
> no baseline approval or replacement

Preflight already has the first one. `TextureFidelity` implements alpha-weighted CIELAB ΔE (CIE76) with
`JUST_NOTICEABLE = 1.0` and `OBVIOUS = 2.0`, and `Report.perceptuallyLossless()`. That is exactly the
perceptual tolerance RenderProve names as a next slice.

So the answer to "do we write a library" is: **not yet, and probably not a general one.** The options,
recorded so this is not re-argued:

1. *A published shared library.* Rejected for now. Two consumers, one of them pre-alpha, and the
   Java/Node split means it would have to be reimplemented rather than depended on. A shared library
   with one real user is a maintenance obligation bought with no leverage.
2. *Copy the algorithm into RenderProve.* Viable and cheap. CIE76 ΔE is about forty lines. Both repos
   are Apache 2.0, so the licensing is a non-issue. This is the right move *when RenderProve actually
   reaches that slice*, not before.
3. *Do nothing.* Correct today.

One caveat that should travel with the algorithm whenever it does: **for rendered and compressed
images, [FLIP](https://dl.acm.org/doi/10.1145/3406183) is the better metric than either CIE76 or SSIM.**
It weights edge regions, where minor variations are most perceptible, and models viewing conditions
(display resolution, observer distance) in a perceptually uniform space. Block-compression artifacts
live almost entirely on edges — the blocky discontinuities BC1 produces at 4×4 boundaries — which is
precisely where CIE76 underweights them. This is a real accuracy upgrade path for `TextureFidelity` and
is currently unbuilt. Worth noting: every fidelity number this project has published (ΔE 0.80 on real
art, the 0.206/0.439 driver-disagreement pricing) is therefore mildly *optimistic* about edge artifacts,
though not in a way that changes any decision taken so far.

## The gap no metric can see

The strongest argument for building a visual artifact is not the game window. It is this, from
[BlockCacheBaker.java](../preflight-cli/src/main/java/dev/starsector/preflight/cli/BlockCacheBaker.java):

```java
boolean shaderMap = path.contains("/shaders/") || path.contains("_normal")
        || path.contains("_material") || path.contains("_surface")
        || path.contains("_glow") || path.contains("/maps/");
```

A filename-substring heuristic decides which textures are too precision-sensitive to compress, applied
across ~70 mods that share no naming convention. It will misfire in both directions, and **ΔE cannot
see either failure**:

- A normal map that slips past the filter gets BC1-encoded. Normal maps are smooth, so its ΔE is
  *excellent*. It is still wrong, because normals need numerical precision rather than perceptual
  similarity — the metric reports "fine" in exactly the case where it is not measuring the right thing.
- Ordinary art whose filename happens to contain `_glow` is skipped, costs VRAM for nothing, and
  produces no number at all, because skipped textures are never measured.

Neither appears in anything the baker currently prints. The only way to catch them is to look at the
image alongside its classification — and "is this a normal map or is it art?" is a question a
vision-language model answers well and a metric answers not at all.

This motivated `assets contact-sheet`, which is now built: it renders each sampled texture as
`source | reconstruction | difference`, captioned with logical path, dimensions, chosen format, ΔE p99
and mean, and the baker's disposition (`cached`, `over gate`, `shader map`, `unreadable`). Three payoffs
from one artifact:

1. The Tier-C "no visible corruption" check becomes glancing at one image rather than launching a game.
   A swapped channel or a wrong row order is obvious in the reconstruction column.
2. The report carries a `panelsSha256` that pins in CI — RenderProve's convergence trick applied to a
   cache instead of a page.
3. It is the input a VLM can audit for classifier misfires.

It needs no GPU, no display and no game, so it runs on the VPS.

Four decisions inside it are worth recording, because each had a plausible opposite:

- **The difference column reduces by maximum, not average.** Everything else in this project averages
  when it shrinks an image, because averaging is what preserves an image. This panel is not preserving
  an image, it is hunting a defect, and a four-pixel artifact on a 2048-square texture survives an
  average at roughly a 256th of its strength — invisible — and survives a maximum intact.
- **Nothing is ever enlarged.** Textures smaller than a panel are centred at 1:1. Invented pixels in an
  artifact whose purpose is spotting artifacts would be the wrong kind of help.
- **Shader maps are hatched rather than rendered.** Showing what one *would* have looked like encoded
  costs nothing and would quietly imply the number beside it meant something. The policy says it does
  not, so the panel says so too.
- **The digest covers panel data, not the PNG.** Captions are drawn with a platform font, so two
  machines can produce different file bytes from identical findings. The digest covers exactly what the
  sheet is evidence about.

The difference panel is drawn from `TextureFidelity.deltaEMap`, added for this purpose, so the picture
and the gate are the same measurement. A difference image drawn from a second, similar-looking metric
would disagree with the number printed beside it, and the disagreement would be invisible.

Likewise `TextureKind` was lifted out of `BlockCacheBaker` so both consumers share one classifier. Two
copies of that rule would let the sheet certify a decision the baker never made — which is the specific
way this kind of artifact goes wrong.

## Tooling survey

Checked because "there is probably something off the shelf" is usually true and was worth confirming.

| tool | what it is | verdict |
|---|---|---|
| [Percy](https://percy.io/blog/visual-screenshot-testing) / Chromatic / Applitools | hosted visual regression, DOM-anchored | wrong shape — the artifact under test is a texture cache, not a page |
| [Unreal Screenshot Comparison](https://dev.epicgames.com/documentation/en-us/unreal-engine/screenshot-comparison-tool-in-unreal-engine) | golden images, per-build history, tolerance, integrated with the [Automation Test Framework](https://dev.epicgames.com/documentation/en-us/unreal-engine/automation-test-framework-in-unreal-engine) | closest conceptual prior art; lives inside Unreal and cannot be extracted |
| odiff / pixelmatch | fast byte-ish image diffing | strictly weaker than what `TextureFidelity` already does |
| [FLIP](https://dl.acm.org/doi/10.1145/3406183) | perceptual difference evaluator for rendered images | **genuinely better than the current metric**; see above |
| Khronos VK-GL-CTS / piglit | the real driver conformance suites | answer "is this driver conformant", not "is this cache right"; worth knowing they exist |
| AltTester | open-source Unity test automation; Unreal support announced but not production-ready as of mid-2026 | not applicable |

Conclusion: nothing off the shelf fits, and the reason is structural rather than incidental. Every
mature tool in this space assumes the thing under test is a *rendered frame from an engine it controls*
or a *page in a browser it controls*. The artifact here is a block-compressed texture cache that no
existing tool has a concept of.

## Agent-harness landscape

Surveyed separately, since the recent generation of agent harnesses is a different category from visual
regression tooling and might have applied.

Most of that ecosystem — browser-driving agents, GUI agents, computer-use harnesses — is built to
*operate* an interface. That is not the problem here. Nothing in this project needs a thing that clicks;
the interfaces involved are a CLI and a GL context, both of which are already scriptable. Importing a
browser-based agent harness would add a driving layer over something that does not need driving.

The slice that *is* relevant is **VLM-as-judge**: using a vision model to evaluate an image against a
checklist rather than against a reference digest. That pattern is now well established — WebVoyager uses
GPT-4V to judge task completion from screenshots in place of rule-based heuristics, and VisualWebArena
uses multimodal models to assess open-ended visual tasks. The
[established practice](https://arxiv.org/pdf/2510.09724) is instructive and matches what is wanted here:
a reference image for the correct output, a candidate image under the same input state, and **an
explicit checklist of properties that must be visible and correct** — with the VLM judge *supplemented
by programmatic assertions wherever the expected outcome can be expressed as a predicate*.

That last clause is the design rule to keep. Applied here it partitions cleanly:

- **Programmatic** (already built, keep authoritative): ΔE thresholds, block-length validation against
  format and dimensions, manifest/blob agreement, codec version, driver byte agreement.
- **VLM judge** (the genuine gap): "does this texture's content match the category the classifier
  assigned it?" — a question with no predicate form, because it is about what the image *depicts*.

So the harness to build is not an agent harness. It is one deterministic artifact — the contact sheet —
that a VLM can be pointed at, with every mechanically checkable property still checked mechanically. The
model is a second opinion on the one question the machine cannot phrase, and it never gates a build on
its own.

Sandbox providers were surveyed in the same pass; see the topology section above. The short version is
that the GPU requirement decides it and nothing else is close.

## Decisions taken

- Keep both conformance vectors. The synthetic one is deterministic and therefore answers questions
  *about a driver*, where two machines' results must be comparable. The cache vector answers whether
  *this cache* survives *a real driver*. Same probe, different questions.
- Do not adopt RenderProve or SmolRunner into this project. Lift the receipt-versioning discipline;
  leave the code where it is.
- Do not extract a shared ΔE library. Revisit as a forty-line copy into RenderProve when that project
  reaches perceptual comparison.
- Build `assets contact-sheet` as the next verification artifact. **Done**; see
  [asset-quality-track.md](asset-quality-track.md) §"The classifier is now visible".

## The verification gap this document did not have, found the day it was written

Worth recording in full, because it is an instance of exactly what the tier map is about, and because
the tier map did not catch it.

`main` had been failing CI since PR #179 — **seventeen commits**, including the four that this document
was written to summarise, all of which were reported at the time as "green on the full suite." They were
not. The reports were based on `mvn test`, which runs surefire and **not** failsafe, so the entire
integration-test tier was never executed locally. The correct command is `mvn verify`.

The failure itself is a small, precise thing. `TexturePreparedPixelRuntime` serves a prepared texture
directly only when it needs no padding — every NPOT texture stays on Starsector's original path unless
the coherent-direct diagnostic is on. The default integration fixture was one pixel square, which
satisfied that rule *only by accident*: the agent's own next-power-of-two returned 1 for a one-pixel
edge. PR #179 replaced that with Slick's `get2Fold`, which floors at two — the arithmetic the installed
loader actually uses — so the fixture became a 2×2 upload with nine bytes of padding and the agent began
declining it.

Correctly. And **silently**, because falling open is precisely what it is designed to do. Two tests went
on asserting a bypass that had stopped happening.

Three things follow, and all three are about this document's subject rather than about one bug:

1. **Fail-open is a verification hazard, not only a safety property.** The project's central design
   commitment — never break the game, always fall back — means a defect in the fast path degrades into
   correct-but-slow behaviour. Nothing throws. Nothing logs an error. The only signal is a counter
   (`hits: 0`, `npotProbeFallbacks: 1`) that no test was reading. Any future consumer of the block cache
   will have exactly this property, so the block cache needs an assertion on *the cache being used*,
   not merely on the output being right.
2. **A test can pass for a reason that is not the reason it was written.** The 1×1 fixture exercised the
   warm-hit path because of an off-by-one, not because 1×1 is power-of-two-shaped. When the off-by-one
   was fixed the test told the truth and looked like a regression.
3. **The synthetic harness models the engine, and models drift.** The stub `com.fs.graphics.TextureLoader`
   still carries the pre-#179 padding rule in three places. It is harmless today, and it is the concrete
   version of the caveat in the tier map above: the synthetic is a model of the engine, not the engine.

The diagnosis took a `git bisect run` over the seventeen commits with the single failing IT as the
predicate, then reading the adapter report the agent already writes — which named the cause outright
(`"status": "insufficient-original-buffer"`, `sourceBytes: 3`, `uploadBytes: 12`). The instrumentation
was adequate; nothing was looking at it.

One wrong turn, recorded because it was tempting and plausible: the first attempt fixed the *stub's*
padding rule instead. That took the failures from two to four, because the stub's arithmetic was never
what the agent was objecting to. Reverted.

## Open questions

- Whether FLIP should replace CIE76 in `TextureFidelity`, and whether any published number changes
  materially if it does.
- Whether the PR #119 offline installed-class contract checker pattern extends to the block upload path,
  which would move part of Tier C into Tier A.
- ~~Whether the shader-map classifier should stay a filename heuristic at all, or become content-based
  once the contact sheet shows how often it misfires.~~ **Answered 2026-07-26: it stays.** On 24
  textures sampled from the real 72-mod profile the filename heuristic was correct every time. The
  convention holds because it is machine-enforced — 4,926 of the 11,000 shader maps are generated into
  GraphicsLib's `cache/` directory by GraphicsLib itself. Revisit only if a sheet ever shows a misfire.
  See [2026-07-26-first-real-profile-run.md](evidence/2026-07-26-first-real-profile-run.md).
- ~~How `meanDeltaE` and `p99DeltaE` should be reconciled.~~ **Answered 2026-07-26: the mean was
  fixed.** It is now `weightedSum / visible`, putting it on the same coverage-scaled footing as p99,
  max and both fractions. A no-op for fully opaque images, and cache membership is unchanged in every
  figure, because the gate reads p99. Gate tuning is unblocked.
- ~~Whether the synthetic `TextureLoader` stub should be corrected to Slick's `get2Fold`.~~
  **Answered 2026-07-26: corrected, and the independence kept.** The stub now models both of the
  installed loader's power-of-two implementations separately — an extracted `get2Fold` sizing the
  allocation, and an inlined copy sizing the upload buffer — with the arithmetic written out rather
  than delegated to `GpuTextureFootprint`, so the fixture is still able to disagree with production.
  Two integration expectations changed, because they encoded the old fixture's belief that a
  one-pixel edge uploads as one pixel.
- Whether any runtime consumer of the block cache should assert on cache *use* rather than only on
  output correctness, given that fail-open makes a broken fast path indistinguishable from a slow one.
