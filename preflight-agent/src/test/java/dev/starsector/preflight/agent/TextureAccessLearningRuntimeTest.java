package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.PreparedTextureAccessOrderIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TextureAccessLearningRuntimeTest {
    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void startClean() {
        TextureAccessLearningRuntime.beginSession();
        TextureCompatibilityRuntime.beginSession();
    }

    @AfterEach
    void reset() {
        TextureAccessLearningRuntime.beginSession();
        TextureCompatibilityRuntime.beginSession();
    }

    @Test
    void learnsFallbackPathsWithoutAPreparedTextureManifest() throws Exception {
        String profile = "ab".repeat(32);
        assertTrue(TextureAccessLearningRuntime.configure(temporaryDirectory, profile));

        assertNull(TextureCompatibilityRuntime.lookup("graphics/ships/first.png"));
        assertNull(TextureCompatibilityRuntime.lookup("graphics/ships/first.png"));
        assertNull(TextureCompatibilityRuntime.lookup("graphics/second.jpg"));
        TextureAccessLearningRuntime.complete();

        Path access = PreparedTextureAccessOrderIO.path(temporaryDirectory, profile);
        assertTrue(Files.isRegularFile(access));
        assertEquals(
                List.of("graphics/ships/first.png", "graphics/second.jpg"),
                PreparedTextureAccessOrderIO.read(access, profile));
    }

    @Test
    void extendsAnExistingProfileOrderWithoutReplacingIt() throws Exception {
        String profile = "cd".repeat(32);
        Path access = PreparedTextureAccessOrderIO.path(temporaryDirectory, profile);
        PreparedTextureAccessOrderIO.write(
                access, profile, List.of("graphics/already.png"));

        assertTrue(TextureAccessLearningRuntime.configure(temporaryDirectory, profile));
        TextureCompatibilityRuntime.lookup("graphics/new.png");
        TextureAccessLearningRuntime.complete();

        assertEquals(
                List.of("graphics/already.png", "graphics/new.png"),
                PreparedTextureAccessOrderIO.read(access, profile));
    }

    @Test
    void replacesADamagedHintInsteadOfDisablingLearning() throws Exception {
        String profile = "ef".repeat(32);
        Path access = PreparedTextureAccessOrderIO.path(temporaryDirectory, profile);
        Files.createDirectories(access.getParent());
        Files.writeString(access, "damaged");

        assertTrue(TextureAccessLearningRuntime.configure(temporaryDirectory, profile));
        assertNull(TextureCompatibilityRuntime.lookup("graphics/recovered.png"));
        TextureAccessLearningRuntime.complete();

        assertEquals(
                List.of("graphics/recovered.png"),
                PreparedTextureAccessOrderIO.read(access, profile));
    }
}
