package dev.starsector.preflight.cli;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Bounded, read-only lifecycle evidence from log and child-console bytes written during one run. */
final class StarsectorRunLogEvidence {
    static final int FATAL_LIFECYCLE_EXIT = 6;
    static final String CONTROLLER_STOP_FILE = "controller-stop.requested";
    private static final long MAX_BYTES = 16L * 1024L * 1024L;
    private static final int MAX_FILES = 8;
    private static final int MAX_MATCHES = 8;
    private static final int MAX_MESSAGE_CHARACTERS = 512;
    private static final int MAX_STACK_LINES = 32;
    private static final long MAX_CONTROLLER_STOP_BYTES = 4 * 1024;
    private static final Pattern ROTATED_LOG = Pattern.compile("starsector\\.log(?:\\.\\d+)?", Pattern.CASE_INSENSITIVE);
    private static final List<FatalMarker> FATAL_MARKERS = List.of(
            new FatalMarker(
                    "launcher-fatal",
                    "FATAL com.fs.starfarer.launcher.",
                    null),
            new FatalMarker(
                    "uncaught-main-thread",
                    "Exception in thread \"main\"",
                    "at com.fs."),
            new FatalMarker(
                    "combat-main-top-level",
                    " ERROR com.fs.starfarer.combat.CombatMain  - ",
                    "at com.fs.starfarer.combat.CombatMain.main("));

    private StarsectorRunLogEvidence() {
    }

    static Snapshot snapshot(Path installRoot) {
        Path directory = installRoot.toAbsolutePath().normalize().resolve("logs");
        List<String> problems = new ArrayList<>();
        List<FileStamp> files = stamps(directory, problems);
        return new Snapshot(directory, Instant.now(), files, List.copyOf(problems));
    }

    static Evidence inspect(Snapshot before) {
        return inspect(before, null);
    }

    static Evidence inspect(Snapshot before, ChildProcessOutput.Result console) {
        return inspect(before, console, false);
    }

    static Evidence inspect(
            Snapshot before,
            ChildProcessOutput.Result console,
            boolean controllerStopRequested) {
        List<String> problems = new ArrayList<>(before.problems());
        List<FileStamp> after = stamps(before.logDirectory(), problems);
        List<FileStamp> changed = after.stream()
                .filter(file -> changedSince(before.files(), file))
                .sorted(Comparator.comparing(FileStamp::modified)
                        .thenComparing(file -> file.path().toString())
                        .reversed())
                .toList();
        boolean truncated = changed.size() > MAX_FILES;
        if (changed.size() > MAX_FILES) {
            changed = changed.subList(0, MAX_FILES);
        }

        long remaining = MAX_BYTES;
        long examined = 0;
        long consoleExamined = 0;
        List<Map<String, Object>> matches = new ArrayList<>();
        List<Map<String, Object>> ignoredMatches = new ArrayList<>();
        int examinedFiles = 0;
        for (FileStamp file : changed) {
            if (remaining == 0) {
                truncated = true;
                break;
            }
            long start = startOffset(before.files(), file);
            long available = Math.max(0, file.size() - start);
            if (available == 0) {
                continue;
            }
            long readStart = start;
            long length = Math.min(available, remaining);
            if (available > length) {
                readStart = file.size() - length;
                truncated = true;
            }
            try {
                String text = read(file.path(), readStart, length);
                examined += length;
                remaining -= length;
                examinedFiles++;
                findFatalMarkers(
                        "logFile",
                        file.path().getFileName().toString(),
                        text,
                        controllerStopRequested,
                        matches,
                        ignoredMatches);
            } catch (IOException error) {
                addProblem(problems, file.path().getFileName() + ": " + error.getMessage());
            }
        }

        boolean consoleAvailable = console != null && Files.isRegularFile(console.file());
        String consoleFile = console == null || console.file().getFileName() == null
                ? null
                : console.file().getFileName().toString();
        if (console != null) {
            truncated |= console.truncated();
        }
        if (consoleAvailable) {
            try {
                long length = Files.size(console.file());
                String text = read(console.file(), 0, length);
                consoleExamined = length;
                examined += length;
                findFatalMarkers(
                        "consoleFile",
                        consoleFile,
                        text,
                        controllerStopRequested,
                        matches,
                        ignoredMatches);
            } catch (IOException error) {
                addProblem(problems, consoleFile + ": " + error.getMessage());
            }
        }

        if (matches.size() > MAX_MATCHES) {
            matches = new ArrayList<>(matches.subList(0, MAX_MATCHES));
            truncated = true;
        }
        if (ignoredMatches.size() > MAX_MATCHES) {
            ignoredMatches = new ArrayList<>(ignoredMatches.subList(0, MAX_MATCHES));
            truncated = true;
        }
        return new Evidence(
                !after.isEmpty(),
                consoleAvailable,
                !matches.isEmpty(),
                controllerStopRequested,
                examined,
                consoleExamined,
                examinedFiles,
                truncated,
                consoleFile,
                List.copyOf(matches),
                List.copyOf(ignoredMatches),
                List.copyOf(problems));
    }

