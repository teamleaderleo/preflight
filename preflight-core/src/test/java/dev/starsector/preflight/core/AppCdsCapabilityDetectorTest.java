package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class AppCdsCapabilityDetectorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    @Timeout(60)
    void currentJdkCreatesAndConsumesAProbeArchive() throws Exception {
        Path java = currentJavaExecutable();
        AppCdsCapabilityDetector.Result result = AppCdsCapabilityDetector.detect(
                java,
                temporaryDirectory.resolve("probe"),
                Duration.ofSeconds(20));

        assertEquals(AppCdsCapabilityDetector.Status.SUPPORTED, result.status(), result.detail());
        assertTrue(result.supported());
        assertEquals(java.toRealPath(), result.javaExecutable());
        assertTrue(result.javaExecutableBytes() > 0);
        assertTrue(result.javaExecutableModifiedMillis() > 0);
        assertEquals(64, result.javaExecutableSha256().length());
        assertEquals(0, result.generationExitCode());
        assertEquals(0, result.consumptionExitCode());
        assertFalse(result.outputTruncated());
        assertTrue(result.proofArchiveBytes() > 0);
        assertEquals(64, result.proofArchiveSha256().length());

        Path applicationArchive = temporaryDirectory.resolve("application.jsa");
        Path expectedCreationArchive = temporaryDirectory.toRealPath().resolve("application.jsa");
        assertEquals(
                List.of(
                        AppCdsCapabilityDetector.XSHARE_ON,
                        AppCdsCapabilityDetector.ARCHIVE_AT_EXIT_PREFIX + expectedCreationArchive),
                result.archiveCreationArguments(java, applicationArchive));

        Files.write(applicationArchive, new byte[] {1});
        assertEquals(
                List.of(
                        AppCdsCapabilityDetector.XSHARE_ON,
                        AppCdsCapabilityDetector.SHARED_ARCHIVE_PREFIX + applicationArchive.toRealPath()),
                result.archiveConsumptionArguments(java, applicationArchive));

        Path copiedJava = temporaryDirectory.resolve(java.getFileName().toString());
        Files.copy(java, copiedJava);
        assertTrue(result.archiveCreationArguments(copiedJava, temporaryDirectory.resolve("wrong-java.jsa")).isEmpty());
        assertTrue(result.archiveConsumptionArguments(copiedJava, applicationArchive).isEmpty());

        Path directoryTarget = temporaryDirectory.resolve("directory.jsa");
        Files.createDirectory(directoryTarget);
        assertTrue(result.archiveCreationArguments(java, directoryTarget).isEmpty());
        assertTrue(result.archiveConsumptionArguments(java, temporaryDirectory.resolve("missing.jsa")).isEmpty());
        Path emptyArchive = temporaryDirectory.resolve("empty.jsa");
        Files.createFile(emptyArchive);
        assertTrue(result.archiveConsumptionArguments(java, emptyArchive).isEmpty());
    }

    @Test
    void invalidExecutableContributesNoArchiveFlags() {
        Path missingExecutable = temporaryDirectory.resolve("missing-java");
        AppCdsCapabilityDetector.Result result = AppCdsCapabilityDetector.detect(
                missingExecutable,
                temporaryDirectory.resolve("probe"),
                Duration.ofSeconds(1));

        assertEquals(AppCdsCapabilityDetector.Status.ERROR, result.status());
        assertFalse(result.supported());
        assertTrue(result.archiveCreationArguments(
                currentJavaExecutable(), temporaryDirectory.resolve("unused.jsa")).isEmpty());
        assertTrue(result.archiveConsumptionArguments(
                currentJavaExecutable(), temporaryDirectory.resolve("unused.jsa")).isEmpty());
    }

    private static Path currentJavaExecutable() {
        String executable = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "java.exe"
                : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable);
    }
}
