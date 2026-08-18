package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UninstallExternalWinnerTest {
    @TempDir
    Path home;

    @Test
    void externalGenerationWinningPublicPathAfterQuarantineSurvives() throws Exception {
        PreflightHome preflight = PreflightHome.resolve(Platform.LINUX, home, Map.of());
        Path command = preflight.pathOf(PreflightHome.Id.LINUX_COMMAND);
        Files.createDirectories(command.getParent());
        Files.writeString(command,
                "#!/bin/sh\n"
                        + IntegrationOwnership.POSIX_MARKER
                        + "\nexec java -jar /tmp/preflight.jar run --fast --game /games/Starsector \"$@\"\n");

        String external = "#!/bin/sh\necho external winner\n";
        try (UninstallCommand.RemovalTestHookScope ignored = UninstallCommand.installRemovalTestHook(
                (event, integration, path) -> {
                    if (event == UninstallCommand.RemovalEvent.AFTER_QUARANTINE
                            && integration.id() == PreflightHome.Id.LINUX_COMMAND) {
                        Files.writeString(command, external, StandardCharsets.UTF_8);
                    }
                })) {
            assertEquals(0, UninstallCommand.run(preflight, false, true, quiet()));
        }

        assertTrue(Files.isRegularFile(command));
        assertEquals(external, Files.readString(command));
    }

    private static PrintStream quiet() {
        return new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    }
}
