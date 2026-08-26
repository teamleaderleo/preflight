package dev.starsector.preflight.cli;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Linux X11 smoke adapter that discovers windows only from the recorded process ID. */
final class LinuxDesktopSmokeDriver implements DesktopSmokeDriver {
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration QUIT_GRACE = Duration.ofSeconds(8);
    private static final int LOG_TAIL_BYTES = 1024 * 1024;
    private static final Map<String, String> KEYS = Map.ofEntries(
            Map.entry("a", "a"),
            Map.entry("s", "s"),
            Map.entry("d", "d"),
            Map.entry("f", "f"),
            Map.entry("w", "w"),
            Map.entry("r", "r"),
            Map.entry("u", "u"),
            Map.entry("n", "n"),
            Map.entry("return", "Return"),
            Map.entry("tab", "Tab"),
            Map.entry("space", "space"),
            Map.entry("escape", "Escape"),
            Map.entry("capslock", "Caps_Lock"));
    private static final Map<String, TargetPoint> TARGETS = targets();

    private final DesktopCommandExecutor commands;
    private final String xdotool;
    private final String imageMagickImport;
    private final Map<String, String> environment;
    private ProcessTarget target;

    LinuxDesktopSmokeDriver() {
        this(new SystemDesktopCommandExecutor(), "xdotool", "import", System.getenv());
    }

    LinuxDesktopSmokeDriver(
            DesktopCommandExecutor commands,
            String xdotool,
            String imageMagickImport,
            Map<String, String> environment) {
        this.commands = commands;
        this.xdotool = xdotool;
        this.imageMagickImport = imageMagickImport;
        this.environment = Map.copyOf(environment);
    }

    @Override
    public Descriptor descriptor() throws Exception {
        if (Platform.current() != Platform.LINUX) {
            throw new UnavailableException(
                    "The PID-addressed Linux driver is unavailable on this platform");
        }
        String session = environment.getOrDefault("XDG_SESSION_TYPE", "")
                .toLowerCase(Locale.ROOT);
        if ("wayland".equals(session)) {
            throw new UnavailableException(
                    "Linux desktop automation currently requires an X11 session; Wayland is skipped");
        }
        if (environment.getOrDefault("DISPLAY", "").isBlank()) {
            throw new UnavailableException("Linux desktop automation requires an X11 DISPLAY");
        }
        try {
            command(List.of(xdotool, "version"), "xdotool");
            command(List.of(imageMagickImport, "-version"), "ImageMagick import");
        } catch (IOException unavailable) {
            throw new UnavailableException(
                    "Linux desktop automation requires xdotool and ImageMagick import", unavailable);
        }
        return new Descriptor(
                "linux-xdotool-pid",
                "1",
                "linux",
                Set.of("process-control", "window-control", "screen-capture", "evidence-read"),
                List.of("X11 window ownership and capture are verified on first use"));
    }

    @Override
    public void attach(ProcessTarget target) throws Exception {
        if (target == null || target.pid() <= 0 || target.startedAt() == null) {
            throw new IllegalArgumentException("A PID and process start instant are required");
        }
        requireSameLifetime(target);
        this.target = target;
    }

    @Override
    public ActionResult execute(Map<String, Object> step, Path runDirectory) throws Exception {
        ProcessTarget attached = attached();
        requireSameLifetime(attached);
        String kind = step.get("kind").toString();
        return switch (kind) {
            case "activate-window" -> activate(attached);
            case "click" -> click(attached, step.get("target").toString());
            case "press-key" -> pressKey(attached, step.get("key").toString());
            case "hold-key" -> holdKey(attached, step.get("key").toString(),
                    ((Number) step.get("durationMillis")).intValue());
            case "scroll-wheel" -> scrollWheel(
                    attached, step.get("direction").toString(),
                    ((Number) step.get("clicks")).intValue());
            case "capture" -> capture(attached, step, runDirectory);
            case "quit" -> quit(attached);
            default -> throw new IllegalArgumentException(
                    "Unsupported Linux smoke action: " + kind);
        };
    }

    @Override
    public Observation observe() throws Exception {
        ProcessTarget attached = attached();
        requireSameLifetime(attached);
        Window window = window(attached.pid());
        String focused = command(List.of(xdotool, "getwindowfocus"), "xdotool")
                .output().trim();
        return new Observation("PID " + attached.pid() + " X11 window " + window.id()
                + " " + window.x() + "," + window.y() + "," + window.width() + ","
                + window.height() + " frontmost=" + Long.toString(window.id()).equals(focused));
    }

