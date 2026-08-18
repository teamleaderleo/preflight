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

class UninstallHumanOutputTest {
    @Test
    void previewLabelsThePlanAndConfirmationBoundary() {
        UninstallCommand.Plan plan = new UninstallCommand.Plan(
                UninstallCommand.Scope.LAUNCHER,
                true,
                false,
                1024,
                1,
                List.of(new UninstallCommand.Target(
                        "launch-integration", "Preflight launcher", Path.of("/tmp/preflight"), 1024, 1)),
                List.of());

        String output = render(plan, false);

        assertTrue(output.startsWith("Preview: would remove:\n"), output);
        assertTrue(output.contains("Preview only; nothing was removed."), output);
        assertTrue(output.contains("Re-run with --yes to apply this exact scope."), output);
        assertFalse(output.contains("\u001b["), output);
    }

    @Test
    void appliedPlanUsesPastTenseAndClosesCleanly() {
        UninstallCommand.Plan plan = new UninstallCommand.Plan(
                UninstallCommand.Scope.LAUNCHER,
                true,
                true,
                1024,
                1,
                List.of(new UninstallCommand.Target(
                        "launch-integration", "Preflight launcher", Path.of("/tmp/preflight"), 1024, 1)),
                List.of());

        String output = render(plan, false);

        assertTrue(output.startsWith("Removed:\n"), output);
        assertTrue(output.endsWith("Done.\n"), output);
        assertFalse(output.contains("Preview:"), output);
    }

    @Test
    void emptyPreviewStillIdentifiesItselfAsPreview() {
        UninstallCommand.Plan plan = new UninstallCommand.Plan(
                UninstallCommand.Scope.LAUNCHER, true, false, 0, 0, List.of(), List.of());

        assertEquals(
                "Preview: nothing to remove for the selected scope.\n",
                render(plan, false));
    }

    @Test
    void unsafePreviewUsesARefusalHeading() {
        UninstallCommand.Plan plan = new UninstallCommand.Plan(
                UninstallCommand.Scope.ALL_DATA,
                false,
                false,
                0,
                0,
                List.of(),
                List.of("Home path could not be verified."));

        assertEquals(
                "Preview refused:\n  Home path could not be verified.\n",
                render(plan, false));
    }

    @Test
    void jsonOutputKeepsTheMachineDocumentFreeOfHumanDecoration() {
        UninstallCommand.Plan plan = new UninstallCommand.Plan(
                UninstallCommand.Scope.LAUNCHER, true, false, 0, 0, List.of(), List.of());

        String output = render(plan, true);

        assertTrue(output.startsWith("{\"format\":\"preflight-removal-v1\""), output);
        assertTrue(output.contains("\"applied\":false"), output);
        assertFalse(output.contains("Preview:"), output);
        assertFalse(output.contains("\u001b["), output);
        assertEquals(1, output.lines().count(), output);
    }

    private static String render(UninstallCommand.Plan plan, boolean json) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            UninstallCommand.print(plan, json, out);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
