package dev.starsector.preflight.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Builds file-only log4j 1.2 configurations, optionally buffering the file appender. */
final class QuietLogConfiguration {
    static final String FILE_NAME = "log4j-quiet.properties";
    static final String FILE_ONLY_NAME = "log4j-file-only.properties";

    private static final String FILE_CONTENT = """
            log4j.rootLogger=INFO, file
            log4j.appender.file=org.apache.log4j.RollingFileAppender
            log4j.appender.file.File=${com.fs.starfarer.settings.paths.logs}/starsector.log
            log4j.appender.file.layout=org.apache.log4j.PatternLayout
            log4j.appender.file.layout.ConversionPattern=%-4r [%t] %-5p %c %x - %m%n
            log4j.appender.file.MaxFileSize=50000KB
            log4j.appender.file.MaxBackupIndex=3
            """;
    private static final String BUFFERING = """
            log4j.appender.file.BufferedIO=true
            log4j.appender.file.BufferSize=65536
            """;

    private QuietLogConfiguration() {
    }

    static Path path(Path runDirectory) {
        return runDirectory.resolve(FILE_NAME).toAbsolutePath().normalize();
    }

    static Path path(Path runDirectory, boolean buffered) {
        return runDirectory.resolve(buffered ? FILE_NAME : FILE_ONLY_NAME)
                .toAbsolutePath().normalize();
    }

    static String javaOption(Path configuration) {
        return "-Dlog4j.configuration=" + configuration.toUri().toASCIIString();
    }

    static void write(Path configuration) throws IOException {
        write(configuration, true);
    }

    static void write(Path configuration, boolean buffered) throws IOException {
        Files.writeString(configuration, FILE_CONTENT + (buffered ? BUFFERING : ""),
                StandardCharsets.ISO_8859_1);
    }
}
