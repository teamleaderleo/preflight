package dev.starsector.preflight.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns decoded pixels into a {@link BlockTexture}: picks a format, encodes, and measures what the
 * encoding cost.
 *
 * <p>Baking is the expensive half of the whole idea and runs once, offline, so it is written for
 * quality rather than speed. The saving is entirely on the other side — a cached block texture is
 * uploaded straight to the GPU with no PNG decode, no raster walk and no power-of-two padding, which
 * on the measured profile is 94.6% of what a texture load costs today.
 *
 * <p>Every bake measures its own loss and the measurement travels with the blocks. This is not
 * bookkeeping: block compression is the one step in preflight that is deliberately not exact, so the
 * question "how much worse did this get" has to be answerable per texture, months later, without the
 * source image to hand.
 */
public final class BlockTextureBaker {
    private BlockTextureBaker() {
    }

    /** Bakes pixels that were not resized on the way in. */
    public static BlockTexture bake(String sourceSha256, int[] argb, int width, int height, Mips mips) {
        return bake(sourceSha256, argb, width, height, width, height, mips);
    }

    /**
     * @param sourceSha256 hash of the source image bytes, so a changed asset invalidates the blob
     * @param argb packed ARGB pixels, row major, {@code width * height} long
     * @param originalWidth width before any resize, for reporting; pass {@code width} if unresized
     */
    public static BlockTexture bake(
            String sourceSha256,
            int[] argb,
            int width,
            int height,
            int originalWidth,
            int originalHeight,
            Mips mips) {
        if (argb == null || width <= 0 || height <= 0 || argb.length != Math.multiplyExact(width, height)) {
            throw new IllegalArgumentException("argb must hold exactly width*height pixels");
        }
        BlockTexture.Format format = chooseFormat(argb);
        List<byte[]> levels = new ArrayList<>();
        int[] pixels = argb;
        int levelWidth = width;
        int levelHeight = height;
        int levelCount = mips == Mips.FULL_CHAIN ? BlockTexture.levelCount(width, height) : 1;
        TextureFidelity.Report fidelity = null;
        for (int level = 0; level < levelCount; level++) {
            byte[] blocks = BlockCompressor.encode(pixels, levelWidth, levelHeight, format.withAlpha());
            levels.add(blocks);
            if (level == 0) {
                // Level 0 only. A mip level is already a resampling of the original, so comparing it
                // against a downsampled reference would fold two different losses into one number and
                // answer neither question; what a player sees at full size is level 0.
                fidelity = TextureFidelity.compare(
                        pixels, BlockCompressor.decode(blocks, levelWidth, levelHeight, format.withAlpha()));
            }
            if (level + 1 < levelCount) {
                // Repeated halving and BlockTexture's per-level size have to agree; if they ever
                // stopped, the constructor below would reject the level lengths rather than write a
                // blob whose header disagrees with its contents.
                pixels = ImageResampler.halve(pixels, levelWidth, levelHeight);
                levelWidth = ImageResampler.halved(levelWidth);
                levelHeight = ImageResampler.halved(levelHeight);
            }
        }
        return new BlockTexture(
                sourceSha256,
                format,
                BlockCompressor.CODEC_VERSION,
                originalWidth,
                originalHeight,
                width,
                height,
                fidelity,
                levels);
    }

    /**
     * Picks BC1 for fully opaque images and BC3 for everything else.
     *
     * <p>BC3 costs twice as much — one byte per pixel against half — so the choice is worth making per
     * texture rather than globally, and on real Starsector art most of the VRAM sits in large opaque
     * backgrounds while the alpha lives in comparatively small sprites.
     *
     * <p>A single non-opaque pixel forces BC3 because BC1's opaque mode does not carry alpha at all;
     * there is no partial answer. BC1 does have a punch-through mode that would encode one-bit alpha
     * at half the cost, which would suit cutout sprites, but {@link BlockCompressor} never emits it —
     * it orders its endpoints so the four-colour mode bit is always set — so that saving is not
     * available here and is not pretended at.
     */
    public static BlockTexture.Format chooseFormat(int[] argb) {
        for (int pixel : argb) {
            if ((pixel >>> 24) != 0xff) {
                return BlockTexture.Format.BC3;
            }
        }
        return BlockTexture.Format.BC1;
    }

    /** Whether to bake a full mip chain or level 0 alone. */
    public enum Mips {
        /**
         * Level 0 only. Starsector uploads most textures without mips, and one that is never sampled
         * minified does not benefit from a chain it still has to store.
         */
        NONE,
        /**
         * Every level down to 1x1. The engine gives {@code GL_LINEAR_MIPMAP_LINEAR} to a named subset
         * of paths, and those are the large textures where baking the chain offline saves the most.
         */
        FULL_CHAIN
    }
}
