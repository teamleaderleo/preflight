package dev.starsector.preflight.core.drift;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class DriftReportTest {
    @Test
    void serializesToJsonAdheringToSchema() {
        DriftReport.DriftSummary summary = new DriftReport.DriftSummary(1, 0, 1, 0, 0, 0, 0, 0);
        ModDriftItem item = new ModDriftItem(
                "mod_sample",
                "Sample Mod",
                "sample_dir",
                ModDriftDetector.DriftSeverity.SAME_VERSION_DRIFT,
                "1.0.0",
                "1.0.0",
                "1111111111111111111111111111111111111111111111111111111111111111",
                "2222222222222222222222222222222222222222222222222222222222222222",
                1000L,
                1000L,
                5,
                5,
                false,
                false,
                true,
                List.of(),
                List.of(),
                List.of(new DriftReport.FileDiffEntry(
                        "data/settings.json", 100, 120, "oldsha", "newsha")),
                List.of(),
                List.of()
        );

        DriftReport report = new DriftReport(
                DriftReport.FORMAT,
                "2026-08-18T00:00:00Z",
                "/Applications/Starsector",
                "CACHE_PROFILE",
                "profile-sha",
                summary,
                List.of(item),
                List.of()
        );

        String json = report.toJson();
        assertTrue(json.contains("\"format\":\"starsector-preflight-mod-drift-v1\""));
        assertTrue(json.contains("\"sameVersionDriftCount\":1"));
        assertTrue(json.contains("\"modId\":\"mod_sample\""));
        assertTrue(json.contains("\"hasDrift\":true"));
        assertEquals(DriftReport.FORMAT, report.format());
    }
}
