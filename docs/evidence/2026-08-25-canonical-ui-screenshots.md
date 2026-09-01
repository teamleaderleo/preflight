# Canonical UI screenshots: 2026-08-25

This note binds the six public screenshots to accepted `main` after the first-launch, Home, Hangar,
recovery, and support-copy polish through #1133.

## Accepted frontend source

- accepted `main`: `9ef75b37fcf10c76676b702089787bc2f555e542`;
- accepted tree: `eaa8d8aee8171b39343086e79effac4e4de15d2c`;
- accepted `preflight-desktop` subtree: `58c3b5ed3d49ba7eb3dd5cf35d8b73a3eacbed68`.

The frontend was built in a detached worktree at that exact accepted revision. The repository capture
helper then drove its preview with the pinned Playwright 1.55 / Chromium environment. This local
documentation capture does not claim a hosted artifact or candidate-package identity.

React Activity now retains the hidden Home tree after navigation. The first capture attempt exposed
an ambiguous page-wide `Main campaign` locator on the Mods page; the helper now binds that readiness
check to the visible `.profiles-page` before taking the screenshot.

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
waits for the destination controls, rejects page and console errors, and captures only the viewport.
Chromium's implicit `/favicon.ico` request is answered inside the capture context because the
packaged Tauri frontend neither requests nor ships a browser favicon. Product resource errors still
fail the capture.

## Captured image identities

| Image | Pixels | Bytes | SHA-256 |
| --- | ---: | ---: | --- |
| `docs/images/desktop-home-dark.png` | 1040×700 | 330,112 | `9357dddbe3e49fe7be5e4e5adf94fa498dabad6173497eebce175e89d9f039ad` |
| `docs/images/desktop-home-light.png` | 1040×700 | 271,531 | `5cf3fdc978073f2a004e8490b99539381a64d96d37fc4bed3315f1a1fe915f36` |
| `docs/images/desktop-profiles-light.png` | 1040×700 | 166,216 | `096350aa0ae3c5a56e257518da2c53d360b31f7ab5d64b8cab20f50a4dfc8727` |
| `docs/images/walkthrough-benchmark.png` | 1040×700 | 152,308 | `f6c80023c62b4083ecd7f425fe379c707f3b47377c14b1b02c80eded7bc96b6f` |
| `docs/images/walkthrough-ready.png` | 720×560 | 178,803 | `83eccb77df2733fa85067d22f3db734ede8ba010d9b6d76bc1727a5b70a44101` |
| `docs/images/walkthrough-setup.png` | 720×560 | 128,919 | `62ee63b02473fcfb137282b4f769b49d4497ebe1596a7bd30e6b8f48fc0866f9` |

All six images were inspected at original resolution. They contain the current Home composition and
launcher, icon-based visibility controls, saved-profile view, compact setup and Ready states, and
pre-run benchmark layout. The two minimum-window captures remain contained at 720×560, and the
capture completed without page or console errors.
