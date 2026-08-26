package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.starsector.preflight.core.PreparedTextureAccessOrderIO;
import dev.starsector.preflight.core.PreparedTexture;
import dev.starsector.preflight.core.PreparedTexturePackOrderIO;
import dev.starsector.preflight.core.TextureManifest;
import dev.starsector.preflight.core.TextureManifestIO;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TexturePreparationScopeTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void fullNeedsNoObservationAndLearnedUsesExactObservedOrder() throws Exception {
        String profile = "12".repeat(32);
        assertEquals(List.of(), TexturePreparationScope.FULL.selectedLogicalPaths(
                temporaryDirectory, profile));
        assertThrows(IOException.class, () -> TexturePreparationScope.LEARNED.selectedLogicalPaths(
                temporaryDirectory, profile));

        Path access = PreparedTextureAccessOrderIO.path(temporaryDirectory, profile);
        PreparedTextureAccessOrderIO.write(
                access, profile, List.of("graphics/second.png", "graphics/first.png"));
        assertEquals(
                List.of("graphics/second.png", "graphics/first.png"),
                TexturePreparationScope.LEARNED.selectedLogicalPaths(
                        temporaryDirectory, profile));
    }

    @Test
    void migratesAnExistingPackOrderToLogicalPaths() throws Exception {
        String profile = "34".repeat(32);
        String firstBlob = "prepared-textures/aa/first.spft";
        String secondBlob = "prepared-textures/bb/second.spft";
        TextureManifest manifest = new TextureManifest(profile, Map.of(
                "graphics/first.png", entry(firstBlob),
                "graphics/second.png", entry(secondBlob)));
        TextureManifestIO.write(
                TextureManifestIO.directory(temporaryDirectory).resolve(profile + ".spfm"),
                manifest);
        PreparedTexturePackOrderIO.write(
                PreparedTexturePackOrderIO.path(temporaryDirectory, profile),
                profile,
                List.of(secondBlob, firstBlob));

        assertEquals(
                List.of("graphics/second.png", "graphics/first.png"),
                TexturePreparationScope.LEARNED.selectedLogicalPaths(
                        temporaryDirectory, profile));
    }

    private static TextureManifest.Entry entry(String blob) {
        return new TextureManifest.Entry(
                "ab".repeat(32), PreparedTexture.Transformation.IDENTITY, blob, 1, 1, 4, 4);
    }
}
