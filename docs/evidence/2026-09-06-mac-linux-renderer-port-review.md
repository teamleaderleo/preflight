# Mac/Linux Fast Rendering port review, 2026-09-06

## What was already done

The existing prior-art review already examined `jontyab/starsector-render` at its then-v0.7.7
generation, including the Mac/Linux symbol mappings. It counted 49 divergent symbols among 85
and confirmed Preflight's Mac image/audio decoder and AlphaAdder findings. Treating the fork as
newly discovered was incorrect. Those mapping and PathCache observations are historical source
review, not proof of the current port's runtime behavior.

A targeted search of local docs and retained benchmark text/JSON/log files found that earlier
review but no identifiable Mac/Linux combined-run receipt naming this fork. This is a retrieval
limit, not proof that the user never ran it. The latest Windows comparison retained Preflight alone
as the faster first-interactive-menu configuration; do not generalize that to Mac/Linux or gameplay
FPS. Earlier graphics-preload observations use a different endpoint.

## Current source inspection

Inspected the fork's `master-asm` at
`df21df8196fe3f759f5a09cc8c570610a519637c` through GitHub's read-only API. Its agent identifies
itself as Fast Rendering v0.8.7. The release list includes `v0.8.7-port-6a226999` with Mac/Linux
packages; the branch commit and release artifact are separate identities, and no release bytes
were downloaded or admitted by this review.

- Both scripts load `fr.agent.jar` and put `fr.jar` on the classpath. The Mac script runs through
  the app's normal shell-launch path; a Windows batch file is not required.
- Mac/Linux mapping files remain in the repository. This is not evidence that every current
  transformation is equivalent across platforms.
- The current texture loader is under `com/genir/renderer/overrides/loading/textures/TextureLoader`.
  Preflight's existing `FastRenderingPreparedTexturePlan` pins the 0.8.4 class under
  `com/genir/renderer/overrides/loading/TextureLoader`, its exact hash, descriptor and archive.
  That existing bridge cannot match the differently named current class as-is.
- `LaunchOwnership.detect` reads the selected script and recognizes its `fr.jar` reference, so
  detection is not inherently Windows-only. This source check does not prove that automatic
  discovery always selects the intended Linux script; that needs a fixture for the actual layout.

Primary sources: [pinned fork](https://github.com/jontyab/starsector-render/tree/df21df8196fe3f759f5a09cc8c570610a519637c),
[releases](https://github.com/jontyab/starsector-render/releases).

The checked `/Applications/Starsector.app` had neither FR JAR nor FR reference in its selected
script, and no game process was running. No installation, launch, settings change, build or
benchmark was performed for this review.

## Decision for Mac and Linux

Keep the current Preflight configuration. Do not install FR by default, weaken exact gates, or
claim that the new port is covered by the old 0.8.4 integration. Also do not label it Windows-only.

The next useful engineering slice is a pinned-port compatibility review: select actual Mac/Linux
release artifacts, map agent order and replaced classes, check launcher selection with small
fixtures, then decide whether a new prepared-texture bridge preserves DDS handling, image
consumers, buffer lifetime, upload ownership and fallbacks. Recheck the historical case-folding
concern on that selected generation. Coordinate this with the existing FR research lane #1153.

Only after that source/fixture work should an attended native correctness run test startup,
rendering and exit. A performance campaign is a separate decision: use the existing 83-mod setup
and `processStartedAt → mainMenuInteractiveAt` clock, and distinguish startup from gameplay FPS.
The user's actively used Mac is unsuitable for attributing small timing differences now. Existing
Mac prepared audio and texture fixes remain useful independently of this optional renderer.
