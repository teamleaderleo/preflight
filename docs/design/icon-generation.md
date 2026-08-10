# Application icon source

The current application mark is the versioned 512px bitmap
`preflight-desktop/src/assets/preflight-mark-v10.png`. It keeps the warm drafting-paper spacecraft
and replaces the upper-right star and construction circles with a foreshortened wireframe
wormhole. The spacecraft crosses the darker near rim while lighter cross-sections and guide lines
recede toward the upper-right corner.

The mark was produced with the built-in image-editing tool from the accepted illustrated
spacecraft base. A first perspective pass established the wormhole; the final localized edit used
this prompt:

> Apply a local one-sided warp to the existing wormhole near rim. Keep the spacecraft and canvas
> geometry in their current positions and keep the lower-left half of the doubled rim anchored
> around the nose. Pull only the upper-right quadrant upward toward the top frame and outward toward
> the right frame, producing a smooth asymmetrical fisheye bulge without translating or enlarging
> the whole opening. Reproject the lighter cross-sections and longitudinal guide lines to meet the
> warped rim and converge on the same upper-right vanishing point. Preserve the ship, paper, grid,
> frame, registration marks, crop, palette, and drafting style. Avoid a symmetric oval, giant
> centered portal, extra geometry, glow, color, fill, text, or a ship redesign.

The generated 1024px result was reduced to 512px with:

```sh
sips -z 512 512 preflight-desktop/src/assets/preflight-mark-v10.png
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
