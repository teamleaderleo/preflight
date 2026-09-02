package dev.starsector.preflight.agent;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Intrusive proof that main can execute SpecStore while one worker owns the live Display. */
public final class DisplayThreadSpecStoreProbeRuntime {
    static final String ENABLED_PROPERTY = "preflight.startup.displayThreadSpecStoreProbe";
    private static final int GL_TEXTURE_2D = 0x0DE1;
    private static final int GL_TEXTURE_BINDING_2D = 0x8069;
    private static final int GL_RGBA = 0x1908;
    private static final int GL_UNSIGNED_BYTE = 0x1401;
    private static final int GL_NO_ERROR = 0;
    private static final long ACQUIRE_TIMEOUT_MILLIS = 2_000L;
    private static final long RELEASE_TIMEOUT_MILLIS = 120_000L;
    private static final long JOIN_TIMEOUT_MILLIS = 10_000L;
    private static final AtomicBoolean ATTEMPTED = new AtomicBoolean();

    private static volatile Session session;
    private static volatile boolean installed;
    private static volatile boolean validated;
    private static volatile boolean cleanupComplete;
    private static volatile boolean workerTerminated;
    private static volatile boolean displayRestored;
    private static volatile String status = "not-requested";
    private static volatile String problem;
    private static volatile String mainThread;
    private static volatile String workerThread;
    private static volatile long displayReleaseNanos;
    private static volatile long workerAcquireNanos;
    private static volatile long specStoreOverlapNanos;
    private static volatile long workerReleaseNanos;
    private static volatile long displayRestoreNanos;
    private static volatile long validationNanos;
    private static volatile long totalNanos;
    private static volatile int workerGlError;
    private static volatile int mainGlError;

    private DisplayThreadSpecStoreProbeRuntime() {
    }

    static boolean requested() {
        return "on".equalsIgnoreCase(System.getProperty(ENABLED_PROPERTY, "off"));
    }

