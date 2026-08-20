package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.starsector.preflight.core.Hashes;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpenedFileGenerationAuthorityTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void twoUnchangedExactReadsKeepOneGeneration() throws Exception {
        Path file = Files.writeString(temporaryDirectory.resolve("source.bin"), "AAAA");
        OpenedFileGenerationAuthority.Generation generation =
                OpenedFileGenerationAuthority.capture(file);
        if (OpenedFileGenerationAuthority.WINDOWS_PROVIDER.equals(generation.provider())) {
            assertEquals(4, generation.token().split(":", -1).length,
                    "volume, high file ID, low file ID, and USN must be unambiguous fields");
        }

        OpenedFileGenerationAuthority.HashEvidence first =
                OpenedFileGenerationAuthority.hash(file, generation);
        OpenedFileGenerationAuthority.HashEvidence second =
                OpenedFileGenerationAuthority.hash(file, generation);

        assertEquals(generation, first.generation());
        assertEquals(generation, second.generation());
        assertEquals(Hashes.sha256("AAAA".getBytes(StandardCharsets.UTF_8)), first.sha256());
        assertEquals(first.sha256(), second.sha256());
    }

    @Test
    void linuxCtimePolicyRejectsSecondGranularityFilesystems() {
        assertTrue(OpenedFileGenerationAuthority.supportsLinuxFilesystem("ext4"));
        assertFalse(OpenedFileGenerationAuthority.supportsLinuxFilesystem("ext2"));
        assertFalse(OpenedFileGenerationAuthority.supportsLinuxFilesystem("ext3"));
    }

    @Test
    void macCtimePolicyIsApfsOnly() {
        assertTrue(OpenedFileGenerationAuthority.supportsMacFilesystem("apfs"));
        assertFalse(OpenedFileGenerationAuthority.supportsMacFilesystem("hfs"));
        assertFalse(OpenedFileGenerationAuthority.supportsMacFilesystem("hfsplus"));
    }

    @Test
    void sameSizeMutationWithRestoredMtimeAdvancesGeneration() throws Exception {
        Path file = Files.writeString(temporaryDirectory.resolve("source.bin"), "AAAA");
        FileTime modified = Files.getLastModifiedTime(file);
        OpenedFileGenerationAuthority.Generation before =
                OpenedFileGenerationAuthority.capture(file);

        Files.writeString(file, "BBBB");
        Files.setLastModifiedTime(file, modified);

        OpenedFileGenerationAuthority.Generation after =
                OpenedFileGenerationAuthority.capture(file);
        assertNotEquals(before, after);
    }

    @Test
    void sameInodeMutationDuringOpenedReadIsRejectedEvenWhenBytesAndMtimeReturn() throws Exception {
        Path file = Files.writeString(temporaryDirectory.resolve("source.bin"), "AAAA");
        FileTime modified = Files.getLastModifiedTime(file);
        OpenedFileGenerationAuthority.Generation reviewed =
                OpenedFileGenerationAuthority.capture(file);

        assertThrows(IOException.class, () -> OpenedFileGenerationAuthority.hash(
                file,
                reviewed,
                (publicPath, input) -> {
                    Files.writeString(publicPath, "BBBB");
                    Files.setLastModifiedTime(publicPath, modified);
                    String raced = Hashes.sha256(input);
                    Files.writeString(publicPath, "AAAA");
                    Files.setLastModifiedTime(publicPath, modified);
                    return raced;
                }));
    }

    @Test
    void pathnameAbaCannotRedirectBytesAwayFromOpenedObject() throws Exception {
        Path file = Files.writeString(temporaryDirectory.resolve("source.bin"), "AAAA");
        Path original = temporaryDirectory.resolve("original.bin");
        FileTime modified = Files.getLastModifiedTime(file);
        OpenedFileGenerationAuthority.Generation reviewed =
                OpenedFileGenerationAuthority.capture(file);

        assertThrows(IOException.class, () -> OpenedFileGenerationAuthority.hash(
                file,
                reviewed,
                (publicPath, input) -> {
                    Files.move(publicPath, original, StandardCopyOption.ATOMIC_MOVE);
                    Files.writeString(publicPath, "BBBB");
                    String openedDigest = Hashes.sha256(input);
                    assertEquals(
                            Hashes.sha256("AAAA".getBytes(StandardCharsets.UTF_8)),
                            openedDigest);
                    Files.delete(publicPath);
                    Files.move(original, publicPath, StandardCopyOption.ATOMIC_MOVE);
                    Files.setLastModifiedTime(publicPath, modified);
                    return openedDigest;
                }));
    }

    @Test
    void windowsExistingWriterMakesGenerationCaptureFailClosed() throws Exception {
        assumeTrue(isWindows());
        Path file = Files.writeString(temporaryDirectory.resolve("writer-open.bin"), "AAAA");

        try (FileChannel writer = FileChannel.open(file, StandardOpenOption.WRITE)) {
            assertThrows(IOException.class, () -> OpenedFileGenerationAuthority.capture(file));
        }
    }

    @Test
    void windowsPinnedExactReadRefusesASecondWriter() throws Exception {
        assumeTrue(isWindows());
        Path file = Files.writeString(temporaryDirectory.resolve("writer-blocked.bin"), "AAAA");
        OpenedFileGenerationAuthority.Generation reviewed =
                OpenedFileGenerationAuthority.capture(file);

        OpenedFileGenerationAuthority.HashEvidence evidence =
                OpenedFileGenerationAuthority.hash(file, reviewed, (publicPath, input) -> {
                    assertThrows(IOException.class, () -> {
                        try (FileChannel ignored = FileChannel.open(publicPath, StandardOpenOption.WRITE)) {
                            // Opening the writer is the assertion; the body must never run.
                        }
                    });
                    return Hashes.sha256(input);
                });

        assertEquals(reviewed, evidence.generation());
        assertEquals(Hashes.sha256("AAAA".getBytes(StandardCharsets.UTF_8)), evidence.sha256());
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }
}
