package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SlowCallWindowsTest {
    @Test
    void retainsOnlyTheBoundedSlowestExactWindows() {
        SlowCallWindows windows = new SlowCallWindows();
        assertEquals(0L, windows.record(999_999L));
        for (int call = 1; call <= SlowCallWindows.LIMIT + 4; call++) {
            assertTrue(windows.record(call * 1_000_000L) > 0L);
        }

        List<Map<String, Object>> report = windows.report();
        assertEquals(SlowCallWindows.LIMIT, report.size());
        assertEquals(36.0, report.get(0).get("durationMillis"));
        assertEquals(5.0, report.get(report.size() - 1).get("durationMillis"));
        for (Map<String, Object> call : report) {
            double start = ((Number) call.get("startEpochMillis")).doubleValue();
            double end = ((Number) call.get("endEpochMillis")).doubleValue();
            assertEquals(((Number) call.get("durationMillis")).doubleValue(), end - start, 0.001);
        }

        windows.reset();
        assertEquals(List.of(), windows.report());
    }
}
