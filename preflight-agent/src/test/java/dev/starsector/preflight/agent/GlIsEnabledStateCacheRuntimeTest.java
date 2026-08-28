package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GlIsEnabledStateCacheRuntimeTest {
    private static final int GL_LIGHTING = 0x0B50;
    private static final int GL_ALPHA_TEST = 0x0BC0;
    private static final int GL_BLEND = 0x0BE2;
    private static final int GL_STENCIL_TEST = 0x0B90;
    private static final int GL_SCISSOR_TEST = 0x0C11;
    private static final int GL_TEXTURE_2D = 0x0DE1;
    private static final int GL_ENABLE_BIT = 0x00002000;
    private static final int GL_COLOR_BUFFER_BIT = 0x00004000;

    private final Object context = new Object();

    @BeforeEach
    void enable() {
        GlIsEnabledStateCacheRuntime.beginSessionForTest(true);
    }

    @AfterEach
    void reset() {
        GlIsEnabledStateCacheRuntime.resetForTest();
    }

    @Test
    void seedsFromNativeGetterThenTracksEnableAndDisable() {
        assertEquals(-1, GlIsEnabledStateCacheRuntime.cached(GL_BLEND, context));
        GlIsEnabledStateCacheRuntime.observedQuery(GL_BLEND, true, context);
        assertEquals(1, GlIsEnabledStateCacheRuntime.cached(GL_BLEND, context));
        GlIsEnabledStateCacheRuntime.disable(GL_BLEND, context);
        assertEquals(0, GlIsEnabledStateCacheRuntime.cached(GL_BLEND, context));
        GlIsEnabledStateCacheRuntime.enable(GL_BLEND, context);
        assertEquals(1, GlIsEnabledStateCacheRuntime.cached(GL_BLEND, context));

        Map<String, Object> telemetry = GlIsEnabledStateCacheRuntime.telemetry();
        assertEquals(3L, telemetry.get("hits"));
        assertEquals(1L, telemetry.get("nativeSeeds"));
        assertEquals(1L, telemetry.get("enableUpdates"));
        assertEquals(1L, telemetry.get("disableUpdates"));
    }

    @Test
    void attribStackRestoresOnlyCapabilitiesCoveredByMask() {
        GlIsEnabledStateCacheRuntime.disable(GL_BLEND, context);
        GlIsEnabledStateCacheRuntime.disable(GL_TEXTURE_2D, context);
        GlIsEnabledStateCacheRuntime.pushAttrib(GL_COLOR_BUFFER_BIT, context);
        GlIsEnabledStateCacheRuntime.enable(GL_BLEND, context);
        GlIsEnabledStateCacheRuntime.enable(GL_TEXTURE_2D, context);
        GlIsEnabledStateCacheRuntime.popAttrib(context);

        assertEquals(0, GlIsEnabledStateCacheRuntime.cached(GL_BLEND, context));
        assertEquals(1, GlIsEnabledStateCacheRuntime.cached(GL_TEXTURE_2D, context));

        GlIsEnabledStateCacheRuntime.pushAttrib(GL_ENABLE_BIT, context);
        GlIsEnabledStateCacheRuntime.disable(GL_TEXTURE_2D, context);
        GlIsEnabledStateCacheRuntime.enable(GL_STENCIL_TEST, context);
        GlIsEnabledStateCacheRuntime.popAttrib(context);
        assertEquals(1, GlIsEnabledStateCacheRuntime.cached(GL_TEXTURE_2D, context));
        assertEquals(-1, GlIsEnabledStateCacheRuntime.cached(GL_STENCIL_TEST, context));
    }

    @Test
    void displayListCompilationAndCallsForceNativeFallback() {
        GlIsEnabledStateCacheRuntime.enable(GL_SCISSOR_TEST, context);
        assertEquals(1, GlIsEnabledStateCacheRuntime.cached(GL_SCISSOR_TEST, context));

        GlIsEnabledStateCacheRuntime.beginList(context);
        assertEquals(-1, GlIsEnabledStateCacheRuntime.cached(GL_SCISSOR_TEST, context));
        GlIsEnabledStateCacheRuntime.disable(GL_SCISSOR_TEST, context);
        assertEquals(-1, GlIsEnabledStateCacheRuntime.cached(GL_SCISSOR_TEST, context));
        GlIsEnabledStateCacheRuntime.endList(context);
        assertEquals(-1, GlIsEnabledStateCacheRuntime.cached(GL_SCISSOR_TEST, context));

        GlIsEnabledStateCacheRuntime.observedQuery(GL_SCISSOR_TEST, false, context);
        assertEquals(0, GlIsEnabledStateCacheRuntime.cached(GL_SCISSOR_TEST, context));
        GlIsEnabledStateCacheRuntime.callList(context);
        assertEquals(-1, GlIsEnabledStateCacheRuntime.cached(GL_SCISSOR_TEST, context));
    }

    @Test
    void contextChangeDropsEveryKnownValue() {
        Object otherContext = new Object();
        GlIsEnabledStateCacheRuntime.enable(GL_ALPHA_TEST, context);
        GlIsEnabledStateCacheRuntime.disable(GL_LIGHTING, context);
        assertEquals(1, GlIsEnabledStateCacheRuntime.cached(GL_ALPHA_TEST, context));
        assertEquals(0, GlIsEnabledStateCacheRuntime.cached(GL_LIGHTING, context));
        assertEquals(-1, GlIsEnabledStateCacheRuntime.cached(GL_ALPHA_TEST, otherContext));
        assertEquals(1L, GlIsEnabledStateCacheRuntime.telemetry().get("contextChanges"));
    }

    @Test
    void unsupportedCapabilityAlwaysFallsThrough() {
        int unsupported = 0x0B44; // GL_CULL_FACE: deliberately outside FR's tracked six.
        assertEquals(-1, GlIsEnabledStateCacheRuntime.cached(unsupported, context));
        GlIsEnabledStateCacheRuntime.observedQuery(unsupported, true, context);
        assertEquals(-1, GlIsEnabledStateCacheRuntime.cached(unsupported, context));
        assertEquals(2L, GlIsEnabledStateCacheRuntime.telemetry().get("unsupportedQueries"));
    }

    @Test
    void allSixFastRenderingCapabilitiesCanBecomeKnown() {
        int[] caps = {
            GL_STENCIL_TEST, GL_ALPHA_TEST, GL_TEXTURE_2D, GL_BLEND, GL_LIGHTING, GL_SCISSOR_TEST
        };
        for (int cap : caps) {
            GlIsEnabledStateCacheRuntime.enable(cap, context);
            assertEquals(1, GlIsEnabledStateCacheRuntime.cached(cap, context));
        }
    }
}
