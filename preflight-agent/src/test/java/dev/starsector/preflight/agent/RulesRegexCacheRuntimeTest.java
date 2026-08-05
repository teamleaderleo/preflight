package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Random;
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
    void fixedFastPathsMatchRegexAcrossRandomRuleText() {
        Random random = new Random(0x5eedL);
        String alphabet = "ab:# OR\r\n\t\u000b\f\u00a0";
        List<String> replacements = List.of("\\r", "\\n", "\\s+$");
        List<String> splits = List.of("\\n", "\nOR\n", "\\Q:\\E");
        for (int sample = 0; sample < 10_000; sample++) {
            int length = random.nextInt(80);
            StringBuilder input = new StringBuilder(length);
            for (int index = 0; index < length; index++) {
                input.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            String value = input.toString();
            for (String regex : replacements) {
                assertEquals(value.replaceAll(regex, ""),
                        RulesRegexCacheRuntime.replaceAll(value, regex, ""),
                        () -> "replacement mismatch for " + printable(value) + " / " + regex);
            }
            for (String regex : splits) {
                assertArrayEquals(value.split(regex), RulesRegexCacheRuntime.split(value, regex),
                        () -> "split mismatch for " + printable(value) + " / " + regex);
            }
        }
    }

    @Test
    void smartQuoteFastPathsMatchRegexAcrossRandomText() {
        Random random = new Random(0x51a7eL);
        String alphabet = "ab \u201c\u201d\u2018\u2019\ufffd\ud83d\ude80";
        List<List<String>> replacements = List.of(
                List.of("[\\u201c\\u201d]+", "\""),
                List.of("[\\u2018\\u2019\\ufffd]+", "'"));
        for (int sample = 0; sample < 10_000; sample++) {
            int length = random.nextInt(80);
            StringBuilder input = new StringBuilder(length);
            for (int index = 0; index < length; index++) {
                input.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            String value = input.toString();
            for (List<String> replacement : replacements) {
                String regex = replacement.get(0);
                String result = replacement.get(1);
                assertEquals(value.replaceAll(regex, result),
                        RulesRegexCacheRuntime.replaceAll(value, regex, result),
                        () -> "smart-quote mismatch for " + value + " / " + regex);
            }
        }
        assertEquals(20_000L,
                RulesRegexCacheRuntime.telemetry().get("smartQuoteReplacements"));
    }

    @Test
    void invalidAndNullInputsFailLikeString() {
        assertThrows(PatternSyntaxException.class,
                () -> RulesRegexCacheRuntime.split("x", "["));
        assertThrows(NullPointerException.class,
                () -> RulesRegexCacheRuntime.replaceAll(null, "[", ""));
    }

    private static String printable(String value) {
        return value.replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
    }
}
