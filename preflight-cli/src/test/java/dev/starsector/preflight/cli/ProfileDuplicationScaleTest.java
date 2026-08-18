package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.Json;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ProfileDuplicationScaleTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void duplicatesALargeOrderedProfileWithoutChangingTheLiveSelection() throws Exception {
        List<String> enabled = new ArrayList<>();
        for (int index = 0; index < 128; index++) {
            enabled.add(String.format("mod-%03d", index));
        }
        Path game = Files.createDirectories(temporaryDirectory.resolve("game"));
        Path mods = Files.createDirectories(game.resolve("mods"));
        Path enabledFile = mods.resolve("enabled_mods.json");
        Files.writeString(enabledFile, Json.object(Map.of("enabledMods", enabled)));
        for (String id : enabled) {
            Path directory = Files.createDirectories(mods.resolve(id));
            Files.writeString(directory.resolve("mod_info.json"), "{\"id\":\"" + id + "\"}");
        }
        byte[] liveBefore = Files.readAllBytes(enabledFile);
        PreflightHome home = new PreflightHome(
                temporaryDirectory.resolve("home").resolve(PreflightHome.DIRECTORY_NAME).toAbsolutePath().normalize(),
                List.of());

        ByteArrayOutputStream saved = new ByteArrayOutputStream();
        assertEquals(0, ProfileCommand.save(home, game, "Large source", true, stream(saved)));
        String intendedFingerprint = JsonText.string(saved.toString(StandardCharsets.UTF_8), "profileFingerprint");

        ByteArrayOutputStream preview = new ByteArrayOutputStream();
        assertEquals(0, ProfileCommand.duplicate(
                home, game, "Large source", "Large copy", null, false, true, stream(preview)));
        String reviewToken = JsonText.string(preview.toString(StandardCharsets.UTF_8), "profileFingerprint");
        assertEquals(0, ProfileCommand.duplicate(
                home, game, "Large source", "Large copy", reviewToken, true, true,
                stream(new ByteArrayOutputStream())));

        assertArrayEquals(liveBefore, Files.readAllBytes(enabledFile));
        try (var files = Files.list(home.profiles())) {
            List<Path> profiles = files.filter(Files::isRegularFile).sorted().toList();
            assertEquals(2, profiles.size());
            String copy = profiles.stream()
                    .map(path -> {
                        try {
                            return Files.readString(path, StandardCharsets.UTF_8);
                        } catch (java.io.IOException error) {
                            throw new java.io.UncheckedIOException(error);
                        }
                    })
                    .filter(json -> "Large copy".equals(JsonText.string(json, "name")))
                    .findFirst()
                    .orElseThrow();
            assertEquals(enabled, JsonText.stringArray(copy, "enabledMods"));
            assertEquals(intendedFingerprint, JsonText.string(copy, "profileFingerprint"));
        }
        assertTrue(ProfileCommand.retainedFingerprints(home).fingerprints().contains(intendedFingerprint));
    }

    private static PrintStream stream(ByteArrayOutputStream output) {
        return new PrintStream(output, true, StandardCharsets.UTF_8);
    }
}
