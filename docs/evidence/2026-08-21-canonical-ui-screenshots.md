# Canonical UI screenshots — 2026-08-21

This note binds the README/walkthrough screenshot refresh owned by #1000 to the accepted combined desktop frontend after the final defrumping/accessibility pass.

## Accepted frontend source

- accepted `main`: `67b44d0ff28148847ac8a3788bd446d94c490614`;
- accepted tree: `48aaeae9973887a02dee368c6099a05489cedfb8`;
- equivalent reviewed PR head/tree: `6ec6ef04c580554c1c16773bca479370bbe76816` / `48aaeae9973887a02dee368c6099a05489cedfb8`;
- Source boundary run: `32497310415` — success;
- CI run: `32497310615` — success;
- Desktop application run: `32497310481` — success;
- frontend artifact: `preflight-desktop-frontend-32497310481` / `9452137462`;
- artifact digest: `sha256:cffd8e2adb6d30e01475908d6c788baf7b8451e96ef1dc151ea377ec34f8acdd`.

The accepted `main` tree and reviewed PR-head tree are byte-identical. The capture job downloads that exact artifact archive and verifies its SHA-256 and internal frontend manifest before serving it. The screenshot carrier itself contains only documentation/capture tooling before generation; the rendered product bytes come from the accepted tree above.

## Canonical capture set

The public set stays intentionally small and reuses the README's existing image paths:

| Image | State | Window | Theme |
| --- | --- | --- | --- |
| `docs/images/desktop-home-light.png` | settled Home | 1040×700 | Blueprint / light |
| `docs/images/desktop-home-dark.png` | settled Home | 1040×700 | Blueprint / dark |
| `docs/images/desktop-profiles-light.png` | Mods / saved profiles | 1040×700 | Blueprint / light |
| `docs/images/walkthrough-setup.png` | no installation selected | 720×560 | Blueprint / light |
| `docs/images/walkthrough-ready.png` | settled Home | 720×560 | Blueprint / light |
| `docs/images/walkthrough-benchmark.png` | Benchmark before a run | 1040×700 | Blueprint / light |

The benchmark image is deliberately the pre-run state. Browser-preview benchmark execution uses synthetic fixture timings for interaction review; those synthetic values are not publication evidence and should not appear as if they were the README's measured result.

The helper `preflight-desktop/scripts/capture-doc-screenshots.py` treats browser console/page errors as capture failures, waits for the actual target controls before each screenshot, fixes locale/timezone/theme/palette/reduced-motion for repeatability, and appends the accepted PNG Git blob identities to the source-boundary review allowlist.

## Captured image identities

The screenshot-generation run replaces the rows below with the final PNG dimensions, byte sizes, and SHA-256 values before committing the images.

<!-- canonical-screenshot-identities:start -->
_Pending capture._
<!-- canonical-screenshot-identities:end -->
