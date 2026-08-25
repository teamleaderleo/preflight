package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class EvidenceSelectionTest {
    @Test
    void selectsExactNamedSessionsWithoutUsingPathsOrRecency() {
        EvidenceRetention.Session newest = session("run", "ordinary-run", 300);
        EvidenceRetention.Session paired = session("run", "desktop-benchmark-20260812T100000Z", 200);
        EvidenceRetention.Session campaign = session("benchmark", "campaign-20260812", 100);
        EvidenceRetention.Inventory inventory = new EvidenceRetention.Inventory(
                List.of(newest, paired), List.of(campaign));

        EvidenceRetention.Inventory selected = EvidenceCommand.selectSessions(
                inventory,
                List.of("desktop-benchmark-20260812T100000Z"),
                List.of());

        assertEquals(List.of(paired), selected.runs());
        assertEquals(List.of(), selected.benchmarks());
    }

    @Test
    void refusesUnknownOrDuplicateSessionNames() {
        EvidenceRetention.Session paired = session("run", "desktop-benchmark-20260812T100000Z", 200);
        EvidenceRetention.Inventory inventory = new EvidenceRetention.Inventory(List.of(paired), List.of());

        assertThrows(IllegalArgumentException.class, () -> EvidenceCommand.selectSessions(
                inventory, List.of("missing"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> EvidenceCommand.selectSessions(
                inventory,
                List.of("desktop-benchmark-20260812T100000Z", "desktop-benchmark-20260812T100000Z"),
                List.of()));
    }

    private static EvidenceRetention.Session session(String kind, String name, long modifiedMillis) {
        return new EvidenceRetention.Session(
                kind, Path.of("evidence").resolve(name), 100, 1, modifiedMillis, false, false);
    }
}
