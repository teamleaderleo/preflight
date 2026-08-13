package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SupportEvidenceProjectionTest {
    @Test
    void keepsBoundedSupportFieldsAndDropsUnknownOrPathBearingFields() {
        String projected = text(SupportEvidenceProjection.project("benchmark-result.json", """
                {
                  "format":"starsector-preflight-desktop-benchmark-v1",
                  "installRoot":"/Users/example/Starsector",
                  "secret":"do-not-export",
                  "metrics":{
                    "processToMainMenuMs":{
                      "measurementOnly":80000,
                      "optimized":15880,
                      "improvementPercent":80.15,
                      "secret":"nested-secret"
                    }
                  },
                  "summary":{"status":"accepted","path":"C:\\\\private\\\\report"}
                }
                """));

        assertTrue(projected.contains("starsector-preflight-support-evidence-v1"), projected);
        assertTrue(projected.contains("metrics.processToMainMenuMs.optimized"), projected);
        assertTrue(projected.contains("15880"), projected);
        assertTrue(projected.contains("summary.status"), projected);
        assertFalse(projected.contains("installRoot"), projected);
        assertFalse(projected.contains("do-not-export"), projected);
        assertFalse(projected.contains("nested-secret"), projected);
        assertFalse(projected.contains("private"), projected);
    }

    @Test
    void dropsAllowedStringFieldWhenItsValueLooksLikeAnAbsolutePath() {
        String projected = text(SupportEvidenceProjection.project(
                "runtime-state.json",
                "{\"state\":\"/home/example/private\",\"status\":\"stopped\"}"));

        assertFalse(projected.contains("/home/example/private"), projected);
        assertTrue(projected.contains("stopped"), projected);
    }

    @Test
    void projectsJsonlIntoBoundedRecords() {
        String projected = text(SupportEvidenceProjection.project(
                "results.jsonl",
                "{\"seconds\":16.28,\"secret\":\"x\"}\n{\"seconds\":15.88}\n"));

        assertTrue(projected.contains("16.28"), projected);
        assertTrue(projected.contains("15.88"), projected);
        assertFalse(projected.contains("secret"), projected);
    }

    @Test
    void rejectsMalformedJsonlRatherThanCopyingItAsText() {
        assertThrows(IllegalArgumentException.class, () -> SupportEvidenceProjection.project(
                "results.jsonl",
                "{\"seconds\":16.28}\nnot-json\n"));
    }

    @Test
    void boundsFreeFormStrings() {
        String oversized = "x".repeat(SupportEvidenceProjection.MAX_STRING_CHARS + 1);
        String projected = text(SupportEvidenceProjection.project(
                "runtime-state.json",
                "{\"reason\":\"" + oversized + "\",\"status\":\"ok\"}"));

        assertFalse(projected.contains(oversized), projected);
        assertTrue(projected.contains("status"), projected);
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
