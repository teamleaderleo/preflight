# Application icon source

The current application icon was generated with OpenAI's built-in image editing tool. The light
drafting-paper version was derived from `preflight-mark-v3.png`; later edits established the
quadrant-sized destination and stronger line hierarchy. Tauri's icon command converts the
project-bound master, `preflight-desktop/src/assets/preflight-mark-v6.png`, into the platform icon
set.

The final edit prompt was:

> Use case: logo-brand. Asset type: square desktop application icon and in-app brand mark designed
> to remain legible at 16px, 32px, and 64px. Refine the supplied spacecraft drafting icon for
> small-size readability and a stronger Renaissance engineering-folio character. Preserve its
> composition and large upper-right celestial destination while creating a deliberate line-weight
> hierarchy and geometrically symmetric star. Make the spacecraft's outer silhouette, nose, wing
> tips, engine pods, and main fuselage edges roughly 2.5–3 times heavier. Use medium-weight lines for
> major cockpit and panel divisions. Remove or simplify tiny interior marks that disappear at 32px.
> Keep the grid, compass arcs, measurement ticks, construction lines, and hatching much finer and
> lighter. Evoke a clean Leonardo da Vinci engineering folio through confident dark ink contours,
> subtle parallel hatching, compass-drawn circles, restrained geometric construction marks, and
> lightly imperfect hand pressure without making the paper antique or distressed. Reconstruct the
> huge top-right sun/star as a precisely radial, symmetrical 12- or 16-point compass star around an
> off-canvas center near x=500, y=60, with equal alternating long and short rays at regular angles
> and one or two concentric construction circles. Keep it lighter than the ship, filling the entire
> quadrant and cropped beyond the top and right edges. Use warm ivory and strong desaturated
> midnight-navy ink. Avoid uniformly thin lines, fuzzy silhouettes, illegible micro-detail,
> lopsided geometry, irregular ray spacing, a tangent at the nose, orange, text, transparency,
> gradients, glow, neon, drop shadows, cartoon clip art, stained parchment, and clutter.

The oversized macOS `.icns` output is admitted by the source-boundary audit only at its exact byte
length and SHA-256. Regenerating it therefore requires an explicit review and fingerprint update.
