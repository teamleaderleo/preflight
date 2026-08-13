package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LoadJsonMemoRuntimeTest {
    private static int vanillaCalls;

    @AfterEach
    void reset() {
        LoadJsonMemoRuntime.enable(false);
        LoadJsonMemoRuntime.reset();
        ResolverFixture.INSTANCE.pending = null;
        ResolverFixture.flag = false;
        vanillaCalls = 0;
    }

    @Test
    void prelinkedGuardStillBypassesMemoForBothOneShotResolverStates() throws Throwable {
        installFixtureHandles();
        LoadJsonMemoRuntime.enable(true);
        MethodHandle vanilla = MethodHandles.lookup().findStatic(
                LoadJsonMemoRuntimeTest.class, "vanilla",
                MethodType.methodType(Object.class, String.class));

        Object cached = LoadJsonMemoRuntime.loadJson("data/test.json", vanilla);
        ResolverFixture.INSTANCE.pending = new Object();
        Object restricted = LoadJsonMemoRuntime.loadJson("data/test.json", vanilla);
        ResolverFixture.flag = true;
        Object flagged = LoadJsonMemoRuntime.loadJson("data/test.json", vanilla);

        assertNotSame(cached, restricted);
        assertNotSame(restricted, flagged);
        assertEquals(3, vanillaCalls);
        assertEquals(2L,
                LoadJsonMemoRuntime.report().get("bypassedForPendingResolverState"));
        assertEquals(0L, LoadJsonMemoRuntime.report().get("failures"));
        assertEquals("prelinked-method-handles",
                LoadJsonMemoRuntime.report().get("resolverGuard"));
    }

    private static Object vanilla(String ignored) {
        vanillaCalls++;
        ResolverFixture.INSTANCE.pending = null;
        ResolverFixture.flag = false;
        return new Object();
    }

    private static void installFixtureHandles() throws Exception {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        set("resolverSingleton", lookup.findStatic(ResolverFixture.class, "instance",
                MethodType.methodType(ResolverFixture.class)));
        set("pendingDirectoryGetter", lookup.findGetter(
                ResolverFixture.class, "pending", Object.class));
        set("pendingFlagGetter", lookup.findStaticGetter(
                ResolverFixture.class, "flag", boolean.class));
        Field ready = LoadJsonMemoRuntime.class.getDeclaredField("restrictionProbeReady");
        ready.setAccessible(true);
        ready.setBoolean(null, true);
    }

    private static void set(String fieldName, MethodHandle value) throws Exception {
        Field field = LoadJsonMemoRuntime.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static final class ResolverFixture {
        private static final ResolverFixture INSTANCE = new ResolverFixture();
        private Object pending;
        private static boolean flag;

        private static ResolverFixture instance() {
            return INSTANCE;
        }
    }
}
