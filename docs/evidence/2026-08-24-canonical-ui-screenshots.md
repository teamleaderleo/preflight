# Canonical UI screenshots: 2026-08-24

This note binds the six public screenshots to the accepted frontend after #1105 made the Home ship,
time, motion, and support controls direct and responsive.

## Accepted frontend source

- accepted `main`: `035102f092a134ede9fb9ebc74319db8a1c42a63`;
- accepted tree: `19399e879b65c85125f91d88d1851a2480f55907`;
- equivalent reviewed PR head/tree: `15950d7d8ea403043fb3cb394e71848758e63aa9` / `19399e879b65c85125f91d88d1851a2480f55907`;
- Source Boundary run: `32652729425`, success;
- CI run: `32652729641`, success;
- Desktop application run: `32652729519`, success;
- frontend artifact: `preflight-desktop-frontend-32652729519` / `9496639767`;
- artifact digest: `sha256:35b565e96486fb6b20f96967821ca058182a78a8a837e0aff95bd8524b570bc2`.

The artifact's internal manifest verified nine files and 718,186 bytes before capture. The accepted
`main` tree and reviewed PR-head tree are identical. The screenshot carrier changes only the
documentation images, their reviewed source-boundary identities, and the capture helper.

The minimum-window Ready image was refreshed once more from PR #1107 after its responsive Home
controls kept the words `Hide time` and `Hide ship` visible at the supported 720×560 size:

- base `main`: `6a643d17d0d2ee9ec1975cd1ece887c97f061619`;
- reviewed head: `89da2cc21d3e0ddc9b748faa948cbb3ab8d021ee`;
- Desktop application run: `32654586077`, success;
- frontend artifact: `preflight-desktop-frontend-32654586077` / `9497109591`;
- artifact digest: `sha256:0ddd5da51a42f6cbdc872aa830ffd34652ce47861af0c257c95b0649c1604133`.

That artifact's internal manifest verified nine files and 718,299 bytes before capture.

## Canonical capture set

| Image | State | Window | Theme |
| --- | --- | --- | --- |
| `docs/images/desktop-home-light.png` | settled Home | 1040×700 | Blueprint / light |
| `docs/images/desktop-home-dark.png` | settled Home | 1040×700 | Blueprint / dark |
| `docs/images/desktop-profiles-light.png` | Mods / saved profiles | 1040×700 | Blueprint / light |
| `docs/images/walkthrough-setup.png` | no installation selected | 720×560 | Blueprint / light |
| `docs/images/walkthrough-ready.png` | settled Home | 720×560 | Blueprint / light |
| `docs/images/walkthrough-benchmark.png` | Benchmark before a run | 1040×700 | Blueprint / light |

The browser context fixes locale, timezone, theme, palette, device scale, and reduced motion. It
waits for the page's actual target controls, rejects page and console errors, and captures only the
viewport. Chromium's implicit `/favicon.ico` request is answered inside the capture context because
the packaged Tauri frontend neither requests nor ships a browser favicon. Product resource errors
still fail the capture.

## Captured image identities

| Image | Pixels | Bytes | SHA-256 |
| --- | ---: | ---: | --- |
| `docs/images/desktop-home-dark.png` | 1040×700 | 276,824 | `e93f31c9dba418416848def8d5644ebd92753120319e3949eddb9695618124e6` |
| `docs/images/desktop-home-light.png` | 1040×700 | 225,729 | `896be46058c0cd3efc6e9724c91ce6da7f6a0bee2489268ec76ef11155c74a93` |
| `docs/images/desktop-profiles-light.png` | 1040×700 | 145,567 | `4b4140268eddd2ff94fa7d0338e535265ecafb52fe6840ca665f0890bce55366` |
| `docs/images/walkthrough-benchmark.png` | 1040×700 | 135,908 | `ab50968c284585cc63f6398b252716d42db0a284a36eb7f7c4d1010949d2c6ca` |
| `docs/images/walkthrough-ready.png` | 720×560 | 126,779 | `a038a648e11f523a3408b62685da711aa8b9a5966be0c3eb381332b45509c2a8` |
| `docs/images/walkthrough-setup.png` | 720×560 | 124,928 | `485239763fbfaaf0d6a6c595bb8c1893fca5c79b58856a0f983e4da0dc330bbd` |

All six images were inspected at original resolution. The settled Home captures show the accepted
ship selector, playtime and ship visibility controls, launch action, installation identity, and
motion control. The minimum-window captures remain contained at 720×560. The benchmark image stays
in its pre-run state, so fixture timing cannot be mistaken for a measured result. The minimum-window
Ready image keeps the time and ship controls labelled instead of relying on icons alone.
