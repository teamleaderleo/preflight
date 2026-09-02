package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

class DisplayThreadTextureProbeRuntimeTest {
    @BeforeEach
    void reset() {
        DisplayThreadTextureProbeRuntime.beginSession();
    }

    @AfterEach
    void clear() {
        DisplayThreadTextureProbeRuntime.beginSession();
    }

    @Test
    void transfersTheDisplayContextValidatesBytesAndRestoresTheMainThread() {
        FakeGlApi gl = new FakeGlApi(false);

        DisplayThreadTextureProbeRuntime.executeProof(gl, 2_000L, 100L);

        Map<String, Object> telemetry = DisplayThreadTextureProbeRuntime.telemetry();
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
        assertEquals(73, gl.mainBinding);
        assertEquals(Thread.currentThread(), gl.owner);
        assertEquals(2, gl.deletedTextureIds.size());
    }

    @Test
    void restoresTheDisplayAfterAContainedWorkerAcquireFailure() {
        FakeGlApi gl = new FakeGlApi(true);

        DisplayThreadTextureProbeRuntime.executeProof(gl, 2_000L, 100L);

        Map<String, Object> telemetry = DisplayThreadTextureProbeRuntime.telemetry();
        assertEquals("failed", telemetry.get("status"));
        assertEquals("destroyed", telemetry.get("stage"));
        assertEquals(false, telemetry.get("validated"));
        assertEquals(true, telemetry.get("cleanupComplete"));
        assertEquals(true, telemetry.get("workerTerminated"));
        assertEquals(true, telemetry.get("displayRestored"));
        assertTrue(String.valueOf(telemetry.get("problem"))
                .contains("synthetic acquire failure"));
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

    private static final class FakeGlApi implements DisplayThreadTextureProbeRuntime.ProbeGlApi {
        private final Object display = new Object();
        private final Thread mainThread = Thread.currentThread();
        private final boolean failWorkerAcquire;
        private final AtomicInteger nextTextureId = new AtomicInteger(1);
        private final Map<Integer, byte[]> textures = new LinkedHashMap<>();
        private final List<Integer> deletedTextureIds = new ArrayList<>();
        private final ThreadLocal<Integer> binding = ThreadLocal.withInitial(() -> 0);
        private volatile Thread owner = Thread.currentThread();
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
        public boolean displayIsCurrent() {
            return owner == Thread.currentThread();
        }

        @Override
        public synchronized void displayReleaseContext() {
            assertCurrent();
            owner = null;
        }

        @Override
        public synchronized void displayMakeCurrent() {
            if (owner != null) throw new IllegalStateException("unsafe Display acquire");
            if (failWorkerAcquire && Thread.currentThread() != mainThread) {
                throw new IllegalStateException("synthetic acquire failure");
            }
            owner = Thread.currentThread();
            if (owner == mainThread) binding.set(mainBinding);
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
                throw new IllegalStateException("GL call without current Display context");
            }
        }
    }
}
