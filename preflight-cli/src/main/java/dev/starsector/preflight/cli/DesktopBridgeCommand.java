package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.format.DateTimeParseException;
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
    private static final long MAX_ADAPTER_HEALTH_BYTES = 256 * 1024;
    private static final long MAX_RUN_METADATA_BYTES = 256 * 1024;

    private DesktopBridgeCommand() {
    }

    static int execute(String[] args, int offset) throws IOException {
        if (offset < args.length && "bootstrap".equals(args[offset])) {
            try {
                return bootstrap(args, offset);
            } catch (Exception failure) {
                throw new IOException("Could not read the desktop bootstrap state", failure);
            }
        }
        if (offset < args.length && "home-state".equals(args[offset])) {
            try {
                return DesktopHomeStateCommand.execute(args, offset + 1);
            } catch (Exception failure) {
                throw new IOException("Could not read the desktop home state", failure);
            }
        }
        if (offset < args.length && "scenario".equals(args[offset])) {
            return scenario(args, offset + 1);
        }
        if (offset < args.length && "process".equals(args[offset])) {
            return process(args, offset + 1);
        }
        if (offset < args.length && "evidence".equals(args[offset])) {
            return evidence(args, offset + 1);
        }
        if (offset < args.length && "smoke".equals(args[offset])) {
            return smoke(args, offset + 1);
        }
        if (offset < args.length && "benchmark".equals(args[offset])) {
            return benchmark(args, offset + 1);
        }
        Options options = Options.parse(args, offset, "snapshot");
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

    /**
     * Discovers the installation and reads the first page's data in one JVM. The desktop used to
     * launch this command once for discovery and then launch a second JVM for {@code home-state}.
     * Starting the bundled runtime twice was visible in the time between opening Preflight and
     * getting an actionable Home page.
     */
    private static int bootstrap(String[] args, int offset) throws Exception {
        Options options = Options.parse(args, offset, "bootstrap");
        Platform platform = Platform.current();
        Path home = Path.of(System.getProperty("user.home"));
        Map<String, Object> result = bootstrap(
                platform,
                home,
                Path.of(System.getProperty("user.dir")),
                System.getenv(),
                options.game(),
                options.launcher());
        System.out.println(Json.object(result));
        return 0;
    }

    static Map<String, Object> bootstrap(
            Platform platform,
            Path home,
            Path currentDirectory,
            Map<String, String> environment,
            Path explicitGame,
            Path explicitLauncher) throws IOException {
        Map<String, Object> snapshot = snapshot(
                platform,
                home,
                currentDirectory,
                environment,
                explicitGame,
                explicitLauncher);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("format", "starsector-preflight-desktop-bootstrap-v1");
        result.put("snapshot", snapshot);
        Object selected = snapshot.get("selected");
        if (!(selected instanceof Map<?, ?> target)
                || !(target.get("installRoot") instanceof Path installRoot)) {
            result.put("homeState", null);
            result.put("homeStateError", null);
        } else {
            try {
                PreflightHome desktopHome = PreflightHome.resolve(platform, home, environment);
                result.put("homeState", DesktopHomeStateCommand.read(desktopHome, installRoot));
                result.put("homeStateError", null);
            } catch (Exception failure) {
                String message = failure.getMessage();
                result.put("homeState", null);
                result.put("homeStateError", message == null || message.isBlank()
                        ? failure.getClass().getSimpleName()
                        : message.replaceAll("\\s+", " ").strip());
            }
        }
        return result;
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

    private static int process(String[] args, int offset) throws IOException {
        if (args.length != offset + 2 || !"validate".equals(args[offset])) {
            throw new IllegalArgumentException(
                    "Expected desktop bridge request: desktop process validate <runtime-process.json>");
        }
        RuntimeProcessIdentity identity = RuntimeProcessIdentity.read(Path.of(args[offset + 1]));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocol", PROTOCOL_VERSION);
        result.put("process", identity.inspect());
        System.out.println(Json.object(result));
        return 0;
    }

    private static int evidence(String[] args, int offset) throws IOException {
        if (args.length != offset + 4 || !"collect".equals(args[offset])) {
            throw new IllegalArgumentException(
                    "Expected desktop bridge request: desktop evidence collect "
                            + "<scenario.json> <driver-result.json> <smoke-evidence.json>");
        }
        DesktopSmokeScenario scenario = DesktopSmokeScenario.read(Path.of(args[offset + 1]));
        Map<String, Object> result = DesktopSmokeEvidence.collect(
                scenario, Path.of(args[offset + 2]), Path.of(args[offset + 3]));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("protocol", PROTOCOL_VERSION);
        response.put("evidence", result);
        System.out.println(Json.object(response));
        return 0;
    }

    private static int smoke(String[] args, int offset) throws IOException {
        if (args.length == offset + 1 && "probe".equals(args[offset])) {
            DesktopSmokeDriver driver = driver();
            Map<String, Object> probe = new LinkedHashMap<>();
            try {
                DesktopSmokeDriver.Descriptor descriptor = driver.descriptor();
                probe.put("ready", true);
                probe.put("driver", descriptor(descriptor));
                probe.put("diagnostics", descriptor.diagnostics());
            } catch (DesktopSmokeDriver.UnavailableException unavailable) {
                probe.put("ready", false);
                probe.put("driver", null);
                probe.put("diagnostics", List.of(unavailable.getMessage()));
            } catch (Exception failure) {
                probe.put("ready", false);
                probe.put("driver", null);
                probe.put("diagnostics", List.of(
                        "Desktop smoke probe failed: " + failure.getClass().getSimpleName()
                                + ": " + failure.getMessage()));
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("protocol", PROTOCOL_VERSION);
            response.put("probe", probe);
            System.out.println(Json.object(response));
            return 0;
        }
        if (offset < args.length && "launch".equals(args[offset])) {
            if (args.length < offset + 3) {
                throw new IllegalArgumentException(
                        "Expected desktop bridge request: desktop smoke launch "
                                + "<scenario.json> <run-directory> "
                                + "[--game <path>] [--launcher <path>]");
            }
            DesktopSmokeScenario scenario = DesktopSmokeScenario.read(Path.of(args[offset + 1]));
            Path runDirectory = Path.of(args[offset + 2]);
            SmokeLaunchOptions options = SmokeLaunchOptions.parse(args, offset + 3);
            DesktopSmokeDriver driver = driver();
            Map<String, Object> launched;
            try {
                launched = DesktopSmokeLaunch.launch(
                        scenario,
                        runDirectory,
                        options.game(),
                        options.launcher(),
                        driver,
                        java.time.Clock.systemUTC());
            } catch (Exception failure) {
                throw launchFailure("Desktop smoke launch failed", failure);
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("protocol", PROTOCOL_VERSION);
            response.put("launch", launched);
            System.out.println(Json.object(response));
            return statusExitCode(launched.get("status"));
        }
        if (args.length != offset + 4 || !"run".equals(args[offset])) {
            throw new IllegalArgumentException(
                    "Expected desktop smoke request: run <scenario.json> "
                            + "<runtime-process.json> <run-directory>, or launch "
                            + "<scenario.json> <run-directory>");
        }
        DesktopSmokeScenario scenario = DesktopSmokeScenario.read(Path.of(args[offset + 1]));
        Path runtimeProcess = Path.of(args[offset + 2]);
        Path runDirectory = Path.of(args[offset + 3]);
        DesktopSmokeDriver driver = driver();
        Map<String, Object> evidence = DesktopSmokeRunner.run(
                scenario, runtimeProcess, runDirectory, driver, java.time.Clock.systemUTC());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("protocol", PROTOCOL_VERSION);
        response.put("evidence", evidence);
        System.out.println(Json.object(response));
        return statusExitCode(evidence.get("status"));
    }

    private static int benchmark(String[] args, int offset) throws IOException {
        if (args.length < offset + 4 || !"launch".equals(args[offset])) {
            throw new IllegalArgumentException(
                    "Expected desktop bridge request: desktop benchmark launch "
                            + "<measurement-scenario.json> <optimized-scenario.json> "
                            + "<session-directory> [--game <path>] [--launcher <path>]");
        }
        DesktopSmokeScenario baseline = DesktopSmokeScenario.read(Path.of(args[offset + 1]));
        DesktopSmokeScenario candidate = DesktopSmokeScenario.read(Path.of(args[offset + 2]));
        Path session = Path.of(args[offset + 3]);
        SmokeLaunchOptions options = SmokeLaunchOptions.parse(args, offset + 4);
        Map<String, Object> launched;
        try {
            launched = DesktopBenchmarkLaunch.launch(
                    baseline,
                    candidate,
                    session,
                    options.game(),
                    options.launcher(),
                    driver(),
                    java.time.Clock.systemUTC());
        } catch (Exception failure) {
            throw launchFailure("Desktop benchmark launch failed", failure);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("protocol", PROTOCOL_VERSION);
        response.put("launch", launched);
        System.out.println(Json.object(response));
        return statusExitCode(launched.get("status"));
    }

    static int statusExitCode(Object status) {
        return "passed".equals(status) ? 0 : "skipped".equals(status) ? 3 : 1;
    }

    static IOException launchFailure(String context, Exception failure) {
        if (failure instanceof IOException io) return io;
        String detail = failure.getMessage();
        return new IOException(
                detail == null || detail.isBlank() ? context : context + ": " + detail,
                failure);
    }

    private static DesktopSmokeDriver driver() {
        return switch (Platform.current()) {
            case MAC -> new MacDesktopSmokeDriver();
            case WINDOWS -> new WindowsDesktopSmokeDriver();
            case LINUX -> new LinuxDesktopSmokeDriver();
            default -> new UnavailableDesktopSmokeDriver();
        };
    }

    private static Map<String, Object> descriptor(DesktopSmokeDriver.Descriptor descriptor) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", descriptor.id());
        value.put("version", descriptor.version());
        value.put("platform", descriptor.platform());
        value.put("capabilities", descriptor.capabilities());
        return value;
    }

    private record SmokeLaunchOptions(Path game, Path launcher) {
        private static SmokeLaunchOptions parse(String[] args, int offset) {
            Path game = null;
            Path launcher = null;
            for (int index = offset; index < args.length; index++) {
                String option = args[index];
                if ("--game".equals(option) && index + 1 < args.length) {
                    game = Path.of(args[++index]);
                } else if ("--launcher".equals(option) && index + 1 < args.length) {
                    launcher = Path.of(args[++index]);
                } else {
                    throw new IllegalArgumentException(
                            "Unknown desktop smoke launch option: " + option);
                }
            }
            return new SmokeLaunchOptions(game, launcher);
        }
    }

    private static final class UnavailableDesktopSmokeDriver implements DesktopSmokeDriver {
        @Override
        public Descriptor descriptor() throws UnavailableException {
            throw new UnavailableException(
                    "No PID-addressed desktop smoke driver is available on this platform yet");
        }

        @Override
        public void attach(ProcessTarget target) throws UnavailableException {
            throw new UnavailableException("Desktop smoke attachment is unavailable");
        }

        @Override
        public ActionResult execute(Map<String, Object> step, Path runDirectory)
                throws UnavailableException {
            throw new UnavailableException("Desktop smoke actions are unavailable");
        }

        @Override
        public Observation observe() throws UnavailableException {
            throw new UnavailableException("Desktop smoke observation is unavailable");
        }
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
        PreflightHome desktopHome = PreflightHome.resolve(platform, home, environment);
        Path preflightHome = desktopHome.root();

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
        result.put("playtime", playtime(desktopHome));
        return result;
    }

    private static Map<String, Object> playtime(PreflightHome home) {
        LaunchLedgerBackfill.runOnce(home);
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Playtime.Summary summary = Playtime.of(LaunchLedger.read(home));
            result.put("readable", true);
            result.put("totalMillis", summary.totalMillis());
            result.put("longestSessionMillis", summary.longestSessionMillis());
            result.put("averageMillis", summary.averageMillis());
            result.put("launches", summary.launches());
            result.put("sessionsWithoutDuration", summary.sessionsWithoutDuration());
            result.put("first", summary.first());
            result.put("last", summary.last());
        } catch (IOException unreadable) {
            result.put("readable", false);
            result.put("totalMillis", 0);
            result.put("longestSessionMillis", 0);
            result.put("averageMillis", 0);
            result.put("launches", 0);
            result.put("sessionsWithoutDuration", 0);
            result.put("first", null);
            result.put("last", null);
        }
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
            Path latest = runs.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .max(Comparator.comparing(DesktopBridgeCommand::modifiedAt))
                    .orElse(null);
            if (latest == null) {
                return null;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("directory", latest.toAbsolutePath().normalize());
            result.put("modifiedAt", modifiedAt(latest).toInstant());
            result.put("adapterHealth", adapterHealth(latest.resolve("adapter-health.json")));
            Map<String, Object> run = runSummary(latest.resolve("run.json"));
            if (run != null) {
                result.put("started", run.get("started"));
                result.put("ended", run.get("ended"));
                result.put("durationMillis", run.get("durationMillis"));
                result.put("outcome", run.get("outcome"));
                result.put("exitCode", run.get("exitCode"));
            }
            return result;
        } catch (IOException ignored) {
            return null;
        }
    }

    private static Map<String, Object> adapterHealth(Path healthFile) {
        try {
            if (!Files.isRegularFile(healthFile, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(healthFile) > MAX_ADAPTER_HEALTH_BYTES) {
                return null;
            }
            byte[] encoded;
            try (InputStream input = Files.newInputStream(healthFile, LinkOption.NOFOLLOW_LINKS)) {
                encoded = input.readNBytes(Math.toIntExact(MAX_ADAPTER_HEALTH_BYTES + 1));
            }
            if (encoded.length > MAX_ADAPTER_HEALTH_BYTES) {
                return null;
            }
            Map<String, Object> health = StrictJson.object(new String(encoded, StandardCharsets.UTF_8));
            if (!AdapterHealthReport.FORMAT.equals(health.get("format"))) {
                return null;
            }
            String status = health.get("status") instanceof String value ? value : "";
            if (!List.of("ACTIVE", "PARTIAL", "SAFE_FALLBACK", "DISABLED", "PROBE_ONLY", "NO_TARGETS", "ERROR")
                    .contains(status)) {
                return null;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("format", AdapterHealthReport.FORMAT);
            result.put("status", status);
            result.put("accelerationsActive", Boolean.TRUE.equals(health.get("accelerationsActive")));
            result.put("originalCodeRetained", Boolean.TRUE.equals(health.get("originalCodeRetained")));
            result.put("reviewRecommended", Boolean.TRUE.equals(health.get("reviewRecommended")));
            result.put("transformationsApplied", nonNegativeNumber(health.get("transformationsApplied")));
            result.put("registryTargets", nonNegativeNumber(health.get("registryTargets")));
            result.put("containedFailures", nonNegativeNumber(health.get("containedFailures")));
            result.put("evidenceKinds", strings(health.get("evidenceKinds"), 32));
            result.put("suggestedActions", strings(health.get("suggestedActions"), 16));
            return result;
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    private static Map<String, Object> runSummary(Path runFile) {
        try {
            if (!Files.isRegularFile(runFile, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(runFile) > MAX_RUN_METADATA_BYTES) {
                return null;
            }
            byte[] encoded;
            try (InputStream input = Files.newInputStream(runFile, LinkOption.NOFOLLOW_LINKS)) {
                encoded = input.readNBytes(Math.toIntExact(MAX_RUN_METADATA_BYTES + 1));
            }
            if (encoded.length > MAX_RUN_METADATA_BYTES) {
                return null;
            }
            Map<String, Object> run = StrictJson.object(new String(encoded, StandardCharsets.UTF_8));
            String started = run.get("started") instanceof String value ? value : null;
            String ended = run.get("ended") instanceof String value ? value : null;
            Long durationMillis = null;
            if (started != null && ended != null) {
                try {
                    long diff = Instant.parse(ended).toEpochMilli() - Instant.parse(started).toEpochMilli();
                    if (diff >= 0) {
                        durationMillis = diff;
                    }
                } catch (DateTimeParseException ignored) {
                }
            }
            String outcome = run.get("outcome") instanceof String value ? value : null;
            Long exitCode = run.get("exitCode") instanceof Number value ? value.longValue() : null;

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("started", started);
            result.put("ended", ended);
            result.put("durationMillis", durationMillis);
            result.put("outcome", outcome);
            result.put("exitCode", exitCode);
            return result;
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    private static long nonNegativeNumber(Object value) {
        return value instanceof Number number ? Math.max(0L, number.longValue()) : 0L;
    }

    private static List<String> strings(Object value, int limit) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(String::strip)
                .filter(item -> !item.isEmpty())
                .limit(limit)
                .toList();
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
        private static Options parse(String[] args, int offset, String command) {
            if (offset >= args.length || !command.equals(args[offset])) {
                throw new IllegalArgumentException(
                        "Expected desktop bridge request: desktop " + command
                                + " [--game <path>] [--launcher <path>]");
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
