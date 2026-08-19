package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only portable projection of the local launch ledger. */
final class PlayHistoryExport {
    static final String FORMAT = "starsector-preflight-play-history-v1";

    private PlayHistoryExport() {
    }

    static Result export(PreflightHome home, Path jsonOutput, Path csvOutput) throws IOException {
        if (jsonOutput == null) {
            throw new IllegalArgumentException("play-history export requires a JSON output path");
        }
        Path json = jsonOutput.toAbsolutePath().normalize();
        Path csv = csvOutput == null ? null : csvOutput.toAbsolutePath().normalize();
        if (csv != null && csv.equals(json)) {
            throw new IllegalArgumentException("JSON and CSV history outputs must be different files");
        }

        List<LaunchLedger.Entry> entries = new ArrayList<>(LaunchLedger.read(home));
        entries.sort(Comparator.comparing(LaunchLedger.Entry::started)
                .thenComparing(LaunchLedger.Entry::launchId));

        String jsonText = Json.object(document(entries)) + System.lineSeparator();
        String csvText = csv == null ? null : csv(entries);

        writeNew(json, jsonText);
        if (csv != null) {
            writeNew(csv, csvText);
        }
        return new Result(json, csv, entries.size(), Playtime.of(entries).totalMillis());
    }

    static Map<String, Object> document(List<LaunchLedger.Entry> entries) {
        List<LaunchLedger.Entry> ordered = new ArrayList<>(entries);
        ordered.sort(Comparator.comparing(LaunchLedger.Entry::started)
                .thenComparing(LaunchLedger.Entry::launchId));

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("format", FORMAT);
        document.put("events", ordered.stream().map(PlayHistoryExport::event).toList());
        document.put("summary", summary(Playtime.of(ordered)));
        return document;
    }

    static String csv(List<LaunchLedger.Entry> entries) {
        List<LaunchLedger.Entry> ordered = new ArrayList<>(entries);
        ordered.sort(Comparator.comparing(LaunchLedger.Entry::started)
                .thenComparing(LaunchLedger.Entry::launchId));

        StringBuilder text = new StringBuilder(
                "launchId,started,elapsedMillis,outcome,profileFingerprint,provenance\n");
        for (LaunchLedger.Entry entry : ordered) {
            text.append(quote(entry.launchId())).append(',')
                    .append(quote(entry.started().toString())).append(',');
            if (entry.elapsedMillis() != null) {
                text.append(entry.elapsedMillis());
            }
            text.append(',')
                    .append(quote(entry.outcome())).append(',')
                    .append(quote(entry.profileFingerprint())).append(',')
                    .append(quote("observed"))
                    .append('\n');
        }
        return text.toString();
    }

    private static Map<String, Object> event(LaunchLedger.Entry entry) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("launchId", entry.launchId());
        event.put("started", entry.started());
        event.put("elapsedMillis", entry.elapsedMillis());
        event.put("outcome", entry.outcome());
        event.put("profileFingerprint", entry.profileFingerprint());
        event.put("provenance", "observed");
        return event;
    }

    private static Map<String, Object> summary(Playtime.Summary summary) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("totalMillis", summary.totalMillis());
        value.put("longestSessionMillis", summary.longestSessionMillis());
        value.put("averageMillis", summary.averageMillis());
        value.put("countedSessions", summary.launches());
        value.put("recordedEvents", summary.recorded());
        value.put("sessionsWithoutDuration", summary.sessionsWithoutDuration());
        value.put("ignoredAttempts", summary.ignoredAttempts());
        value.put("first", summary.first());
        value.put("last", summary.last());
        return value;
    }

    private static void writeNew(Path output, String content) throws IOException {
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(
                output,
                content,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
    }

    private static String quote(String value) {
        if (value == null) {
            return "\"\"";
        }
        boolean spreadsheetControl = false;
        if (!value.isEmpty()) {
            char first = value.charAt(0);
            spreadsheetControl = "=+-@".indexOf(first) >= 0
                    || first == '\t'
                    || first == '\r'
                    || first == '\n';
        }
        String safe = spreadsheetControl ? "'" + value : value;
        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    record Result(Path json, Path csv, int events, long totalMillis) {
    }
}
