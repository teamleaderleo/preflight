package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * A deliberately small, machine-readable bridge for the desktop application.
 *
 * <p>This command is intentionally absent from the public CLI help. The desktop host is the only
 * supported caller, and every invocation emits exactly one JSON document to standard output.
 */
final class DesktopBridgeCommand {
    private static final int PROTOCOL_VERSION = 1;

    private DesktopBridgeCommand() {
    }

    static int execute(String[] args, int offset) throws IOException {
        if (offset < args.length && "scenario".equals(args[offset])) {
            return scenario(args, offset + 1);
        }
        Options options = Options.parse(args, offset);
        Map<String, Object> snapshot = snapshot(
                Platform.current(),
                Path.of(System.getProperty("user.home")),
                Path.of(System.getProperty("user.dir")),
                System.getenv(),
                options.game(),
                options.launcher());
        System.out.println(Json.object(snapshot));
        return 0;
    }

    private static int scenario(String[] args, int offset) throws IOException {
        if (args.length != offset + 2 || !"validate".equals(args[offset])) {
            throw new IllegalArgumentException(
                    "Expected desktop bridge request: desktop scenario validate <scenario.json>");
        }
        DesktopSmokeScenario scenario = DesktopSmokeScenario.read(Path.of(args[offset + 1]));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocol", PROTOCOL_VERSION);
        result.put("valid", true);
        result.put("scenario", scenario.view());
        System.out.println(Json.object(result));
        return 0;
    }

    static Map<String, Object> snapshot(
            Platform platform,
            Path home,
            Path currentDirectory,
            Map<String, String> environment,
            Path explicitGame,
            Path explicitLauncher) throws IOException {
        DiscoveryResult discovery = StarsectorDiscovery.discover(
                platform, home, currentDirectory, environment, explicitGame, explicitLauncher);
        Path preflightHome = home.resolve(".starsector-preflight").toAbsolutePath().normalize();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocol", PROTOCOL_VERSION);
        result.put("engineVersion", engineVersion());
        result.put("platform", platform.name().toLowerCase(Locale.ROOT));
        result.put("ready", discovery.selected() != null);
        result.put("selected", target(discovery.selected()));
        result.put("candidates", discovery.candidates().stream()
                .map(DesktopBridgeCommand::target)
                .toList());
        result.put("diagnostics", discovery.diagnostics());
        result.put("preflightHome", preflightHome);
        result.put("cachePresent", Files.isDirectory(preflightHome.resolve("cache")));
        result.put("lastRun", lastRun(preflightHome.resolve("runs")));
        return result;
    }

    private static Map<String, Object> target(LaunchTarget target) {
        if (target == null) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("installRoot", target.installRoot());
        result.put("launcher", target.launcher());
        result.put("kind", target.kind());
        result.put("score", target.score());
        result.put("source", target.source());
        return result;
    }

    private static Map<String, Object> lastRun(Path runsDirectory) {
        if (!Files.isDirectory(runsDirectory)) {
            return null;
        }
        try (Stream<Path> runs = Files.list(runsDirectory)) {
            Path latest = runs.filter(Files::isDirectory)
                    .max(Comparator.comparing(DesktopBridgeCommand::modifiedAt))
                    .orElse(null);
            if (latest == null) {
                return null;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("directory", latest.toAbsolutePath().normalize());
            result.put("modifiedAt", modifiedAt(latest).toInstant());
            return result;
        } catch (IOException ignored) {
            return null;
        }
    }

    private static FileTime modifiedAt(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException ignored) {
            return FileTime.from(Instant.EPOCH);
        }
    }

    private static String engineVersion() {
        String version = PreflightCli.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? "development" : version;
    }

    private record Options(Path game, Path launcher) {
        private static Options parse(String[] args, int offset) {
            if (offset >= args.length || !"snapshot".equals(args[offset])) {
                throw new IllegalArgumentException(
                        "Expected desktop bridge request: desktop snapshot [--game <path>] [--launcher <path>]");
            }

            Path game = null;
            Path launcher = null;
            List<String> remaining = new ArrayList<>();
            for (int index = offset + 1; index < args.length; index++) {
                String argument = args[index];
                if (("--game".equals(argument) || "--launcher".equals(argument)) && index + 1 < args.length) {
                    Path value = Path.of(args[++index]);
                    if ("--game".equals(argument)) {
                        game = value;
                    } else {
                        launcher = value;
                    }
                } else {
                    remaining.add(argument);
                }
            }
            if (!remaining.isEmpty()) {
                throw new IllegalArgumentException(
                        "Unknown desktop bridge option" + (remaining.size() == 1 ? "" : "s")
                                + ": " + String.join(", ", remaining));
            }
            return new Options(game, launcher);
        }
    }
}
