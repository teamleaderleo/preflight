package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AdapterHealthReportInputTest {
    private static final long MAX_INPUT_BYTES = 32L * 1024 * 1024;

    @TempDir
    Path temporaryDirectory;

    @Test
    void adapterReportRejectsAnInitiallyOversizedFileBeforeParsing() throws Exception {
        Path adapter = temporaryDirectory.resolve("oversized-adapter.json");
        Path output = temporaryDirectory.resolve("adapter-health.json");
        try (FileChannel channel = FileChannel.open(
                adapter, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.position(MAX_INPUT_BYTES);
            channel.write(ByteBuffer.wrap(new byte[] {0}));
        }
        assertEquals(MAX_INPUT_BYTES + 1L, Files.size(adapter));

        IOException error = assertThrows(
                IOException.class,
                () -> AdapterHealthReport.analyze(adapter, output));

        assertTrue(error.getMessage().contains("Adapter report exceeds"), error.getMessage());
        assertFalse(Files.exists(output));
    }

    @Test
    void malformedAdapterReportUtf8IsRejectedBeforeJsonParsing() throws Exception {
        Path adapter = temporaryDirectory.resolve("malformed-adapter.json");
        Path output = temporaryDirectory.resolve("adapter-health.json");
        Files.write(adapter, new byte[] {
                (byte) '{', (byte) '"', (byte) 'x', (byte) '"', (byte) ':',
                (byte) '"', (byte) 0x80, (byte) '"', (byte) '}'
        });

        IOException error = assertThrows(
                IOException.class,
                () -> AdapterHealthReport.analyze(adapter, output));

        assertTrue(error.getMessage().contains("UTF-8"), error.getMessage());
        assertFalse(Files.exists(output));
    }

    @Test
    void malformedAdapterReportJsonKeepsTheExistingHardParseFailure() throws Exception {
        Path adapter = temporaryDirectory.resolve("malformed-json-adapter.json");
        Path output = temporaryDirectory.resolve("adapter-health.json");
        Files.writeString(adapter, "{");

        assertThrows(
                IllegalArgumentException.class,
                () -> AdapterHealthReport.analyze(adapter, output));

        assertFalse(Files.exists(output));
    }
}
