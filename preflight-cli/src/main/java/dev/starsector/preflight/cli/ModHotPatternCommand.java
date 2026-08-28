package dev.starsector.preflight.cli;

import java.nio.file.Files;
import java.nio.file.Path;

/** CLI boundary for the offline mod bytecode lead generator. */
final class ModHotPatternCommand {
    private static final int DEFAULT_LIMIT = 250;

    private ModHotPatternCommand() {
    }

    static int execute(String[] args, int offset) throws Exception {
        Options options = parse(args, offset);
        DiscoveryResult discovery = StarsectorDiscovery.discover(
                Platform.current(),
                Path.of(System.getProperty("user.home")),
                Path.of(System.getProperty("user.dir")),
                System.getenv(),
                options.game(),
                options.launcher());
        LaunchTarget target = discovery.selected();
        if (target == null) {
            System.err.println("Preflight could not locate Starsector. Run `doctor` or provide --game.");
            return 3;
        }

        String json = ModHotPatternAudit.scan(target.installRoot(), options.limit()).toJson();
        if (options.output() == null) {
            System.out.println(json);
        } else {
            Path output = options.output().toAbsolutePath().normalize();
            Path parent = output.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(output, json + System.lineSeparator());
            System.out.println(output);
        }
        return 0;
    }

    private static Options parse(String[] args, int offset) {
        Path game = null;
        Path launcher = null;
        Path output = null;
        int limit = DEFAULT_LIMIT;
        for (int index = offset; index < args.length; index++) {
            switch (args[index]) {
                case "--game" -> game = Path.of(requireValue(args, ++index, "--game"));
                case "--launcher" -> launcher = Path.of(requireValue(args, ++index, "--launcher"));
                case "--json" -> output = Path.of(requireValue(args, ++index, "--json"));
                case "--limit" -> limit = parseLimit(requireValue(args, ++index, "--limit"));
                default -> throw new IllegalArgumentException(
                        "Unknown classpath hot-patterns option: " + args[index]);
            }
        }
        return new Options(game, launcher, output, limit);
    }

    private static int parseLimit(String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1 || parsed > 10_000) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("--limit must be between 1 and 10000");
        }
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length) throw new IllegalArgumentException("Missing value for " + option);
        return args[index];
    }

    private record Options(Path game, Path launcher, Path output, int limit) {
    }
}
