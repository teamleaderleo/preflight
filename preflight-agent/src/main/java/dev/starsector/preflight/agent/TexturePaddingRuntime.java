package dev.starsector.preflight.agent;

import java.lang.reflect.InvocationTargetException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Decides whether the installed loader's power-of-two dimension fold should be bypassed.
 *
 * <p>Starsector inherits Slick2D's {@code get2Fold}: every texture is uploaded into an allocation
 * rounded up to the next power of two on each axis. On the reviewed 72-mod profile that is
 * <b>1.86 GiB allocated and never sampled</b>, and unlike every other lever in the footprint program
 * removing it costs no fidelity at all — the pixels are identical, there are simply fewer of them.
 *
 * <p>It is only safe where the live desktop OpenGL context supports OpenGL 2.0 or
 * {@code GL_ARB_texture_non_power_of_two}. That is a property of the context, not of the build, so
 * this is a runtime gate and its default is off. A host that never sets the property gets exactly
 * today's behaviour, and a property cannot override a missing capability.
 *
 * <p><b>This gate governs one half of an invariant.</b> The installed loader computes padded
 * dimensions in two places: the extracted fold this bypasses, which sizes the {@code glTexImage2D}
 * allocation, and an inlined copy in the converter that sizes the upload buffer. They must agree
 * about every texture. The inlined copy is never edited — when the prepared path serves a texture the
 * original converter is not called at all — so the agreement holds only while both of these are true
 * together:
 *
 * <ul>
 *   <li>this gate is on, so the allocation uses true dimensions; and
 *   <li>the prepared path is serving, so the buffer does too.
 * </ul>
 *
 * <p>Turning this on without the second is the {@code insufficient-original-buffer} failure reached
 * from the opposite direction: a shrunken allocation handed a padded buffer. See
 * {@code docs/evidence/2026-07-26-padding-removal-needs-no-instruction-surgery.md}.
 */
public final class TexturePaddingRuntime {
    static final String PLAN_ID = "texture-padding-v1";

    /**
     * Requests true-size uploads. The live LWJGL capability probe still has final authority; absent
     * or false means the fold behaves exactly as shipped.
     */
    public static final String UNPADDED_PROPERTY = "preflight.padding.unpadded";

    /**
     * Optional diagnostic ceiling for true-size uploads. A positive value keeps textures whose
     * original width or height exceeds the ceiling on Starsector's padded path. Missing, zero, and
     * negative values preserve the existing all-sizes behavior.
     *
     * <p>This exists for software OpenGL drivers that advertise NPOT support but may have a
     * size-dependent slow or broken path. It is deliberately a property rather than a preset: a
     * host must first prove a safe threshold against its exact driver and workload.
     */
    public static final String MAX_UNPADDED_DIMENSION_PROPERTY =
            "preflight.padding.maxUnpaddedDimension";

    private static volatile boolean FOLD_BYPASS_INSTALLED;
    private static volatile NpotCapability NPOT_CAPABILITY = NpotCapability.UNCHECKED;
    private static volatile String NPOT_CAPABILITY_REASON = "not checked";
    private static volatile boolean OPENGL_20;
    private static volatile boolean ARB_NPOT;

    private static final AtomicLong BYPASSED = new AtomicLong();
    private static final AtomicLong FOLDED = new AtomicLong();
    private static final AtomicLong TEXTURES = new AtomicLong();
    private static final AtomicLong BYTES_AVOIDED = new AtomicLong();
    private static final AtomicLong DIMENSION_CEILING_DECLINES = new AtomicLong();

    private TexturePaddingRuntime() {
    }

    static boolean ready() {
        return true;
    }

    /**
     * The gate itself, without recording anything. Used by callers that ask more than once about the
     * same texture, so that {@link #unpadded()}'s counters keep meaning "dimension folds".
     */
    public static boolean enabled() {
        return available();
    }

    /** Whether this JVM may serve a verified prepared texture at its true dimensions. */
    static boolean available() {
        return FOLD_BYPASS_INSTALLED
                && Boolean.getBoolean(UNPADDED_PROPERTY)
                && npotSupported();
    }

    /** Whether this exact texture is inside the optional, explicitly configured NPOT ceiling. */
    static boolean availableFor(int width, int height) {
        if (!available()) {
            return false;
        }
        int ceiling = maxUnpaddedDimension();
        if (ceiling > 0 && (width > ceiling || height > ceiling)) {
            DIMENSION_CEILING_DECLINES.incrementAndGet();
            return false;
        }
        return true;
    }

    /**
     * Records that the fold bypass was actually woven into the installed loader.
     *
     * <p>This is the invariant above turned into something the code enforces rather than
     * something a comment asks for. The property alone only ever controlled one half: it makes
     * the prepared path supply a true-size buffer. If the fold was never bypassed the allocation
     * is still padded, and the very first non-power-of-two texture kills the process with
     * {@code Number of remaining buffer elements is 668043, must be at least 1572864} -- observed
     * on 2026-07-31 against graphics/ui/launcher_bg.jpg, which the launcher loads before a human
     * can click anything. Failing closed here costs a padded upload; failing open costs the run.
     */
    static void foldBypassInstalled() {
        FOLD_BYPASS_INSTALLED = true;
    }

    /**
     * Clears the latch, alongside the other runtimes a session resets. The latch describes one
     * JVM's installed loader, so it must not outlive the session that wove it.
     */
    static void beginSession() {
        FOLD_BYPASS_INSTALLED = false;
        NPOT_CAPABILITY = NpotCapability.UNCHECKED;
        NPOT_CAPABILITY_REASON = "not checked";
        OPENGL_20 = false;
        ARB_NPOT = false;
    }

