package dev.starsector.preflight.agent;

import java.util.LinkedHashMap;
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
 * <p>It is only safe where the driver supports {@code GL_ARB_texture_non_power_of_two}. That is a
 * property of the machine, not of the build, so this is a runtime gate and its default is off. A
 * host that never sets the property gets exactly today's behaviour.
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
     * Set only by a host that has observed non-power-of-two support on the live context. Absent or
     * false means the fold behaves exactly as shipped.
     */
    static final String UNPADDED_PROPERTY = "preflight.padding.unpadded";

    private static final AtomicLong BYPASSED = new AtomicLong();
    private static final AtomicLong FOLDED = new AtomicLong();

    private TexturePaddingRuntime() {
    }

    static boolean ready() {
        return true;
    }

    /**
     * Called from the installed loader on every dimension fold, so it stays a property read rather
     * than anything that could allocate or block.
     *
     * @return true when the caller should use the dimension it was given
     */
    public static boolean unpadded() {
        if (Boolean.getBoolean(UNPADDED_PROPERTY)) {
            BYPASSED.incrementAndGet();
            return true;
        }
        FOLDED.incrementAndGet();
        return false;
    }

    static Map<String, Object> report() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("planId", PLAN_ID);
        values.put("unpaddedProperty", UNPADDED_PROPERTY);
        values.put("unpaddedEnabled", Boolean.getBoolean(UNPADDED_PROPERTY));
        values.put("dimensionsBypassed", BYPASSED.get());
        values.put("dimensionsFolded", FOLDED.get());
        return values;
    }

    static void reset() {
        BYPASSED.set(0);
        FOLDED.set(0);
    }
}
