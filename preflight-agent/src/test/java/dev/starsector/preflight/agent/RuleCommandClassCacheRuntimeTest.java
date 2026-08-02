package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.PreparedRuleCommandClassCache;
import dev.starsector.preflight.core.PreparedRuleCommandClassCacheIO;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the rewritten method rather than inspecting it.
 *
 * <p>The fixture is generated with the game's exact instruction shape, rewritten by the plan, then
 * defined and invoked, so these check what a launch would actually observe: which class comes back,
 * how many classpath lookups it cost, what reached the artifact, and what happens when the artifact
 * is wrong.
 *
 * <p>The two command packages are real classes in this test tree.
 * {@code commandsa.Alpha} is abstract and {@code commandsb.Alpha} is not, which reproduces the one
 * case that must never be shortcut: vanilla loads and <em>initialises</em> the abstract one before
 * {@code newInstance()} fails, so a name resolved past it has a side effect a shortcut would skip.
 * {@code Beta} exists only in the later package, so its whole prefix is
 * {@code ClassNotFoundException} and it is safe.
 */
class RuleCommandClassCacheRuntimeTest {
    private static final String PACKAGE_A = "dev.starsector.preflight.agent.commandsa";
    private static final String PACKAGE_B = "dev.starsector.preflight.agent.commandsb";
    private static final List<String> PACKAGES = List.of(PACKAGE_A, PACKAGE_B);
    private static final String PROFILE = "a".repeat(64);

    @TempDir
    Path cacheDirectory;

    private Path artifact;

    @BeforeEach
    @AfterEach
    void reset() {
        RuleCommandClassCacheRuntime.beginSession();
        RuleCommandLoaderProbe.reset();
    }

    @BeforeEach
    void chooseArtifact() {
        artifact = cacheDirectory.resolve(PROFILE + ".sprk");
    }

    @Test
    void aLearningRunRecordsOnlyTheNameWhoseWholePrefixWasNotFound() throws Exception {
        RuleCommandClassCacheRuntime.configure(artifact);
        Lookup lookup = Lookup.create();

        assertEquals(PACKAGE_B + ".Beta", lookup.resolve("Beta"));
        assertEquals(PACKAGE_B + ".Alpha", lookup.resolve("Alpha"));
        RuleCommandClassCacheRuntime.complete();

        PreparedRuleCommandClassCache written = PreparedRuleCommandClassCacheIO.read(artifact);
        assertEquals(PACKAGES, written.commandPackages());
        assertEquals(PACKAGE_B, written.winningPackage("Beta"));
        assertNull(written.winningPackage("Alpha"),
                "Alpha's prefix initialised an abstract class, so shortcutting it would skip that");
        assertEquals(1L, telemetry().get("uncleanNames"));
        assertEquals(1L, telemetry().get("writes"));
    }

    @Test
    void aWarmRunResolvesTheSameClassInFewerClasspathLookups() throws Exception {
        RuleCommandClassCacheRuntime.configure(artifact);
        Lookup cold = Lookup.create();
        cold.resolve("Beta");
        RuleCommandClassCacheRuntime.complete();
        long coldAttempts = attemptsFor("Beta");

        RuleCommandClassCacheRuntime.beginSession();
        RuleCommandClassCacheRuntime.configure(artifact);
        Lookup warm = Lookup.create();
        RuleCommandLoaderProbe.reset();
        long before = RuleCommandLoaderProbe.ATTEMPTS.get();

        assertEquals(PACKAGE_B + ".Beta", warm.resolve("Beta"));

        long warmAttempts = RuleCommandLoaderProbe.ATTEMPTS.get() - before;
        assertEquals("hit", RuleCommandClassCacheRuntime.status());
        assertEquals(1L, telemetry().get("hits"));
        assertTrue(warmAttempts < coldAttempts,
                "the shortcut exists to remove failed lookups: " + warmAttempts + " vs " + coldAttempts);
        assertEquals(1L, warmAttempts, "only the winning package should be tried");
    }

    @Test
    void aHitStillLoadsInitialisesAndInstantiatesTheWinningClass() throws Exception {
        RuleCommandClassCacheRuntime.configure(artifact);
        Lookup cold = Lookup.create();
        cold.resolve("Beta");
        RuleCommandClassCacheRuntime.complete();

        RuleCommandClassCacheRuntime.beginSession();
        RuleCommandClassCacheRuntime.configure(artifact);
        Lookup warm = Lookup.create();
        int before = dev.starsector.preflight.agent.commandsb.Beta.constructed;

        warm.resolve("Beta");

        // Vanilla constructs one instance and throws it away. Pre-seeding the memo instead would
        // move that class's static initialisation to first use, which is why the hit does the work.
        assertEquals(before + 1, dev.starsector.preflight.agent.commandsb.Beta.constructed);
    }

