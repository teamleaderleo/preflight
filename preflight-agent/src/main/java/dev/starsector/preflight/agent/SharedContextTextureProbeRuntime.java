package dev.starsector.preflight.agent;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Intrusive, opt-in capability proof for #1214. It uploads deterministic textures in an LWJGL 2
 * shared worker context and verifies their bytes from Starsector's live Display context. Normal
 * game textures are never intercepted or replaced by this probe.
 */
public final class SharedContextTextureProbeRuntime {
    static final String ENABLED_PROPERTY = "preflight.startup.sharedContextTextureProbe";
    private static final int GL_TEXTURE_2D = 0x0DE1;
    private static final int GL_TEXTURE_BINDING_2D = 0x8069;
    private static final int GL_RGBA = 0x1908;
    private static final int GL_UNSIGNED_BYTE = 0x1401;
    private static final int GL_NO_ERROR = 0;
    private static final int PBUFFER_SUPPORTED = 1;
    private static final int LARGE_EDGE = 1024;
    private static final long WORKER_TIMEOUT_MILLIS = 15_000L;
    private static final long INTERRUPT_GRACE_MILLIS = 2_000L;

    private static final AtomicBoolean ATTEMPTED = new AtomicBoolean();
    private static volatile boolean installed;
    private static volatile boolean supported;
    private static volatile boolean validated;
    private static volatile boolean cleanupComplete;
    private static volatile boolean workerTerminated;
    private static volatile String status = "not-requested";
    private static volatile String problem;
    private static volatile String mainThread;
    private static volatile String workerThread;
    private static volatile long contextCreateNanos;
    private static volatile long workerUploadNanos;
    private static volatile long mainValidationNanos;
    private static volatile long totalNanos;
    private static volatile int texturesUploaded;
    private static volatile long bytesUploaded;
    private static volatile int workerGlError;
    private static volatile int mainGlError;
    private static volatile int pbufferCapabilities;

    private SharedContextTextureProbeRuntime() {
    }

    static boolean requested() {
        return "on".equalsIgnoreCase(System.getProperty(ENABLED_PROPERTY, "off"));
    }

    /** Called once from the first reviewed Display.update boundary, after Display.create returns. */
    public static void onDisplayBoundary() {
        installed = true;
        if (!requested() || !ATTEMPTED.compareAndSet(false, true)) return;
        long started = System.nanoTime();
        mainThread = threadIdentity(Thread.currentThread());
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            status = "declined-non-windows";
            problem = "#1214 is gated to the exact Windows capability proof";
            totalNanos = System.nanoTime() - started;
            return;
        }

