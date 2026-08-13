package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fs.starfarer.api.util.Misc;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The memo is only worth having if a hit is indistinguishable from a scan. These compare answers
 * against {@link Misc#tokenize}, which reproduces the shipped tokenizer's relevant properties.
 */
class RuleTokenCacheRuntimeTest {
    private static final String PROFILE = "b".repeat(64);
    private static MethodHandle vanilla;

    @TempDir
    Path temporary;

    @BeforeEach
    @AfterEach
    void reset() throws Exception {
        RuleTokenCacheRuntime.beginSession();
        Misc.tokenizeCalls = 0;
        vanilla = MethodHandles.publicLookup().findStatic(
                Misc.class, "tokenize", MethodType.methodType(List.class, String.class));
    }

    @Test
    void withTheGateShutEveryCallReachesTheTokenizer() throws Throwable {
        List<?> first = tokenize("$player.credits > 1000");
        List<?> second = tokenize("$player.credits > 1000");

        assertEquals(3, first.size());
        assertEquals(2, Misc.tokenizeCalls, "the memo must be inert until it is enabled");
        assertEquals(0L, RuleTokenCacheRuntime.telemetry().get("calls"));
        assertNotSame(first, second);
    }

    @Test
    void aHitReproducesTheScanExactlyWithoutRescanning() throws Throwable {
        RuleTokenCacheRuntime.enable();
        List<?> scanned = tokenize("$entity.isHostile == true");
        int afterMiss = Misc.tokenizeCalls;

        List<?> memoised = tokenize("$entity.isHostile == true");

        assertEquals(afterMiss, Misc.tokenizeCalls, "a hit must not reach the tokenizer");
        assertEquals(1L, RuleTokenCacheRuntime.telemetry().get("hits"));
        assertSameTokens(scanned, memoised);
    }

    @Test
    void everyDerivedFieldOnAVariableTokenSurvivesTheMemo() throws Throwable {
        RuleTokenCacheRuntime.enable();
        List<?> scanned = tokenize("$market.size");
        List<?> memoised = tokenize("$market.size");

        Misc.Token original = (Misc.Token) scanned.get(0);
        Misc.Token rebuilt = (Misc.Token) memoised.get(0);
        assertEquals("market", original.varMemoryKey, "the fixture must exercise the derived fields");
        assertEquals(original.varMemoryKey, rebuilt.varMemoryKey);
        assertEquals(original.varNameWithoutMemoryKeyIfKeyIsValid,
                rebuilt.varNameWithoutMemoryKeyIfKeyIsValid);
    }

    @Test
    void nothingIsEverSharedBetweenTwoAnswers() throws Throwable {
        RuleTokenCacheRuntime.enable();
        List<?> first = tokenize("FireBest DialogOptionSelected");
        List<?> second = tokenize("FireBest DialogOptionSelected");

        assertNotSame(first, second, "the caller removes from this list");
        for (int index = 0; index < first.size(); index++) {
            assertNotSame(first.get(index), second.get(index),
                    "Token has public mutable fields and must never be shared");
        }
        // Mutating one answer must not be visible in another, which is the failure a shared token
        // would produce: one rule rewriting another rule's parsed expression.
        ((Misc.Token) second.get(0)).string = "mutated";
        List<?> third = tokenize("FireBest DialogOptionSelected");
        assertEquals("FireBest", ((Misc.Token) third.get(0)).string);
    }

    @Test
    void theCallerMayMutateTheListItReceives() throws Throwable {
        RuleTokenCacheRuntime.enable();
        tokenize("ShowDefaultVisual now");
        List<?> memoised = tokenize("ShowDefaultVisual now");

        memoised.remove(0);

        List<?> again = tokenize("ShowDefaultVisual now");
        assertEquals(2, again.size(), "removing from one answer must not shrink the cached shape");
    }

    @Test
    void aMalformedExpressionThrowsTheSameWayItAlwaysDid() {
        RuleTokenCacheRuntime.enable();

        assertThrows(IllegalArgumentException.class, () -> tokenize("   "),
                "the tokenizer's own exception must propagate unwrapped");
        assertEquals(1, Misc.tokenizeCalls);
        assertEquals(0L, RuleTokenCacheRuntime.telemetry().get("hits"));
    }

    @Test
    void anUnusableTokenClassFallsBackToTheTokenizer() throws Throwable {
        RuleTokenCacheRuntime.enable();

        List<?> tokens = (List<?>) RuleTokenCacheRuntime.tokenize(
                "$isPerson", vanilla, String.class);

        assertEquals(1, tokens.size());
        assertEquals(1, Misc.tokenizeCalls);
        assertTrue((Long) RuleTokenCacheRuntime.telemetry().get("declined") > 0,
                "a token class the runtime cannot read must be declined, not guessed at");
    }

    @Test
    void repeatedExpressionsCollapseToOneScanEach() throws Throwable {
        RuleTokenCacheRuntime.enable();
        for (int round = 0; round < 50; round++) {
            tokenize("FireAll PopulateOptions");
            tokenize("$hasMarket");
        }

        assertEquals(2, Misc.tokenizeCalls, "two distinct strings are two scans, however often used");
        assertEquals(100L, RuleTokenCacheRuntime.telemetry().get("calls"));
        assertEquals(98L, RuleTokenCacheRuntime.telemetry().get("hits"));
        assertEquals(2L, RuleTokenCacheRuntime.telemetry().get("misses"));
    }

    @Test
    void completedStartupPublishesAndNextSessionServesPreparedShapes() throws Throwable {
        Path rules = temporary.resolve(PROFILE + ".sprc");
        RuleTokenCacheRuntime.configure(rules);
        List<?> learned = tokenize("$market.size > 3");
        assertEquals(1, Misc.tokenizeCalls);

        RuleTokenCacheRuntime.complete();
        assertTrue(Files.isRegularFile(temporary.resolve(PROFILE + ".sprt")));
        assertEquals(1L, RuleTokenCacheRuntime.telemetry().get("writes"));

        RuleTokenCacheRuntime.beginSession();
        Misc.tokenizeCalls = 0;
        RuleTokenCacheRuntime.configure(rules);
        List<?> restored = tokenize("$market.size > 3");

        assertEquals(0, Misc.tokenizeCalls);
        assertSameTokens(learned, restored);
        assertEquals(1L, RuleTokenCacheRuntime.telemetry().get("preparedHits"));
        assertEquals(0L, RuleTokenCacheRuntime.telemetry().get("misses"));
    }

    @Test
    void corruptPreparedArtifactFallsBackToVanillaAndCanBeRelearned() throws Throwable {
        Path rules = temporary.resolve(PROFILE + ".sprc");
        Files.write(temporary.resolve(PROFILE + ".sprt"), new byte[] {1, 2, 3});

        RuleTokenCacheRuntime.configure(rules);
        assertEquals(2, tokenize("FireBest now").size());

        assertEquals(1, Misc.tokenizeCalls);
        assertEquals(1L, RuleTokenCacheRuntime.telemetry().get("loadFailures"));
        assertEquals(1L, RuleTokenCacheRuntime.telemetry().get("misses"));
    }

    private static List<?> tokenize(String expression) throws Throwable {
        return RuleTokenCacheRuntime.tokenize(expression, vanilla, Misc.Token.class);
    }

    private static void assertSameTokens(List<?> expected, List<?> actual) {
        assertEquals(expected.size(), actual.size());
        for (int index = 0; index < expected.size(); index++) {
            Misc.Token left = (Misc.Token) expected.get(index);
            Misc.Token right = (Misc.Token) actual.get(index);
            assertEquals(left.string, right.string);
            assertEquals(left.type, right.type);
            assertEquals(left.varMemoryKey, right.varMemoryKey);
            assertEquals(left.varNameWithoutMemoryKeyIfKeyIsValid,
                    right.varNameWithoutMemoryKeyIfKeyIsValid);
        }
    }
}
