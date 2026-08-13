package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuietLogConfigurationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void keepsTheRollingFileAndEveryInfoLineButDropsTheDuplicateConsole() throws Exception {
        Path configuration = QuietLogConfiguration.path(temporaryDirectory);
        QuietLogConfiguration.write(configuration);
        String content = Files.readString(configuration, StandardCharsets.ISO_8859_1);

        assertTrue(content.contains("log4j.rootLogger=INFO, file"));
        assertTrue(content.contains("RollingFileAppender"));
        assertTrue(content.contains("starsector.log"));
        assertTrue(content.contains("ConversionPattern=%-4r [%t] %-5p %c %x - %m%n"));
        assertTrue(content.contains("BufferedIO=true"));
        assertTrue(content.contains("BufferSize=65536"));
        assertFalse(content.contains("ConsoleAppender"));
    }

    @Test
    void fileOnlyModeKeepsSynchronousCrashSafeWrites() throws Exception {
        Path configuration = QuietLogConfiguration.path(temporaryDirectory, false);
        QuietLogConfiguration.write(configuration, false);
        String content = Files.readString(configuration, StandardCharsets.ISO_8859_1);

        assertTrue(content.contains("log4j.rootLogger=INFO, file"));
        assertTrue(content.contains("RollingFileAppender"));
        assertFalse(content.contains("ConsoleAppender"));
        assertFalse(content.contains("BufferedIO"));
        assertFalse(content.contains("BufferSize"));
        assertTrue(configuration.endsWith(QuietLogConfiguration.FILE_ONLY_NAME));
    }

    @Test
    void usesAnAsciiUriThatCannotSplitJavaToolOptionsAtPathSpaces() {
        Path configuration = QuietLogConfiguration.path(
                temporaryDirectory.resolve("run with spaces"));
        String option = QuietLogConfiguration.javaOption(configuration);

        assertTrue(option.startsWith("-Dlog4j.configuration=file:"));
        assertTrue(option.contains("run%20with%20spaces"));
        assertFalse(option.contains(" "));
    }
}