        Object workerDrawable = null;
        GlApi gl = null;
        int priorBinding = 0;
        int[] textureIds = new int[2];
        try {
            gl = GlApi.load(Thread.currentThread().getContextClassLoader());
            priorBinding = gl.getInteger(GL_TEXTURE_BINDING_2D);
            gl.drainErrors();

            long contextStarted = System.nanoTime();
            Object displayDrawable = gl.displayGetDrawable.invoke(null);
            pbufferCapabilities = (Integer) gl.pbufferGetCapabilities.invoke(null);
            if ((pbufferCapabilities & PBUFFER_SUPPORTED) == 0) {
                status = "declined-pbuffer-unsupported";
                problem = "LWJGL reports no Pbuffer support";
                return;
            }
            Object pixelFormat = gl.pixelFormatConstructor.newInstance();
            workerDrawable = gl.pbufferConstructor.newInstance(1, 1, pixelFormat,
                    displayDrawable);
            contextCreateNanos = System.nanoTime() - contextStarted;
            supported = true;

            WorkerResult result = new WorkerResult(gl, workerDrawable, textureIds);
            Thread worker = new Thread(result, "Preflight-Shared-Texture-Probe");
            worker.setDaemon(true);
            worker.start();
            worker.join(WORKER_TIMEOUT_MILLIS);
            workerThread = result.workerIdentity;
            if (worker.isAlive()) {
                status = "worker-timeout";
                problem = "shared-context worker exceeded " + WORKER_TIMEOUT_MILLIS + " ms";
                worker.interrupt();
                worker.join(INTERRUPT_GRACE_MILLIS);
            }
            workerTerminated = !worker.isAlive();
            if (!workerTerminated) return;
            workerUploadNanos = result.uploadNanos;
            workerGlError = result.glError;
            texturesUploaded = result.uploaded;
            bytesUploaded = result.bytes;
            if (result.failure != null) throw result.failure;
            if (workerGlError != GL_NO_ERROR) {
                throw new IllegalStateException("worker GL error 0x"
                        + Integer.toHexString(workerGlError));
            }

            long validationStarted = System.nanoTime();
            verify(gl, textureIds[0], 4);
            verify(gl, textureIds[1], LARGE_EDGE);
            mainGlError = gl.getError();
            if (mainGlError != GL_NO_ERROR) {
                throw new IllegalStateException("main GL error 0x"
                        + Integer.toHexString(mainGlError));
            }
            mainValidationNanos = System.nanoTime() - validationStarted;
            validated = true;
            status = "validated";
        } catch (Throwable failure) {
            problem = describe(unwrap(failure));
            if (!status.endsWith("timeout")) status = "failed";
        } finally {
            if (gl != null && workerTerminated) {
                try {
                    for (int textureId : textureIds) {
                        if (textureId != 0) gl.deleteTexture(textureId);
                    }
                    gl.bindTexture(GL_TEXTURE_2D, priorBinding);
                    if (workerDrawable != null) gl.drawableDestroy.invoke(workerDrawable);
                    cleanupComplete = true;
                } catch (Throwable cleanupFailure) {
                    cleanupComplete = false;
                    if (problem == null) problem = "cleanup: " + describe(unwrap(cleanupFailure));
                    status = "cleanup-failed";
                }
            }
            totalNanos = System.nanoTime() - started;
        }
    }

    private static void verify(GlApi gl, int textureId, int edge) throws Exception {
        gl.bindTexture(GL_TEXTURE_2D, textureId);
        ByteBuffer actual = ByteBuffer.allocateDirect(Math.multiplyExact(edge * edge, 4));
        gl.getTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, actual);
        int size = actual.capacity();
        for (int index = 0; index < size; index++) {
            byte expected = value(index, edge);
            if (actual.get(index) != expected) {
                throw new IllegalStateException("shared texture mismatch edge=" + edge
                        + " byte=" + index + " expected=" + (expected & 0xff)
                        + " actual=" + (actual.get(index) & 0xff));
            }
        }
    }

    private static ByteBuffer pixels(int edge) {
        int size = Math.multiplyExact(edge * edge, 4);
        ByteBuffer pixels = ByteBuffer.allocateDirect(size);
        for (int index = 0; index < size; index++) pixels.put(value(index, edge));
        pixels.flip();
        return pixels;
    }

    private static byte value(int index, int edge) {
        return (byte) ((index * 31 + edge * 17 + (index >>> 7)) & 0xff);
    }

    static synchronized Map<String, Object> telemetry() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("property", ENABLED_PROPERTY);
        result.put("requested", requested());
        result.put("installed", installed);
        result.put("attempted", ATTEMPTED.get());
        result.put("supported", supported);
        result.put("validated", validated);
        result.put("cleanupComplete", cleanupComplete);
        result.put("workerTerminated", workerTerminated);
        result.put("status", status);
        result.put("problem", problem);
        result.put("mainThread", mainThread);
        result.put("workerThread", workerThread);
        result.put("contextCreateMicros", micros(contextCreateNanos));
        result.put("workerUploadMicros", micros(workerUploadNanos));
        result.put("mainValidationMicros", micros(mainValidationNanos));
        result.put("totalMicros", micros(totalNanos));
        result.put("texturesUploaded", texturesUploaded);
        result.put("bytesUploaded", bytesUploaded);
        result.put("workerGlError", workerGlError);
        result.put("mainGlError", mainGlError);
        result.put("workerDrawable", "1x1-pbuffer-shared-with-display");
        result.put("pbufferCapabilities", pbufferCapabilities);
        result.put("classification", "intrusive synthetic capability/correctness probe");
        result.put("semanticEffect", "no normal game texture interception or replacement");
        return Collections.unmodifiableMap(result);
    }

    static synchronized void beginSession() {
        ATTEMPTED.set(false);
        installed = false;
        supported = false;
        validated = false;
        cleanupComplete = false;
        workerTerminated = false;
        status = "not-requested";
        problem = null;
        mainThread = null;
        workerThread = null;
        contextCreateNanos = 0L;
        workerUploadNanos = 0L;
        mainValidationNanos = 0L;
        totalNanos = 0L;
        texturesUploaded = 0;
        bytesUploaded = 0L;
        workerGlError = 0;
        mainGlError = 0;
        pbufferCapabilities = 0;
    }

    private static Double micros(long nanos) {
        return nanos == 0L ? null : nanos / 1_000.0;
    }

    private static String threadIdentity(Thread thread) {
        return thread.getName() + "#" + thread.getId();
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof InvocationTargetException invocation
                && invocation.getCause() != null) current = invocation.getCause();
        return current;
    }

    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getName() + (message == null ? "" : ": " + message);
    }

    private static final class WorkerResult implements Runnable {
        private final GlApi gl;
        private final Object drawable;
        private final int[] textureIds;
        private volatile Throwable failure;
        private volatile String workerIdentity;
        private volatile long uploadNanos;
        private volatile int glError;
        private volatile int uploaded;
        private volatile long bytes;

        private WorkerResult(GlApi gl, Object drawable, int[] textureIds) {
            this.gl = gl;
            this.drawable = drawable;
            this.textureIds = textureIds;
        }

        @Override
        public void run() {
            workerIdentity = threadIdentity(Thread.currentThread());
            boolean current = false;
            try {
                gl.drawableMakeCurrent.invoke(drawable);
                current = true;
                gl.drainErrors();
                long started = System.nanoTime();
                textureIds[0] = upload(gl, 4);
                textureIds[1] = upload(gl, LARGE_EDGE);
                gl.finish();
                uploadNanos = System.nanoTime() - started;
                glError = gl.getError();
                uploaded = 2;
                bytes = 4L * 4L * 4L + (long) LARGE_EDGE * LARGE_EDGE * 4L;
            } catch (Throwable caught) {
                failure = unwrap(caught);
            } finally {
                if (current) {
                    try {
                        gl.drawableRelease.invoke(drawable);
                    } catch (Throwable releaseFailure) {
                        if (failure == null) failure = unwrap(releaseFailure);
                    }
                }
            }
        }

        private static int upload(GlApi gl, int edge) throws Exception {
            int textureId = gl.genTexture();
            gl.bindTexture(GL_TEXTURE_2D, textureId);
            gl.texImage2d(GL_TEXTURE_2D, 0, GL_RGBA, edge, edge, 0,
                    GL_RGBA, GL_UNSIGNED_BYTE, pixels(edge));
            return textureId;
        }
    }

    private static final class GlApi {
        private final Constructor<?> pixelFormatConstructor;
        private final Constructor<?> pbufferConstructor;
        private final Method pbufferGetCapabilities;
        private final Method displayGetDrawable;
        private final Method drawableMakeCurrent;
        private final Method drawableRelease;
        private final Method drawableDestroy;
        private final Method glGenTextures;
        private final Method glBindTexture;
        private final Method glTexImage2d;
        private final Method glGetTexImage;
        private final Method glDeleteTextures;
        private final Method glGetInteger;
        private final Method glGetError;
        private final Method glFinish;

        private GlApi(Class<?> display, Class<?> drawable, Class<?> pixelFormat,
                Class<?> pbuffer, Class<?> gl11)
                throws ReflectiveOperationException {
            pixelFormatConstructor = pixelFormat.getConstructor();
            pbufferConstructor = pbuffer.getConstructor(
                    int.class, int.class, pixelFormat, drawable);
            pbufferGetCapabilities = pbuffer.getMethod("getCapabilities");
            displayGetDrawable = display.getMethod("getDrawable");
            drawableMakeCurrent = drawable.getMethod("makeCurrent");
            drawableRelease = drawable.getMethod("releaseContext");
            drawableDestroy = drawable.getMethod("destroy");
            glGenTextures = gl11.getMethod("glGenTextures");
            glBindTexture = gl11.getMethod("glBindTexture", int.class, int.class);
            glTexImage2d = gl11.getMethod("glTexImage2D", int.class, int.class, int.class,
                    int.class, int.class, int.class, int.class, int.class, ByteBuffer.class);
            glGetTexImage = gl11.getMethod("glGetTexImage", int.class, int.class, int.class,
                    int.class, ByteBuffer.class);
            glDeleteTextures = gl11.getMethod("glDeleteTextures", int.class);
            glGetInteger = gl11.getMethod("glGetInteger", int.class);
            glGetError = gl11.getMethod("glGetError");
            glFinish = gl11.getMethod("glFinish");
        }

        static GlApi load(ClassLoader loader) throws ReflectiveOperationException {
            Class<?> display = Class.forName("org.lwjgl.opengl.Display", true, loader);
            Class<?> drawable = Class.forName("org.lwjgl.opengl.Drawable", true, loader);
            Class<?> pixelFormat = Class.forName("org.lwjgl.opengl.PixelFormat", true, loader);
            Class<?> pbuffer = Class.forName("org.lwjgl.opengl.Pbuffer", true, loader);
            Class<?> gl11 = Class.forName("org.lwjgl.opengl.GL11", true, loader);
            return new GlApi(display, drawable, pixelFormat, pbuffer, gl11);
        }

        int genTexture() throws Exception {
            return (Integer) glGenTextures.invoke(null);
        }

        void bindTexture(int target, int texture) throws Exception {
            glBindTexture.invoke(null, target, texture);
        }

        void texImage2d(int target, int level, int internalFormat, int width, int height,
                int border, int format, int type, ByteBuffer pixels) throws Exception {
            glTexImage2d.invoke(null, target, level, internalFormat, width, height, border,
                    format, type, pixels);
        }

        void getTexImage(int target, int level, int format, int type, ByteBuffer output)
                throws Exception {
            glGetTexImage.invoke(null, target, level, format, type, output);
        }

        void deleteTexture(int texture) throws Exception {
            glDeleteTextures.invoke(null, texture);
        }

        int getInteger(int name) throws Exception {
            return (Integer) glGetInteger.invoke(null, name);
        }

        int getError() throws Exception {
            return (Integer) glGetError.invoke(null);
        }

        void finish() throws Exception {
            glFinish.invoke(null);
        }

        void drainErrors() throws Exception {
            for (int attempts = 0; attempts < 32 && getError() != GL_NO_ERROR; attempts++) {
                // Bound stale-error clearing so the probe starts with an attributable error state.
            }
        }
    }
}
