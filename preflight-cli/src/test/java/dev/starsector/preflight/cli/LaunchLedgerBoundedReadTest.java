package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LaunchLedgerBoundedReadTest {
    @TempDir
    Path root;

    @Test
    void hugeSingleLineCostsOnlyThatPhysicalRow() throws Exception {
        PreflightHome home = home();
        Path path = LaunchLedger.path(home);
        Files.createDirectories(path.getParent());
        String huge = "x".repeat(LaunchLedger.MAX_ENCODED_ENTRY_BYTES + 1);
        Files.writeString(
                path,
                huge + "\n" + line(entry("recent", "2026-08-20T08:00:00Z")) + "\n",
                StandardCharsets.UTF_8);

        List<LaunchLedger.Entry> entries = LaunchLedger.read(home);

        assertEquals(1, entries.size());
        assertEquals("recent", entries.get(0).launchId());
        assertNull(LaunchLedger.record(home, entry("newest", "2026-08-20T09:00:00Z")));
        assertEquals(List.of("recent", "newest"), ids(LaunchLedger.read(home)));
        assertTrue(Files.size(path) < LaunchLedger.MAX_ENCODED_FILE_BYTES);
    }

    @Test
    void initiallyOversizedLedgerIsRepairedFromItsBoundedRecentSuffix() throws Exception {
        PreflightHome home = home();
        Path path = LaunchLedger.path(home);
        Files.createDirectories(path.getParent());
        try (FileChannel output = FileChannel.open(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            output.position(LaunchLedger.MAX_ENCODED_FILE_BYTES + 1024);
            output.write(ByteBuffer.wrap(new byte[] {'\n'}));
            output.write(ByteBuffer.wrap(
                    (line(entry("recent", "2026-08-20T08:00:00Z")) + "\n")
                            .getBytes(StandardCharsets.UTF_8)));
        }
        assertTrue(Files.size(path) > LaunchLedger.MAX_ENCODED_FILE_BYTES);

        assertNull(LaunchLedger.record(home, entry("newest", "2026-08-20T09:00:00Z")));

        assertEquals(List.of("recent", "newest"), ids(LaunchLedger.read(home)));
        assertTrue(Files.size(path) < LaunchLedger.MAX_ENCODED_FILE_BYTES);
    }

    @Test
    void openedReadIgnoresGrowthPastItsSampledExtent() throws Exception {
        Path path = root.resolve("growing.jsonl");
        byte[] original = ("one\n" + "two\n").getBytes(StandardCharsets.UTF_8);
        Files.write(path, original);

        try (FileChannel raw = FileChannel.open(path, StandardOpenOption.READ);
                GrowingReadChannel growing = new GrowingReadChannel(raw, path)) {
            long sampledExtent = raw.size();

            LaunchLedger.RetainedLines retained = LaunchLedger.scanRetainedLines(
                    growing,
                    sampledExtent,
                    32,
                    LaunchLedger.requiredScanWorkBytes(32, 10),
                    10);

            assertTrue(growing.appended);
            assertEquals(sampledExtent, retained.bytesRead());
            assertEquals(List.of("one", "two"), text(retained));
            assertEquals(sampledExtent + 6L, Files.size(path));
        }
    }

    @Test
    void excessiveSmallRowsRetainTheNewestPhysicalRows() throws Exception {
        Path path = root.resolve("small-lines.jsonl");
        Files.writeString(path, "a\nb\nc\nd\ne\n", StandardCharsets.UTF_8);
        try (FileChannel input = FileChannel.open(path, StandardOpenOption.READ)) {
            LaunchLedger.RetainedLines retained = LaunchLedger.scanRetainedLines(
                    input,
                    input.size(),
                    16,
                    64,
                    3);

            assertEquals(List.of("c", "d", "e"), text(retained));
            assertTrue(retained.countTruncated());
            assertTrue(retained.rewriteNeeded());
        }
    }

    @Test
    void scanRejectsAWorkCeilingThatCannotCoverItsRetentionContract() throws Exception {
        Path path = root.resolve("incompatible-bounds.jsonl");
        Files.writeString(path, "a\nb\nc\n", StandardCharsets.UTF_8);

        try (FileChannel input = FileChannel.open(path, StandardOpenOption.READ)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> LaunchLedger.scanRetainedLines(
                            input,
                            input.size(),
                            16,
                            LaunchLedger.requiredScanWorkBytes(16, 3) - 1L,
                            3));
        }
    }

    @Test
    void batchAdmissionFinishesBeforeAnyLedgerMutation() throws Exception {
        PreflightHome home = home();
        LaunchLedger.Entry oversized = new LaunchLedger.Entry(
                "oversized-batch",
                Instant.parse("2026-08-20T08:00:01Z"),
                15_250L,
                "COMPLETED",
                0,
                false,
                "recommended",
                List.of(),
                "x".repeat(LaunchLedger.MAX_ENCODED_ENTRY_BYTES),
                null);
        List<LaunchLedger.Entry> batch = List.of(
                entry("valid-old", "2026-08-20T08:00:00Z"),
                oversized,
                entry("valid-new", "2026-08-20T08:00:02Z"));

        assertThrows(
                IOException.class,
                () -> LaunchLedger.withExclusiveLock(home, () -> {
                    LaunchLedger.appendAllUnlocked(home, batch);
                    return null;
                }));

        assertTrue(LaunchLedger.read(home).isEmpty());
    }

    @Test
    void oversizedHistoricalRowDoesNotFenceLaterBackfillRows() throws Exception {
        PreflightHome home = home();
        writeHistoricalRun("valid-old", "2026-08-20T08:00:00Z", "recommended");
        writeHistoricalRun(
                "oversized",
                "2026-08-20T08:01:00Z",
                "x".repeat(LaunchLedger.MAX_ENCODED_ENTRY_BYTES));
        writeHistoricalRun("valid-new", "2026-08-20T08:02:00Z", "recommended");

        assertEquals(2, LaunchLedgerBackfill.runOnce(home));
        assertEquals(
                List.of("valid-old", "valid-new"),
                LaunchLedger.read(home).stream().map(LaunchLedger.Entry::runDirectory).toList());
        assertTrue(Files.readString(
                        LaunchLedgerBackfill.marker(home), StandardCharsets.UTF_8)
                .contains("Imported 2 launches"));
    }

    @Test
    void exactTenThousandEntryBoundaryIsInclusive() throws Exception {
        PreflightHome home = home();
        Path path = LaunchLedger.path(home);
        Files.createDirectories(path.getParent());
        String canonical = line(entry("same", "2026-08-20T08:00:00Z")) + "\n";
        try (var writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            for (int index = 0; index < LaunchLedger.MAX_ENTRIES; index++) {
                writer.write(canonical);
            }
        }

        List<LaunchLedger.Entry> entries = LaunchLedger.read(home);

        assertEquals(LaunchLedger.MAX_ENTRIES, entries.size());
    }

    @Test
    void backfilledOlderLaunchesAreRetainedByAppendPosition() throws Exception {
        PreflightHome home = home();
        Path path = LaunchLedger.path(home);
        Files.createDirectories(path.getParent());
        Instant base = Instant.parse("2026-08-01T00:00:00Z");
        try (var writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            for (int index = 0; index < LaunchLedger.MAX_ENTRIES; index++) {
                writer.write(line(entry("current-" + index, base.plusSeconds(index).toString())));
                writer.write('\n');
            }
        }

        LaunchLedger.Entry backfilled = entry("backfilled", "2026-07-01T00:00:00Z");
        assertNull(LaunchLedger.record(home, backfilled));

        List<LaunchLedger.Entry> entries = LaunchLedger.read(home);
        assertEquals(LaunchLedger.MAX_ENTRIES, entries.size());
        assertEquals("current-1", entries.get(0).launchId());
        assertEquals("backfilled", entries.get(entries.size() - 1).launchId());
        LaunchLedger.Summary summary = LaunchLedger.summarize(entries);
        assertEquals(Instant.parse("2026-07-01T00:00:00Z"), summary.first());
        assertEquals(base.plusSeconds(LaunchLedger.MAX_ENTRIES - 1L), summary.last());
    }

    @Test
    void batchAppendWritesOnlyRowsThatCanSurvivePhysicalRetention() throws Exception {
        PreflightHome home = home();
        List<LaunchLedger.Entry> imported = new ArrayList<>();
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        for (int index = 0; index < LaunchLedger.MAX_ENTRIES + 3; index++) {
            imported.add(entry("import-" + index, base.plusSeconds(index).toString()));
        }

        LaunchLedger.withExclusiveLock(home, () -> {
            LaunchLedger.appendAllUnlocked(home, imported);
            return null;
        });

        List<LaunchLedger.Entry> retained = LaunchLedger.read(home);
        assertEquals(LaunchLedger.MAX_ENTRIES, retained.size());
        assertEquals("import-3", retained.get(0).launchId());
        assertEquals("import-" + (LaunchLedger.MAX_ENTRIES + 2),
                retained.get(retained.size() - 1).launchId());
    }

    @Test
    void internallyGeneratedOversizedRowIsBestEffortRefused() {
        PreflightHome home = home();
        LaunchLedger.Entry oversized = new LaunchLedger.Entry(
                "oversized",
                Instant.parse("2026-08-20T08:00:00Z"),
                null,
                "COMPLETED",
                0,
                false,
                "recommended",
                List.of(),
                "x".repeat(LaunchLedger.MAX_ENCODED_ENTRY_BYTES),
                null);

        String problem = LaunchLedger.record(home, oversized);

        assertFalse(problem == null || problem.isBlank());
    }

    private void writeHistoricalRun(String name, String started, String preset)
            throws IOException {
        Path directory = Files.createDirectories(root.resolve("runs").resolve(name));
        String ended = Instant.parse(started).plusSeconds(60).toString();
        Files.writeString(
                directory.resolve("run.json"),
                "{\"started\":\"" + started + "\","
                        + "\"ended\":\"" + ended + "\","
                        + "\"exitCode\":0,"
                        + "\"outcome\":\"COMPLETED\","
                        + "\"lifecycleEvidence\":{\"fatalDetected\":false},"
                        + "\"optimizationPreset\":\"" + preset + "\","
                        + "\"disabledOptimizationDomains\":[]}",
                StandardCharsets.UTF_8);
    }

    private PreflightHome home() {
        return new PreflightHome(root, List.of());
    }

    private static LaunchLedger.Entry entry(String id, String started) {
        return new LaunchLedger.Entry(
                id,
                Instant.parse(started),
                15_250L,
                "COMPLETED",
                0,
                false,
                "recommended",
                List.of(),
                "20260820-080000-000-abcdef01",
                null);
    }

    private static String line(LaunchLedger.Entry entry) {
        return Json.object(entry.toMap());
    }

    private static List<String> ids(List<LaunchLedger.Entry> entries) {
        return entries.stream().map(LaunchLedger.Entry::launchId).toList();
    }

    private static List<String> text(LaunchLedger.RetainedLines retained) {
        List<String> result = new ArrayList<>();
        for (LaunchLedger.RetainedLine line : retained.lines()) {
            if (line.bytes() != null) {
                result.add(new String(line.bytes(), StandardCharsets.UTF_8));
            }
        }
        return result;
    }

    private static final class GrowingReadChannel implements SeekableByteChannel {
        private final FileChannel delegate;
        private final Path path;
        private boolean open = true;
        private boolean appended;

        private GrowingReadChannel(FileChannel delegate, Path path) {
            this.delegate = delegate;
            this.path = path;
        }

        @Override
        public int read(ByteBuffer destination) throws IOException {
            if (!appended && destination.hasRemaining()) {
                int originalLimit = destination.limit();
                destination.limit(destination.position() + 1);
                int read;
                try {
                    read = delegate.read(destination);
                } finally {
                    destination.limit(originalLimit);
                }
                if (read > 0) {
                    Files.writeString(
                            path,
                            "growth",
                            StandardCharsets.UTF_8,
                            StandardOpenOption.APPEND);
                    appended = true;
                }
                return read;
            }
            return delegate.read(destination);
        }

        @Override
        public int write(ByteBuffer source) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long position() throws IOException {
            return delegate.position();
        }

        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            delegate.position(newPosition);
            return this;
        }

        @Override
        public long size() throws IOException {
            return delegate.size();
        }

        @Override
        public SeekableByteChannel truncate(long size) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isOpen() {
            return open && delegate.isOpen();
        }

        @Override
        public void close() throws IOException {
            open = false;
            delegate.close();
        }
    }
}
