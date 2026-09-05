package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.*;

import dev.starsector.preflight.core.PreparedTexture;
import java.awt.image.DataBufferByte;
import java.awt.image.WritableRaster;
import java.util.Random;
import org.junit.jupiter.api.Test;

class PreparedPixelSnapshotRasterTest {
    @Test
    void snapshotsMatchRgbAndRgbaPixelsAndRemainIndependentWritableCopies() {
        for (int channels : new int[] {3, 4}) {
            byte[] bytes = new byte[7 * 5 * channels];
            new Random(channels).nextBytes(bytes);
            PreparedTexture texture = new PreparedTexture("ab".repeat(32),
                    PreparedTexture.Transformation.IDENTITY, 7, 5, 7, 5, channels, 0, 0, 0, bytes);
            var expected = TexturePreparedPixelCarrierSurface.coherent(texture).raster();
            var snapshot = TexturePreparedPixelCarrierSurface.snapshot(texture);
            assertInstanceOf(DataBufferByte.class, snapshot.getDataBuffer());
            int[] target = new int[channels + 1];
            target[channels] = 999;
            for (int y = 0; y < 5; y++) for (int x = 0; x < 7; x++) {
                assertArrayEquals(expected.getPixel(x, y, (int[]) null), snapshot.getPixel(x, y, (int[]) null));
                assertSame(target, snapshot.getPixel(x, y, target));
                assertEquals(999, target[channels]);
                for (int band = 0; band < channels; band++) assertEquals(expected.getSample(x, y, band), target[band]);
            }
            var second = TexturePreparedPixelCarrierSurface.snapshot(texture);
            int old = second.getSample(0, 0, 0);
            ((WritableRaster) snapshot).setSample(0, 0, 0, old ^ 255);
            assertEquals(old ^ 255, snapshot.getPixel(0, 0, target)[0]);
            assertEquals(old, second.getSample(0, 0, 0));
            snapshot.getDataBuffer().setElem(0, old);
            assertEquals(old, snapshot.getPixel(0, 0, target)[0]);
            assertThrows(ArrayIndexOutOfBoundsException.class, () -> snapshot.getPixel(-1, 0, target));
            assertThrows(ArrayIndexOutOfBoundsException.class, () -> snapshot.getPixel(7, 0, target));
            assertThrows(ArrayIndexOutOfBoundsException.class, () -> snapshot.getPixel(0, 5, target));
            assertThrows(ArrayIndexOutOfBoundsException.class, () -> snapshot.getPixel(0, 0, new int[2]));
        }
    }
}
