package dev.starsector.preflight.cli;

import java.nio.file.Path;

final class AnalysisCommand {
    private AnalysisCommand() {
    }

    static int execute(String[] args, int offset) throws Exception {
        if (offset < args.length && "setup".equals(args[offset])) {
            return SetupCheckCommand.execute(args, offset + 1);
        }
        if (offset >= args.length || !"probe".equals(args[offset])) {
            throw new IllegalArgumentException(
                    "Expected: analyze <setup|probe> ...");
        }
        if (offset + 2 >= args.length) {
            throw new IllegalArgumentException(
                    "Expected: analyze probe <adapter.json> <summary.json> [--json <adapter-analysis.json>]");
        }
        Path adapter = Path.of(args[offset + 1]);
        Path summary = Path.of(args[offset + 2]);
        Path output = defaultOutput(adapter);
        int cursor = offset + 3;
        while (cursor < args.length) {
            if ("--json".equals(args[cursor])) {
                if (++cursor >= args.length) {
                    throw new IllegalArgumentException("Missing value for --json");
                }
                output = Path.of(args[cursor++]);
            } else {
                throw new IllegalArgumentException("Unknown analyze probe option: " + args[cursor]);
            }
        }
        AdapterProbeAnalysis.Result result = AdapterProbeAnalysis.analyze(adapter, summary, output);
        System.out.println(result.output());
        return 0;
    }

    private static Path defaultOutput(Path adapter) {
        Path absolute = adapter.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        return (parent == null ? Path.of(".").toAbsolutePath().normalize() : parent)
                .resolve("adapter-analysis.json");
    }
}
