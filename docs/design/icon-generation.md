# Application icon source

The current application icon is deterministic vector artwork. Its editable source is
`preflight-desktop/src/assets/preflight-mark-v7.svg`; the checked-in 512px render is
`preflight-desktop/src/assets/preflight-mark-v7.png`.

The composition uses only:

- a centered, rotated delta-spacecraft silhouette;
- a cockpit and engine aperture;
- three thick concentric circles occupying the upper-right third;
- a faint 32px drafting grid, center axes, border corners, and measurement ticks;
- warm ivory (`#f4f0e3`) and midnight navy (`#18344d`), plus one pale cockpit fill.

The SVG is rendered on macOS with:

```sh
sips -s format png -z 512 512 \
  preflight-desktop/src/assets/preflight-mark-v7.svg \
  --out preflight-desktop/src/assets/preflight-mark-v7.png
```

Tauri's icon command converts that PNG into the platform icon set. The earlier generated marks are
kept as design history; they aren't consumed by the app or package. The current mark doesn't depend
on image generation and can be changed by editing exact SVG geometry, colors, or stroke widths.
