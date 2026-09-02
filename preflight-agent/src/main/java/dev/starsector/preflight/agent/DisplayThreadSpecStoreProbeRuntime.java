package dev.starsector.preflight.agent;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Intrusive proof that main can execute SpecStore while one worker owns the live Display. */
public final class DisplayThreadSpecStoreProbeRuntime {
    static final String ENABLED_PROPERTY = "preflight.startup.displayThreadSpecStoreProbe";
    static final String CANDIDATE_PROPERTY =
            "preflight.startup.windowsSpecStoreTextureOverlap";
    private static final int MAX_CANDIDATE_PATHS = 8_192;
    private static final int GL_TEXTURE_2D = 0x0DE1;
    private static final int GL_TEXTURE_BINDING_2D = 0x8069;
    private static final int GL_RGBA = 0x1908;
    private static final int GL_UNSIGNED_BYTE = 0x1401;
    private static final int GL_NO_ERROR = 0;
    private static final long ACQUIRE_TIMEOUT_MILLIS = 2_000L;
    private static final long RELEASE_TIMEOUT_MILLIS = 120_000L;
    private static final long JOIN_TIMEOUT_MILLIS = 10_000L;
    private static final AtomicBoolean ATTEMPTED = new AtomicBoolean();
    private static final AtomicLong CANDIDATE_ATTEMPTS = new AtomicLong();
    private static final AtomicLong CANDIDATE_COMPLETIONS = new AtomicLong();
    private static final AtomicLong CANDIDATE_HITS = new AtomicLong();
    private static final AtomicLong CANDIDATE_FAILURES = new AtomicLong();
    private static final Set<String> CANDIDATE_STAGED = ConcurrentHashMap.newKeySet();
    private static final List<String> CANDIDATE_FAILURE_SAMPLES = new ArrayList<>();
    private static final ThreadLocal<Boolean> CANDIDATE_WORKER =
            ThreadLocal.withInitial(() -> false);

