package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SetupAnalysisContractTest {
    @Test
    void parametersAreCanonicalizedByKey() {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("z", 2);
        parameters.put("a", 1);
        SetupAnalysis.Finding finding = new SetupAnalysis.Finding(
                "dependency.missing",
                "mod-metadata",
                SetupAnalysis.Severity.BLOCKING,
                "A required dependency is missing",
                parameters,
                List.of(),
                List.of());

        assertEquals(List.of("a", "z"), finding.parameters().keySet().stream().toList());
    }

    @Test
    void parameterMapsHaveAnExplicitBound() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        for (int i = 0; i < 33; i++) {
            parameters.put("key-" + i, i);
        }
        assertThrows(IllegalArgumentException.class, () -> new SetupAnalysis.Finding(
                "code",
                "provider",
                SetupAnalysis.Severity.INFO,
                "summary",
                parameters,
                List.of(),
                List.of()));
    }
}
