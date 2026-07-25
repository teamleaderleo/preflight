import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.Pbuffer;
import org.lwjgl.opengl.PixelFormat;

/**
 * Asks the graphics driver which texture formats it will actually accept, from inside the exact
 * context Starsector runs in.
 *
 * <p>Every texture-footprint plan depends on the answer, and the answer is not portable: it varies
 * by GPU, by driver, by operating system, and on macOS by the OpenGL-over-Metal translation layer.
 * A format that is unremarkable on a 2019 Windows desktop can be entirely absent on a 2026 Mac. So
 * this asks rather than assumes, and it asks by performing real uploads and reading back what was
 * stored — an extension string is a claim, an accepted upload is a fact, and the two are known to
 * disagree.
 *
 * <p>It runs on the game's own LWJGL 2 and its own bundled JVM, so the context it measures is the
 * one the game gets rather than an approximation of it. That also makes it work identically on
 * Windows, macOS and Linux, which the earlier macOS-only CGL probe could not.
 *
 * <p>Read-only: an offscreen pbuffer, a few textures, no window and no game launch.
 */
public final class GpuCapabilityProbe {

    /** A candidate compressed format, with the block size the pre-encoded path must feed. */
    private record Format(String name, int token, int bytesPerBlock) { }

    private static final Format[] FORMATS = {
        new Format("BC1  GL_COMPRESSED_RGB_S3TC_DXT1", 0x83F0, 8),
        new Format("BC1a GL_COMPRESSED_RGBA_S3TC_DXT1", 0x83F1, 8),
        new Format("BC2  GL_COMPRESSED_RGBA_S3TC_DXT3", 0x83F2, 16),
        new Format("BC3  GL_COMPRESSED_RGBA_S3TC_DXT5", 0x83F3, 16),
        new Format("BC4  GL_COMPRESSED_RED_RGTC1", 0x8DBB, 8),
        new Format("BC5  GL_COMPRESSED_RG_RGTC2", 0x8DBD, 16),
        new Format("BC7  GL_COMPRESSED_RGBA_BPTC_UNORM", 0x8E8C, 16),
        new Format("ASTC GL_COMPRESSED_RGBA_ASTC_4x4_KHR", 0x93B0, 16),
    };

    private static final String[] EXTENSIONS = {
        "GL_EXT_texture_compression_s3tc",
        "GL_ARB_texture_compression",
        "GL_ARB_texture_compression_bptc",
        "GL_ARB_texture_compression_rgtc",
        "GL_ARB_texture_non_power_of_two",
        "GL_KHR_texture_compression_astc_ldr",
        "GL_ARB_pixel_buffer_object",
        "GL_ARB_texture_storage",
    };

    private static final int GL_TEXTURE_COMPRESSED = 0x86A1;
    private static final int GL_TEXTURE_COMPRESSED_IMAGE_SIZE = 0x86A0;
    private static final int GL_TEXTURE_INTERNAL_FORMAT = 0x1003;

    private static final int PROBE_SIZE = 512;
    // The real non-power-of-two case observed against the running engine, recorded in
    // docs/evidence/2026-07-22-prepared-pixel-npot-padding.md. get2Fold pads it to 1024x512.
    private static final int NPOT_WIDTH = 597;
    private static final int NPOT_HEIGHT = 373;

    private final Map<String, Object> json = new LinkedHashMap<>();

    public static void main(String[] args) {
        try {
            new GpuCapabilityProbe().run();
        } catch (Throwable failure) {
            System.out.println("PROBE FAILED: " + failure);
            failure.printStackTrace(System.out);
            System.exit(1);
        }
    }

    private void run() throws Exception {
        Pbuffer buffer = new Pbuffer(64, 64, new PixelFormat(8, 24, 0), null);
        buffer.makeCurrent();
        try {
            reportEnvironment();
            reportDriver();
            reportExtensions();
            reportDriverSideCompression();
            reportPreEncodedUploads();
            reportNonPowerOfTwo();
        } finally {
            buffer.destroy();
        }
        System.out.println();
        System.out.println("--- machine readable ---");
        System.out.println(toJson(json));
    }

