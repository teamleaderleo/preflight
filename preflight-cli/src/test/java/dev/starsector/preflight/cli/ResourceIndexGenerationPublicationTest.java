package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResourceIndexGenerationPublicationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void finalGenerationRecheckRejectsMutationAfterCaptureBeforePublication() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("publication-race"));
        Path source = Files.writeString(root.resolve("shared.bin"), "AAAA");
        FileTime modified = Files.getLastModifiedTime(source);

        assertThrows(IOException.class, () -> ResourceIndexBuilder.buildStandalone(
                root,
                "mod",
                () -> {
                    Files.writeString(source, "BBBB");
                    Files.setLastModifiedTime(source, modified);
                }));
    }
}
