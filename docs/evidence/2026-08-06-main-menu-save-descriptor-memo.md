# Main-menu save-descriptor compatibility memo

Date: 2026-08-06

Install: Starsector 0.98a-RC8, current 83-mod profile, macOS on Apple M5, bundled x86-64
Zulu 17 under Rosetta, default balanced texture policy, `--fast`

## Finding

A full-depth JFR recording separated the remaining public startup interval into roughly 1.7 seconds
before `AppDriver`, 12.8 seconds in `ResourceLoaderState`, and 2.6 seconds constructing the title
screen. The earlier apparent 4.8--5.9-second pre-resource gap was a clock-origin artifact: the
startup-phase timer begins about 4.76 seconds before the launch harness's game-log anchor.

The title-screen sample exposed one avoidable duplicate. Vanilla calls
`CampaignGameManager.Ö00000(): boolean` twice while constructing the main menu. Each call builds an
XStream instance and deserializes the current save's approximately 113KB `descriptor.xml` merely to
compare `saveFileVersion` with `0.6` and decide whether Continue should be enabled.

Preflight now lets the first call execute untouched, fingerprints all three descriptor candidates,
and memoises the exact boolean only for the current JVM session. The second identical call reuses
that result. It does not persist the result between launches and it does not replace XStream with a
partial XML/tag scan.

## Live result

| launch | main menu | transform cache | descriptor memo | result |
| --- | ---: | --- | --- | --- |
| session memo implementation gate | 17.174s | cold | 1 miss, 1 record, 1 hit | ACTIVE |
| final cold correctness gate | 17.548s | cold | 1 miss, 1 record, 1 hit | ACTIVE |
| unchanged-binary warm gate | **16.21s** | **41/41 hits, 0ms** | **1 miss, 1 record, 1 hit** | **ACTIVE** |

The final warm run hashed 338,835 bytes across the three exact descriptor candidates, reported zero
fingerprint failures, applied all 41 transformations, and reported zero decline or contained
failure. GraphicsLib followed its normal compact path: 6,184/6,184 normal-cache hits, zero fallback,
and no generated-map cleanup storm.

Runs:

- `save-descriptor-session-memo-20260806-074003`
- `session-memo-final-cold-20260806-074202`
- `session-memo-final-warm-20260806-074405`

## Rejected cross-launch variant

An initial exact-content implementation persisted the boolean across launches. Its cold gate was
correct, but both the immediate warm gate and a cooled retry reached the title screen roughly half a
second earlier and triggered GraphicsLib's pathological full generated-map buffer cleanup. The
resource phase itself remained normal, but startup exceeded two minutes.

The first vanilla descriptor parse was accidentally providing an ordering delay for GraphicsLib's
background texture state. That cross-launch shortcut was therefore removed completely. This is a
causal safety boundary, not a cache-invalidation limitation: even a byte-perfect persisted result is
unsafe because it changes first-call ordering.

Rejected runs:

- `save-descriptor-cold-20260806-073322`
- `save-descriptor-warm-20260806-073408`
- `save-descriptor-warm-cooled-20260806-073741`

## Safety boundary

The rewrite binds the shipped core archive, exact class SHA-256, class-file version, app loader, and
reviewed four-return method shape. A game update or bytecode drift declines the transform and keeps
vanilla. Lookup failures, non-regular candidates, concurrent descriptor changes, and hashing errors
all return a normal miss and execute vanilla. The fingerprint includes the contents and exact state
of `descriptor.xml`, `descriptor.xml.bak`, and `descriptor.xml.inprogress`, with before/after file
attributes guarding concurrent writes. Session startup clears the memo unconditionally.
