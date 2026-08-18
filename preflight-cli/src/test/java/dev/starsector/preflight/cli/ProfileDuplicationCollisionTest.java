package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ProfileDuplicationCollisionTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void destinationCreatedAfterTheFinalCollisionCheckIsNeverOverwritten() throws Exception {
        Fixture fixture = fixture();
        assertEquals(0, ProfileCommand.save(
                fixture.home(), fixture.game(), "Original", false, stream(new ByteArrayOutputStream())));

        Path source = onlyProfileFile(fixture.home());
        byte[] sourceBefore = Files.readAllBytes(source);
        String racedProfile = new String(sourceBefore, StandardCharsets.UTF_8)
                .replace("\"name\":\"Original\"", "\"name\":\"Experiment\"");
        assertFalse(racedProfile.equals(new String(sourceBefore, StandardCharsets.UTF_8)));

        ByteArrayOutputStream preview = new ByteArrayOutputStream();
        assertEquals(0, ProfileCommand.duplicate(
                fixture.home(), fixture.game(), "Original", "Experiment", null,
                false, true, stream(preview)));
        String reviewToken = JsonText.string(
                preview.toString(StandardCharsets.UTF_8), "profileFingerprint");

        AtomicReference<Path> racedTarget = new AtomicReference<>();
        boolean refused = false;
        try {
            ProfileCommand.duplicate(
                    fixture.home(),
                    fixture.game(),
                    "Original",
                    "Experiment",
                    reviewToken,
                    true,
                    true,
                    stream(new ByteArrayOutputStream()),
                    target -> {
                        // This hook is invoked immediately after duplicateOwned's final ordinary
                        // Files.exists collision check and before the final publication primitive.
                        assertFalse(Files.exists(target));
                        racedTarget.set(target);
                        Files.writeString(target, racedProfile, StandardCharsets.UTF_8);
                    });
        } catch (IOException expected) {
            refused = expected.getMessage().contains("already exists");
        }

        assertTrue(refused);
        assertArrayEquals(sourceBefore, Files.readAllBytes(source));
        assertEquals(racedProfile, Files.readString(racedTarget.get(), StandardCharsets.UTF_8));
        assertEquals(2, profileFileCount(fixture.home()));
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
            return files.filter(Files::isRegularFile).count();
        }
    }

    private static PrintStream stream(ByteArrayOutputStream output) {
        return new PrintStream(output, true, StandardCharsets.UTF_8);
    }

    private record Fixture(Path game, PreflightHome home) {
    }
}