    /** Whether the half of the invariant this class does not control is in place. */
    public static boolean foldBypassReady() {
        return FOLD_BYPASS_INSTALLED;
    }

    /**
     * Called from the installed loader on every dimension fold, so it stays a property read rather
     * than anything that could allocate or block.
     *
     * @return true when the caller should use the dimension it was given
     */
    public static boolean unpadded() {
        if (available() && TexturePreparedPixelRuntime.claimCurrentThreadTrueSizeFold()) {
            BYPASSED.incrementAndGet();
            return true;
        }
        FOLDED.incrementAndGet();
        return false;
    }

    /**
     * Records one texture served at its true size. The byte count is the padding that would have
     * been allocated and never sampled, so summed across a profile it is the whole point of the
     * lever stated in the units the roadmap uses.
     */
    static void served(long paddingBytesAvoided) {
        TEXTURES.incrementAndGet();
        BYTES_AVOIDED.addAndGet(paddingBytesAvoided);
    }

    static Map<String, Object> report() {
        boolean effective = enabled();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("planId", PLAN_ID);
        values.put("unpaddedProperty", UNPADDED_PROPERTY);
        values.put("unpaddedEnabled", Boolean.getBoolean(UNPADDED_PROPERTY));
        values.put("maxUnpaddedDimensionProperty", MAX_UNPADDED_DIMENSION_PROPERTY);
        values.put("maxUnpaddedDimension", maxUnpaddedDimension());
        values.put("foldBypassInstalled", FOLD_BYPASS_INSTALLED);
        values.put("npotCapability", NPOT_CAPABILITY.name().toLowerCase(Locale.ROOT));
        values.put("npotCapabilityReason", NPOT_CAPABILITY_REASON);
        values.put("openGl20", OPENGL_20);
        values.put("arbTextureNonPowerOfTwo", ARB_NPOT);
        values.put("unpaddedEffective", effective);
        values.put("dimensionsBypassed", BYPASSED.get());
        values.put("dimensionsFolded", FOLDED.get());
        values.put("texturesServedUnpadded", TEXTURES.get());
        values.put("paddingBytesAvoided", BYTES_AVOIDED.get());
        values.put("dimensionCeilingDeclines", DIMENSION_CEILING_DECLINES.get());
        return values;
    }

    static void reset() {
        BYPASSED.set(0);
        FOLDED.set(0);
        TEXTURES.set(0);
        BYTES_AVOIDED.set(0);
        DIMENSION_CEILING_DECLINES.set(0);
    }

    private static int maxUnpaddedDimension() {
        return Integer.getInteger(MAX_UNPADDED_DIMENSION_PROPERTY, 0);
    }

    /**
     * Reads LWJGL 2's capabilities from the context that is current on the texture-loading thread.
     * OpenGL 2.0 made full NPOT textures core; the ARB flag covers older contexts that expose the
     * same contract as an extension. Reflection keeps the agent independent of any redistributed
     * LWJGL binary and resolves against the copy owned by the installation.
     *
     * <p>A missing current context is transient and leaves the state unchecked so a later texture
     * can try again. Missing classes, fields, or an affirmative context without either capability
     * are permanent declines for this session. The original power-of-two fold remains reachable in
     * every decline case.
     */
    private static boolean npotSupported() {
        NpotCapability observed = NPOT_CAPABILITY;
        if (observed != NpotCapability.UNCHECKED) {
            return observed == NpotCapability.SUPPORTED;
        }
        synchronized (TexturePaddingRuntime.class) {
            observed = NPOT_CAPABILITY;
            if (observed != NpotCapability.UNCHECKED) {
                return observed == NpotCapability.SUPPORTED;
            }
            try {
                Class<?> glContext = Class.forName("org.lwjgl.opengl.GLContext");
                Object capabilities = glContext.getMethod("getCapabilities").invoke(null);
                if (capabilities == null) {
                    NPOT_CAPABILITY_REASON = "LWJGL returned no current OpenGL context";
                    return false;
                }
                Class<?> type = capabilities.getClass();
                OPENGL_20 = type.getField("OpenGL20").getBoolean(capabilities);
                ARB_NPOT = type.getField("GL_ARB_texture_non_power_of_two").getBoolean(capabilities);
                if (OPENGL_20 || ARB_NPOT) {
                    NPOT_CAPABILITY = NpotCapability.SUPPORTED;
                    NPOT_CAPABILITY_REASON = OPENGL_20
                            ? "OpenGL 2.0 core capability"
                            : "GL_ARB_texture_non_power_of_two extension";
                    return true;
                }
                NPOT_CAPABILITY = NpotCapability.UNSUPPORTED;
                NPOT_CAPABILITY_REASON = "live context exposes neither OpenGL 2.0 nor the ARB extension";
                return false;
            } catch (InvocationTargetException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof IllegalStateException) {
                    NPOT_CAPABILITY_REASON = "no OpenGL context is current on this thread yet";
                    return false;
                }
                return capabilityFailure(cause == null ? failure : cause);
            } catch (ReflectiveOperationException | LinkageError failure) {
                return capabilityFailure(failure);
            }
        }
    }

    private static boolean capabilityFailure(Throwable failure) {
        NPOT_CAPABILITY = NpotCapability.UNSUPPORTED;
        NPOT_CAPABILITY_REASON = "capability probe failed: " + failure.getClass().getSimpleName();
        return false;
    }

    private enum NpotCapability {
        UNCHECKED,
        SUPPORTED,
        UNSUPPORTED
    }
}