    public static void beforeSpecStore() {
        installed = true;
        if (!requested() || !ATTEMPTED.compareAndSet(false, true)) return;
        long started = System.nanoTime();
        mainThread = threadIdentity(Thread.currentThread());
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            status = "declined-non-windows";
            problem = "SpecStore ownership proof is gated to Windows";
            totalNanos = System.nanoTime() - started;
            return;
        }
        try {
            start(DisplayThreadTextureProbeRuntime.ReflectionGlApi.load(
                    Thread.currentThread().getContextClassLoader()), started);
        } catch (Throwable failure) {
            abortStart(failure, started);
            throw new IllegalStateException("SpecStore Display ownership proof could not start",
                    unwrap(failure));
        }
    }

    static void beforeSpecStore(DisplayThreadTextureProbeRuntime.ProbeGlApi gl) {
        installed = true;
        if (!requested() || !ATTEMPTED.compareAndSet(false, true)) return;
        long started = System.nanoTime();
        mainThread = threadIdentity(Thread.currentThread());
        try {
            start(gl, started);
        } catch (Throwable failure) {
            abortStart(failure, started);
            throw new IllegalStateException("SpecStore Display ownership proof could not start",
                    unwrap(failure));
        }
    }

    private static void start(
            DisplayThreadTextureProbeRuntime.ProbeGlApi gl, long started) throws Throwable {
        if (!gl.displayIsCurrent()) {
            throw new IllegalStateException("Display was not current before SpecStore");
        }
        int priorBinding = gl.getInteger(GL_TEXTURE_BINDING_2D);
        gl.drainErrors();
        long releaseStarted = System.nanoTime();
        gl.displayReleaseContext();
        displayReleaseNanos = System.nanoTime() - releaseStarted;
        Session replacement = new Session(gl, priorBinding, started);
        session = replacement;
        replacement.worker.start();
        if (!replacement.current.await(ACQUIRE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("Display worker acquisition exceeded "
                    + ACQUIRE_TIMEOUT_MILLIS + " ms");
        }
        if (replacement.failure != null) throw replacement.failure;
        replacement.specStoreStartedNanos = System.nanoTime();
        status = "worker-current-main-running-spec-store";
    }

    public static void afterSpecStore() {
        Session current = session;
        if (current == null) return;
        specStoreOverlapNanos = System.nanoTime() - current.specStoreStartedNanos;
        try {
            current.release.countDown();
            current.worker.join(JOIN_TIMEOUT_MILLIS);
            workerTerminated = !current.worker.isAlive();
            if (!workerTerminated) {
                throw new IllegalStateException("Display worker release exceeded "
                        + JOIN_TIMEOUT_MILLIS + " ms");
            }
            if (current.failure != null) throw current.failure;
            restore(current);
            long validationStarted = System.nanoTime();
            verify(current.gl, current.textureId);
            mainGlError = current.gl.getError();
            if (mainGlError != GL_NO_ERROR) {
                throw new IllegalStateException("main GL error 0x"
                        + Integer.toHexString(mainGlError));
            }
            validationNanos = System.nanoTime() - validationStarted;
            validated = true;
            status = "validated";
        } catch (Throwable failure) {
            failAndRestore(failure);
            throw new IllegalStateException("SpecStore Display ownership proof failed",
                    unwrap(failure));
        } finally {
            cleanup(current);
            totalNanos = System.nanoTime() - current.startedNanos;
            session = null;
        }
    }

    private static void restore(Session current) throws Exception {
        if (displayRestored) return;
        long started = System.nanoTime();
        current.gl.displayMakeCurrent();
        displayRestoreNanos = System.nanoTime() - started;
        displayRestored = current.gl.displayIsCurrent();
        if (!displayRestored) throw new IllegalStateException("Display did not restore on main");
    }

    private static void verify(
            DisplayThreadTextureProbeRuntime.ProbeGlApi gl, int textureId) throws Exception {
        if (textureId == 0) throw new IllegalStateException("worker returned texture id 0");
        gl.bindTexture(GL_TEXTURE_2D, textureId);
        ByteBuffer actual = ByteBuffer.allocateDirect(4 * 4 * 4);
        gl.getTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, actual);
        for (int index = 0; index < actual.capacity(); index++) {
            byte expected = value(index);
            if (actual.get(index) != expected) {
                throw new IllegalStateException("SpecStore texture mismatch at byte " + index);
            }
        }
    }

    private static void cleanup(Session current) {
        if (current == null || !displayRestored) return;
        try {
            if (current.textureId != 0) current.gl.deleteTexture(current.textureId);
            current.gl.bindTexture(GL_TEXTURE_2D, current.priorBinding);
            cleanupComplete = true;
        } catch (Throwable failure) {
            cleanupComplete = false;
            if (problem == null) problem = "cleanup: " + describe(unwrap(failure));
            status = "cleanup-failed";
        }
    }

    private static void failAndRestore(Throwable failure) {
        problem = describe(unwrap(failure));
        status = "failed";
        Session current = session;
        if (current == null) return;
        current.release.countDown();
        try {
            current.worker.join(JOIN_TIMEOUT_MILLIS);
            workerTerminated = !current.worker.isAlive();
            if (workerTerminated) restore(current);
        } catch (Throwable restoreFailure) {
            problem += "; restore: " + describe(unwrap(restoreFailure));
            status = "restore-failed";
        }
    }

    private static void abortStart(Throwable failure, long started) {
        failAndRestore(failure);
        Session current = session;
        cleanup(current);
        totalNanos = System.nanoTime() - started;
        session = null;
    }

    static void beginSession() {
        ATTEMPTED.set(false);
        session = null;
        installed = false;
        validated = false;
        cleanupComplete = false;
        workerTerminated = false;
        displayRestored = false;
        status = requested() ? "requested" : "not-requested";
        problem = null;
        mainThread = null;
        workerThread = null;
        displayReleaseNanos = 0L;
        workerAcquireNanos = 0L;
        specStoreOverlapNanos = 0L;
        workerReleaseNanos = 0L;
        displayRestoreNanos = 0L;
        validationNanos = 0L;
        totalNanos = 0L;
        workerGlError = 0;
        mainGlError = 0;
    }

    static synchronized Map<String, Object> telemetry() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("property", ENABLED_PROPERTY);
        result.put("requested", requested());
        result.put("installed", installed);
        result.put("attempted", ATTEMPTED.get());
        result.put("validated", validated);
        result.put("cleanupComplete", cleanupComplete);
        result.put("workerTerminated", workerTerminated);
        result.put("displayRestored", displayRestored);
        result.put("status", status);
        result.put("problem", problem);
        result.put("mainThread", mainThread);
        result.put("workerThread", workerThread);
        result.put("displayReleaseMicros", micros(displayReleaseNanos));
        result.put("workerAcquireMicros", micros(workerAcquireNanos));
        result.put("specStoreOverlapMicros", micros(specStoreOverlapNanos));
        result.put("workerReleaseMicros", micros(workerReleaseNanos));
        result.put("displayRestoreMicros", micros(displayRestoreNanos));
        result.put("validationMicros", micros(validationNanos));
        result.put("totalMicros", micros(totalNanos));
        result.put("workerGlError", workerGlError);
        result.put("mainGlError", mainGlError);
        result.put("classification", "intrusive synthetic ownership-overlap probe");
        result.put("semanticEffect", "holds Display on a worker while original SpecStore runs");
        return result;
    }

    private static Double micros(long nanos) {
        return nanos == 0L ? null : Math.round(nanos / 100.0) / 10.0;
    }

    private static byte value(int index) {
        return (byte) ((index * 31 + 17) & 0xff);
    }

    private static ByteBuffer pixels() {
        ByteBuffer pixels = ByteBuffer.allocateDirect(4 * 4 * 4);
        for (int index = 0; index < pixels.capacity(); index++) pixels.put(value(index));
        pixels.flip();
        return pixels;
    }

    private static String threadIdentity(Thread thread) {
        return thread.getName() + "#" + thread.getId();
    }

    private static String describe(Throwable failure) {
        return failure.getClass().getName() + ": " + String.valueOf(failure.getMessage());
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable cursor = failure;
        while (cursor instanceof java.lang.reflect.InvocationTargetException invocation
                && invocation.getCause() != null) cursor = invocation.getCause();
        return cursor;
    }

    private static final class Session {
        private final DisplayThreadTextureProbeRuntime.ProbeGlApi gl;
        private final int priorBinding;
        private final long startedNanos;
        private final CountDownLatch current = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final Thread worker;
        private volatile long specStoreStartedNanos;
        private volatile int textureId;
        private volatile Throwable failure;

        private Session(
                DisplayThreadTextureProbeRuntime.ProbeGlApi gl,
                int priorBinding,
                long startedNanos) {
            this.gl = gl;
            this.priorBinding = priorBinding;
            this.startedNanos = startedNanos;
            worker = new Thread(this::run, "Preflight-SpecStore-Display-Probe");
            worker.setDaemon(true);
        }

        private void run() {
            workerThread = threadIdentity(Thread.currentThread());
            boolean currentContext = false;
            try {
                long started = System.nanoTime();
                gl.displayMakeCurrent();
                workerAcquireNanos = System.nanoTime() - started;
                currentContext = true;
                if (!gl.displayIsCurrent()) {
                    throw new IllegalStateException("Display did not become current on worker");
                }
                gl.drainErrors();
                textureId = gl.genTexture();
                gl.bindTexture(GL_TEXTURE_2D, textureId);
                gl.texImage2d(GL_TEXTURE_2D, 0, GL_RGBA, 4, 4, 0,
                        GL_RGBA, GL_UNSIGNED_BYTE, pixels());
                gl.finish();
                workerGlError = gl.getError();
                if (workerGlError != GL_NO_ERROR) {
                    throw new IllegalStateException("worker GL error 0x"
                            + Integer.toHexString(workerGlError));
                }
            } catch (Throwable caught) {
                failure = unwrap(caught);
            } finally {
                current.countDown();
            }
            try {
                if (failure == null
                        && !release.await(RELEASE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                    failure = new IllegalStateException("SpecStore overlap exceeded "
                            + RELEASE_TIMEOUT_MILLIS + " ms");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                failure = interrupted;
            } finally {
                if (currentContext) {
                    try {
                        long started = System.nanoTime();
                        gl.displayReleaseContext();
                        workerReleaseNanos = System.nanoTime() - started;
                    } catch (Throwable caught) {
                        if (failure == null) failure = unwrap(caught);
                    }
                }
            }
        }
    }
}