    private void reportEnvironment() {
        record("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        record("arch", System.getProperty("os.arch"));
        record("java", System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
        System.out.println("== environment");
        System.out.println("   os        " + json.get("os"));
        System.out.println("   arch      " + json.get("arch"));
        System.out.println("   java      " + json.get("java"));
        System.out.println();
    }

    private void reportDriver() {
        record("glVersion", GL11.glGetString(GL11.GL_VERSION));
        record("glRenderer", GL11.glGetString(GL11.GL_RENDERER));
        record("glVendor", GL11.glGetString(GL11.GL_VENDOR));
        record("glslVersion", GL11.glGetString(0x8B8C));
        record("maxTextureSize", GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE));
        System.out.println("== driver (the context Starsector's LWJGL 2 creates)");
        System.out.println("   GL_VERSION           " + json.get("glVersion"));
        System.out.println("   GL_RENDERER          " + json.get("glRenderer"));
        System.out.println("   GL_VENDOR            " + json.get("glVendor"));
        System.out.println("   GLSL                 " + json.get("glslVersion"));
        System.out.println("   GL_MAX_TEXTURE_SIZE  " + json.get("maxTextureSize"));
        System.out.println();
    }

    private void reportExtensions() {
        String all = " " + GL11.glGetString(GL11.GL_EXTENSIONS) + " ";
        System.out.println("== extensions claimed");
        for (String extension : EXTENSIONS) {
            boolean present = all.contains(" " + extension + " ");
            record("ext." + extension, present);
            System.out.printf("   %-38s %s%n", extension, present ? "yes" : "no");
        }
        System.out.println();
    }

    /**
     * Whether asking for a compressed internal format is enough on its own. Where it is, the
     * smallest possible change to the engine's upload is a single different constant at the
     * existing call site — TextureLoader hardcodes GL_RGBA there.
     */
    private void reportDriverSideCompression() {
        ByteBuffer pixels = buffer(PROBE_SIZE * PROBE_SIZE * 4);
        long uncompressed = (long) PROBE_SIZE * PROBE_SIZE * 4;

        int texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        clearErrors();
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, PROBE_SIZE, PROBE_SIZE, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        int stored = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL_TEXTURE_INTERNAL_FORMAT);
        System.out.println("== baseline: the upload the engine performs today");
        System.out.printf("   internalformat=GL_RGBA  error=0x%04X stored=0x%04X resident=%d bytes%n%n",
                GL11.glGetError(), stored, uncompressed);
        GL11.glDeleteTextures(texture);

        System.out.println("== driver-side compression: glTexImage2D with a compressed internalformat");
        for (Format format : FORMATS) {
            int name = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, name);
            clearErrors();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, format.token(), PROBE_SIZE, PROBE_SIZE, 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            int error = GL11.glGetError();
            boolean compressed = error == GL11.GL_NO_ERROR
                    && GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL_TEXTURE_COMPRESSED) != 0;
            int size = compressed
                    ? GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL_TEXTURE_COMPRESSED_IMAGE_SIZE)
                    : 0;
            record("driverCompress." + format.name().substring(0, 4).trim(), compressed);
            System.out.printf("   %-40s error=0x%04X compressed=%-4s size=%-9d%s%n", format.name(),
                    error, compressed ? "yes" : "no", size,
                    size > 0 ? String.format("%.2fx smaller", (double) uncompressed / size) : "");
            GL11.glDeleteTextures(name);
        }
        System.out.println();
    }

    /**
     * Whether pre-encoded blocks are accepted. This is the path that lets a good offline encoder be
     * used instead of whichever fast one the driver ships, and the only one that permits a
     * per-texture policy.
     */
    private void reportPreEncodedUploads() {
        System.out.println("== offline encoder path: glCompressedTexImage2D with pre-encoded blocks");
        for (Format format : FORMATS) {
            int blocks = (PROBE_SIZE / 4) * (PROBE_SIZE / 4) * format.bytesPerBlock();
            int name = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, name);
            clearErrors();
            GL13.glCompressedTexImage2D(GL11.GL_TEXTURE_2D, 0, format.token(), PROBE_SIZE,
                    PROBE_SIZE, 0, buffer(blocks));
            int error = GL11.glGetError();
            boolean accepted = error == GL11.GL_NO_ERROR;
            record("preEncoded." + format.name().substring(0, 4).trim(), accepted);
            System.out.printf("   %-40s error=0x%04X accepted=%s%n", format.name(), error,
                    accepted ? "yes" : "no");
            GL11.glDeleteTextures(name);
        }
        System.out.println();
    }

    /**
     * Whether the loader's power-of-two padding is required by this driver at all. On the reviewed
     * ~70-mod profile that padding is 1.86 GiB of resident video memory.
     */
    private void reportNonPowerOfTwo() {
        int name = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, name);
        clearErrors();
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, NPOT_WIDTH, NPOT_HEIGHT, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer(NPOT_WIDTH * NPOT_HEIGHT * 4));
        int error = GL11.glGetError();
        int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
        boolean native2d = error == GL11.GL_NO_ERROR && width == NPOT_WIDTH && height == NPOT_HEIGHT;
        record("nonPowerOfTwoNative", native2d);
        System.out.println("== non-power-of-two: is the loader's get2Fold padding needed here?");
        System.out.printf("   %dx%d RGBA  error=0x%04X stored=%dx%d  (padded upload would be 1024x512)%n",
                NPOT_WIDTH, NPOT_HEIGHT, error, width, height);
        System.out.println(native2d
                ? "   -> padding is NOT required by this driver; it is inherited Slick2D behaviour."
                : "   -> padding IS required by this driver; removing it would break textures here.");
        GL11.glDeleteTextures(name);
    }

    private static ByteBuffer buffer(int bytes) {
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
    }

    private static void clearErrors() {
        while (GL11.glGetError() != GL11.GL_NO_ERROR) {
            // Drain state left by earlier calls so the next error read is unambiguous.
        }
    }

    private void record(String key, Object value) {
        json.put(key, value);
    }

    private static String toJson(Map<String, Object> values) {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append('"').append(entry.getKey()).append("\":");
            Object value = entry.getValue();
            if (value instanceof Boolean || value instanceof Number) {
                out.append(value);
            } else {
                out.append('"').append(String.valueOf(value).replace("\\", "\\\\").replace("\"", "\\\""))
                        .append('"');
            }
        }
        return out.append('}').toString();
    }
}
