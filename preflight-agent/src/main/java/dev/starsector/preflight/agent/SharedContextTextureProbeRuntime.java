package dev.starsector.preflight.agent;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Intrusive, opt-in ownership-handoff proof for #1215. It releases Starsector's live LWJGL 2
 * Display context, uploads deterministic textures from one shared Pbuffer context, restores the
 * Display on its original thread, and verifies every byte. Normal game textures are never
 * intercepted or replaced by this probe.
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
    private static final int MAX_STAGE_EVENTS = 32;

    private static final AtomicBoolean ATTEMPTED = new AtomicBoolean();
    private static final Object STAGE_LOCK = new Object();
    private static final List<Map<String, Object>> STAGE_EVENTS = new ArrayList<>();
    private static volatile boolean installed;
    private static volatile boolean supported;
    private static volatile boolean validated;
    private static volatile boolean cleanupComplete;
    private static volatile boolean workerTerminated;
    private static volatile boolean displayCurrentBeforeRelease;
    private static volatile boolean displayReleaseAttempted;
    private static volatile boolean displayReleased;
    private static volatile boolean displayRestored;
    private static volatile boolean displayCurrentAfterRestore;
    private static volatile boolean workerCurrentAfterAcquire;
    private static volatile boolean workerCurrentAfterRelease;
    private static volatile String status = "not-requested";
    private static volatile String stage = "not-requested";
    private static volatile String problem;
    private static volatile String mainThread;
    private static volatile String workerThread;
    private static volatile String displayDrawableIdentity;
    private static volatile String workerDrawableIdentity;
    private static volatile long probeStartedNanos;
    private static volatile long contextCreateNanos;
    private static volatile long displayReleaseNanos;
    private static volatile long workerAcquireNanos;
    private static volatile long workerUploadNanos;
    private static volatile long workerReleaseNanos;
    private static volatile long displayRestoreNanos;
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
        probeStartedNanos = System.nanoTime();
        mainThread = threadIdentity(Thread.currentThread());
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            status = "declined-non-windows";
            problem = "#1215 is gated to the exact Windows ownership-handoff proof";
            totalNanos = System.nanoTime() - probeStartedNanos;
            return;
        }

        try {
            executeProof(ReflectionGlApi.load(Thread.currentThread().getContextClassLoader()),
                    WORKER_TIMEOUT_MILLIS, INTERRUPT_GRACE_MILLIS);
        } catch (Throwable failure) {
            problem = describe(unwrap(failure));
            status = "failed-before-handoff";
            transition("failed-before-handoff");
            totalNanos = System.nanoTime() - probeStartedNanos;
        }
    }

    /** Package-private seam for deterministic state-machine tests without loading LWJGL. */
    static void executeProof(ProbeGlApi gl, long workerTimeoutMillis,
            long interruptGraceMillis) {
        if (probeStartedNanos == 0L) probeStartedNanos = System.nanoTime();
        if (mainThread == null) mainThread = threadIdentity(Thread.currentThread());
        Object workerDrawable = null;
        int priorBinding = 0;
        boolean priorBindingCaptured = false;
        int[] textureIds = new int[2];
        WorkerResult result = null;
        boolean workerTimedOut = false;
        try {
            transition("display-current");
            displayCurrentBeforeRelease = gl.displayIsCurrent();
            if (!displayCurrentBeforeRelease) {
                throw new IllegalStateException("Display was not current on the probe thread");
            }
            priorBinding = gl.getInteger(GL_TEXTURE_BINDING_2D);
            priorBindingCaptured = true;
            gl.drainErrors();

            long contextStarted = System.nanoTime();
            Object displayDrawable = gl.displayDrawable();
            displayDrawableIdentity = objectIdentity(displayDrawable);
            pbufferCapabilities = gl.pbufferCapabilities();
            if ((pbufferCapabilities & PBUFFER_SUPPORTED) == 0) {
                status = "declined-pbuffer-unsupported";
                problem = "LWJGL reports no Pbuffer support";
                transition("declined-pbuffer-unsupported");
                return;
            }
            workerDrawable = gl.createSharedPbuffer(displayDrawable);
            workerDrawableIdentity = objectIdentity(workerDrawable);
            contextCreateNanos = System.nanoTime() - contextStarted;
            supported = true;

            transition("display-releasing");
            long releaseStarted = System.nanoTime();
            displayReleaseAttempted = true;
            gl.displayReleaseContext();
            displayReleaseNanos = System.nanoTime() - releaseStarted;
            displayReleased = true;
            transition("display-released");

            result = new WorkerResult(gl, workerDrawable, textureIds);
            Thread worker = new Thread(result, "Preflight-Shared-Texture-Probe");
            worker.setDaemon(true);
            worker.start();
            worker.join(workerTimeoutMillis);
            workerThread = result.workerIdentity;
            if (worker.isAlive()) {
                workerTimedOut = true;
                status = "worker-timeout";
                problem = "ownership worker exceeded " + workerTimeoutMillis + " ms";
                transition("ownership-timeout");
                worker.interrupt();
                worker.join(interruptGraceMillis);
            }
            workerTerminated = !worker.isAlive();
            if (!workerTerminated) {
                // The native ownership call may still complete later. Reacquiring or destroying
                // either context here would race it. The disposable external harness must stop the
                // process; main-thread GL work remains intentionally disabled after this return.
                return;
            }

            restoreDisplay(gl);
            workerUploadNanos = result.uploadNanos;
            workerAcquireNanos = result.acquireNanos;
            workerReleaseNanos = result.releaseNanos;
            workerGlError = result.glError;
            texturesUploaded = result.uploaded;
            bytesUploaded = result.bytes;
            if (workerTimedOut) {
                throw new IllegalStateException("ownership worker exceeded the bounded wait");
            }
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
            transition("validated");
        } catch (Throwable failure) {
            problem = describe(unwrap(failure));
            if (!status.endsWith("timeout")) status = "failed";
            transition(status);
        } finally {
            if (displayReleased && !displayRestored && workerTerminated) {
                try {
                    restoreDisplay(gl);
                } catch (Throwable restoreFailure) {
                    if (problem == null) {
                        problem = "display restore: " + describe(unwrap(restoreFailure));
                    }
                    status = "display-restore-failed";
                    transition("display-restore-failed");
                }
            }
            boolean safeToClean = !displayReleaseAttempted || displayRestored;
            if (safeToClean && (workerTerminated || result == null)) {
                try {
                    for (int textureId : textureIds) {
                        if (textureId != 0) gl.deleteTexture(textureId);
                    }
                    if (priorBindingCaptured) gl.bindTexture(GL_TEXTURE_2D, priorBinding);
                    if (workerDrawable != null) gl.destroyDrawable(workerDrawable);
                    cleanupComplete = true;
                    transition("destroyed");
                } catch (Throwable cleanupFailure) {
                    cleanupComplete = false;
                    if (problem == null) problem = "cleanup: " + describe(unwrap(cleanupFailure));
                    status = "cleanup-failed";
                    transition("cleanup-failed");
                }
            }
            totalNanos = System.nanoTime() - probeStartedNanos;
        }
    }

    private static void restoreDisplay(ProbeGlApi gl) throws Exception {
        transition("display-restoring");
        long restoreStarted = System.nanoTime();
        gl.displayMakeCurrent();
        displayRestoreNanos = System.nanoTime() - restoreStarted;
        displayRestored = true;
        displayCurrentAfterRestore = gl.displayIsCurrent();
        transition("display-restored");
        if (!displayCurrentAfterRestore) {
            throw new IllegalStateException("Display did not become current after worker release");
        }
    }

    private static void verify(ProbeGlApi gl, int textureId, int edge) throws Exception {
        if (textureId == 0) throw new IllegalStateException("worker returned texture id 0");
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
        result.put("displayCurrentBeforeRelease", displayCurrentBeforeRelease);
        result.put("displayReleaseAttempted", displayReleaseAttempted);
        result.put("displayReleased", displayReleased);
        result.put("displayRestored", displayRestored);
        result.put("displayCurrentAfterRestore", displayCurrentAfterRestore);
        result.put("workerCurrentAfterAcquire", workerCurrentAfterAcquire);
        result.put("workerCurrentAfterRelease", workerCurrentAfterRelease);
        result.put("status", status);
        result.put("stage", stage);
        result.put("stages", stageEventsSnapshot());
        result.put("problem", problem);
        result.put("mainThread", mainThread);
        result.put("workerThread", workerThread);
        result.put("displayDrawableIdentity", displayDrawableIdentity);
        result.put("workerDrawableIdentity", workerDrawableIdentity);
        result.put("contextCreateMicros", micros(contextCreateNanos));
        result.put("displayReleaseMicros", micros(displayReleaseNanos));
        result.put("workerAcquireMicros", micros(workerAcquireNanos));
        result.put("workerUploadMicros", micros(workerUploadNanos));
        result.put("workerReleaseMicros", micros(workerReleaseNanos));
        result.put("displayRestoreMicros", micros(displayRestoreNanos));
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
        displayCurrentBeforeRelease = false;
        displayReleaseAttempted = false;
        displayReleased = false;
        displayRestored = false;
        displayCurrentAfterRestore = false;
        workerCurrentAfterAcquire = false;
        workerCurrentAfterRelease = false;
        status = "not-requested";
        stage = "not-requested";
        problem = null;
        mainThread = null;
        workerThread = null;
        displayDrawableIdentity = null;
        workerDrawableIdentity = null;
        probeStartedNanos = 0L;
        contextCreateNanos = 0L;
        displayReleaseNanos = 0L;
        workerAcquireNanos = 0L;
        workerUploadNanos = 0L;
        workerReleaseNanos = 0L;
        displayRestoreNanos = 0L;
        mainValidationNanos = 0L;
        totalNanos = 0L;
        texturesUploaded = 0;
        bytesUploaded = 0L;
        workerGlError = 0;
        mainGlError = 0;
        pbufferCapabilities = 0;
        synchronized (STAGE_LOCK) {
            STAGE_EVENTS.clear();
        }
    }

    private static void transition(String next) {
        stage = next;
        long elapsed = probeStartedNanos == 0L ? 0L : System.nanoTime() - probeStartedNanos;
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("stage", next);
        event.put("thread", threadIdentity(Thread.currentThread()));
        event.put("elapsedMicros", elapsed / 1_000.0);
        synchronized (STAGE_LOCK) {
            if (STAGE_EVENTS.size() < MAX_STAGE_EVENTS) {
                STAGE_EVENTS.add(Collections.unmodifiableMap(event));
            }
        }
    }

    private static List<Map<String, Object>> stageEventsSnapshot() {
        synchronized (STAGE_LOCK) {
            return List.copyOf(STAGE_EVENTS);
        }
    }

    private static Double micros(long nanos) {
        return nanos == 0L ? null : nanos / 1_000.0;
    }

    private static String threadIdentity(Thread thread) {
        return thread.getName() + "#" + thread.getId();
    }

    private static String objectIdentity(Object object) {
        if (object == null) return null;
        return object.getClass().getName() + "@"
                + Integer.toHexString(System.identityHashCode(object));
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

    interface ProbeGlApi {
        Object displayDrawable() throws Exception;
        int pbufferCapabilities() throws Exception;
        Object createSharedPbuffer(Object displayDrawable) throws Exception;
        boolean displayIsCurrent() throws Exception;
        void displayReleaseContext() throws Exception;
        void displayMakeCurrent() throws Exception;
        void drawableMakeCurrent(Object drawable) throws Exception;
        boolean drawableIsCurrent(Object drawable) throws Exception;
        void drawableRelease(Object drawable) throws Exception;
        void destroyDrawable(Object drawable) throws Exception;
        int genTexture() throws Exception;
        void bindTexture(int target, int texture) throws Exception;
        void texImage2d(int target, int level, int internalFormat, int width, int height,
                int border, int format, int type, ByteBuffer pixels) throws Exception;
        void getTexImage(int target, int level, int format, int type, ByteBuffer output)
                throws Exception;
        void deleteTexture(int texture) throws Exception;
        int getInteger(int name) throws Exception;
        int getError() throws Exception;
        void finish() throws Exception;
        void drainErrors() throws Exception;
    }

    private static final class WorkerResult implements Runnable {
        private final ProbeGlApi gl;
        private final Object drawable;
        private final int[] textureIds;
        private volatile Throwable failure;
        private volatile String workerIdentity;
        private volatile long acquireNanos;
        private volatile long uploadNanos;
        private volatile long releaseNanos;
        private volatile int glError;
        private volatile int uploaded;
        private volatile long bytes;

        private WorkerResult(ProbeGlApi gl, Object drawable, int[] textureIds) {
            this.gl = gl;
            this.drawable = drawable;
            this.textureIds = textureIds;
        }

        @Override
        public void run() {
            workerIdentity = threadIdentity(Thread.currentThread());
            workerThread = workerIdentity;
            boolean current = false;
            try {
                transition("worker-acquiring");
                long acquireStarted = System.nanoTime();
                gl.drawableMakeCurrent(drawable);
                acquireNanos = System.nanoTime() - acquireStarted;
                workerAcquireNanos = acquireNanos;
                current = true;
                workerCurrentAfterAcquire = gl.drawableIsCurrent(drawable);
                transition("worker-current");
                if (!workerCurrentAfterAcquire) {
                    throw new IllegalStateException("Pbuffer did not become current on worker");
                }
                gl.drainErrors();
                long started = System.nanoTime();
                transition("uploading-tiny");
                textureIds[0] = upload(gl, 4);
                transition("uploading-representative");
                textureIds[1] = upload(gl, LARGE_EDGE);
                transition("finishing");
                gl.finish();
                uploadNanos = System.nanoTime() - started;
                glError = gl.getError();
                uploaded = 2;
                bytes = 4L * 4L * 4L + (long) LARGE_EDGE * LARGE_EDGE * 4L;
                workerUploadNanos = uploadNanos;
                workerGlError = glError;
                texturesUploaded = uploaded;
                bytesUploaded = bytes;
            } catch (Throwable caught) {
                failure = unwrap(caught);
            } finally {
                if (current) {
                    try {
                        transition("worker-releasing");
                        long releaseStarted = System.nanoTime();
                        gl.drawableRelease(drawable);
                        releaseNanos = System.nanoTime() - releaseStarted;
                        workerReleaseNanos = releaseNanos;
                        workerCurrentAfterRelease = gl.drawableIsCurrent(drawable);
                        transition("worker-released");
                    } catch (Throwable releaseFailure) {
                        if (failure == null) failure = unwrap(releaseFailure);
                    }
                }
                workerTerminated = true;
                totalNanos = System.nanoTime() - probeStartedNanos;
            }
        }

        private static int upload(ProbeGlApi gl, int edge) throws Exception {
            int textureId = gl.genTexture();
            gl.bindTexture(GL_TEXTURE_2D, textureId);
            gl.texImage2d(GL_TEXTURE_2D, 0, GL_RGBA, edge, edge, 0,
                    GL_RGBA, GL_UNSIGNED_BYTE, pixels(edge));
            return textureId;
        }
    }

    private static final class ReflectionGlApi implements ProbeGlApi {
        private final Constructor<?> pixelFormatConstructor;
        private final Constructor<?> pbufferConstructor;
        private final Method pbufferGetCapabilities;
        private final Method displayGetDrawable;
        private final Method displayIsCurrent;
        private final Method displayRelease;
        private final Method displayMakeCurrent;
        private final Method drawableMakeCurrent;
        private final Method drawableIsCurrent;
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

        private ReflectionGlApi(Class<?> display, Class<?> drawable, Class<?> pixelFormat,
                Class<?> pbuffer, Class<?> gl11) throws ReflectiveOperationException {
            pixelFormatConstructor = pixelFormat.getConstructor();
            pbufferConstructor = pbuffer.getConstructor(
                    int.class, int.class, pixelFormat, drawable);
            pbufferGetCapabilities = pbuffer.getMethod("getCapabilities");
            displayGetDrawable = display.getMethod("getDrawable");
            displayIsCurrent = display.getMethod("isCurrent");
            displayRelease = display.getMethod("releaseContext");
            displayMakeCurrent = display.getMethod("makeCurrent");
            drawableMakeCurrent = drawable.getMethod("makeCurrent");
            drawableIsCurrent = drawable.getMethod("isCurrent");
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

        static ReflectionGlApi load(ClassLoader loader) throws ReflectiveOperationException {
            Class<?> display = Class.forName("org.lwjgl.opengl.Display", true, loader);
            Class<?> drawable = Class.forName("org.lwjgl.opengl.Drawable", true, loader);
            Class<?> pixelFormat = Class.forName("org.lwjgl.opengl.PixelFormat", true, loader);
            Class<?> pbuffer = Class.forName("org.lwjgl.opengl.Pbuffer", true, loader);
            Class<?> gl11 = Class.forName("org.lwjgl.opengl.GL11", true, loader);
            return new ReflectionGlApi(display, drawable, pixelFormat, pbuffer, gl11);
        }

        @Override public Object displayDrawable() throws Exception { return displayGetDrawable.invoke(null); }
        @Override public int pbufferCapabilities() throws Exception { return (Integer) pbufferGetCapabilities.invoke(null); }
        @Override public Object createSharedPbuffer(Object displayDrawable) throws Exception {
            return pbufferConstructor.newInstance(1, 1, pixelFormatConstructor.newInstance(), displayDrawable);
        }
        @Override public boolean displayIsCurrent() throws Exception { return (Boolean) displayIsCurrent.invoke(null); }
        @Override public void displayReleaseContext() throws Exception { displayRelease.invoke(null); }
        @Override public void displayMakeCurrent() throws Exception { displayMakeCurrent.invoke(null); }
        @Override public void drawableMakeCurrent(Object drawable) throws Exception { drawableMakeCurrent.invoke(drawable); }
        @Override public boolean drawableIsCurrent(Object drawable) throws Exception { return (Boolean) drawableIsCurrent.invoke(drawable); }
        @Override public void drawableRelease(Object drawable) throws Exception { drawableRelease.invoke(drawable); }
        @Override public void destroyDrawable(Object drawable) throws Exception { drawableDestroy.invoke(drawable); }
        @Override public int genTexture() throws Exception { return (Integer) glGenTextures.invoke(null); }
        @Override public void bindTexture(int target, int texture) throws Exception { glBindTexture.invoke(null, target, texture); }
        @Override public void texImage2d(int target, int level, int internalFormat, int width, int height,
                int border, int format, int type, ByteBuffer pixels) throws Exception {
            glTexImage2d.invoke(null, target, level, internalFormat, width, height, border, format, type, pixels);
        }
        @Override public void getTexImage(int target, int level, int format, int type, ByteBuffer output)
                throws Exception { glGetTexImage.invoke(null, target, level, format, type, output); }
        @Override public void deleteTexture(int texture) throws Exception { glDeleteTextures.invoke(null, texture); }
        @Override public int getInteger(int name) throws Exception { return (Integer) glGetInteger.invoke(null, name); }
        @Override public int getError() throws Exception { return (Integer) glGetError.invoke(null); }
        @Override public void finish() throws Exception { glFinish.invoke(null); }
        @Override public void drainErrors() throws Exception {
            for (int attempts = 0; attempts < 32 && getError() != GL_NO_ERROR; attempts++) {
                // Bound stale-error clearing so the proof starts with an attributable error state.
            }
        }
    }
}
