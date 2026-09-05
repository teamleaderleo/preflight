package dev.starsector.preflight.agent;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded live-loader memo; no persisted compiler output or bypassed source discovery. */
public final class JaninoUnitMemoRuntime {
    static final String PLAN_ID = "windows-janino-live-unit-memo-v1";
    static final String PROPERTY = "preflight.janino.unitMemo";
    private static final int LIMIT = 4096;
    private static final AtomicLong COMPILED = new AtomicLong(), HITS = new AtomicLong();
    private static final AtomicLong DECLINED = new AtomicLong();
    private static final ClassValue<Method> COMPILE = new ClassValue<>() {
        @Override protected Method computeValue(Class<?> type) {
            try { return type.getMethod("compileUnit", boolean.class, boolean.class, boolean.class); }
            catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
    };
    private static final ClassValue<Field> MEMO = new ClassValue<>() {
        @Override protected Field computeValue(Class<?> type) {
            try {
                Field field = type.getDeclaredField(JaninoUnitMemoPlan.FIELD);
                field.setAccessible(true);
                return field;
            } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
    };

    private static final ClassValue<Field[]> HANDLERS = new ClassValue<>() {
        @Override protected Field[] computeValue(Class<?> type) {
            try {
                Field error = type.getDeclaredField("optionalCompileErrorHandler");
                Field warning = type.getDeclaredField("optionalWarningHandler");
                error.setAccessible(true); warning.setAccessible(true);
                return new Field[] {error, warning};
            } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
    };

    private JaninoUnitMemoRuntime() { }

    static boolean enabled() {
        return AdapterPlanControl.allows(PLAN_ID) && Boolean.getBoolean(PROPERTY);
    }

    static void beginSession() { COMPILED.set(0); HITS.set(0); DECLINED.set(0); }
    static Map<String, Object> report() {
        return Map.of("enabled", enabled(), "compiled", COMPILED.get(), "hits", HITS.get(),
                "declined", DECLINED.get(), "unitLimit", LIMIT);
    }

    /** Called only at the exact loader's compileUnit instruction. Original exceptions propagate. */
    public static Object compile(Object unit, boolean source, boolean lines, boolean vars, Object loader)
            throws Throwable {
        if (!enabled() || !source || !lines || !vars
                || !unit.getClass().getName().equals("org.codehaus.janino.UnitCompiler")
                || !loader.getClass().getName().equals("org.codehaus.janino.JavaSourceClassLoader")) {
            DECLINED.incrementAndGet();
            return original(unit, source, lines, vars);
        }
        Field[] handlers = HANDLERS.get(unit.getClass());
        if (handlers[0].get(unit) != null || handlers[1].get(unit) != null) {
            DECLINED.incrementAndGet();
            return original(unit, source, lines, vars);
        }
        synchronized (loader) {
            @SuppressWarnings("unchecked")
            IdentityHashMap<Object, Object> memo = (IdentityHashMap<Object, Object>) MEMO.get(loader.getClass()).get(loader);
            if (memo == null) {
                memo = new IdentityHashMap<>();
                MEMO.get(loader.getClass()).set(loader, memo);
            }
            Object cached = memo.get(unit);
            if (cached != null) { HITS.incrementAndGet(); return cached; }
            Object result = original(unit, source, lines, vars);
            if (result != null && memo.size() < LIMIT) memo.put(unit, result);
            return result;
        }
    }

    private static Object original(Object unit, boolean source, boolean lines, boolean vars) throws Throwable {
        COMPILED.incrementAndGet();
        try { return COMPILE.get(unit.getClass()).invoke(unit, source, lines, vars); }
        catch (InvocationTargetException error) { throw error.getCause(); }
    }
}
