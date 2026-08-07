package dev.starsector.preflight.cli;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** macOS desktop smoke adapter that resolves the game window only through its recorded PID. */
final class MacDesktopSmokeDriver implements DesktopSmokeDriver {
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration QUIT_GRACE = Duration.ofSeconds(8);
    private static final int LOG_TAIL_BYTES = 1024 * 1024;
    private static final Map<String, TargetPoint> TARGETS = targets();
    private static final Map<String, Integer> KEY_CODES = Map.of(
            "a", 0,
            "s", 1,
            "d", 2,
            "w", 13,
            "return", 36,
            "space", 49,
            "escape", 53);

    private final CommandExecutor commands;
    private final Path osascript;
    private final Path screenCapture;
    private ProcessTarget target;

    MacDesktopSmokeDriver() {
        this(new SystemCommandExecutor(), Path.of("/usr/bin/osascript"),
                Path.of("/usr/sbin/screencapture"));
    }

    MacDesktopSmokeDriver(CommandExecutor commands, Path osascript, Path screenCapture) {
        this.commands = commands;
        this.osascript = osascript;
        this.screenCapture = screenCapture;
    }

    @Override
    public Descriptor descriptor() throws Exception {
        if (Platform.current() != Platform.MAC) {
            throw new UnavailableException("The PID-addressed macOS driver is unavailable on this platform");
        }
        if (!Files.isExecutable(osascript)) {
            throw new UnavailableException("osascript is unavailable at " + osascript);
        }
        CommandResult accessibility = command(List.of(
                osascript.toString(), "-e",
                "tell application \"System Events\" to return UI elements enabled"));
        if (!"true".equals(accessibility.output().trim().toLowerCase(Locale.ROOT))) {
            throw new UnavailableException(
                    "macOS Accessibility permission isn't enabled for this Preflight process");
        }
        Set<String> capabilities = Files.isExecutable(screenCapture)
                ? Set.of("process-control", "window-control", "screen-capture", "evidence-read")
                : Set.of("process-control", "window-control", "evidence-read");
        List<String> diagnostics = Files.isExecutable(screenCapture)
                ? List.of("Accessibility is enabled; Screen Recording is verified on first bounded capture")
                : List.of("screencapture is unavailable at " + screenCapture);
        return new Descriptor("macos-system-events-pid", "1", "mac", capabilities, diagnostics);
    }

