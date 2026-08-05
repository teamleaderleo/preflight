package dev.starsector.preflight.agent;

import dev.starsector.preflight.core.GeneratedBytecodeCacheWrapper;
import dev.starsector.preflight.core.GeneratedBytecodeContext;
import dev.starsector.preflight.core.GeneratedBytecodePack;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Fail-open runtime for Janino's exact complete-map compilation seam. */
public final class JaninoBytecodeCacheRuntime {
    static final String PLAN_ID = "janino-complete-bytecode-map-cache-v1";
    private static final String EXACT_LOADER = "org.codehaus.janino.JavaSourceClassLoader";

    private static final AtomicLong CALLS = new AtomicLong();
    private static final AtomicLong HITS = new AtomicLong();
    private static final AtomicLong MISSES = new AtomicLong();
    private static final AtomicLong CORRUPT = new AtomicLong();
    private static final AtomicLong ERRORS = new AtomicLong();
    private static final AtomicLong STORED = new AtomicLong();
    private static final AtomicLong POLICY_DECLINED = new AtomicLong();
    private static final AtomicLong INSIDE_NANOS = new AtomicLong();
    private static final AtomicLong HIT_NANOS = new AtomicLong();
    private static final AtomicLong ORIGINAL_NANOS = new AtomicLong();
    private static final AtomicLong POLICY_DECLINED_NANOS = new AtomicLong();
    private static final AtomicLong PACK_HITS = new AtomicLong();
    private static final AtomicLong PACK_MISSES = new AtomicLong();
    private static final AtomicLong PACK_WRITES = new AtomicLong();
    private static final AtomicLong PACK_ERRORS = new AtomicLong();
    private static final ConcurrentHashMap<Class<?>, PolicyAccess> POLICY = new ConcurrentHashMap<>();

    private static volatile State state = State.disabled();

    private JaninoBytecodeCacheRuntime() {
    }

    static void beginSession() {
        CALLS.set(0);
        HITS.set(0);
        MISSES.set(0);
        CORRUPT.set(0);
        ERRORS.set(0);
        STORED.set(0);
        POLICY_DECLINED.set(0);
        INSIDE_NANOS.set(0);
        HIT_NANOS.set(0);
        ORIGINAL_NANOS.set(0);
        POLICY_DECLINED_NANOS.set(0);
        PACK_HITS.set(0);
        PACK_MISSES.set(0);
        PACK_WRITES.set(0);
        PACK_ERRORS.set(0);
        POLICY.clear();
        state = State.disabled();
    }

    static void configure(Path cacheRoot, String contextToken) {
        if (cacheRoot == null || contextToken == null || contextToken.isBlank()) {
            state = State.disabled();
            return;
        }
        try {
            Path root = cacheRoot.toAbsolutePath().normalize();
            GeneratedBytecodeContext context =
                    GeneratedBytecodeContext.fromPortableToken(contextToken);
            Path packPath = GeneratedBytecodePack.path(root, context.keySha256());
            GeneratedBytecodePack pack = null;
            String packStatus = "capture";
            long loadStarted = System.nanoTime();
            if (Files.isRegularFile(packPath)) {
                try {
                    GeneratedBytecodePack candidate = GeneratedBytecodePack.read(packPath);
                    if (context.keySha256().equals(candidate.contextKeySha256())) {
                        pack = candidate;
                        packStatus = "hit";
                    } else {
                        packStatus = "context-mismatch";
                    }
                } catch (Exception error) {
                    packStatus = "rejected:" + message(error);
                }
            }
            state = new State(root, context, "ready", packPath, pack, packStatus,
                    (System.nanoTime() - loadStarted) / 1_000_000L,
                    pack == null
                            ? new GeneratedBytecodePack.Builder(context.keySha256())
                            : new GeneratedBytecodePack.Builder(pack));
        } catch (RuntimeException error) {
            state = State.rejected("rejected:" + message(error));
        }
    }

    static boolean ready() {
        State current = state;
        return current.cacheRoot != null && current.context != null;
    }

    static String status() {
        return state.status;
    }

