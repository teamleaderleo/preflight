package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
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

/** macOS desktop smoke adapter that resolves the game window only through its recorded PID. */
final class MacDesktopSmokeDriver implements DesktopSmokeDriver {
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(10);
    // A large installed profile can spend well over eight seconds loading settings before LWJGL
    // publishes its first macOS window. The runner supplies the tighter per-step/scenario bound;
    // this inner retry window only prevents a premature "no window" result during healthy startup.
    private static final Duration WINDOW_READINESS_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration WINDOW_READINESS_POLL = Duration.ofMillis(100);
    private static final Duration QUIT_GRACE = Duration.ofSeconds(8);
    private static final int LOG_TAIL_BYTES = 1024 * 1024;
    private static final int BRIDGE_RESPONSE_BYTES = 8 * 1024;
    private static final String BRIDGE_ENDPOINT_ENV = "PREFLIGHT_MAC_AUTOMATION_ENDPOINT";
    private static final String BRIDGE_TOKEN_ENV = "PREFLIGHT_MAC_AUTOMATION_TOKEN";
    private static final Map<String, TargetPoint> TARGETS = targets();
    private static final Map<String, Integer> KEY_CODES = Map.ofEntries(
            Map.entry("a", 0),
            Map.entry("s", 1),
            Map.entry("d", 2),
            Map.entry("f", 3),
            Map.entry("w", 13),
            Map.entry("r", 15),
            Map.entry("u", 32),
            Map.entry("n", 45),
            Map.entry("return", 36),
            Map.entry("tab", 48),
            Map.entry("space", 49),
            Map.entry("escape", 53),
            Map.entry("capslock", 57));

    private final DesktopCommandExecutor commands;
    private final Path osascript;
    private final Path screenCapture;
    private final String bridgeEndpoint;
    private final String bridgeToken;
    private ProcessTarget target;

    MacDesktopSmokeDriver() {
        this(new SystemDesktopCommandExecutor(), Path.of("/usr/bin/osascript"),
                Path.of("/usr/sbin/screencapture"),
                System.getenv(BRIDGE_ENDPOINT_ENV), System.getenv(BRIDGE_TOKEN_ENV));
    }

    MacDesktopSmokeDriver(DesktopCommandExecutor commands, Path osascript, Path screenCapture) {
        this(commands, osascript, screenCapture, null, null);
    }

    MacDesktopSmokeDriver(
            DesktopCommandExecutor commands,
            Path osascript,
            Path screenCapture,
            String bridgeEndpoint,
            String bridgeToken) {
        this.commands = commands;
        this.osascript = osascript;
        this.screenCapture = screenCapture;
        this.bridgeEndpoint = validatedBridgeEndpoint(bridgeEndpoint);
        this.bridgeToken = validatedBridgeToken(bridgeToken);
        if ((this.bridgeEndpoint == null) != (this.bridgeToken == null)) {
            throw new IllegalArgumentException(
                    "The macOS native automation bridge needs both endpoint and token");
        }
    }

    @Override
    public Descriptor descriptor() throws Exception {
        if (Platform.current() != Platform.MAC) {
            throw new UnavailableException("The PID-addressed macOS driver is unavailable on this platform");
        }
        if (!Files.isExecutable(osascript)) {
            throw new UnavailableException("osascript is unavailable at " + osascript);
        }
        String accessibility = automation(
                "probe", 0, null,
                "tell application \"System Events\" to return UI elements enabled");
        if (!"true".equals(accessibility.trim().toLowerCase(Locale.ROOT))) {
            throw new UnavailableException(
                    "macOS Accessibility permission isn't enabled for the automation executable: "
                            + automationExecutable());
        }
        Set<String> capabilities = Files.isExecutable(screenCapture)
                ? Set.of("process-control", "window-control", "screen-capture", "evidence-read")
                : Set.of("process-control", "window-control", "evidence-read");
        List<String> diagnostics = Files.isExecutable(screenCapture)
                ? List.of("Accessibility is enabled; Screen Recording is verified on first bounded capture")
                : List.of("screencapture is unavailable at " + screenCapture);
        return new Descriptor(
                nativeBridge() ? "macos-preflight-native-pid" : "macos-system-events-pid",
                nativeBridge() ? "2" : "1", "mac", capabilities, diagnostics);
    }

