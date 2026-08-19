package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** {@code audio census} — reports the decoded footprint of a profile's sound, without decoding it. */
final class AudioCensusCommand {
    private AudioCensusCommand() {
    }

    static int execute(String[] args, int offset) throws Exception {
        Path game = null;
        Path output = null;
        Path csv = null;
        for (int i = offset; i < args.length; i++) {
            switch (args[i]) {
                case "--game" -> game = Path.of(requireValue(args, ++i, "--game"));
                case "--output" -> output = Path.of(requireValue(args, ++i, "--output"));
                case "--csv" -> csv = Path.of(requireValue(args, ++i, "--csv"));
                default -> throw new IllegalArgumentException("Unknown audio census option: " + args[i]);
            }
        }

        Path installRoot = InstallRoot.resolve(game);
        if (installRoot == null) {
            System.err.println("Preflight could not locate Starsector. Run `doctor` or provide --game.");
            return 3;
        }

        AudioCensus.Result result = AudioCensus.scan(installRoot);
        String json = Json.object(result.report());
        if (output != null) {
            Files.writeString(prepare(output), json + System.lineSeparator(), StandardCharsets.UTF_8);
        }
        if (csv != null) {
            writeCsv(csv, result);
        }
        System.out.println(json);
        return 0;
    }

    /** Per-file rows, for slicing a policy question the aggregate report did not anticipate. */
    private static void writeCsv(Path csv, AudioCensus.Result result) throws Exception {
        StringBuilder text = new StringBuilder("path,provider,kind,codec,channels,sampleRate,frames,"
                + "seconds,encodedBytes,decodedBytes,decodable\n");
        for (AudioCensus.Sound sound : result.sounds()) {
            text.append(quote(sound.logicalPath())).append(',')
                    .append(quote(sound.rootId())).append(',')
                    .append(sound.kind().name().toLowerCase(Locale.ROOT)).append(',')
                    .append(quote(sound.codec())).append(',')
                    .append(sound.channels()).append(',')
                    .append(sound.sampleRate()).append(',')
                    .append(sound.frames()).append(',')
                    .append(String.format(Locale.ROOT, "%.3f", sound.seconds())).append(',')
                    .append(sound.encodedBytes()).append(',')
                    .append(sound.decodedBytes()).append(',')
                    .append(sound.decodable()).append('\n');
        }
        Files.writeString(prepare(csv), text.toString(), StandardCharsets.UTF_8);
    }

    private static Path prepare(Path path) throws Exception {
        Path resolved = path.toAbsolutePath().normalize();
        Path parent = resolved.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        return resolved;
    }

    private static String quote(String value) {
        // Spreadsheet parsers also treat leading tab/CR/LF as formula-capable prefixes.
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

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value for " + option);
        }
        return args[index];
    }
}
