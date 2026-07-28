package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * {@code audio decode-probe} — reads a startup recording and reports whether the game opened every
 * declared sound effect while loading.
 *
 * <p>Exits 0 on any answer, including {@code LAZY}. A measurement that says "do not build this" is a
 * successful measurement; only a recording that cannot answer the question is a failure, and that
 * exits 4 so a script cannot mistake it for a result.</p>
 */
final class AudioDecodeProbeCommand {
    private AudioDecodeProbeCommand() {
    }

    static int execute(String[] args, int offset) throws Exception {
        Path recording = null;
        Path game = null;
        Path output = null;
        boolean json = false;
        for (int i = offset; i < args.length; i++) {
            switch (args[i]) {
                case "--recording" -> recording = Path.of(requireValue(args, ++i, "--recording"));
                case "--game" -> game = Path.of(requireValue(args, ++i, "--game"));
                case "--output" -> output = Path.of(requireValue(args, ++i, "--output"));
                case "--json" -> json = true;
                default -> {
                    if (recording == null && !args[i].startsWith("--")) {
                        recording = Path.of(args[i]);
                    } else {
                        throw new IllegalArgumentException("Unknown decode-probe option: " + args[i]);
                    }
                }
            }
        }
        if (recording == null) {
            throw new IllegalArgumentException(
                    "Expected: audio decode-probe <recording.jfr> [--game <dir>] [--json]");
        }
        if (!Files.isRegularFile(recording)) {
            System.err.println("No such recording: " + recording);
            return 3;
        }
        Path installRoot = InstallRoot.resolve(game);
        if (installRoot == null) {
            System.err.println("Preflight could not locate Starsector. Run `doctor` or provide --game.");
            return 3;
        }

        AudioDecodeProbe.Result result = AudioDecodeProbe.run(recording, installRoot);
        String text = Json.object(result.report());
        if (output != null) {
            Path resolved = output.toAbsolutePath().normalize();
            Path parent = resolved.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(resolved, text + System.lineSeparator(), StandardCharsets.UTF_8);
        }
        if (json) {
            System.out.println(text);
        } else {
            System.out.print(render(result));
        }
        return result.verdict() == AudioDecodeProbe.Verdict.UNUSABLE ? 4 : 0;
    }

    static String render(AudioDecodeProbe.Result result) {
        StringBuilder out = new StringBuilder();
        Map<String, Object> report = result.report();
        out.append("Audio decode probe\n\n");

        if (result.verdict() == AudioDecodeProbe.Verdict.UNUSABLE) {
            out.append("  This recording cannot answer the question.\n\n");
            out.append(wrap(result.detail(), "  ")).append('\n');
            return out.toString();
        }

        out.append("  recording spans ")
                .append(seconds(report.get("runSpanMillis")))
                .append(", ")
                .append(report.get("fileReadEvents"))
                .append(" file reads, ")
                .append(report.get("audioFileReadEvents"))
                .append(" of them audio\n\n");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> kinds = (List<Map<String, Object>>) report.get("byKind");
        for (Map<String, Object> kind : kinds) {
            int declared = ((Number) kind.get("declared")).intValue();
            if (declared == 0) {
                continue;
            }
            int opened = ((Number) kind.get("opened")).intValue();
            out.append(String.format("  %-14s %5d declared   %5d opened   %5d never opened%n",
                    kind.get("kind"), declared, opened, declared - opened));
            out.append(String.format("                 %s of PCM behind what was opened, %s behind what was not%n",
                    megabytes(kind.get("openedDecodedBytes")),
                    megabytes(kind.get("neverOpenedDecodedBytes"))));
            @SuppressWarnings("unchecked")
            Map<String, Object> percentiles = (Map<String, Object>) kind.get("firstOpenPercentilesMillis");
            if (!percentiles.isEmpty()) {
                out.append("                 first opened at ");
                List<String> parts = percentiles.entrySet().stream()
                        .map(entry -> entry.getKey() + " " + seconds(entry.getValue()))
                        .toList();
                out.append(String.join(", ", parts)).append('\n');
            }
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> readers = (List<Map<String, Object>>) report.get("topReaders");
        if (!readers.isEmpty()) {
            out.append("\n  read by\n");
            for (Map<String, Object> reader : readers) {
                out.append(String.format("    %8s  %s%n", reader.get("reads"), reader.get("frame")));
            }
        }

        out.append("\n  ").append(result.verdict()).append('\n');
        out.append(wrap(result.detail(), "  ")).append('\n');
        return out.toString();
    }

    private static String seconds(Object millis) {
        return String.format("%.1f s", ((Number) millis).doubleValue() / 1000.0);
    }

    private static String megabytes(Object bytes) {
        return String.format("%.1f MB", ((Number) bytes).doubleValue() / (1024.0 * 1024.0));
    }

    private static String wrap(String text, String indent) {
        StringBuilder out = new StringBuilder();
        int width = 88 - indent.length();
        int lineLength = 0;
        for (String word : text.split(" ")) {
            if (lineLength == 0) {
                out.append(indent);
            } else if (lineLength + 1 + word.length() > width) {
                out.append('\n').append(indent);
                lineLength = 0;
            } else {
                out.append(' ');
                lineLength++;
            }
            out.append(word);
            lineLength += word.length();
        }
        return out.toString();
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value for " + option);
        }
        return args[index];
    }
}
