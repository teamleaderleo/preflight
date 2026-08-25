package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvidenceHumanOutputTest {
    @Test
    void prunePreviewLabelsTheProspectiveRemovalAndConfirmationBoundary() throws Exception {
        EvidenceRetention.Plan plan = plan();

        String output = render(plan, false);

        assertTrue(output.startsWith("Preview: would remove 1 evidence sessions"), output);
        assertTrue(output.contains("Preview only; nothing was removed."), output);
        assertTrue(output.contains("Re-run the same command with --yes to apply it."), output);
        assertFalse(output.contains("\u001b["), output);
    }

    @Test
    void pruneJsonKeepsTheMachineDocumentFreeOfHumanPreviewText() throws Exception {
        String output = render(plan(), true);

        assertTrue(output.startsWith("{\"format\":\"starsector-preflight-evidence-prune-v1\""), output);
        assertTrue(output.contains("\"applied\":false"), output);
        assertFalse(output.contains("Preview:"), output);
        assertFalse(output.contains("\u001b["), output);
        assertEquals(1, output.lines().count(), output);
    }

    private static EvidenceRetention.Plan plan() {
        EvidenceRetention.Session session = new EvidenceRetention.Session(
                "run", Path.of("run-20260818"), 1024, 1, 1L, false);
        return new EvidenceRetention.Plan(List.of(session), 0, null);
    }

    private static String render(EvidenceRetention.Plan plan, boolean json) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            EvidenceCommand.prune(plan, false, json, out);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
