package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AssetLintResourceLimitTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void skipsConfigFilesBeyondTheMemorySafetyLimit() throws Exception {
        Path core = temporaryDirectory.resolve("starsector-core");
        Files.createDirectories(core.resolve("data/config"));
        Files.createDirectories(core.resolve("data/hulls"));
        Files.createDirectories(core.resolve("sounds"));
        Files.createDirectories(temporaryDirectory.resolve("mods"));
        Files.writeString(temporaryDirectory.resolve("mods/enabled_mods.json"),
                "{\"enabledMods\":[]}");
        Files.writeString(core.resolve("data/hulls/huge.ship"),
                " ".repeat(AssetLint.MAX_CONFIG_BYTES + 1));

        AssetLint.Result result = AssetLint.scan(temporaryDirectory, null);

        assertTrue(result.findings().stream()
                .noneMatch(finding -> finding.rule().startsWith("config-")));
        @SuppressWarnings("unchecked")
        List<String> diagnostics = (List<String>) result.report().get("diagnostics");
        assertTrue(diagnostics.stream().anyMatch(message -> message.contains("byte safety limit")),
                diagnostics.toString());
    }
}
