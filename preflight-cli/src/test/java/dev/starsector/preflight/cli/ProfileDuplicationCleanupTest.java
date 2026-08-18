package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.Json;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ProfileDuplicationCleanupTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void cleanupFailureAfterPublicationCannotTurnASuccessfulDuplicateIntoAReportedFailure()
            throws Exception {
        Fixture fixture = fixture();
        assertEquals(0, ProfileCommand.save(
                fixture.home(), fixture.game(), "Original", false, stream(new ByteArrayOutputStream())));

        Path source = onlyProfileFile(fixture.home());
        byte[] sourceBefore = Files.readAllBytes(source);

        ByteArrayOutputStream preview = new ByteArrayOutputStream();
        assertEquals(0, ProfileCommand.duplicate(
                fixture.home(), fixture.game(), "Original", "Experiment", null,
                false, true, stream(preview)));
        String reviewToken = JsonText.string(
                preview.toString(StandardCharsets.UTF_8), "profileFingerprint");

        ByteArrayOutputStream applied = new ByteArrayOutputStream();
        assertEquals(0, ProfileCommand.duplicate(
                fixture.home(),
                fixture.game(),
                "Original",
                "Experiment",
                reviewToken,
                true,
                true,
                stream(applied),
                new ProfileCommand.DuplicatePublicationHook() {
                    @Override
                    public void beforePublication(Path target) {
                    }

                    @Override
                    public void cleanupStagedProfile(Path staged) throws IOException {
                        Files.deleteIfExists(staged);
                        throw new IOException("synthetic post-publication cleanup failure");
                    }
                }));

        assertTrue(applied.toString(StandardCharsets.UTF_8).contains("\"applied\":true"));
        assertArrayEquals(sourceBefore, Files.readAllBytes(source));
        assertEquals(2, profileFileCount(fixture.home()));

        ByteArrayOutputStream listed = new ByteArrayOutputStream();
        assertEquals(0, ProfileCommand.list(
                fixture.home(), fixture.game(), true, stream(listed)));
        String listJson = listed.toString(StandardCharsets.UTF_8);
        assertTrue(listJson.contains("\"name\":\"Original\""));
        assertTrue(listJson.contains("\"name\":\"Experiment\""));
    }

    private Fixture fixture() throws Exception {
        Path game = Files.createDirectories(temporaryDirectory.resolve("game"));
        Path mods = Files.createDirectories(game.resolve("mods"));
        Files.writeString(
                mods.resolve("enabled_mods.json"),
                Json.object(Map.of("enabledMods", List.of("alpha"))));
        Path alpha = Files.createDirectories(mods.resolve("alpha"));
        Files.writeString(alpha.resolve("mod_info.json"), "{\"id\":\"alpha\"}");
        Path root = temporaryDirectory.resolve("home").resolve(PreflightHome.DIRECTORY_NAME);
        return new Fixture(
                game.toAbsolutePath().normalize(),
                new PreflightHome(root.toAbsolutePath().normalize(), List.of()));
    }

    private static Path onlyProfileFile(PreflightHome home) throws Exception {
        try (var files = Files.list(home.profiles())) {
            return files.filter(Files::isRegularFile).findFirst().orElseThrow();
        }
    }

    private static long profileFileCount(PreflightHome home) throws Exception {
        try (var files = Files.list(home.profiles())) {
            return files.filter(path -> path.getFileName().toString().endsWith(".json")).count();
        }
    }

    private static PrintStream stream(ByteArrayOutputStream output) {
        return new PrintStream(output, true, StandardCharsets.UTF_8);
    }

    private record Fixture(Path game, PreflightHome home) {
    }
}
