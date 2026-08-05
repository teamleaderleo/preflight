package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RulesRegexCacheRuntimeTest {
    @BeforeEach
    @AfterEach
    void reset() {
        RulesRegexCacheRuntime.beginSession();
    }

    @Test
    void replacementsMatchStringReplaceAll() {
        for (List<String> row : List.of(
                List.of("a\rb\r", "\\r", ""),
                List.of("a\nb\n", "\\n", ""),
                List.of("condition   \t", "\\s+$", ""),
                List.of("$1 x", "(\\$1)", "[$1]"))) {
            assertEquals(
                    row.get(0).replaceAll(row.get(1), row.get(2)),
                    RulesRegexCacheRuntime.replaceAll(row.get(0), row.get(1), row.get(2)));
        }
        assertEquals(4L, RulesRegexCacheRuntime.telemetry().get("replacements"));
    }

    @Test
    void splitsMatchStringSplitIncludingTrailingEmptyRules() {
        for (List<String> row : List.of(
                List.of("a\nb\n", "\\n"),
                List.of("a\nOR\nb", "\nOR\n"),
                List.of("id:text:more", "\\Q:\\E"),
                List.of("", "\\n"))) {
            assertArrayEquals(
                    row.get(0).split(row.get(1)),
                    RulesRegexCacheRuntime.split(row.get(0), row.get(1)));
        }
        assertEquals(4L, RulesRegexCacheRuntime.telemetry().get("splits"));
    }

    @Test
    void invalidAndNullInputsFailLikeString() {
        assertThrows(PatternSyntaxException.class,
                () -> RulesRegexCacheRuntime.split("x", "["));
        assertThrows(NullPointerException.class,
                () -> RulesRegexCacheRuntime.replaceAll(null, "[", ""));
    }
}
