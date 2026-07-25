package dev.starsector.preflight.core;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * Writes the {@code SPFV} conformance vector: encoded blocks, plus the pixels preflight's decoder says
 * those blocks mean, in a form a small C program can read on a machine with no JVM.
 *
 * <p>This exists because the interesting driver is usually not the one you are sitting at. The
 * in-process check needs LWJGL, LWJGL needs a window system, and a rented GPU is headless. Splitting
 * the check in two removes the constraint: this half needs Java and no GPU,
 * {@code block-conformance-probe.c} needs a GPU and no Java.
 *
 * <p>The format lives in core rather than beside the probe because two things now produce vectors — a
 * fixed synthetic image, and a sample of a real baked cache — and a second copy of the layout would be
 * a second thing to keep in step with a C reader nobody recompiles often.
 *
 * <p>Everything is little-endian so the C side can read it with a plain {@code fread} on every
 * platform that matters.
 */
public final class BlockConformanceVectorIO {
    private static final byte[] MAGIC = {'S', 'P', 'F', 'V'};
    public static final int VERSION = 1;

    private BlockConformanceVectorIO() {
    }

    /**
     * One texture the driver is asked to decode.
     *
     * @param name shown in the probe's output, so a failure names something
     * @param glInternalFormat the {@code internalformat} argument for {@code glCompressedTexImage2D}
     * @param expectedArgb what preflight's decoder says the blocks mean, packed ARGB
     */
    public record Case(String name, int glInternalFormat, int width, int height, byte[] blocks, int[] expectedArgb) {
        public Case {
            Objects.requireNonNull(name, "name");
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("Conformance case dimensions must be positive");
            }
            if (blocks == null || blocks.length == 0) {
                throw new IllegalArgumentException("A conformance case needs blocks");
            }
            if (expectedArgb == null || expectedArgb.length != Math.multiplyExact(width, height)) {
                throw new IllegalArgumentException("expectedArgb must hold exactly width*height pixels");
            }
        }

        /**
         * Builds a case from a baked texture's level 0, decoding it here so the vector states what
         * this build believes rather than what the baker believed when it ran.
         */
        public static Case ofLevelZero(String name, BlockTexture texture) {
            byte[] blocks = texture.level(0);
            return new Case(
                    name,
                    texture.format().glInternalFormat(),
                    texture.width(),
                    texture.height(),
                    blocks,
                    BlockCompressor.decode(blocks, texture.width(), texture.height(), texture.format().withAlpha()));
        }

        /** Bytes this case adds to a vector, which is dominated by the uncompressed expected image. */
        public long vectorBytes() {
            return 5L * Integer.BYTES
                    + name.getBytes(StandardCharsets.UTF_8).length
                    + blocks.length
                    + 4L * expectedArgb.length;
        }
    }

    public static void write(OutputStream stream, List<Case> cases) throws IOException {
        DataOutputStream out = new DataOutputStream(stream);
        out.write(MAGIC);
        writeInt(out, VERSION);
        writeInt(out, cases.size());
        for (Case value : cases) {
            byte[] name = value.name().getBytes(StandardCharsets.UTF_8);
            writeInt(out, name.length);
            out.write(name);
            writeInt(out, value.glInternalFormat());
            writeInt(out, value.width());
            writeInt(out, value.height());
            writeInt(out, value.blocks().length);
            out.write(value.blocks());
            writeInt(out, value.expectedArgb().length * 4);
            // RGBA byte order, matching what glGetTexImage(GL_RGBA, GL_UNSIGNED_BYTE) returns.
            byte[] rgba = new byte[value.expectedArgb().length * 4];
            for (int i = 0; i < value.expectedArgb().length; i++) {
                int pixel = value.expectedArgb()[i];
                rgba[i * 4] = (byte) (pixel >> 16);
                rgba[i * 4 + 1] = (byte) (pixel >> 8);
                rgba[i * 4 + 2] = (byte) pixel;
                rgba[i * 4 + 3] = (byte) (pixel >>> 24);
            }
            out.write(rgba);
        }
        out.flush();
    }

    private static void writeInt(DataOutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }
}