    /** Called only by the woven Janino method; its erased map type avoids a Janino dependency. */
    @SuppressWarnings("unchecked")
    public static Map<String, byte[]> generate(Object loader, String requestedClassName)
            throws ClassNotFoundException {
        CALLS.incrementAndGet();
        long started = System.nanoTime();
        TimingPath timingPath = TimingPath.ORIGINAL;
        try {
            State current = state;
            if (current.cacheRoot == null || current.context == null) {
                return invokeOriginal(loader, requestedClassName);
            }
            if (!livePolicyMatches(loader)) {
                POLICY_DECLINED.incrementAndGet();
                timingPath = TimingPath.POLICY_DECLINED;
                return invokeOriginal(loader, requestedClassName);
            }

            if (current.pack != null) {
                Map<String, byte[]> packed = current.pack.classesFor(requestedClassName);
                if (packed != null) {
                    HITS.incrementAndGet();
                    PACK_HITS.incrementAndGet();
                    timingPath = TimingPath.HIT;
                    return packed;
                }
                PACK_MISSES.incrementAndGet();
            }

            GeneratedBytecodeCacheWrapper.Result result = GeneratedBytecodeCacheWrapper.generate(
                    current.cacheRoot,
                    current.context,
                    requestedClassName,
                    ignored -> invokeOriginal(loader, requestedClassName));
            count(result);
            if (result.cacheUsable() && current.builder != null) {
                current.builder.record(requestedClassName, result.classes());
            }
            timingPath = result.source() == GeneratedBytecodeCacheWrapper.Source.CACHE_HIT
                    ? TimingPath.HIT
                    : TimingPath.ORIGINAL;
            return (Map<String, byte[]>) result.classes();
        } finally {
            recordTiming(timingPath, System.nanoTime() - started);
        }
    }

    static Map<String, Object> telemetry() {
        State current = state;
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("planId", PLAN_ID);
        values.put("status", current.status);
        values.put("calls", CALLS.get());
        values.put("hits", HITS.get());
        values.put("misses", MISSES.get());
        values.put("corrupt", CORRUPT.get());
        values.put("errors", ERRORS.get());
        values.put("stored", STORED.get());
        values.put("livePolicyDeclined", POLICY_DECLINED.get());
        values.put("insideMillis", millis(INSIDE_NANOS.get()));
        values.put("hitInsideMillis", millis(HIT_NANOS.get()));
        values.put("originalInsideMillis", millis(ORIGINAL_NANOS.get()));
        values.put("livePolicyDeclinedInsideMillis", millis(POLICY_DECLINED_NANOS.get()));
        values.put("packStatus", current.packStatus);
        values.put("packArtifact", current.packPath);
        values.put("packLoadMillis", current.packLoadMillis);
        values.put("packRequests", current.pack == null ? 0 : current.pack.requestCount());
        values.put("packClasses", current.pack == null ? 0 : current.pack.classCount());
        values.put("packUniqueBytes", current.pack == null ? 0 : current.pack.uniqueBytecodeBytes());
        values.put("packExpandedBytes", current.pack == null ? 0 : current.pack.expandedBytecodeBytes());
        values.put("packHits", PACK_HITS.get());
        values.put("packMisses", PACK_MISSES.get());
        values.put("packWrites", PACK_WRITES.get());
        values.put("packErrors", PACK_ERRORS.get());
        return values;
    }

    /** Publishes a deduplicated pack after a successful session; the next launch consumes it. */
    static void complete() {
        State current = state;
        if (current.builder == null || current.packPath == null
                || !current.completed.compareAndSet(false, true)) {
            return;
        }
        try {
            GeneratedBytecodePack pack = current.builder.build();
            if (pack != null) {
                GeneratedBytecodePack.write(current.packPath, pack);
                PACK_WRITES.incrementAndGet();
            }
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable error) {
            PACK_ERRORS.incrementAndGet();
            System.err.println("[Preflight] Janino bytecode pack could not be written: "
                    + message(error) + "; individual bundles remain active");
        }
    }

    private static void recordTiming(TimingPath path, long nanos) {
        long elapsed = Math.max(0, nanos);
        INSIDE_NANOS.addAndGet(elapsed);
        switch (path) {
            case HIT -> HIT_NANOS.addAndGet(elapsed);
            case ORIGINAL -> ORIGINAL_NANOS.addAndGet(elapsed);
            case POLICY_DECLINED -> POLICY_DECLINED_NANOS.addAndGet(elapsed);
        }
    }

    private static long millis(long nanos) {
        return nanos / 1_000_000L;
    }

    private static void count(GeneratedBytecodeCacheWrapper.Result result) {
        switch (result.lookupStatus()) {
            case HIT -> HITS.incrementAndGet();
            case MISS -> MISSES.incrementAndGet();
            case CORRUPT -> CORRUPT.incrementAndGet();
            case ERROR -> ERRORS.incrementAndGet();
        }
        if (result.source() == GeneratedBytecodeCacheWrapper.Source.ORIGINAL_STORED) {
            STORED.incrementAndGet();
        }
    }

