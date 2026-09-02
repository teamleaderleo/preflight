package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DisplayThreadSpecStoreProbeRuntimeTest {
    @AfterEach
    void clear() {
        System.clearProperty(DisplayThreadSpecStoreProbeRuntime.ENABLED_PROPERTY);
        DisplayThreadSpecStoreProbeRuntime.beginSession();
    }

    @Test
    void workerOwnsDisplayDuringMainCpuIslandThenRestoresAndValidates() throws Exception {
        System.setProperty(DisplayThreadSpecStoreProbeRuntime.ENABLED_PROPERTY, "on");
        DisplayThreadSpecStoreProbeRuntime.beginSession();
        FakeGlApi gl = new FakeGlApi();

        DisplayThreadSpecStoreProbeRuntime.beforeSpecStore(gl);
        assertFalse(gl.displayIsCurrent(), "main must not own Display during the CPU island");
        Thread.sleep(2L);
        DisplayThreadSpecStoreProbeRuntime.afterSpecStore();

        assertTrue(gl.displayIsCurrent());
        Map<String, Object> telemetry = DisplayThreadSpecStoreProbeRuntime.telemetry();
        assertEquals("validated", telemetry.get("status"));
        assertEquals(true, telemetry.get("validated"));
        assertEquals(true, telemetry.get("cleanupComplete"));
        assertEquals(true, telemetry.get("workerTerminated"));
        assertEquals(true, telemetry.get("displayRestored"));
        assertEquals(0, telemetry.get("workerGlError"));
        assertEquals(0, telemetry.get("mainGlError"));
        assertTrue((Double) telemetry.get("specStoreOverlapMicros") > 0d);
    }

    private static final class FakeGlApi implements DisplayThreadTextureProbeRuntime.ProbeGlApi {
        private Thread owner = Thread.currentThread();
        private int binding = 41;
        private ByteBuffer pixels;

        @Override
        public synchronized Object displayDrawable() {
            return this;
        }

        @Override
        public synchronized boolean displayIsCurrent() {
            return owner == Thread.currentThread();
        }

        @Override
        public synchronized void displayReleaseContext() {
            requireCurrent();
            owner = null;
        }

        @Override
        public synchronized void displayMakeCurrent() {
            if (owner != null) throw new IllegalStateException("Display already has an owner");
            owner = Thread.currentThread();
        }

        @Override
        public synchronized int genTexture() {
            requireCurrent();
            return 7;
        }

        @Override
        public synchronized void bindTexture(int target, int texture) {
            requireCurrent();
            binding = texture;
        }

        @Override
        public synchronized void texImage2d(int target, int level, int internalFormat,
                int width, int height, int border, int format, int type, ByteBuffer source) {
            requireCurrent();
            pixels = ByteBuffer.allocateDirect(source.remaining());
            pixels.put(source.duplicate()).flip();
        }

        @Override
        public synchronized void getTexImage(
                int target, int level, int format, int type, ByteBuffer output) {
            requireCurrent();
            output.put(pixels.duplicate()).flip();
        }

        @Override
        public synchronized void deleteTexture(int texture) {
            requireCurrent();
            pixels = null;
        }

        @Override
        public synchronized int getInteger(int name) {
            requireCurrent();
            return binding;
        }

        @Override
        public synchronized int getError() {
            requireCurrent();
            return 0;
        }

        @Override
        public synchronized void finish() {
            requireCurrent();
        }

        @Override
        public synchronized void drainErrors() {
            requireCurrent();
        }

        private void requireCurrent() {
            if (owner != Thread.currentThread()) {
                throw new IllegalStateException("wrong Display owner");
            }
        }
    }
}
