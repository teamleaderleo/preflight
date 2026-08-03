package dev.starsector.preflight.agent;

import dev.starsector.preflight.core.GeneratedBytecodeCacheWrapper;
import dev.starsector.preflight.core.GeneratedBytecodeContext;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
        POLICY.clear();
        state = State.disabled();
    }

    static void configure(Path cacheRoot, String contextToken) {
        if (cacheRoot == null || contextToken == null || contextToken.isBlank()) {
            state = State.disabled();
            return;
        }
        try {
            state = new State(
                    cacheRoot.toAbsolutePath().normalize(),
                    GeneratedBytecodeContext.fromPortableToken(contextToken),
                    "ready");
        } catch (RuntimeException error) {
            state = new State(null, null, "rejected:" + message(error));
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
        State current = state;
        if (current.cacheRoot == null || current.context == null) {
            return invokeOriginal(loader, requestedClassName);
        }
        if (!livePolicyMatches(loader)) {
            POLICY_DECLINED.incrementAndGet();
            return invokeOriginal(loader, requestedClassName);
        }

        GeneratedBytecodeCacheWrapper.Result result = GeneratedBytecodeCacheWrapper.generate(
                current.cacheRoot,
                current.context,
                requestedClassName,
                ignored -> invokeOriginal(loader, requestedClassName));
        count(result);
        return (Map<String, byte[]>) result.classes();
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
        return values;
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

    private record State(Path cacheRoot, GeneratedBytecodeContext context, String status) {
        static State disabled() {
            return new State(null, null, "disabled");
        }
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
