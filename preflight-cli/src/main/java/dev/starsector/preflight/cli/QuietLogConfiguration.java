package dev.starsector.preflight.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Builds the opt-in log4j 1.2 configuration used by {@code --quiet-logs}. */
final class QuietLogConfiguration {
    static final String FILE_NAME = "log4j-quiet.properties";

    private static final String CONTENT = """
            log4j.rootLogger=INFO, file
            log4j.appender.file=org.apache.log4j.RollingFileAppender
            log4j.appender.file.File=${com.fs.starfarer.settings.paths.logs}/starsector.log
            log4j.appender.file.layout=org.apache.log4j.PatternLayout
            log4j.appender.file.layout.ConversionPattern=%-4r [%t] %-5p %c %x - %m%n
            log4j.appender.file.MaxFileSize=50000KB
            log4j.appender.file.MaxBackupIndex=3
            log4j.appender.file.BufferedIO=true
            log4j.appender.file.BufferSize=65536
            """;

    private QuietLogConfiguration() {
    }

    static Path path(Path runDirectory) {
        return runDirectory.resolve(FILE_NAME).toAbsolutePath().normalize();
    }

    static String javaOption(Path configuration) {
        return "-Dlog4j.configuration=" + configuration.toUri().toASCIIString();
    }

    static void write(Path configuration) throws IOException {
        Files.writeString(configuration, CONTENT, StandardCharsets.ISO_8859_1);
    }
}
