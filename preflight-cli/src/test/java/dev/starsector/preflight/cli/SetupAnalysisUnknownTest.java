package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SetupAnalysisUnknownTest {
    @Test
    void unavailableProvidersDoNotBecomeFalseBlockersOrDisappear() {
        SetupAnalysis.Result result = new SetupAnalysis.Result(
                "game",
                "profile",
                List.of(new SetupAnalysis.Finding(
                        "dynamic.not-decidable",
                        "static-links",
                        SetupAnalysis.Severity.UNKNOWN,
                        "Dynamic script behavior is not statically decidable",
                        Map.of(),
                        List.of(),
                        List.of())),
                List.of("save-history"));

        assertTrue(result.ready(), "unknown evidence is not itself a deterministic blocker");
        assertEquals(1, result.count(SetupAnalysis.Severity.UNKNOWN));
        assertEquals(List.of("save-history"), result.unavailableProviders());
    }
}
