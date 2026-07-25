package dev.starsector.preflight.cli;

import java.util.Locale;

/**
 * What a texture is <em>for</em>, which decides whether a perceptual colour metric means anything about
 * it.
 *
 * <p>A normal or material map stores vectors and scalars in RGB channels. It is never looked at; it is
 * sampled by a shader. Delta-E on one is not a perceptual measurement, so a fidelity gate would be
 * admitting these on the strength of a number that does not describe them — reconstructing a unit vector
 * from two interpolated endpoints is exactly the failure BC5 exists to fix. They are excluded rather
 * than measured and admitted.
 *
 * <p>This lives on its own, rather than inside the baker that acts on it, because a second consumer now
 * exists: {@code assets contact-sheet} renders the classification so a person can check it. Two copies
 * of the rule would let the sheet certify a decision the baker did not make.
 *
 * <h2>The rule is a heuristic, and knowing how it fails is the point</h2>
 *
 * <p>It is filename substrings, applied across roughly seventy mods that share no naming convention, so
 * it misfires in both directions — and <em>neither misfire is visible in any number the baker prints</em>:
 *
 * <ul>
 *   <li>A normal map that slips past gets encoded. Normal maps are smooth, so its Delta-E is excellent.
 *       It is still wrong, because the metric is not measuring the property that matters.
 *   <li>Ordinary art whose filename happens to contain {@code _glow} is skipped, costs video memory for
 *       nothing, and produces no number at all, because skipped textures are never measured.
 * </ul>
 *
 * <p>Both are questions about what an image depicts, which has no predicate form. That is the argument
 * for rendering the classification next to the art instead of trusting it.
 */
enum TextureKind {
    ART,
    SHADER_MAP;

    static TextureKind of(String logicalPath) {
        String path = logicalPath.toLowerCase(Locale.ROOT);
        boolean shaderMap = path.contains("/shaders/")
                || path.contains("_normal")
                || path.contains("_material")
                || path.contains("_surface")
                || path.contains("_glow")
                || path.contains("/maps/");
        return shaderMap ? SHADER_MAP : ART;
    }
}
