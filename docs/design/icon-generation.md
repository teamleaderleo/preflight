# Application icon source

The current mark restores the illustrated v6 drafting-paper spacecraft without redrawing it. The
editable source is `preflight-desktop/src/assets/preflight-mark-v8.svg`; it uses the checked-in v6
PNG as an immutable base and rebuilds only the upper-right celestial layer. The checked-in 512px
render is `preflight-desktop/src/assets/preflight-mark-v8.png`.

That localized layer contains four drafting circles and a single symmetric, unfilled four-ray star.
The former hatched, many-ray starburst is retained only in the historical v6 asset. The ship,
paper, grid, frame, registration marks, construction lines, palette, scale, and composition remain
the v6 artwork.

Quick Look resolves the SVG's relative base-image reference when producing the PNG on macOS:

```sh
render_dir=$(mktemp -d)
qlmanage -t -s 512 -o "$render_dir" \
  preflight-desktop/src/assets/preflight-mark-v8.svg
cp "$render_dir/preflight-mark-v8.svg.png" \
  preflight-desktop/src/assets/preflight-mark-v8.png
```

Tauri's icon command converts that PNG into the platform icon set. Earlier marks remain checked in
as design history and aren't consumed by the app or package.

Two built-in image-edit attempts used the following final constraint and were rejected because they
redrew the spacecraft instead of preserving it:

> Change only the upper-right starburst. Keep every pixel outside it visually identical. Replace the
> many-ray hatched star with a plain symmetric star; preserve the spacecraft, drafting paper, grid,
> border, registration marks, construction lines, circle arcs, scale, crop, and palette.

The shipped v8 mark therefore uses the deterministic localized SVG edit above rather than either
generated result.
