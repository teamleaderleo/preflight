package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AtomicBlobsTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void completeReplacementPublishesOnlyTheNewBytes() throws Exception {
        Path target = temporaryDirectory.resolve("cache/blob.bin");
        Files.createDirectories(target.getParent());
        Files.write(target, new byte[] {1, 2, 3});

        AtomicBlobs.write(target, new byte[] {4, 5, 6, 7});

        assertArrayEquals(new byte[] {4, 5, 6, 7}, Files.readAllBytes(target));
        assertEquals(List.of(), temporarySiblings(target));
    }

    @Test
    void failedPublicationPreservesTheDestinationAndRemovesTheTemporaryFile() throws Exception {
        Path target = temporaryDirectory.resolve("cache/existing-directory");
        Files.createDirectories(target);
        Path sentinel = target.resolve("owned.txt");
        Files.writeString(sentinel, "untouched");

        assertThrows(IOException.class, () -> AtomicBlobs.write(target, new byte[] {9, 8, 7}));

        assertTrue(Files.isDirectory(target));
        assertEquals("untouched", Files.readString(sentinel));
        assertEquals(List.of(), temporarySiblings(target));
    }

    private static List<Path> temporarySiblings(Path target) throws IOException {
        String prefix = target.getFileName() + ".tmp-";
        try (var files = Files.list(target.getParent())) {
            return files.filter(path -> path.getFileName().toString().startsWith(prefix)).toList();
        }
    }
}
