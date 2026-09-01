package dev.starsector.preflight.cli;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Memory-bounded streaming log tailer.
 * Strictly caps total file scanning at 16 MiB and maintains <= 64 KiB memory window.
 */
final class LogTailer {
    static final long MAX_SCAN_BYTES = 16L * 1024L * 1024L; // 16 MiB
    static final int MAX_MEMORY_BYTES = 64 * 1024;           // 64 KiB
    static final int MAX_SNIPPET_LINES = 800;
    static final int CHUNK_BUFFER_BYTES = 16 * 1024;        // 16 KiB

    static final Pattern HS_ERR_PATTERN = Pattern.compile("hs_err_pid\\d+\\.log", Pattern.CASE_INSENSITIVE);
    static final Pattern ROTATED_LOG_PATTERN = Pattern.compile("starsector\\.log(?:\\.\\d+)?", Pattern.CASE_INSENSITIVE);

    private LogTailer() {
    }

    record TailResult(
            List<String> lines,
            long totalBytesScanned,
            boolean truncated,
            List<String> problems
    ) {}

    record LogSource(
            String sourceKind,
            Path path,
            long fileSize,
            long bytesRead
    ) {}

    /**
     * Reads a bounded tail from the specified file, ensuring at most maxMemoryBytes
     * is held in heap memory and at most maxBytesToScan is read from disk.
     */
    static TailResult tailFile(Path path, long maxBytesToScan, int maxMemoryBytes) {
        List<String> problems = new ArrayList<>();
        if (path == null || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return new TailResult(List.of(), 0, false, List.of("File not found or not regular: " + path));
        }

        long fileSize;
        try {
            fileSize = Files.size(path);
        } catch (IOException error) {
            return new TailResult(List.of(), 0, false, List.of("Cannot inspect file size: " + error.getMessage()));
        }

        long scanBudget = Math.min(maxBytesToScan, MAX_SCAN_BYTES);
        int memoryBudget = Math.min(maxMemoryBytes, MAX_MEMORY_BYTES);

        long startOffset = Math.max(0L, fileSize - scanBudget);
        long bytesToRead = fileSize - startOffset;

        BoundedTailCollector collector = new BoundedTailCollector(memoryBudget, MAX_SNIPPET_LINES);
        long scanned = streamLines(path, startOffset, bytesToRead, collector, problems);

        return new TailResult(
                collector.getLines(),
                scanned,
                startOffset > 0 || collector.isEvicted(),
                List.copyOf(problems)
        );
    }

    /**
     * Streams lines sequentially from disk using a fixed 16 KiB I/O buffer and UTF-8 charset decoder.
     */
    static long streamLines(
            Path path,
            long startOffset,
            long length,
            LineConsumer consumer,
            List<String> problems) {
        ByteBuffer byteBuffer = ByteBuffer.allocate(CHUNK_BUFFER_BYTES);
        CharBuffer charBuffer = CharBuffer.allocate(CHUNK_BUFFER_BYTES);
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);

        long totalRead = 0;
        StringBuilder currentLine = new StringBuilder(256);

        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            channel.position(startOffset);

            while (totalRead < length) {
                byteBuffer.clear();
                int toRead = (int) Math.min((long) CHUNK_BUFFER_BYTES, length - totalRead);
                byteBuffer.limit(toRead);

                int readCount = channel.read(byteBuffer);
                if (readCount < 0) {
                    break;
                }
                totalRead += readCount;

                byteBuffer.flip();
                boolean endOfInput = (totalRead >= length || readCount < toRead);
                decoder.decode(byteBuffer, charBuffer, endOfInput);
                charBuffer.flip();

                while (charBuffer.hasRemaining()) {
                    char c = charBuffer.get();
                    if (c == '\r') {
                        continue;
                    }
                    if (c == '\n') {
                        consumer.accept(currentLine.toString());
                        currentLine.setLength(0);
                    } else {
                        if (currentLine.length() < 2048) {
                            currentLine.append(c);
                        }
                    }
                }
                charBuffer.clear();
            }

            if (currentLine.length() > 0) {
                consumer.accept(currentLine.toString());
            }
        } catch (IOException error) {
            problems.add("I/O error streaming " + path.getFileName() + ": " + error.getMessage());
        }

        return totalRead;
    }

    /**
     * Discovers candidate log files for a run or installation in priority order:
     * 1. JVM fatal crash dumps (hs_err_pid*.log)
     * 2. Child process console capture (console.txt)
     * 3. Active and rotated starsector.log files
     */
    static List<Path> discoverLogCandidates(Path installRoot, Path runDirectory) {
        List<Path> candidates = new ArrayList<>();

        // 1. hs_err_pid*.log in run directory, installRoot, or current dir
        if (runDirectory != null && Files.isDirectory(runDirectory)) {
            findHsErrLogs(runDirectory, candidates);
        }
        if (installRoot != null && Files.isDirectory(installRoot)) {
            findHsErrLogs(installRoot, candidates);
        }

        // 2. console.txt in run directory
        if (runDirectory != null && Files.isDirectory(runDirectory)) {
            Path console = runDirectory.resolve("console.txt");
            if (Files.isRegularFile(console, LinkOption.NOFOLLOW_LINKS)) {
                candidates.add(console);
            }
        }

        // 3. starsector.log and rotated logs
        if (installRoot != null && Files.isDirectory(installRoot)) {
            Path logsDir = installRoot.resolve("logs");
            if (Files.isDirectory(logsDir)) {
                Path mainLog = logsDir.resolve("starsector.log");
                if (Files.isRegularFile(mainLog, LinkOption.NOFOLLOW_LINKS)) {
                    candidates.add(mainLog);
                }
                try (Stream<Path> stream = Files.list(logsDir)) {
                    stream.filter(Files::isRegularFile)
                            .filter(p -> !p.equals(mainLog))
                            .filter(p -> ROTATED_LOG_PATTERN.matcher(p.getFileName().toString()).matches())
                            .sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()))
                            .forEach(candidates::add);
                } catch (IOException ignored) {
                }
            }
        }

        return List.copyOf(candidates);
    }

    private static void findHsErrLogs(Path directory, List<Path> candidates) {
        try (Stream<Path> stream = Files.list(directory)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> HS_ERR_PATTERN.matcher(p.getFileName().toString()).matches())
                    .sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()))
                    .forEach(candidates::add);
        } catch (IOException ignored) {
        }
    }

    @FunctionalInterface
    interface LineConsumer {
        void accept(String line);
    }

    /**
     * Circular line collector capping retained characters / lines to bound memory.
     */
    static final class BoundedTailCollector implements LineConsumer {
        private final int maxMemoryBytes;
        private final int maxLines;
        private final Deque<String> deque = new ArrayDeque<>();
        private int currentBytes = 0;
        private boolean evicted = false;

        BoundedTailCollector(int maxMemoryBytes, int maxLines) {
            this.maxMemoryBytes = maxMemoryBytes;
            this.maxLines = maxLines;
        }

        @Override
        public void accept(String line) {
            int lineBytes = line.length() * 2; // rough memory estimate
            deque.addLast(line);
            currentBytes += lineBytes;

            while ((currentBytes > maxMemoryBytes || deque.size() > maxLines) && !deque.isEmpty()) {
                String removed = deque.removeFirst();
                currentBytes -= removed.length() * 2;
                evicted = true;
            }
        }

        List<String> getLines() {
            return new ArrayList<>(deque);
        }

        boolean isEvicted() {
            return evicted;
        }
    }
}
