# Startup native redraw cadence

Date: 2026-08-06

Install: Starsector 0.98a-RC8, current 83-mod profile, macOS on Apple M5, bundled x86-64
Zulu 17 under Rosetta, default balanced texture policy, `--fast`

## Finding

Once resource reconstruction fell below two seconds, vanilla's loading screen became a material
part of the resource loop. `ResourceLoaderState.init` advances its progress bar for every ten units
of resource weight. On this profile that called `renderProgress(float)` **2,618 times**. Every call
sets up the projection, draws three sprites, restores graphics state, and calls LWJGL
`Display.update()`.

The rate has no relationship to elapsed time. The adjacent baseline completed the post-SpecStore
resource interval in 5.941 seconds, so it was asking the native display for roughly 440 updates per
second.

Preflight now admits one progress render every 16.667ms and explicitly invokes the original method
with `1.0f` before the resource executor shuts down. Data loading, iteration order, progress-weight
arithmetic, close-request checks, audio tasks, and the original render method are unchanged.

## Live result

| launch | attempted | rendered | skipped | SpecStore return to progress 100 | main menu |
| --- | ---: | ---: | ---: | ---: | ---: |
| adjacent baseline | 2,618 | 2,618 | 0 | 5.941s | 17.79s |
| 60Hz gate | 2,619 | 200 | 2,419 | **4.542s** | **17.09s** |
| adjacent warm gate | 2,619 | 202 | 2,417 | **4.725s** | **16.68s** |

The exact resource interval improved by 1.399s and 1.216s. The two whole launches are supporting
evidence; their mod callbacks varied independently. Both reached the main menu, stopped normally,
and reported ACTIVE adapter health, 40 exact transformations, zero decline or contained failure,
55,359 resources, 4,479 prioritized resources, and no priority comparison mismatch.

Runs:

- `graphics-refresh-cadence-v3-warm-20260806-065940`
- `progress-render-60hz-20260806-070453`
- `progress-render-60hz-warm-20260806-070538`

## GraphicsLib's nested pumps

The same investigation found GraphicsLib calling `Display.processMessages()` inside three bounded
startup loops. Exact attribution measured 733 calls / 93ms in the texture CSV, 605 / 68ms in the
first texture traversal, and 93 / 16ms in compact normal replay: **1,431 calls and 177ms**.

Its exact 1.12.1 replacement now uses cadences of 500 rows for the CSV and traversal and 1,000
entries for replay. A live gate made 27, 12, and 9 calls respectively—48 total—and measured 17ms
inside them. All 6,184 generated-normal paths remained journal hits with zero fallback, root,
journal-read, or journal-write failure. GraphicsLib completed its normal application callback and
VRAM setup on every gate.

This is a smaller claim than simply subtracting 160ms from wall time: processing native messages
can move some OS work to a later pump. The exact native-call reduction and unchanged callback
outputs are established; launch noise is larger than the remaining callback delta.

## Safety boundary

The loading-screen rewrite is composed into the existing exact `ResourceLoaderState` target. It
requires the shipped class, archive, app loader, one `renderProgress(float)` method, exactly four
original init call sites, and exactly one executor shutdown. Drift declines the rewrite and keeps
vanilla. The limiter is enabled only with the prepared-texture/`--fast` target set, always admits
the first render and explicit final 100% render, and never suppresses game-state or resource work.

GraphicsLib is independently bound to its exact mod class and archive. Its transform proves all
five reviewed modulus/message-pump sites and the final transformed SHA-256. A changed GraphicsLib
version keeps its own implementation.