    @Override
    public void attach(ProcessTarget target) throws Exception {
        if (target == null || target.pid() <= 0 || target.startedAt() == null) {
            throw new IllegalArgumentException("A PID and process start instant are required");
        }
        requireSameLifetime(target);
        WindowBounds bounds = windowBounds(target.pid());
        if (bounds.width() < 100 || bounds.height() < 100) {
            throw new UnavailableException("The recorded process has no usable game window");
        }
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
            case "capture" -> capture(attached, step, runDirectory);
            case "quit" -> quit(attached);
            default -> throw new IllegalArgumentException("Unsupported macOS smoke action: " + kind);
        };
    }

    @Override
    public Observation observe() throws Exception {
        ProcessTarget attached = attached();
        requireSameLifetime(attached);
        String output = command(script(observationScript(attached.pid()))).output().trim();
        return new Observation(output);
    }

    @Override
    public void shutdown() throws Exception {
        ProcessTarget attached = target;
        if (attached == null || !sameLifetime(attached)) return;
        quit(attached);
    }

    private ActionResult activate(ProcessTarget attached) throws Exception {
        String output = command(script(activateScript(attached.pid()))).output().trim();
        return ActionResult.completed(output);
    }

    private ActionResult click(ProcessTarget attached, String name) throws Exception {
        TargetPoint point = TARGETS.get(name);
        if (point == null) throw new IllegalArgumentException("Unsupported macOS smoke target: " + name);
        String output = command(script(clickScript(attached.pid(), point))).output().trim();
        return ActionResult.completed(output);
    }

    private ActionResult pressKey(ProcessTarget attached, String key) throws Exception {
        int code = keyCode(key);
        String output = command(script(keyCodeScript(attached.pid(), code))).output().trim();
        return ActionResult.completed(output);
    }

    private ActionResult holdKey(ProcessTarget attached, String key, int durationMillis) throws Exception {
        String normalized = normalizedKey(key);
        command(script(keyTransitionScript(attached.pid(), normalized, true)));
        boolean interrupted = false;
        try {
            Thread.sleep(durationMillis);
        } catch (InterruptedException error) {
            interrupted = true;
            throw error;
        } finally {
            if (interrupted) Thread.interrupted();
            try {
                releaseKey(attached.pid(), normalized);
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
        return ActionResult.completed("held " + normalized + " for " + durationMillis + " ms");
    }

    private void releaseKey(long pid, String key) throws Exception {
        // key-up remains safe if the game exits between key-down and release: it cannot create input.
        command(script(keyReleaseScript(pid, key)));
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
                        realRun, "runtime-frame-report.json", "desktop-smoke-frame-report.json", artifact));
                case "audio-window" -> throw new UnavailableException(
                        "The macOS adapter doesn't yet provide bounded audio capture");
                default -> throw new IllegalArgumentException("Unsupported capture artifact: " + artifact);
            }
        }
        return new ActionResult("captured " + artifacts.size() + " artifact(s)", List.copyOf(artifacts));
    }

    private Artifact screenshot(ProcessTarget attached, Path runDirectory) throws Exception {
        WindowBounds bounds = windowBounds(attached.pid());
        Path destination = runDirectory.resolve("desktop-smoke.png");
        CommandResult result = command(List.of(
                screenCapture.toString(), "-x", "-R" + bounds.region(), destination.toString()));
        if (!Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)
                || Files.size(destination) == 0) {
            throw new UnavailableException(
                    "macOS Screen Recording didn't produce a bounded game-window capture: "
                            + result.output().trim());
        }
        return new Artifact("screenshot", destination);
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
            Path runDirectory, String sourceName, String destinationName, String kind) throws Exception {
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

    private ActionResult quit(ProcessTarget attached) throws Exception {
        if (!sameLifetime(attached)) return ActionResult.completed("process already stopped");
        try {
            command(script(quitScript(attached.pid())));
        } catch (UnavailableException unavailable) {
            // A vanished window is expected during some crash paths; exact-PID shutdown remains safe.
        }
        long deadline = System.nanoTime() + QUIT_GRACE.toNanos();
        while (sameLifetime(attached) && System.nanoTime() < deadline) {
            Thread.sleep(50L);
        }
        if (sameLifetime(attached)) {
            ProcessHandle process = ProcessHandle.of(attached.pid()).orElse(null);
            if (process != null && sameLifetime(attached)) process.destroy();
            waitForExit(attached, Duration.ofSeconds(2));
        }
        if (sameLifetime(attached)) {
            ProcessHandle process = ProcessHandle.of(attached.pid()).orElse(null);
            if (process != null && sameLifetime(attached)) process.destroyForcibly();
            waitForExit(attached, Duration.ofSeconds(2));
        }
        if (sameLifetime(attached)) {
            throw new IOException("The exact recorded game process didn't stop");
        }
        return ActionResult.completed("stopped exact PID " + attached.pid());
    }

    private static void waitForExit(ProcessTarget target, Duration duration)
            throws InterruptedException {
        long deadline = System.nanoTime() + duration.toNanos();
        while (sameLifetime(target) && System.nanoTime() < deadline) Thread.sleep(25L);
    }

    private WindowBounds windowBounds(long pid) throws Exception {
        String output = command(script(windowBoundsScript(pid))).output().trim();
        String[] parts = output.split("\\s*,\\s*");
        if (parts.length != 4) throw new IOException("Unexpected macOS window bounds: " + output);
        try {
            WindowBounds bounds = new WindowBounds(
                    Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
            if (bounds.width() <= 0 || bounds.height() <= 0) {
                throw new IOException("macOS returned empty window bounds: " + output);
            }
            return bounds;
        } catch (NumberFormatException error) {
            throw new IOException("Unexpected macOS window bounds: " + output, error);
        }
    }

    private ProcessTarget attached() {
        if (target == null) throw new IllegalStateException("The macOS driver isn't attached");
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

    private int keyCode(String key) {
        Integer code = KEY_CODES.get(normalizedKey(key));
        if (code == null) throw new IllegalArgumentException("Unsupported macOS smoke key: " + key);
        return code;
    }

    private static String normalizedKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        if (!KEY_CODES.containsKey(normalized)) {
            throw new IllegalArgumentException("Unsupported macOS smoke key: " + key);
        }
        return normalized;
    }

    private CommandResult command(List<String> command) throws Exception {
        CommandResult result;
        try {
            result = commands.run(command, COMMAND_TIMEOUT);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        }
        if (result.exitCode() != 0) {
            throw new UnavailableException("macOS desktop command failed (exit "
                    + result.exitCode() + "): " + bounded(result.output()));
        }
        return result;
    }

    private List<String> script(String source) {
        return List.of(osascript.toString(), "-e", source);
    }

    static String windowBoundsScript(long pid) {
        return processHeader(pid) + "\n"
                + "set win to window 1 of targetProcess\n"
                + "set winPosition to position of win\n"
                + "set winSize to size of win\n"
                + "return (item 1 of winPosition as text) & \",\" & (item 2 of winPosition as text)"
                + " & \",\" & (item 1 of winSize as text) & \",\" & (item 2 of winSize as text)\n"
                + "end tell";
    }

    static String activateScript(long pid) {
        return processHeader(pid) + "\n"
                + "set frontmost of targetProcess to true\n"
                + "return \"activated PID " + pid + "\"\n"
                + "end tell";
    }

    static String observationScript(long pid) {
        return processHeader(pid) + "\n"
                + "set win to window 1 of targetProcess\n"
                + "set winPosition to position of win\n"
                + "set winSize to size of win\n"
                + "set focused to frontmost of targetProcess\n"
                + "return \"PID " + pid + " window \" & (item 1 of winPosition as text) & \",\""
                + " & (item 2 of winPosition as text) & \",\" & (item 1 of winSize as text)"
                + " & \",\" & (item 2 of winSize as text) & \" frontmost=\" & (focused as text)\n"
                + "end tell";
    }

    static String clickScript(long pid, TargetPoint point) {
        return processHeader(pid) + "\n"
                + "set frontmost of targetProcess to true\n"
                + "set win to window 1 of targetProcess\n"
                + "set winPosition to position of win\n"
                + "set winSize to size of win\n"
                + "set clickX to (item 1 of winPosition) + (round ((item 1 of winSize) * "
                + point.x() + "))\n"
                + "set clickY to (item 2 of winPosition) + (round ((item 2 of winSize) * "
                + point.y() + "))\n"
                + "click at {clickX, clickY}\n"
                + "return \"clicked " + point.name() + " at \" & clickX & \",\" & clickY\n"
                + "end tell";
    }

    static String keyCodeScript(long pid, int code) {
        return processHeader(pid) + "\n"
                + "set frontmost of targetProcess to true\n"
                + "key code " + code + "\n"
                + "return \"pressed key code " + code + "\"\n"
                + "end tell";
    }

    static String keyTransitionScript(long pid, String key, boolean down) {
        String action = down ? "down" : "up";
        return processHeader(pid) + "\n"
                + "set frontmost of targetProcess to true\n"
                + "key " + action + " \"" + key + "\"\n"
                + "return \"key " + action + "\"\n"
                + "end tell";
    }

    static String keyReleaseScript(long pid, String key) {
        return "tell application \"System Events\"\n"
                + "set matches to (every application process whose unix id is " + pid + ")\n"
                + "key up \"" + key + "\"\n"
                + "return \"key up\"\n"
                + "end tell";
    }

    static String quitScript(long pid) {
        return processHeader(pid) + "\n"
                + "set frontmost of targetProcess to true\n"
                + "keystroke \"q\" using {command down}\n"
                + "return \"requested quit for PID " + pid + "\"\n"
                + "end tell";
    }

    private static String processHeader(long pid) {
        if (pid <= 0) throw new IllegalArgumentException("PID must be positive");
        return "tell application \"System Events\"\n"
                + "set matches to (every application process whose unix id is " + pid + ")\n"
                + "if (count of matches) is not 1 then error \"exact PID unavailable\" number 1728\n"
                + "set targetProcess to item 1 of matches";
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

    private record WindowBounds(int x, int y, int width, int height) {
        private String region() {
            return x + "," + y + "," + width + "," + height;
        }
    }

    interface CommandExecutor {
        CommandResult run(List<String> command, Duration timeout) throws Exception;
    }

    record CommandResult(int exitCode, String output) {
    }

    static final class SystemCommandExecutor implements CommandExecutor {
        private static final int OUTPUT_LIMIT = 64 * 1024;

        @Override
        public CommandResult run(List<String> command, Duration timeout) throws Exception {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            BoundedOutput output = new BoundedOutput(process.getInputStream(), OUTPUT_LIMIT);
            Thread reader = new Thread(output, "Preflight-Mac-Desktop-Command-Output");
            reader.setDaemon(true);
            reader.start();
            try {
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(2, TimeUnit.SECONDS);
                    throw new IOException(
                            "macOS desktop command exceeded " + timeout.toSeconds() + " seconds");
                }
                reader.join(2_000L);
                return new CommandResult(process.exitValue(), output.text());
            } catch (InterruptedException interrupted) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                Thread.currentThread().interrupt();
                throw interrupted;
            }
        }
    }

    private static final class BoundedOutput implements Runnable {
        private final InputStream input;
        private final int limit;
        private final ByteArrayOutputStream retained;
        private IOException problem;
        private boolean truncated;

        private BoundedOutput(InputStream input, int limit) {
            this.input = input;
            this.limit = limit;
            this.retained = new ByteArrayOutputStream(limit);
        }

        @Override
        public void run() {
            byte[] bytes = new byte[4_096];
            try (input) {
                int count;
                while ((count = input.read(bytes)) >= 0) {
                    int accepted = Math.min(count, limit - retained.size());
                    if (accepted > 0) retained.write(bytes, 0, accepted);
                    if (accepted < count) truncated = true;
                }
            } catch (IOException error) {
                problem = error;
            }
        }

        private String text() throws IOException {
            if (problem != null) throw problem;
            String value = retained.toString(StandardCharsets.UTF_8);
            return truncated ? value + "\n[output truncated]" : value;
        }
    }
}