    private static boolean livePolicyMatches(Object loader) {
        if (loader == null || !EXACT_LOADER.equals(loader.getClass().getName())) {
            return false;
        }
        try {
            PolicyAccess access = POLICY.computeIfAbsent(loader.getClass(), PolicyAccess::inspect);
            return access.valid
                    && access.debugSource.getBoolean(loader)
                    && access.debugLines.getBoolean(loader)
                    && access.debugVars.getBoolean(loader)
                    && access.protectionDomainFactory.get(loader) == null;
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, byte[]> invokeOriginal(Object loader, String requestedClassName)
            throws ClassNotFoundException {
        if (loader == null) {
            throw new ClassNotFoundException(requestedClassName + " (Janino loader is null)");
        }
        try {
            Method original = null;
            for (Class<?> type = loader.getClass(); type != null; type = type.getSuperclass()) {
                try {
                    original = type.getDeclaredMethod(
                            JaninoBytecodeCachePlan.ORIGINAL_METHOD, String.class);
                    break;
                } catch (NoSuchMethodException ignored) {
                    // A caching loader may subclass the exact transformed Janino implementation.
                }
            }
            if (original == null) {
                throw new NoSuchMethodException(JaninoBytecodeCachePlan.ORIGINAL_METHOD);
            }
            original.setAccessible(true);
            return (Map<String, byte[]>) original.invoke(loader, requestedClassName);
        } catch (InvocationTargetException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof ClassNotFoundException missing) throw missing;
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new ClassNotFoundException(requestedClassName, cause);
        } catch (ReflectiveOperationException | RuntimeException failed) {
            throw new ClassNotFoundException(requestedClassName, failed);
        }
    }

    private static String message(Throwable error) {
        String value = error.getMessage();
        return value == null || value.isBlank() ? error.getClass().getSimpleName() : value;
    }

    private record State(
            Path cacheRoot,
            GeneratedBytecodeContext context,
            String status,
            Path packPath,
            GeneratedBytecodePack pack,
            String packStatus,
            long packLoadMillis,
            GeneratedBytecodePack.Builder builder,
            AtomicBoolean completed) {
        private State(
                Path cacheRoot,
                GeneratedBytecodeContext context,
                String status,
                Path packPath,
                GeneratedBytecodePack pack,
                String packStatus,
                long packLoadMillis,
                GeneratedBytecodePack.Builder builder) {
            this(cacheRoot, context, status, packPath, pack, packStatus, packLoadMillis, builder,
                    new AtomicBoolean());
        }

        static State disabled() {
            return rejected("disabled");
        }

        static State rejected(String status) {
            return new State(null, null, status, null, null, "disabled", 0L, null);
        }
    }

    private enum TimingPath {
        HIT,
        ORIGINAL,
        POLICY_DECLINED
    }

    private static final class PolicyAccess {
        private final boolean valid;
        private final Field debugSource;
        private final Field debugLines;
        private final Field debugVars;
        private final Field protectionDomainFactory;

        private PolicyAccess(
                boolean valid,
                Field debugSource,
                Field debugLines,
                Field debugVars,
                Field protectionDomainFactory) {
            this.valid = valid;
            this.debugSource = debugSource;
            this.debugLines = debugLines;
            this.debugVars = debugVars;
            this.protectionDomainFactory = protectionDomainFactory;
        }

        private static PolicyAccess inspect(Class<?> loaderClass) {
            try {
                Field source = accessible(loaderClass.getDeclaredField("debugSource"));
                Field lines = accessible(loaderClass.getDeclaredField("debugLines"));
                Field vars = accessible(loaderClass.getDeclaredField("debugVars"));
                Field protection = null;
                for (Class<?> type = loaderClass; type != null; type = type.getSuperclass()) {
                    try {
                        protection = accessible(type.getDeclaredField("optionalProtectionDomainFactory"));
                        break;
                    } catch (NoSuchFieldException ignored) {
                        // Continue through Janino's compiler superclass to java.lang.ClassLoader.
                    }
                }
                if (protection == null) {
                    return invalid();
                }
                return new PolicyAccess(true, source, lines, vars, protection);
            } catch (ReflectiveOperationException | RuntimeException error) {
                return invalid();
            }
        }

        private static Field accessible(Field field) {
            field.setAccessible(true);
            return field;
        }

        private static PolicyAccess invalid() {
            return new PolicyAccess(false, null, null, null, null);
        }
    }
}
