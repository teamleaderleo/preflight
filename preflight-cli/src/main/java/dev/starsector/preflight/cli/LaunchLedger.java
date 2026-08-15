package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One line per launch, kept after the launch's evidence is gone.
 *
 * <p>A run directory is about a megabyte and is worth that for a few days: it answers "what
 * happened during this launch", which is a question with a short shelf life. What stays worth
 * keeping is much smaller -- when it ran, how long it took, whether it worked, and under which
 * settings. Those few fields are what a history is made of, and holding them costs a couple of
 * hundred bytes instead of a megabyte.
 *
 * <p>Separating the two means retention no longer has to choose between forgetting last month and
 * carrying last month's diagnostics. Evidence can be evicted early precisely because the ledger is
 * not evidence: it is the record that the launch happened and how it went.
 *
 * <p>Append-only, one JSON object per line. A launch never fails because its ledger line could not
 * be written, and a line that cannot be parsed later is skipped rather than fatal -- a history that
 * refuses to load because of one bad row is worse than one with a gap.
 */
final class LaunchLedger {
    static final String FORMAT = "starsector-preflight-launch-ledger-v1";

    /**
     * Roughly 2 MB at the record sizes this writes, and about a launch a day for thirty years.
     * The cap exists so the file has an answer to "how large can this get", not because anyone is
     * expected to reach it; when it is reached the oldest lines go, since a history's recent end is
     * the end anybody asks about.
     */
    static final int MAX_ENTRIES = 10_000;

    private LaunchLedger() {
    }

    static Path path(PreflightHome home) {
        return home.root().resolve("history").resolve("launches.jsonl");
    }

    /**
     * Appends one launch, and never throws.
     *
     * @return the reason it could not be recorded, or null when it was
     */
    static String record(PreflightHome home, Entry entry) {
        try {
            Path path = path(home);
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(
                    path,
                    Json.object(entry.toMap()) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND);
            trim(path);
            return null;
        } catch (IOException | RuntimeException error) {
            String message = error.getMessage();
            return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
        }
    }

    /** Drops the oldest lines once the file passes the cap, rewriting through a temporary. */
    private static void trim(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.size() <= MAX_ENTRIES) {
            return;
        }
        List<String> kept = lines.subList(lines.size() - MAX_ENTRIES, lines.size());
        Path temporary = path.resolveSibling(
                path.getFileName() + ".tmp-" + ProcessHandle.current().pid());
        Files.write(temporary, kept, StandardCharsets.UTF_8);
        Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /** Every readable line, oldest first. Unreadable lines are skipped, not fatal. */
    static List<Entry> read(PreflightHome home) throws IOException {
        Path path = path(home);
        if (!Files.isRegularFile(path)) {
            return List.of();
        }
        List<Entry> entries = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            Entry entry = Entry.parse(line);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return List.copyOf(entries);
    }

    /** What a history says when you ask it how things have been going. */
    static Summary summarize(List<Entry> entries) {
        long completed = entries.stream().filter(Entry::succeeded).count();
        long fatal = entries.stream().filter(Entry::fatalDetected).count();
        Instant first = entries.isEmpty() ? null : entries.get(0).started();
        Instant last = entries.isEmpty() ? null : entries.get(entries.size() - 1).started();
        return new Summary(entries.size(), completed, fatal, first, last);
    }

    record Summary(int launches, long completed, long fatal, Instant first, Instant last) {
    }

    record Entry(
            Instant started,
            Long elapsedMillis,
            String outcome,
            Integer exitCode,
            boolean fatalDetected,
            String optimizationPreset,
            List<String> disabledOptimizationDomains,
            String runDirectory) {

        boolean succeeded() {
            return "COMPLETED".equals(outcome) && !fatalDetected;
        }

        Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("format", FORMAT);
            values.put("started", started);
            values.put("elapsedMillis", elapsedMillis);
            values.put("outcome", outcome);
            values.put("exitCode", exitCode);
            values.put("fatalDetected", fatalDetected);
            values.put("optimizationPreset", optimizationPreset);
            values.put("disabledOptimizationDomains", disabledOptimizationDomains);
            values.put("runDirectory", runDirectory);
            return values;
        }

        /**
         * One line back into an entry, or null when it is not one.
         *
         * <p>Deliberately forgiving. A ledger is read to answer "how has this been going", and a
         * single malformed line -- a partial write from a machine that lost power mid-launch, most
         * likely -- should cost that line and nothing else.
         */
        static Entry parse(String line) {
            if (line == null || line.isBlank()) {
                return null;
            }
            Map<String, Object> values;
            try {
                values = StrictJson.object(line);
            } catch (RuntimeException notJson) {
                return null;
            }
            if (!FORMAT.equals(values.get("format"))) {
                return null;
            }
            Instant started = instant(values.get("started"));
            if (started == null) {
                return null;
            }
            return new Entry(
                    started,
                    values.get("elapsedMillis") instanceof Number elapsed ? elapsed.longValue() : null,
                    values.get("outcome") instanceof String outcome ? outcome : null,
                    values.get("exitCode") instanceof Number code ? code.intValue() : null,
                    Boolean.TRUE.equals(values.get("fatalDetected")),
                    values.get("optimizationPreset") instanceof String preset ? preset : null,
                    strings(values.get("disabledOptimizationDomains")),
                    values.get("runDirectory") instanceof String directory ? directory : null);
        }

        private static Instant instant(Object value) {
            if (!(value instanceof String text)) {
                return null;
            }
            try {
                return Instant.parse(text);
            } catch (RuntimeException notAnInstant) {
                return null;
            }
        }

        private static List<String> strings(Object value) {
            if (!(value instanceof List<?> list)) {
                return List.of();
            }
            List<String> result = new ArrayList<>(list.size());
            for (Object element : list) {
                if (element instanceof String text) {
                    result.add(text);
                }
            }
            return List.copyOf(result);
        }
    }
}
