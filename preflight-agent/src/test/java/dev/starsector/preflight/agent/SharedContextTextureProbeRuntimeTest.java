package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SharedContextTextureProbeRuntimeTest {
    @BeforeEach
    void reset() {
        SharedContextTextureProbeRuntime.beginSession();
    }

    @AfterEach
    void clear() {
        SharedContextTextureProbeRuntime.beginSession();
    }

    @Test
    void transfersOwnershipValidatesBytesAndRestoresDisplayBeforeCleanup() {
        FakeGlApi gl = new FakeGlApi(false);

        SharedContextTextureProbeRuntime.executeProof(gl, 2_000L, 100L);

        Map<String, Object> telemetry = SharedContextTextureProbeRuntime.telemetry();
        assertEquals("validated", telemetry.get("status"));
        assertEquals("destroyed", telemetry.get("stage"));
        assertEquals(true, telemetry.get("validated"));
        assertEquals(true, telemetry.get("cleanupComplete"));
        assertEquals(true, telemetry.get("workerTerminated"));
        assertEquals(true, telemetry.get("displayReleased"));
        assertEquals(true, telemetry.get("displayRestored"));
        assertEquals(true, telemetry.get("displayCurrentAfterRestore"));
        assertEquals(true, telemetry.get("workerCurrentAfterAcquire"));
        assertEquals(false, telemetry.get("workerCurrentAfterRelease"));
        assertEquals(2, telemetry.get("texturesUploaded"));
        assertEquals(4_194_368L, telemetry.get("bytesUploaded"));
        assertEquals(List.of(
                "display-current",
                "display-releasing",
                "display-released",
                "worker-acquiring",
                "worker-current",
                "uploading-tiny",
                "uploading-representative",
                "finishing",
                "worker-releasing",
                "worker-released",
                "display-restoring",
                "display-restored",
                "validated",
                "destroyed"), stages(telemetry));
        assertTrue(gl.destroyed);
        assertTrue(gl.deletedTextureIds.size() == 2);
        assertEquals(73, gl.mainBinding);
        assertEquals(Thread.currentThread(), gl.owner);
    }

    @Test
    void restoresDisplayAndCleansUpAfterContainedWorkerAcquireFailure() {
        FakeGlApi gl = new FakeGlApi(true);

        SharedContextTextureProbeRuntime.executeProof(gl, 2_000L, 100L);

        Map<String, Object> telemetry = SharedContextTextureProbeRuntime.telemetry();
        assertEquals("failed", telemetry.get("status"));
        assertEquals("destroyed", telemetry.get("stage"));
        assertEquals(false, telemetry.get("validated"));
        assertEquals(true, telemetry.get("cleanupComplete"));
        assertEquals(true, telemetry.get("workerTerminated"));
        assertEquals(true, telemetry.get("displayRestored"));
        assertTrue(String.valueOf(telemetry.get("problem")).contains("synthetic acquire failure"));
        assertTrue(stages(telemetry).contains("display-restored"));
        assertTrue(gl.destroyed);
        assertEquals(Thread.currentThread(), gl.owner);
    }

    @SuppressWarnings("unchecked")
    private static List<String> stages(Map<String, Object> telemetry) {
        List<String> result = new ArrayList<>();
        for (Map<String, Object> event : (List<Map<String, Object>>) telemetry.get("stages")) {
            result.add((String) event.get("stage"));
        }
        return result;
    }

    private static final class FakeGlApi
            implements SharedContextTextureProbeRuntime.ProbeGlApi {
        private final Object display = new Object();
        private final Object pbuffer = new Object();
        private final Thread mainThread = Thread.currentThread();
        private final boolean failWorkerAcquire;
        private final AtomicInteger nextTextureId = new AtomicInteger(1);
        private final Map<Integer, byte[]> textures = new LinkedHashMap<>();
        private final List<Integer> deletedTextureIds = new ArrayList<>();
        private final ThreadLocal<Integer> binding = ThreadLocal.withInitial(() -> 0);
        private volatile Thread owner = mainThread;
        private volatile boolean destroyed;
        private volatile int mainBinding = 73;

        private FakeGlApi(boolean failWorkerAcquire) {
            this.failWorkerAcquire = failWorkerAcquire;
            binding.set(mainBinding);
        }

        @Override
        public Object displayDrawable() {
            assertCurrent();
            return display;
        }

        @Override
        public int pbufferCapabilities() {
            assertCurrent();
            return 3;
        }

        @Override
        public Object createSharedPbuffer(Object displayDrawable) {
            assertCurrent();
            assertEquals(display, displayDrawable);
            return pbuffer;
        }

        @Override
        public boolean displayIsCurrent() {
            return Thread.currentThread() == mainThread && owner == mainThread;
        }

        @Override
        public synchronized void displayReleaseContext() {
            assertCurrent();
            owner = null;
        }

        @Override
        public synchronized void displayMakeCurrent() {
            if (Thread.currentThread() != mainThread || owner != null) {
                throw new IllegalStateException("unsafe display acquire");
            }
            owner = mainThread;
            binding.set(mainBinding);
        }

        @Override
        public synchronized void drawableMakeCurrent(Object drawable) {
            assertEquals(pbuffer, drawable);
            if (failWorkerAcquire) throw new IllegalStateException("synthetic acquire failure");
            if (owner != null) throw new IllegalStateException("display ownership leaked");
            owner = Thread.currentThread();
        }

        @Override
        public boolean drawableIsCurrent(Object drawable) {
            assertEquals(pbuffer, drawable);
            return owner == Thread.currentThread() && Thread.currentThread() != mainThread;
        }

        @Override
        public synchronized void drawableRelease(Object drawable) {
            assertEquals(pbuffer, drawable);
            assertCurrent();
            owner = null;
        }

        @Override
        public void destroyDrawable(Object drawable) {
            assertCurrent();
            assertEquals(pbuffer, drawable);
            destroyed = true;
        }

        @Override
        public int genTexture() {
            assertCurrent();
            return nextTextureId.getAndIncrement();
        }

        @Override
        public void bindTexture(int target, int texture) {
            assertCurrent();
            binding.set(texture);
            if (Thread.currentThread() == mainThread) mainBinding = texture;
        }

        @Override
        public synchronized void texImage2d(int target, int level, int internalFormat,
                int width, int height, int border, int format, int type, ByteBuffer pixels) {
            assertCurrent();
            ByteBuffer source = pixels.duplicate();
            byte[] copy = new byte[source.remaining()];
            source.get(copy);
            textures.put(binding.get(), copy);
        }

        @Override
        public synchronized void getTexImage(int target, int level, int format, int type,
                ByteBuffer output) {
            assertCurrent();
            byte[] source = textures.get(binding.get());
            for (int index = 0; index < source.length; index++) output.put(index, source[index]);
        }

        @Override
        public synchronized void deleteTexture(int texture) {
            assertCurrent();
            textures.remove(texture);
            deletedTextureIds.add(texture);
        }

        @Override
        public int getInteger(int name) {
            assertCurrent();
            return binding.get();
        }

        @Override
        public int getError() {
            assertCurrent();
            return 0;
        }

        @Override
        public void finish() {
            assertCurrent();
        }

        @Override
        public void drainErrors() {
            assertCurrent();
        }

        private void assertCurrent() {
            if (owner != Thread.currentThread()) {
                throw new IllegalStateException("GL call without current context");
            }
        }
    }
}
