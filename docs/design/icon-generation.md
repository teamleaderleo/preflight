# Application icon source

The current application icon was generated as a visual edit of
`preflight-desktop/src/assets/preflight-mark-v3.png` with OpenAI's built-in image generation tool,
then resized and converted into the platform icon set by Tauri's icon command. The project-bound
master is `preflight-desktop/src/assets/preflight-mark-v4.png`.

The edit prompt was:

> Use case: logo-brand. Asset type: square desktop application icon and in-app brand mark. Rework
> the provided spacecraft drafting mark into a light drafting-paper icon with a dark technical
> sketch. Preserve its recognizable centered spacecraft silhouette and three-quarter upward-right
> orientation. Use warm off-white engineering graph paper with an extremely faint blue-gray square
> grid and a restrained thin drafting border. Draw one simplified spacecraft in dark navy technical
> pencil and ink, with a few subtle construction lines and measurement ticks. Keep the line texture
> precise and slightly imperfect, graphic and legible rather than photorealistic. Center it with
> generous padding and simplify small interior details so it remains recognizable at 32×32 pixels.
> Use warm ivory paper, dark desaturated navy linework, and at most one tiny muted rust-orange
> registration dot. Make it square, with no text, letters, wordmark, transparency, or rounded app-icon
> mask baked into the art. Avoid dark backgrounds, neon, cyan or turquoise washes, gradients, glossy
> 3D rendering, game-screenshot styling, stars, glow, drop shadows, clutter, and tiny illegible details.

The oversized macOS `.icns` output is admitted by the source-boundary audit only at its exact byte
length and SHA-256. Regenerating it therefore requires an explicit review and fingerprint update.