    private String automationExecutable() {
        if (nativeBridge()) return "the Preflight application";
        return ProcessHandle.current().info().command()
                .map(command -> Path.of(command).toAbsolutePath().normalize().toString())
                .orElse("the bundled Preflight Java runtime");
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
            default -> throw new IllegalArgumentException("Unsupported macOS smoke action: " + kind);
        };
    }

    @Override
    public Observation observe() throws Exception {
        ProcessTarget attached = attached();
        requireSameLifetime(attached);
        String output = automation(
                "observe", attached.pid(), null, observationScript(attached.pid())).trim();
        return new Observation(output);
    }

    @Override
    public void shutdown() throws Exception {
        ProcessTarget attached = target;
        if (attached == null || !sameLifetime(attached)) return;
        quit(attached);
    }

    private ActionResult activate(ProcessTarget attached) throws Exception {
        long deadline = System.nanoTime() + WINDOW_READINESS_TIMEOUT.toNanos();
        UnavailableException lastUnavailable = null;
        do {
            requireSameLifetime(attached);
            try {
                String output = automation(
                        "activate", attached.pid(), null, activateScript(attached.pid())).trim();
                return ActionResult.completed(output);
            } catch (UnavailableException unavailable) {
                if (!windowReadinessUnavailable(unavailable)) throw unavailable;
                lastUnavailable = unavailable;
            }
            try {
                Thread.sleep(WINDOW_READINESS_POLL.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            }
        } while (System.nanoTime() < deadline);
        throw new UnavailableException(
                "The exact game process did not publish a macOS window within "
                        + WINDOW_READINESS_TIMEOUT.toSeconds() + " seconds: "
                        + lastUnavailable.getMessage());
    }

    private static boolean windowReadinessUnavailable(UnavailableException unavailable) {
        String message = unavailable.getMessage();
        return message != null && (message.contains("exact PID unavailable")
                || message.contains("Invalid index. (-1719)"));
    }

    private ActionResult click(ProcessTarget attached, String name) throws Exception {
        TargetPoint point = TARGETS.get(name);
        if (point == null) throw new IllegalArgumentException("Unsupported macOS smoke target: " + name);
        String output = automation(
                "click", attached.pid(), name, clickScript(attached.pid(), point)).trim();
        return ActionResult.completed(output);
    }

    private ActionResult pressKey(ProcessTarget attached, String key) throws Exception {
        int code = keyCode(key);
        String normalized = normalizedKey(key);
        String output = automation(
                "press-key", attached.pid(), normalized,
                keyCodeScript(attached.pid(), code)).trim();
        return ActionResult.completed(output);
    }

    private ActionResult holdKey(ProcessTarget attached, String key, int durationMillis) throws Exception {
        String normalized = normalizedKey(key);
        automation(
                "key-down", attached.pid(), normalized,
                keyTransitionScript(attached.pid(), normalized, true));
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

    private ActionResult scrollWheel(ProcessTarget attached, String direction, int clicks)
            throws Exception {
        String normalized = scrollDirection(direction);
        if (clicks < 1 || clicks > 24) {
            throw new IllegalArgumentException("macOS scroll clicks must be in 1..24");
        }
        automation("activate", attached.pid(), null, activateScript(attached.pid()));
        WindowBounds bounds = windowBounds(attached.pid());
        requireSameLifetime(attached);
        String source = scrollWheelScript(attached.pid(), bounds, normalized, clicks);
        if (nativeBridge()) {
            automation("scroll-wheel", attached.pid(), normalized + ":" + clicks, source);
        } else {
            command(List.of(osascript.toString(), "-l", "JavaScript", "-e", source));
        }
        requireSameLifetime(attached);
        return ActionResult.completed(
                "scrolled " + normalized + " " + clicks + " clicks in exact PID " + attached.pid());
    }

    private void releaseKey(long pid, String key) throws Exception {
        // key-up remains safe if the game exits between key-down and release: it cannot create input.
        automation("release-key", pid, key, keyReleaseScript(pid, key));
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
        if (nativeBridge()) {
            automation("capture", attached.pid(), null, windowBoundsScript(attached.pid()));
            Path destination = runDirectory.resolve("desktop-smoke.png");
            if (!Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(destination) == 0) {
                throw new UnavailableException(
                        "The native macOS bridge didn't produce a bounded game-window capture");
            }
            return new Artifact("screenshot", destination);
        }
        WindowBounds bounds = windowBounds(attached.pid());
        Path destination = runDirectory.resolve("desktop-smoke.png");
        DesktopCommandExecutor.Result result = command(List.of(
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
            automation("quit", attached.pid(), null, quitScript(attached.pid()));
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
        String output = automation("window-bounds", pid, null, windowBoundsScript(pid)).trim();
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

    private DesktopCommandExecutor.Result command(List<String> command) throws Exception {
        DesktopCommandExecutor.Result result;
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

    static String scrollWheelScript(
            long pid, WindowBounds bounds, String direction, int clicks) {
        if (pid <= 0) throw new IllegalArgumentException("PID must be positive");
        // CoreGraphics synthetic wheel events follow the configured macOS scroll direction;
        // Starsector's LWJGL input sees positive as the player-equivalent zoom-out gesture here.
        int delta = "out".equals(scrollDirection(direction)) ? 1 : -1;
        if (clicks < 1 || clicks > 24) {
            throw new IllegalArgumentException("macOS scroll clicks must be in 1..24");
        }
        int x = bounds.x() + bounds.width() / 2;
        int y = bounds.y() + bounds.height() / 2;
        return "ObjC.import('CoreGraphics');"
                + "var se=Application('System Events');"
                + "var a=se.applicationProcesses.whose({unixId:" + pid + "})();"
                + "if(a.length!==1||!a[0].frontmost())throw new Error('exact PID is not frontmost');"
                + "var p=$.CGPointMake(" + x + "," + y + ");"
                + "var m=$.CGEventCreateMouseEvent(null,$.kCGEventMouseMoved,p,$.kCGMouseButtonLeft);"
                + "$.CGEventPost($.kCGHIDEventTap,m);"
                + "for(var i=0;i<" + clicks + ";i++){"
                + "var e=$.CGEventCreateScrollWheelEvent(null,$.kCGScrollEventUnitLine,1,"
                + delta + ");$.CGEventPost($.kCGHIDEventTap,e);delay(0.025);}";
    }

    private static String scrollDirection(String direction) {
        String normalized = direction == null ? "" : direction.toLowerCase(Locale.ROOT);
        if (!Set.of("in", "out").contains(normalized)) {
            throw new IllegalArgumentException("Unsupported macOS scroll direction: " + direction);
        }
        return normalized;
    }

    private boolean nativeBridge() {
        return bridgeEndpoint != null;
    }

    private String automation(
            String operation, long pid, String argument, String fallbackScript) throws Exception {
        if (!nativeBridge()) return command(script(fallbackScript)).output();
        Map<String, Object> request = bridgePayload(bridgeToken, operation, pid, argument);
        InetSocketAddress endpoint = bridgeAddress(bridgeEndpoint);
        try (Socket socket = new Socket()) {
            socket.connect(endpoint, (int) COMMAND_TIMEOUT.toMillis());
            socket.setSoTimeout((int) COMMAND_TIMEOUT.toMillis());
            socket.getOutputStream().write(
                    (Json.object(request) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            socket.shutdownOutput();
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            while (response.size() <= BRIDGE_RESPONSE_BYTES) {
                int read = socket.getInputStream().read(buffer);
                if (read < 0) break;
                response.write(buffer, 0, read);
            }
            if (response.size() > BRIDGE_RESPONSE_BYTES) {
                throw new UnavailableException("The native macOS automation response is too large");
            }
            Map<String, Object> value = StrictJson.object(response.toString(StandardCharsets.UTF_8));
            if (!value.keySet().equals(Set.of("protocol", "ok", "output", "error"))
                    || !Long.valueOf(1).equals(value.get("protocol"))
                    || !(value.get("ok") instanceof Boolean ok)) {
                throw new UnavailableException("The native macOS automation response is invalid");
            }
            if (!ok) {
                Object error = value.get("error");
                throw new UnavailableException(error instanceof String detail
                        ? bounded(detail) : "The native macOS automation request failed");
            }
            Object output = value.get("output");
            if (!(output instanceof String text) || value.get("error") != null) {
                throw new UnavailableException("The native macOS automation response is inconsistent");
            }
            return text;
        } catch (UnavailableException unavailable) {
            throw unavailable;
        } catch (IOException | IllegalArgumentException error) {
            throw new UnavailableException(
                    "Could not use the native macOS automation bridge: " + bounded(error.getMessage()));
        }
    }

    static Map<String, Object> bridgePayload(
            String token, String operation, long pid, String argument) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("protocol", 1);
        request.put("token", token);
        request.put("operation", operation);
        request.put("pid", pid > 0 ? pid : null);
        request.put("argument", argument);
        return java.util.Collections.unmodifiableMap(request);
    }

    private static InetSocketAddress bridgeAddress(String endpoint) {
        int separator = endpoint == null ? -1 : endpoint.lastIndexOf(':');
        if (separator <= 0 || !"127.0.0.1".equals(endpoint.substring(0, separator))) {
            throw new IllegalArgumentException("The native macOS automation endpoint isn't loopback");
        }
        int port = Integer.parseInt(endpoint.substring(separator + 1));
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("The native macOS automation port is invalid");
        }
        return new InetSocketAddress("127.0.0.1", port);
    }

    private static String validatedBridgeEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) return null;
        bridgeAddress(endpoint);
        return endpoint;
    }

    private static String validatedBridgeToken(String token) {
        if (token == null || token.isBlank()) return null;
        if (token.length() != 64 || !token.chars().allMatch(
                character -> Character.isDigit(character)
                        || character >= 'a' && character <= 'f')) {
            throw new IllegalArgumentException("The native macOS automation token is invalid");
        }
        return token;
    }

    static void removeBridgeCredentials(Map<String, String> environment) {
        environment.remove(BRIDGE_ENDPOINT_ENV);
        environment.remove(BRIDGE_TOKEN_ENV);
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
                + "set win to window 1 of targetProcess\n"
                + "set frontmost of targetProcess to true\n"
                + "return \"activated PID " + pid + "\"\n"
                + "end tell";
    }

    static String observationScript(long pid) {
        return processHeader(pid) + "\n"
                + "set win to window 1 of targetProcess\n"
                + "set winPosition to position of win\n"
                + "set winSize to size of win\n"
                + "set isFrontmost to frontmost of targetProcess\n"
                + "return \"PID " + pid + " window \" & (item 1 of winPosition as text) & \",\""
                + " & (item 2 of winPosition as text) & \",\" & (item 1 of winSize as text)"
                + " & \",\" & (item 2 of winSize as text) & \" frontmost=\" & (isFrontmost as text)\n"
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

    static record WindowBounds(int x, int y, int width, int height) {
        private String region() {
            return x + "," + y + "," + width + "," + height;
        }
    }

}
