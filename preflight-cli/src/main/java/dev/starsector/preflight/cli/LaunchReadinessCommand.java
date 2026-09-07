package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Machine-readable pre-launch admission for benchmark and automation callers. */
final class LaunchReadinessCommand {
    private LaunchReadinessCommand() {
    }

    static int execute(String[] args, int from) throws Exception {
        Path game = null;
        Path launcher = null;
        Path cache = null;
        String preset = "recommended";
        List<String> disabledDomains = new ArrayList<>();
        for (int index = from; index < args.length; index++) {
            switch (args[index]) {
                case "--game" -> game = Path.of(requireValue(args, ++index, "--game"));
                case "--launcher" -> launcher = Path.of(requireValue(args, ++index, "--launcher"));
                case "--cache-dir" -> cache = Path.of(requireValue(args, ++index, "--cache-dir"));
                case "--optimization-preset" -> preset = requireValue(args, ++index, "--optimization-preset");
                case "--disable-optimization-domain" -> disabledDomains.add(
                        requireValue(args, ++index, "--disable-optimization-domain"));
                case "--json" -> {
                    // Readiness is always machine-readable. Accepted for symmetry with cache health.
                }
                case "--help", "-h" -> {
                    PreflightCli.commandUsage("cache", System.out);
                    return 0;
                }
                default -> {
                    System.err.println("preflight cache readiness: unknown option: " + args[index]);
                    return 2;
                }
            }
        }

        List<String> run = new ArrayList<>();
        run.add("run");
        if (game != null) {
            run.add("--game");
            run.add(game.toString());
        }
        if (launcher != null) {
            run.add("--launcher");
            run.add(launcher.toString());
        }
        run.add("--optimization-preset");
        run.add(preset);
        for (String domain : disabledDomains) {
            run.add("--disable-optimization-domain");
            run.add(domain);
        }
        run.add("--no-record");
        run.add("--no-scan");
        CommandLine options = CommandLine.parse(run.toArray(String[]::new), 1);
        Platform platform = Platform.current();
        DiscoveryResult discovery = StarsectorDiscovery.discover(
                platform,
                Path.of(System.getProperty("user.home")),
                Path.of(System.getProperty("user.dir")),
                System.getenv(),
                options.game(),
                options.launcher());
        if (discovery.selected() == null) {
            System.err.println("preflight cache readiness: no launch target selected");
            return 3;
        }
        RunIdentity identity = RunIdentity.capture(SelfJar.locate());
        LaunchReadiness.Report readiness = LaunchReadiness.inspect(
                platform, options, discovery.selected(), cache, identity);
        System.out.println(Json.object(LaunchReadiness.json(readiness)));
        return 0;
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " requires a value");
        }
        return args[index];
    }
}
