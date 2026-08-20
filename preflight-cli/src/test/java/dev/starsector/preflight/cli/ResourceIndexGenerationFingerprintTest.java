package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

import dev.starsector.preflight.core.ResourceIndex;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResourceIndexGenerationFingerprintTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void sameSizeRestoredMtimeMutationChangesGenerationAndProfileFingerprint() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("root"));
        Path file = Files.writeString(root.resolve("shared.bin"), "AAAA");
        FileTime modified = Files.getLastModifiedTime(file);
        ResourceIndex before = ResourceIndexBuilder.buildStandalone(root, "mod").index();
        ResourceIndex.Provider beforeProvider = before.winner("shared.bin").orElseThrow();

        Files.writeString(file, "BBBB");
        Files.setLastModifiedTime(file, modified);
        ResourceIndex after = ResourceIndexBuilder.buildStandalone(root, "mod").index();
        ResourceIndex.Provider afterProvider = after.winner("shared.bin").orElseThrow();

        assertNotEquals(beforeProvider.generationToken(), afterProvider.generationToken());
        assertNotEquals(before.profileFingerprint(), after.profileFingerprint());
    }
}