    private static volatile Session session;
    private static volatile Object textureLoader;
    private static volatile Method texturePathLoader;
    private static volatile int candidatePlanned;
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
        return syntheticRequested() || candidateRequested();
    }

    private static boolean syntheticRequested() {
        return "on".equalsIgnoreCase(System.getProperty(ENABLED_PROPERTY, "off"));
    }

    static boolean candidateRequested() {
        return Boolean.getBoolean(CANDIDATE_PROPERTY);
    }

    /** Captures the exact Windows TextureLoader singleton without changing ordinary launches. */
    public static void captureTextureLoader(Object loader) {
        if (!candidateRequested() || loader == null) return;
        try {
            Method method = loader.getClass().getDeclaredMethod("o00000", String.class);
            if (!"com.fs.graphics.Object".equals(method.getReturnType().getName())) return;
            method.setAccessible(true);
            textureLoader = loader;
            texturePathLoader = method;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // The exact bytecode plan will decline drift; runtime capture remains fail-open.
        }
    }

    /** Counts later vanilla requests satisfied by the game's own worker-populated path cache. */
    public static void observeTextureRequest(String path) {
        if (!candidateRequested() || Boolean.TRUE.equals(CANDIDATE_WORKER.get()) || path == null) {
            return;
        }
        if (CANDIDATE_STAGED.remove(path)) CANDIDATE_HITS.incrementAndGet();
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
        List<String> candidatePaths = List.of();
        if (candidateRequested()) {
            if (textureLoader == null || texturePathLoader == null) {
                status = "declined-texture-loader-unavailable";
                problem = "Exact Windows TextureLoader singleton was not captured";
                totalNanos = System.nanoTime() - started;
                return;
            }
            candidatePaths = TextureAccessLearningRuntime.snapshot();
            if (candidatePaths.isEmpty()) {
                status = "declined-learned-order-unavailable";
                problem = "No learned prepared-prefetch order was available";
                totalNanos = System.nanoTime() - started;
                return;
            }
            if (candidatePaths.size() > MAX_CANDIDATE_PATHS) {
                candidatePaths = List.copyOf(candidatePaths.subList(0, MAX_CANDIDATE_PATHS));
            }
            candidatePlanned = candidatePaths.size();
        }
        if (!gl.displayIsCurrent()) {
            throw new IllegalStateException("Display was not current before SpecStore");
        }
        int priorBinding = gl.getInteger(GL_TEXTURE_BINDING_2D);
        gl.drainErrors();
        long releaseStarted = System.nanoTime();
        gl.displayReleaseContext();
        displayReleaseNanos = System.nanoTime() - releaseStarted;
        Session replacement = new Session(
                gl, priorBinding, started, candidatePaths, textureLoader, texturePathLoader);
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
            if (!current.candidate) verify(current.gl, current.textureId);
            mainGlError = current.gl.getError();
            if (mainGlError != GL_NO_ERROR) {
                throw new IllegalStateException("main GL error 0x"
                        + Integer.toHexString(mainGlError));
            }
            validationNanos = current.candidate ? 0L : System.nanoTime() - validationStarted;
            validated = true;
            status = current.candidate
                    ? (CANDIDATE_FAILURES.get() == 0
                            ? "candidate-completed" : "candidate-completed-with-fallback")
                    : "validated";
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
            if (!current.candidate && current.textureId != 0) {
                current.gl.deleteTexture(current.textureId);
            }
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
        textureLoader = null;
        texturePathLoader = null;
        candidatePlanned = 0;
        CANDIDATE_ATTEMPTS.set(0L);
        CANDIDATE_COMPLETIONS.set(0L);
        CANDIDATE_HITS.set(0L);
        CANDIDATE_FAILURES.set(0L);
        CANDIDATE_STAGED.clear();
        synchronized (CANDIDATE_FAILURE_SAMPLES) {
            CANDIDATE_FAILURE_SAMPLES.clear();
        }
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
        result.put("candidateProperty", CANDIDATE_PROPERTY);
        result.put("requested", requested());
        result.put("mode", candidateRequested() ? "learned-texture-overlap" : "synthetic-proof");
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
        result.put("candidatePlanned", candidatePlanned);
        result.put("candidateAttempts", CANDIDATE_ATTEMPTS.get());
        result.put("candidateCompletions", CANDIDATE_COMPLETIONS.get());
        result.put("candidateLaterCacheHits", CANDIDATE_HITS.get());
        result.put("candidateUnconsumed", CANDIDATE_STAGED.size());
        result.put("candidateFailures", CANDIDATE_FAILURES.get());
        synchronized (CANDIDATE_FAILURE_SAMPLES) {
            result.put("candidateFailureSamples", List.copyOf(CANDIDATE_FAILURE_SAMPLES));
        }
        result.put("classification", candidateRequested()
                ? "intrusive learned-texture overlap candidate"
                : "intrusive synthetic ownership-overlap probe");
        result.put("semanticEffect", candidateRequested()
                ? "preloads learned textures into vanilla TextureLoader during original SpecStore"
                : "holds Display on a worker while original SpecStore runs");
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
        private final boolean candidate;
        private final List<String> candidatePaths;
        private final Object candidateLoader;
        private final Method candidateMethod;
        private volatile long specStoreStartedNanos;
        private volatile int textureId;
        private volatile Throwable failure;

        private Session(
                DisplayThreadTextureProbeRuntime.ProbeGlApi gl,
                int priorBinding,
                long startedNanos,
                List<String> candidatePaths,
                Object candidateLoader,
                Method candidateMethod) {
            this.gl = gl;
            this.priorBinding = priorBinding;
            this.startedNanos = startedNanos;
            this.candidatePaths = candidatePaths;
            this.candidateLoader = candidateLoader;
            this.candidateMethod = candidateMethod;
            candidate = !candidatePaths.isEmpty();
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
                if (candidate) {
                    current.countDown();
                    CANDIDATE_WORKER.set(true);
                    preloadCandidatePaths();
                } else {
                    textureId = gl.genTexture();
                    gl.bindTexture(GL_TEXTURE_2D, textureId);
                    gl.texImage2d(GL_TEXTURE_2D, 0, GL_RGBA, 4, 4, 0,
                            GL_RGBA, GL_UNSIGNED_BYTE, pixels());
                    gl.finish();
                }
                workerGlError = gl.getError();
                if (workerGlError != GL_NO_ERROR) {
                    throw new IllegalStateException("worker GL error 0x"
                            + Integer.toHexString(workerGlError));
                }
            } catch (Throwable caught) {
                failure = unwrap(caught);
            } finally {
                CANDIDATE_WORKER.remove();
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

        private void preloadCandidatePaths() throws Exception {
            for (String path : candidatePaths) {
                if (release.getCount() == 0L) break;
                CANDIDATE_ATTEMPTS.incrementAndGet();
                try {
                    Object texture = candidateMethod.invoke(candidateLoader, path);
                    if (texture == null) throw new IllegalStateException("loader returned null");
                    CANDIDATE_STAGED.add(path);
                    CANDIDATE_COMPLETIONS.incrementAndGet();
                } catch (Throwable failure) {
                    Throwable cause = unwrap(failure);
                    CANDIDATE_FAILURES.incrementAndGet();
                    synchronized (CANDIDATE_FAILURE_SAMPLES) {
                        if (CANDIDATE_FAILURE_SAMPLES.size() < 8) {
                            CANDIDATE_FAILURE_SAMPLES.add(path + ": " + describe(cause));
                        }
                    }
                    break;
                }
            }
            gl.finish();
        }
    }
}
