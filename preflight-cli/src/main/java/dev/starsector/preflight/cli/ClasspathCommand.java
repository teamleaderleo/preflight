package dev.starsector.preflight.cli;

import java.nio.file.Files;
import java.nio.file.Path;

final class ClasspathCommand {
    private ClasspathCommand() {
    }

    static int execute(String[] args, int offset) throws Exception {
        if (offset >= args.length) {
            throw new IllegalArgumentException("Expected: classpath <audit|index|hot-patterns> ...");
        }
        if ("index".equals(args[offset])) {
            return ClasspathIndexCommand.execute(args, offset + 1);
        }
        if ("hot-patterns".equals(args[offset])) {
            return ModHotPatternCommand.execute(args, offset + 1);
        }
        if (!"audit".equals(args[offset])) {
            throw new IllegalArgumentException("Expected: classpath <audit|index|hot-patterns> ...");
        }
        Options options = parse(args, offset + 1);
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

        String json = ClasspathAudit.scan(target.installRoot()).toJson();
        if (options.output() == null) {
            System.out.println(json);
        } else {
            Path output = options.output().toAbsolutePath().normalize();
            Path parent = output.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(output, json + System.lineSeparator());
            System.out.println(output);
        }
        return 0;
    }

    private static Options parse(String[] args, int offset) {
        Path game = null;
        Path launcher = null;
        Path output = null;
        for (int i = offset; i < args.length; i++) {
            switch (args[i]) {
                case "--game" -> game = Path.of(requireValue(args, ++i, "--game"));
                case "--launcher" -> launcher = Path.of(requireValue(args, ++i, "--launcher"));
                case "--json" -> output = Path.of(requireValue(args, ++i, "--json"));
                default -> throw new IllegalArgumentException("Unknown classpath audit option: " + args[i]);
            }
        }
        return new Options(game, launcher, output);
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value for " + option);
        }
        return args[index];
    }

    private record Options(Path game, Path launcher, Path output) {
    }
}