    @Test
    void aHitMemoisesSoTheSecondCallNeverReachesTheRuntimeAtAll() throws Exception {
        RuleCommandClassCacheRuntime.configure(artifact);
        Lookup cold = Lookup.create();
        cold.resolve("Beta");
        RuleCommandClassCacheRuntime.complete();

        RuleCommandClassCacheRuntime.beginSession();
        RuleCommandClassCacheRuntime.configure(artifact);
        Lookup warm = Lookup.create();
        warm.resolve("Beta");
        int constructed = dev.starsector.preflight.agent.commandsb.Beta.constructed;

        assertEquals(PACKAGE_B + ".Beta", warm.resolve("Beta"));

        assertEquals(1L, telemetry().get("hits"), "the second call must be answered by vanilla's map");
        assertEquals(constructed, dev.starsector.preflight.agent.commandsb.Beta.constructed,
                "a memoised name must not be instantiated again");
    }

    @Test
    void aPackageListThatMovedDisablesEveryShortcut() throws Exception {
        PreparedRuleCommandClassCacheIO.write(artifact, new PreparedRuleCommandClassCache(
                PROFILE, List.of(PACKAGE_B, PACKAGE_A), Map.of("Beta", PACKAGE_B)));
        RuleCommandClassCacheRuntime.configure(artifact);
        Lookup lookup = Lookup.create();

        assertEquals(PACKAGE_B + ".Beta", lookup.resolve("Beta"));

        assertFalse((Boolean) telemetry().get("packagesMatched"),
                "a reordered list can resolve a different class, so no name may be shortcut");
        assertEquals(0L, telemetry().get("hits"));
        assertEquals(1L, telemetry().get("misses"));
    }

    @Test
    void aWinnerThatNoLongerResolvesFallsBackToTheWalkAndStopsTrusting() throws Exception {
        PreparedRuleCommandClassCacheIO.write(artifact, new PreparedRuleCommandClassCache(
                PROFILE, PACKAGES, Map.of("Beta", PACKAGE_A)));
        RuleCommandClassCacheRuntime.configure(artifact);
        Lookup lookup = Lookup.create();

        assertEquals(PACKAGE_B + ".Beta", lookup.resolve("Beta"),
                "a wrong prepared answer must still produce vanilla's answer");

        assertEquals(1L, telemetry().get("disagreements"));
        assertEquals(0L, telemetry().get("hits"));
    }

    @Test
    void anUnknownCommandStillThrowsTheWayItAlwaysDid() throws Exception {
        RuleCommandClassCacheRuntime.configure(artifact);
        Lookup lookup = Lookup.create();

        InvocationTargetException thrown =
                assertThrows(InvocationTargetException.class, () -> lookup.resolve("NoSuchCommand"));

        assertTrue(thrown.getCause() instanceof RuntimeException);
        assertTrue(thrown.getCause().getMessage().contains("NoSuchCommand"));
    }

    @Test
    void withNoArtifactConfiguredNothingIsObservedAndNothingIsWritten() throws Exception {
        Lookup lookup = Lookup.create();

        assertEquals(PACKAGE_B + ".Beta", lookup.resolve("Beta"));
        RuleCommandClassCacheRuntime.complete();

        assertEquals("disabled", RuleCommandClassCacheRuntime.status());
        assertFalse(Files.exists(artifact));
    }

    private long attemptsFor(String name) throws Exception {
        RuleCommandClassCacheRuntime.beginSession();
        Lookup fresh = Lookup.create();
        RuleCommandLoaderProbe.reset();
        long before = RuleCommandLoaderProbe.ATTEMPTS.get();
        fresh.resolve(name);
        return RuleCommandLoaderProbe.ATTEMPTS.get() - before;
    }

    private static Map<String, Object> telemetry() {
        return RuleCommandClassCacheRuntime.telemetry();
    }

    /** One freshly defined copy of the rewritten class, with its own empty memo. */
    private record Lookup(Method method) {
        static Lookup create() throws Exception {
            byte[] original = RuleCommandLookupFixture.vanilla();
            byte[] rewritten = RuleCommandClassCachePlan.transform(
                    ClassSignature.parse(original), original);
            assertNotNull(rewritten);

            Class<?> type = new ByteArrayLoader().define(rewritten);
            Field memo = type.getField(RuleCommandLookupFixture.MEMO_FIELD);
            memo.set(null, new HashMap<String, String>());
            Field packages = type.getField(RuleCommandLookupFixture.PACKAGES_FIELD);
            packages.set(null, PACKAGES);
            return new Lookup(type.getMethod(
                    RuleCommandClassCachePlan.LOOKUP_METHOD, String.class));
        }

        String resolve(String name) throws Exception {
            return (String) method.invoke(null, name);
        }
    }

    private static final class ByteArrayLoader extends ClassLoader {
        private ByteArrayLoader() {
            super(RuleCommandClassCacheRuntimeTest.class.getClassLoader());
        }

        private Class<?> define(byte[] bytes) {
            return defineClass(null, bytes, 0, bytes.length);
        }
    }
}
