# Application icon source

The current application mark is the versioned 512px bitmap
`preflight-desktop/src/assets/preflight-mark-v9.png`. It keeps the warm drafting-paper spacecraft
and replaces the upper-right star and construction circles with a foreshortened wireframe
wormhole. The spacecraft crosses the darker near rim while lighter cross-sections and guide lines
recede toward the upper-right corner.

The mark was produced with the built-in image-editing tool from the accepted illustrated
spacecraft base. The final prompt was:

> Replace the star and circular construction geometry with an aesthetically distinctive
> hand-drafted wormhole that the spacecraft is entering. Create a foreshortened wireframe tunnel
> sweeping from the spacecraft nose toward a vanishing point beyond the upper-right edge. Use
> three receding elliptical cross-section rings plus a doubled near rim, connected by five or six
> sparse curved longitudinal guide lines. Let the nose visibly overlap the near rim. Make the near
> rim slightly darker and the distant geometry lighter. Remove the star, dashed construction
> circle, and old arcs. Preserve the spacecraft, ivory drafting paper, faint grid, frame,
> registration ticks, straight construction marks, crop, palette, and technical-sketch style.
> Avoid flat circles, a bullseye, glow, gradients, color, fill, dense ornament, text, or a ship
> redesign.

The generated 1024px result was reduced to 512px with:

```sh
sips -z 512 512 preflight-desktop/src/assets/preflight-mark-v9.png
```

Tauri's icon command converts that PNG into the checked-in macOS and Windows icon set. Earlier
marks remain in `preflight-desktop/src/assets` as design history and aren't consumed by the app or
package.
