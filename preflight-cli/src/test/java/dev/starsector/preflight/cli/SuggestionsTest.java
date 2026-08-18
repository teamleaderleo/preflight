package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class SuggestionsTest {
    @Test
    void suggestsAUniquePartialCommandWithoutAcceptingItAsAnAlias() {
        assertEquals(
                "doctor",
                Suggestions.closest("doct", List.of("run", "stop", "doctor", "prepare", "profile")));
        assertEquals(
                "profile",
                Suggestions.closest("prof", List.of("run", "prepare", "profile", "cache")));
    }

    @Test
    void suggestsAUniquePartialLongOption() {
        assertEquals(
                "--launcher",
                Suggestions.closest("--lau", List.of("--game", "--launcher", "--trace-dir")));
    }

    @Test
    void ambiguousPrefixesStayQuiet() {
        assertNull(Suggestions.closest("pre", List.of("prepare", "precheck")));
        assertNull(Suggestions.closest("--pro", List.of("--profile", "--progress")));
    }

    @Test
    void veryShortPrefixesStayQuiet() {
        assertNull(Suggestions.closest("st", List.of("stop", "run")));
        assertNull(Suggestions.closest("--ga", List.of("--game", "--launcher")));
    }

    @Test
    void editDistanceSuggestionsStillHandleActualTypos() {
        assertEquals(
                "cache",
                Suggestions.closest("cahce", List.of("run", "stop", "cache", "profile")));
    }
}
