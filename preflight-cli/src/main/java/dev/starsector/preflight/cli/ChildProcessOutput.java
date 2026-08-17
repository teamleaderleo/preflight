package dev.starsector.preflight.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Streams combined child output while retaining a bounded head and tail.
 *
 * <p>The bound was always here; what it kept was only the last {@value #MAX_CAPTURE_BYTES} bytes.
 * That is the wrong half for the question this file is opened to answer. A launch that fails
 * explains itself at the end, but a launch that merely behaves oddly explains itself at the
 * beginning -- the game prints its version, its resolution, its enabled mods, and every mod's own
 * startup complaint before it reaches a menu. On a short session the tail held all of that. On a
 * three-hour one it held the last few minutes of combat spam and nothing that identifies the setup.
 *
 * <p>So the retained window is split. The head is kept because it is where a launch says what it
 * is, the tail because it is where a launch says what went wrong, and the bytes between them are
 * dropped with a line saying how many. Starsector makes the same trade for its own file log, via a
 * {@code RollingFileAppender} at 50000KB with three backups in the {@code log4j.properties} inside
 * {@code starfarer_obf.jar} -- but rolling keeps recency only, which is affordable there because
 * the file it rolls is not the only copy.
 */
final class ChildProcessOutput {
    static final int MAX_CAPTURE_BYTES = 1024 * 1024;
    /** A quarter of the window. Enough for the startup banner, settings, and the mod list. */
    static final int MAX_HEAD_BYTES = MAX_CAPTURE_BYTES / 4;
    /**
     * Space held back for the line that says what was dropped, so the marker cannot push the file
     * past the bound it exists to describe. The longest that line can render is under 128 bytes:
     * three decimal counts, none wider than a {@code long}, in a fixed sentence.
     */
    static final int ELISION_RESERVE_BYTES = 128;
    private static final int COPY_BUFFER_BYTES = 16 * 1024;

    private ChildProcessOutput() {
    }

    static Result run(ProcessBuilder builder, Path outputFile) throws IOException, InterruptedException {
        return run(builder, outputFile, System.out);
    }

    static Result run(ProcessBuilder builder, Path outputFile, PrintStream operatorStream)
            throws IOException, InterruptedException {
        builder.redirectErrorStream(true);
        builder.redirectInput(ProcessBuilder.Redirect.INHERIT);
        Process process = builder.start();
        SaveProfileObservation.Observer saveObserver =
                SaveProfileObservation.start(builder, process, System.err);
        try {
            HeadTailBuffer capture = new HeadTailBuffer(
                    MAX_HEAD_BYTES, MAX_CAPTURE_BYTES - MAX_HEAD_BYTES - ELISION_RESERVE_BYTES);
            byte[] copy = new byte[COPY_BUFFER_BYTES];
            try (InputStream input = process.getInputStream()) {
                int count;
                while ((count = input.read(copy)) >= 0) {
                    if (count == 0) {
                        continue;
                    }
                    operatorStream.write(copy, 0, count);
                    operatorStream.flush();
                    capture.append(copy, 0, count);
                }
            } catch (IOException error) {
                process.destroyForcibly();
                throw error;
            }
            int exitCode = process.waitFor();
            Path absolute = outputFile.toAbsolutePath().normalize();
            Path parent = absolute.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            byte[] retained = capture.bytes();
            Files.write(absolute, retained);
            return new Result(exitCode, absolute, capture.totalBytes(), retained.length, capture.truncated());
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                // Completing the observation after the child is gone keeps interruption or output
                // capture failure from extending the owned filesystem interval.
                process.onExit().join();
            }
            saveObserver.processEnded(Instant.now());
        }
    }

    record Result(int exitCode, Path file, long totalBytes, int capturedBytes, boolean truncated) {
        Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("file", file);
            values.put("combinedStdoutStderr", true);
            values.put("totalBytes", totalBytes);
            values.put("capturedBytes", capturedBytes);
            values.put("truncated", truncated);
            values.put("maxCaptureBytes", MAX_CAPTURE_BYTES);
            values.put("maxHeadBytes", MAX_HEAD_BYTES);
            return values;
        }
    }

    /**
     * Keeps the first bytes seen and the last, and counts what fell between them.
     *
     * <p>When nothing was dropped the two halves are simply adjacent, so a capture that fits the
     * window reproduces the stream exactly and carries no marker. That property is what makes the
     * marker trustworthy: it appears if and only if something is missing.
     */
    static final class HeadTailBuffer {
        private final byte[] head;
        private final byte[] tail;
        private int headSize;
        private int tailStart;
        private int tailSize;
        private long totalBytes;

        HeadTailBuffer(int headCapacity, int tailCapacity) {
            this.head = new byte[headCapacity];
            this.tail = new byte[tailCapacity];
        }

        void append(byte[] source, int offset, int length) {
            totalBytes = saturatedAdd(totalBytes, length);
            int index = offset;
            int remaining = length;
            if (headSize < head.length) {
                int take = Math.min(head.length - headSize, remaining);
                System.arraycopy(source, index, head, headSize, take);
                headSize += take;
                index += take;
                remaining -= take;
            }
            for (int i = 0; i < remaining; i++) {
                tail[(tailStart + tailSize) % tail.length] = source[index + i];
                if (tailSize < tail.length) {
                    tailSize++;
                } else {
                    tailStart = (tailStart + 1) % tail.length;
                }
            }
        }

        byte[] bytes() {
            byte[] elision = truncated()
                    ? ("\n[Preflight omitted " + droppedBytes()
                            + " bytes of output here; the first " + headSize
                            + " and last " + tailSize + " bytes are kept]\n")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                    : new byte[0];
            byte[] result = new byte[headSize + elision.length + tailSize];
            System.arraycopy(head, 0, result, 0, headSize);
            System.arraycopy(elision, 0, result, headSize, elision.length);
            int at = headSize + elision.length;
            int first = Math.min(tailSize, tail.length - tailStart);
            System.arraycopy(tail, tailStart, result, at, first);
            if (first < tailSize) {
                System.arraycopy(tail, 0, result, at + first, tailSize - first);
            }
            return result;
        }

        long totalBytes() {
            return totalBytes;
        }

        long droppedBytes() {
            return Math.max(0, totalBytes - headSize - tailSize);
        }

        boolean truncated() {
            return droppedBytes() > 0;
        }

        private static long saturatedAdd(long left, long right) {
            return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
        }
    }
}