    static int effectiveExitCode(int launcherExitCode, Evidence evidence) {
        if (launcherExitCode != 0) {
            return launcherExitCode;
        }
        return evidence.fatalDetected() ? FATAL_LIFECYCLE_EXIT : 0;
    }

    /** Accepts stop intent only when its PID/start pair matches this run's strict runtime record. */
    static boolean exactControllerStopRequested(Path runDirectory) {
        Path receipt = runDirectory.resolve(CONTROLLER_STOP_FILE);
        Path runtime = runDirectory.resolve("runtime-process.json");
        try {
            if (!Files.isRegularFile(receipt, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(receipt) > MAX_CONTROLLER_STOP_BYTES) {
                return false;
            }
            Map<String, Object> root = StrictJson.object(
                    Files.readString(receipt, StandardCharsets.UTF_8));
            if (!root.keySet().equals(Set.of("pid", "startedAt"))) return false;
            Object pidValue = root.get("pid");
            Object startedValue = root.get("startedAt");
            if (!(pidValue instanceof Number number)
                    || number.doubleValue() != number.longValue()
                    || number.longValue() <= 0
                    || !(startedValue instanceof String startedText)) {
                return false;
            }
            RuntimeProcessIdentity identity = RuntimeProcessIdentity.read(runtime);
            return number.longValue() == identity.pid()
                    && Instant.parse(startedText).equals(identity.startedAt());
        } catch (Exception invalidOrUnavailable) {
            return false;
        }
    }

    private static List<FileStamp> stamps(Path directory, List<String> problems) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(directory)) {
            List<Path> paths = entries
                    .filter(Files::isRegularFile)
                    .filter(path -> ROTATED_LOG.matcher(path.getFileName().toString()).matches())
                    .sorted()
                    .toList();
            List<FileStamp> files = new ArrayList<>();
            for (Path path : paths) {
                try {
                    BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
                    files.add(new FileStamp(
                            path.toAbsolutePath().normalize(),
                            attributes.fileKey(),
                            attributes.size(),
                            attributes.lastModifiedTime().toInstant()));
                } catch (IOException error) {
                    addProblem(problems, path.getFileName() + ": " + error.getMessage());
                }
            }
            return List.copyOf(files);
        } catch (IOException error) {
            addProblem(problems, "logs: " + error.getMessage());
            return List.of();
        }
    }

    private static boolean changedSince(List<FileStamp> before, FileStamp after) {
        FileStamp prior = sameFile(before, after);
        return prior == null || prior.size() != after.size() || !prior.modified().equals(after.modified());
    }

    private static long startOffset(List<FileStamp> before, FileStamp after) {
        FileStamp prior = sameFile(before, after);
        if (prior == null || after.size() < prior.size()) {
            return 0;
        }
        return prior.size();
    }

    private static FileStamp sameFile(List<FileStamp> before, FileStamp after) {
        if (after.fileKey() != null) {
            for (FileStamp candidate : before) {
                if (after.fileKey().equals(candidate.fileKey())) {
                    return candidate;
                }
            }
        }
        for (FileStamp candidate : before) {
            if (after.path().equals(candidate.path()) && candidate.fileKey() == null) {
                return candidate;
            }
        }
        return null;
    }

    private static String read(Path path, long start, long length) throws IOException {
        if (length > Integer.MAX_VALUE) {
            throw new IOException("bounded log read is too large");
        }
        ByteBuffer buffer = ByteBuffer.allocate((int) length);
        try (SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
            channel.position(start);
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                // Continue until the bounded segment is exhausted or EOF is reached.
            }
        }
        buffer.flip();
        return StandardCharsets.UTF_8.decode(buffer).toString();
    }

    private static void findFatalMarkers(
            String sourceField,
            String sourceName,
            String text,
            boolean controllerStopRequested,
            List<Map<String, Object>> matches,
            List<Map<String, Object>> ignoredMatches) {
        String[] lines = text.split("\\R");
        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            if (matches.size() > MAX_MATCHES) {
                return;
            }
            String line = lines[lineIndex];
            for (FatalMarker marker : FATAL_MARKERS) {
                int index = line.indexOf(marker.text());
                if (index < 0 || !hasRequiredFrame(lines, lineIndex, marker.requiredFrame())) {
                    continue;
                }
                if (expectedControllerStopOpenAlCleanup(
                        lines, lineIndex, marker, controllerStopRequested)) {
                    Map<String, Object> ignored = new LinkedHashMap<>();
                    ignored.put("category", "controller-stop-openal-cleanup");
                    ignored.put(sourceField, sourceName);
                    ignored.put("message", bounded(
                            line.substring(index + marker.text().length()).trim()));
                    ignoredMatches.add(ignored);
                    break;
                }
                Map<String, Object> match = new LinkedHashMap<>();
                match.put("category", marker.category());
                match.put(sourceField, sourceName);
                match.put("message", bounded(line.substring(index + marker.text().length()).trim()));
                matches.add(match);
                break;
            }
        }
    }

    /**
     * Starsector's OpenAL cleanup can race native-library teardown after the smoke controller sends
     * SIGTERM to its exact owned JVM. Suppress only the two observed shutdown-only calls and only
     * when the controller published its stop intent before the signal. Any other native-link error,
     * or the same error during an ordinary run, remains fatal.
     */
    private static boolean expectedControllerStopOpenAlCleanup(
            String[] lines,
            int markerLine,
            FatalMarker marker,
            boolean controllerStopRequested) {
        if (!controllerStopRequested || !"combat-main-top-level".equals(marker.category())) {
            return false;
        }
        String message = lines[markerLine];
        if (!message.contains("java.lang.UnsatisfiedLinkError:")) return false;
        int limit = Math.min(lines.length, markerLine + MAX_STACK_LINES + 1);
        for (int i = markerLine + 1; i < limit; i++) {
            String line = lines[i];
            if (line.contains("at org.lwjgl.openal.AL10.nalGetError(")
                    || line.contains("at org.lwjgl.openal.AL10.nalListenerfv(")) {
                return true;
            }
            if (!line.isBlank() && Character.isDigit(line.charAt(0))) return false;
        }
        return false;
    }

    private static boolean hasRequiredFrame(String[] lines, int markerLine, String requiredFrame) {
        if (requiredFrame == null || requiredFrame.isBlank()) {
            return true;
        }
        int limit = Math.min(lines.length, markerLine + MAX_STACK_LINES + 1);
        for (int i = markerLine + 1; i < limit; i++) {
            if (lines[i].contains(requiredFrame)) {
                return true;
            }
            if (!lines[i].isBlank() && Character.isDigit(lines[i].charAt(0))) {
                return false;
            }
        }
        return false;
    }

    private static String bounded(String value) {
        String normalized = value.replace('\u0000', '?');
        return normalized.length() <= MAX_MESSAGE_CHARACTERS
                ? normalized
                : normalized.substring(0, MAX_MESSAGE_CHARACTERS) + "...";
    }

    private static void addProblem(List<String> problems, String problem) {
        if (problems.size() < MAX_MATCHES) {
            problems.add(bounded(problem == null ? "unknown log-read problem" : problem));
        }
    }

    record Snapshot(Path logDirectory, Instant capturedAt, List<FileStamp> files, List<String> problems) {
    }

    record Evidence(
            boolean logAvailable,
            boolean consoleAvailable,
            boolean fatalDetected,
            boolean controllerStopRequested,
            long bytesExamined,
            long consoleBytesExamined,
            int filesExamined,
            boolean truncated,
            String consoleFile,
            List<Map<String, Object>> matches,
            List<Map<String, Object>> ignoredMatches,
            List<String> problems) {
        Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("logAvailable", logAvailable);
            values.put("consoleAvailable", consoleAvailable);
            values.put("fatalDetected", fatalDetected);
            values.put("controllerStopRequested", controllerStopRequested);
            values.put("bytesExamined", bytesExamined);
            values.put("consoleBytesExamined", consoleBytesExamined);
            values.put("filesExamined", filesExamined);
            values.put("truncated", truncated);
            values.put("consoleFile", consoleFile);
            values.put("matches", matches);
            values.put("ignoredMatches", ignoredMatches);
            values.put("problems", problems);
            return values;
        }
    }

    private record FileStamp(Path path, Object fileKey, long size, Instant modified) {
    }

    private record FatalMarker(String category, String text, String requiredFrame) {
        private FatalMarker {
            category = category.toLowerCase(Locale.ROOT);
        }
    }
}
