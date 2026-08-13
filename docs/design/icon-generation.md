# Application icon source

The current application mark is the versioned 512px bitmap
`preflight-desktop/src/assets/preflight-mark-v12-diagonal-candidate.png`. It keeps the centered
drafting-paper spacecraft against a diagonal black-hole boundary. The black body remains circular
at a scale larger than the canvas, and a narrow white, cyan, blue, violet, and magenta lensing edge
separates it from the graph-paper field.

The mark was produced from the accepted illustrated spacecraft base through a sequence of
image-editing passes. The v11 reference preserves the earlier right-side semicircle; v12 retains
the ship and paper field while turning that boundary into the larger diagonal composition.

The generated result was reduced to 512px with:

```sh
sips -z 512 512 preflight-desktop/src/assets/preflight-mark-v12-diagonal-candidate.png
```

Tauri's icon command converts that PNG into the checked-in macOS and Windows icon set. Earlier
marks remain in `preflight-desktop/src/assets` as design history and aren't consumed by the app or
package.

## Black-hole reference

`preflight-desktop/src/assets/preflight-mark-v11-semicircle-reference.png` preserves the accepted
full composition from the later black-hole exploration: the centered spacecraft and drafting grid,
with a large circular black body cropped into a right-side semicircle and a thin attached glow. It
is a design reference only and isn't consumed by the application. Later geometry experiments must
start from this file or leave it unchanged so the accepted composition remains recoverable.

## Diagonal black-hole application mark

`preflight-desktop/src/assets/preflight-mark-v12-diagonal-candidate.png` is the refined diagonal
composition. It keeps the black body genuinely circular and far larger than the canvas, strengthens
the spacecraft's structural linework and the graph-paper hierarchy, and gives the attached edge a
narrow white/cyan/blue/violet/magenta lensing stack. The desktop and generated platform icon sets
use this file.
