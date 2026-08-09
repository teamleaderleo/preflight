# Application icon source

The current application icon was generated with OpenAI's built-in image editing tool. The light
drafting-paper version was derived from `preflight-mark-v3.png`; the current destination-star edit
uses that light version as its input. Tauri's icon command converts the project-bound master,
`preflight-desktop/src/assets/preflight-mark-v5.png`, into the platform icon set.

The final edit prompt was:

> Use case: logo-brand. Asset type: square desktop application icon and in-app brand mark. Replace
> the tiny orange nose target with one enormous cropped celestial drafting symbol that fills
> essentially the entire upper-right quadrant. Give it broad visual area and curvature rather than
> attaching a small star or thin tangent-line motif to the ship. Preserve the existing spacecraft,
> warm ivory drafting-paper background, faint square grid, technical border, perspective, scale, and
> dark navy linework. Draw a huge circular sun/star construction approximately centered near x=475,
> y=55 on the 512px square and roughly 300–360px in diameter. Let its circular outer arcs and wide
> radial geometry extend beyond the top and right edges and reach inward toward the center grid
> lines. Use a broad multi-point drafting star or sun with long alternating rays and one or two
> partial construction circles. The ship's nose should enter or overlap the lower-left interior of
> the celestial field; it must not terminate on the star center or touch a single ray as a tangent.
> Keep breathing room between the nose and any individual outline. Make the celestial symbol
> slightly lighter than the ship so the ship remains dominant. Use warm ivory and dark navy only,
> with no orange, text, letters, transparency, gradient, glow, neon, drop shadow, tiny star, tiny dot,
> bullseye, corner badge, isolated sparkle, filled cartoon star, or stars elsewhere.

The oversized macOS `.icns` output is admitted by the source-boundary audit only at its exact byte
length and SHA-256. Regenerating it therefore requires an explicit review and fingerprint update.
