package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.genir.renderer.overrides.loading.ResourceHandle;
import com.genir.renderer.overrides.loading.TextureData;
import dev.starsector.preflight.core.PreparedTexture;
import java.awt.Color;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FastRenderingPreparedTextureRuntimeTest {
    @BeforeEach
    void reset() {
        FastRenderingPreparedTextureRuntime.beginSession();
    }

    @Test
    void createsTheExactPublicCarrierWithoutCopyingOrChangingLayout() throws Exception {
        byte[] pixels = new byte[] {1, 2, 3, 4, 5, 6, 7, 8};
        PreparedTexture texture = new PreparedTexture(
                "1".repeat(64),
                PreparedTexture.Transformation.IDENTITY,
                2,
                1,
                2,
                1,
                4,
                PreparedTexture.rgba(10, 20, 30, 40),
                PreparedTexture.rgba(50, 60, 70, 80),
                PreparedTexture.rgba(90, 100, 110, 120),
                pixels);

        TextureData carrier = (TextureData) FastRenderingPreparedTextureRuntime.createCarrier(
                ResourceHandle.class.getClassLoader(), texture);

        assertEquals(2, carrier.width);
        assertEquals(1, carrier.height);
        assertTrue(carrier.hasAlpha);
        assertFalse(carrier.isDDS);
        assertEquals(new Color(10, 20, 30, 40), carrier.mean);
        assertEquals(new Color(50, 60, 70, 80), carrier.weighted);
        assertEquals(new Color(90, 100, 110, 120), carrier.median);
        assertTrue(carrier.buffer.isReadOnly());
        byte[] found = new byte[carrier.buffer.remaining()];
        carrier.buffer.get(found);
        assertArrayEquals(pixels, found);
        assertTrue(FastRenderingPreparedTextureRuntime.supported(texture));
        assertTrue(FastRenderingPreparedTextureRuntime.supportedPort(texture));
        PreparedTexture transparent = new PreparedTexture("4".repeat(64),
                PreparedTexture.Transformation.IDENTITY, 1, 1, 1, 1, 4,
                0, 0, 0, new byte[4]);
        assertFalse(FastRenderingPreparedTextureRuntime.supportedPort(transparent));

        var port = (com.genir.renderer.overrides.loading.textures.TextureData)
                FastRenderingPreparedTextureRuntime.createCarrier(
                        ResourceHandle.class.getClassLoader(), texture, true);
        assertEquals(2, port.imageWidth);
        assertEquals(1, port.imageHeight);
        assertEquals(carrier.width, port.width);
        assertEquals(carrier.height, port.height);
        assertEquals(carrier.mean, port.mean);
        assertEquals(carrier.weighted, port.weighted);
        assertEquals(carrier.median, port.median);
        org.junit.jupiter.api.Assertions.assertNull(port.ddsImagePath);
        assertTrue(port.buffer.isReadOnly());
        byte[] portPixels = new byte[port.buffer.remaining()];
        port.buffer.get(portPixels);
        assertArrayEquals(pixels, portPixels);
        // Carrier shape cache must not confuse two releases in the same class loader.
        assertTrue(FastRenderingPreparedTextureRuntime.createCarrier(
                ResourceHandle.class.getClassLoader(), texture) instanceof TextureData);
    }

    @Test
    void rejectsTransformedOrPaddedLayouts() {
        PreparedTexture transformed = new PreparedTexture(
                "2".repeat(64),
                PreparedTexture.Transformation.ALPHA_ADDER,
                1,
                1,
                2,
                2,
                3,
                0,
                0,
                0,
                new byte[12]);
        PreparedTexture padded = new PreparedTexture(
                "3".repeat(64),
                PreparedTexture.Transformation.IDENTITY,
                1,
                1,
                2,
                2,
                3,
                0,
                0,
                0,
                new byte[12]);
        assertFalse(FastRenderingPreparedTextureRuntime.supported(transformed));
        assertFalse(FastRenderingPreparedTextureRuntime.supported(padded));
    }
}