    @Override
    public void shutdown() throws Exception {
        ProcessTarget attached = target;
        if (attached == null || !sameLifetime(attached)) return;
        quit(attached);
    }

    private ActionResult activate(ProcessTarget attached) throws Exception {
        Window window = window(attached.pid());
        command(List.of(xdotool, "windowactivate", "--sync", Long.toString(window.id())),
                "xdotool");
        verifyWindowOwner(window.id(), attached.pid());
        return ActionResult.completed("activated X11 window " + window.id()
                + " for PID " + attached.pid());
    }

    private ActionResult click(ProcessTarget attached, String name) throws Exception {
        TargetPoint point = TARGETS.get(name);
        if (point == null) {
            throw new IllegalArgumentException("Unsupported Linux smoke target: " + name);
        }
        Window window = window(attached.pid());
        int x = (int) Math.round(window.width() * point.x());
        int y = (int) Math.round(window.height() * point.y());
        command(List.of(
                xdotool,
                "windowactivate", "--sync", Long.toString(window.id()),
                "mousemove", "--window", Long.toString(window.id()),
                Integer.toString(x), Integer.toString(y),
                "click", "1"), "xdotool");
        verifyWindowOwner(window.id(), attached.pid());
        return ActionResult.completed("clicked " + point.name() + " at " + x + "," + y
                + " in X11 window " + window.id());
    }

    private ActionResult pressKey(ProcessTarget attached, String key) throws Exception {
        String normalized = keySymbol(key);
        Window window = window(attached.pid());
        command(List.of(
                xdotool, "windowactivate", "--sync", Long.toString(window.id()),
                "key", normalized), "xdotool");
        verifyWindowOwner(window.id(), attached.pid());
        return ActionResult.completed("pressed " + normalized + " in X11 window " + window.id());
    }

    private ActionResult holdKey(ProcessTarget attached, String key, int durationMillis)
            throws Exception {
        String normalized = keySymbol(key);
        Window window = window(attached.pid());
        command(List.of(
                xdotool, "windowactivate", "--sync", Long.toString(window.id()),
                "keydown", normalized), "xdotool");
        boolean interrupted = false;
        try {
            Thread.sleep(durationMillis);
        } catch (InterruptedException error) {
            interrupted = true;
            throw error;
        } finally {
            if (interrupted) Thread.interrupted();
            try {
                command(List.of(xdotool, "keyup", normalized), "xdotool");
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
        verifyWindowOwner(window.id(), attached.pid());
        return ActionResult.completed("held " + normalized + " for " + durationMillis + " ms");
    }

    private ActionResult scrollWheel(ProcessTarget attached, String direction, int clicks)
            throws Exception {
        if (clicks < 1 || clicks > 24) {
            throw new IllegalArgumentException("Linux scroll clicks must be in 1..24");
        }
        int button = switch (direction.toLowerCase(Locale.ROOT)) {
            case "in" -> 4;
            case "out" -> 5;
            default -> throw new IllegalArgumentException(
                    "Unsupported Linux scroll direction: " + direction);
        };
        Window window = window(attached.pid());
        command(List.of(
                xdotool, "windowactivate", "--sync", Long.toString(window.id()),
                "mousemove", "--window", Long.toString(window.id()),
                Integer.toString(window.width() / 2), Integer.toString(window.height() / 2),
                "click", "--repeat", Integer.toString(clicks), Integer.toString(button)),
                "xdotool");
        verifyWindowOwner(window.id(), attached.pid());
        return ActionResult.completed("scrolled " + direction + " " + clicks
                + " clicks in X11 window " + window.id());
    }

    private ActionResult capture(
            ProcessTarget attached, Map<String, Object> step, Path runDirectory) throws Exception {
        Path realRun = runDirectory.toRealPath();
        @SuppressWarnings("unchecked")
        List<String> requested = (List<String>) step.get("artifacts");
        List<Artifact> artifacts = new ArrayList<>();
        for (String artifact : requested) {
            switch (artifact) {
                case "screenshot" -> artifacts.add(screenshot(attached, realRun));
                case "log-tail" -> artifacts.add(logTail(realRun));
                case "adapter-health" -> artifacts.add(snapshotArtifact(
                        realRun, "runtime-adapter-health.json",
                        "desktop-smoke-adapter-health.json", artifact));
                case "frame-report" -> artifacts.add(snapshotArtifact(
                        realRun, "runtime-frame-report.json",
                        "desktop-smoke-frame-report.json", artifact));
                case "audio-window" -> throw new UnavailableException(
                        "The Linux adapter doesn't yet provide bounded audio capture");
                default -> throw new IllegalArgumentException(
                        "Unsupported capture artifact: " + artifact);
            }
        }
        return new ActionResult("captured " + artifacts.size() + " artifact(s)",
                List.copyOf(artifacts));
    }

    private Artifact screenshot(ProcessTarget attached, Path runDirectory) throws Exception {
        Window window = window(attached.pid());
        Path destination = runDirectory.resolve("desktop-smoke.png");
        command(List.of(
                imageMagickImport, "-window", Long.toString(window.id()),
                "png:" + destination), "ImageMagick import");
        verifyWindowOwner(window.id(), attached.pid());
        if (!Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)
                || Files.size(destination) == 0) {
            throw new UnavailableException("X11 didn't produce a bounded game-window capture");
        }
        return new Artifact("screenshot", destination);
    }

    private ActionResult quit(ProcessTarget attached) throws Exception {
        if (!sameLifetime(attached)) return ActionResult.completed("process already stopped");
        try {
            Window window = window(attached.pid());
            command(List.of(xdotool, "windowclose", Long.toString(window.id())), "xdotool");
        } catch (UnavailableException unavailable) {
            // A vanished window is expected during crash cleanup.
        }
        long deadline = System.nanoTime() + QUIT_GRACE.toNanos();
        while (sameLifetime(attached) && System.nanoTime() < deadline) Thread.sleep(50L);
        if (sameLifetime(attached)) {
            ProcessHandle.of(attached.pid()).ifPresent(ProcessHandle::destroy);
            waitForExit(attached, Duration.ofSeconds(2));
        }
        if (sameLifetime(attached)) {
            ProcessHandle.of(attached.pid()).ifPresent(ProcessHandle::destroyForcibly);
            waitForExit(attached, Duration.ofSeconds(2));
        }
        if (sameLifetime(attached)) {
            throw new IOException("The exact recorded game process didn't stop");
        }
        return ActionResult.completed("stopped exact PID " + attached.pid());
    }

    private Window window(long pid) throws Exception {
        DesktopCommandExecutor.Result found = command(List.of(
                xdotool, "search", "--onlyvisible", "--pid", Long.toString(pid)), "xdotool");
        List<Window> candidates = new ArrayList<>();
        for (String line : found.output().lines().toList()) {
            if (line.isBlank()) continue;
            long id;
            try {
                id = Long.parseLong(line.trim());
            } catch (NumberFormatException invalid) {
                throw new UnavailableException("xdotool returned an invalid window ID: " + line);
            }
            verifyWindowOwner(id, pid);
            candidates.add(geometry(id));
        }
        return candidates.stream()
                .filter(candidate -> candidate.width() >= 100 && candidate.height() >= 100)
                .max(Comparator.comparingLong(
                        candidate -> (long) candidate.width() * candidate.height()))
                .orElseThrow(() -> new UnavailableException(
                        "The exact PID has no usable visible X11 window"));
    }

    private Window geometry(long id) throws Exception {
        String output = command(List.of(
                xdotool, "getwindowgeometry", "--shell", Long.toString(id)), "xdotool")
                .output();
        Map<String, Integer> values = new LinkedHashMap<>();
        for (String line : output.lines().toList()) {
            int equals = line.indexOf('=');
            if (equals <= 0) continue;
            try {
                values.put(line.substring(0, equals), Integer.parseInt(line.substring(equals + 1)));
            } catch (NumberFormatException ignored) {
                // A required invalid field is rejected below.
            }
        }
        try {
            return new Window(id, values.get("X"), values.get("Y"),
                    values.get("WIDTH"), values.get("HEIGHT"));
        } catch (NullPointerException missing) {
            throw new UnavailableException("xdotool returned incomplete window geometry: " + output);
        }
    }

    private void verifyWindowOwner(long window, long pid) throws Exception {
        String owner = command(List.of(
                xdotool, "getwindowpid", Long.toString(window)), "xdotool").output().trim();
        if (!Long.toString(pid).equals(owner)) {
            throw new UnavailableException(
                    "X11 window ownership changed before the desktop action");
        }
    }

    private Artifact logTail(Path runDirectory) throws Exception {
        Path metadata = runDirectory.resolve("run.json");
        if (!Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS)) {
            throw new UnavailableException("run.json is unavailable for bounded game-log capture");
        }
        Object install = StrictJson.object(Files.readString(metadata, StandardCharsets.UTF_8))
                .get("installRoot");
        if (!(install instanceof String value) || value.isBlank()) {
            throw new UnavailableException("run.json doesn't identify the Starsector installation");
        }
        Path source = Path.of(value).toAbsolutePath().normalize().resolve("logs/starsector.log");
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new UnavailableException("The current Starsector log is unavailable");
        }
        Path destination = runDirectory.resolve("desktop-smoke-log-tail.txt");
        copyTail(source, destination, LOG_TAIL_BYTES);
        return new Artifact("log-tail", destination);
    }

    private static Artifact snapshotArtifact(
            Path runDirectory, String sourceName, String destinationName, String kind)
            throws Exception {
        Path source = runDirectory.resolve(sourceName);
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new UnavailableException(sourceName + " isn't available for the live smoke run");
        }
        Path destination = runDirectory.resolve(destinationName);
        Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
        if (!Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)
                || Files.size(destination) == 0) {
            throw new IOException("Captured " + sourceName + " is empty");
        }
        return new Artifact(kind, destination);
    }

    private DesktopCommandExecutor.Result command(List<String> command, String label)
            throws Exception {
        DesktopCommandExecutor.Result result;
        try {
            result = commands.run(command, COMMAND_TIMEOUT);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        }
        if (result.exitCode() != 0) {
            throw new UnavailableException(label + " failed (exit " + result.exitCode()
                    + "): " + bounded(result.output()));
        }
        return result;
    }

    private ProcessTarget attached() {
        if (target == null) throw new IllegalStateException("The Linux driver isn't attached");
        return target;
    }

    private static void requireSameLifetime(ProcessTarget expected) throws UnavailableException {
        if (!sameLifetime(expected)) {
            throw new UnavailableException(
                    "The recorded PID is absent or belongs to another process lifetime");
        }
    }

    private static boolean sameLifetime(ProcessTarget expected) {
        return ProcessHandle.of(expected.pid())
                .filter(ProcessHandle::isAlive)
                .flatMap(process -> process.info().startInstant())
                .map(expected.startedAt()::equals)
                .orElse(false);
    }

    private static void waitForExit(ProcessTarget target, Duration duration)
            throws InterruptedException {
        long deadline = System.nanoTime() + duration.toNanos();
        while (sameLifetime(target) && System.nanoTime() < deadline) Thread.sleep(25L);
    }

    private static String keySymbol(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        String symbol = KEYS.get(normalized);
        if (symbol == null) {
            throw new IllegalArgumentException("Unsupported Linux smoke key: " + key);
        }
        return symbol;
    }

    private static Map<String, TargetPoint> targets() {
        Map<String, TargetPoint> values = new LinkedHashMap<>();
        values.put("main-menu.continue", new TargetPoint("main-menu.continue", 0.775, 0.300));
        return Map.copyOf(values);
    }

    private static void copyTail(Path source, Path destination, int maximumBytes)
            throws IOException {
        long size = Files.size(source);
        int count = (int) Math.min(size, maximumBytes);
        ByteBuffer buffer = ByteBuffer.allocate(count);
        try (SeekableByteChannel input = Files.newByteChannel(source, StandardOpenOption.READ)) {
            input.position(size - count);
            while (buffer.hasRemaining() && input.read(buffer) >= 0) {
                // Continue until the requested tail is complete or the file reaches EOF.
            }
        }
        Files.write(destination, buffer.array(), StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    private static String bounded(String value) {
        String text = value == null ? "" : value.strip();
        return text.length() <= 2_000 ? text : text.substring(0, 2_000);
    }

    record TargetPoint(String name, double x, double y) {
    }

    private record Window(long id, int x, int y, int width, int height) {
    }
}
