package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MacDesktopSmokeDriverTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void everyInputScriptUsesOnlyTheExactNumericPid() {
        long pid = 4_242L;
        List<String> scripts = List.of(
                MacDesktopSmokeDriver.windowBoundsScript(pid),
                MacDesktopSmokeDriver.activateScript(pid),
                MacDesktopSmokeDriver.observationScript(pid),
                MacDesktopSmokeDriver.clickScript(
                        pid, new MacDesktopSmokeDriver.TargetPoint(
                                "main-menu.continue", 0.775, 0.300)),
                MacDesktopSmokeDriver.keyCodeScript(pid, 13),
                MacDesktopSmokeDriver.keyTransitionScript(pid, "w", true),
                MacDesktopSmokeDriver.keyReleaseScript(pid, "w"),
                MacDesktopSmokeDriver.quitScript(pid));

        for (String script : scripts) {
            assertTrue(script.contains("application process whose unix id is " + pid), script);
            assertTrue(script.contains("tell application \"System Events\""), script);
            assertFalse(script.contains("set focused to "), script);
            assertFalse(script.toLowerCase(Locale.ROOT).contains("starsector"), script);
            assertFalse(script.contains("open -a"), script);
            assertFalse(script.contains("tell application \"Starsector\""), script);
        }
        assertTrue(MacDesktopSmokeDriver.observationScript(pid)
                .contains("set isFrontmost to frontmost of targetProcess"));
        assertFalse(MacDesktopSmokeDriver.activateScript(pid)
                .contains("window 1 of targetProcess"));
        assertFalse(MacDesktopSmokeDriver.observationScript(pid)
                .contains("window 1 of targetProcess"));
        assertTrue(MacDesktopSmokeDriver.coreGraphicsWindowBoundsScript(pid)
                .contains("kCGWindowOwnerPID)!==" + pid));
        assertTrue(MacDesktopSmokeDriver.appKitActivateScript(pid)
                .contains("runningApplicationWithProcessIdentifier(" + pid));
        List<String> compatibility = MacDesktopSmokeDriver.legacyPidActivationCommand(pid);
        assertEquals("/usr/bin/python3", compatibility.get(0));
        assertEquals(Long.toString(pid), compatibility.get(3));
        assertTrue(compatibility.get(2).contains("GetProcessForPID"));
        assertTrue(compatibility.get(2).contains("SetFrontProcessWithOptions"));
        assertFalse(compatibility.get(2).toLowerCase(Locale.ROOT).contains("starsector"));
        assertTrue(MacDesktopSmokeDriver.coreGraphicsKeyCodeScript(pid, 13)
                .contains("CGEventPostToPid(" + pid));
    }

    @Test
    void generatedScriptsCompileWithoutResolvingOrLaunchingAnApplicationByName() throws Exception {
        assumeTrue(Platform.current() == Platform.MAC);
        long pid = 4_242L;
        List<String> scripts = List.of(
                MacDesktopSmokeDriver.windowBoundsScript(pid),
                MacDesktopSmokeDriver.activateScript(pid),
                MacDesktopSmokeDriver.observationScript(pid),
                MacDesktopSmokeDriver.clickScript(
                        pid, new MacDesktopSmokeDriver.TargetPoint(
                                "main-menu.continue", 0.775, 0.300)),
                MacDesktopSmokeDriver.keyCodeScript(pid, 13),
                MacDesktopSmokeDriver.keyTransitionScript(pid, "w", true),
                MacDesktopSmokeDriver.keyReleaseScript(pid, "w"),
                MacDesktopSmokeDriver.quitScript(pid));
        for (int index = 0; index < scripts.size(); index++) {
            Path compiled = temporaryDirectory.resolve("driver-" + index + ".scpt");
            Process process = new ProcessBuilder(
                    "/usr/bin/osacompile", "-e", scripts.get(index), "-o", compiled.toString())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(
                    process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), output);
            assertEquals(0, process.exitValue(), output + "\n" + scripts.get(index));
            assertTrue(Files.isRegularFile(compiled));
        }
    }

    @Test
    void invalidPidsAndUnreviewedKeysNeverReachTheCommandBoundary() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> MacDesktopSmokeDriver.windowBoundsScript(0));
        assertThrows(IllegalArgumentException.class,
                () -> MacDesktopSmokeDriver.coreGraphicsWindowBoundsScript(0));
        assertThrows(IllegalArgumentException.class,
                () -> MacDesktopSmokeDriver.appKitActivateScript(0));
        assertThrows(IllegalArgumentException.class,
                () -> MacDesktopSmokeDriver.activateExactPid(0));
        assertThrows(IllegalArgumentException.class,
                () -> MacDesktopSmokeDriver.activateExactPid((long) Integer.MAX_VALUE + 1L));
        assertThrows(IllegalArgumentException.class,
                () -> MacDesktopSmokeDriver.legacyPidActivationCommand(0));
        assertThrows(IllegalArgumentException.class,
                () -> MacDesktopSmokeDriver.legacyPidActivationCommand(
                        (long) Integer.MAX_VALUE + 1L));
        assertThrows(IllegalArgumentException.class,
                () -> MacDesktopSmokeDriver.coreGraphicsKeyCodeScript(1, 128));

        assumeTrue(Platform.current() == Platform.MAC);
        FakeCommands commands = new FakeCommands();
        MacDesktopSmokeDriver driver = driver(commands);
        driver.descriptor();
        driver.attach(currentTarget());

        Map<String, Object> step = new LinkedHashMap<>();
        step.put("kind", "press-key");
        step.put("key", "command-q");
        assertThrows(IllegalArgumentException.class,
                () -> driver.execute(step, temporaryDirectory));
    }

    @Test
    void simulationNavigationAndAutopilotUseTheReviewedMacKeyCodes() throws Exception {
        assumeTrue(Platform.current() == Platform.MAC);
        FakeCommands commands = new FakeCommands();
        MacDesktopSmokeDriver driver = driver(commands);
        driver.descriptor();
        driver.attach(currentTarget());

        for (String key : List.of("f", "r", "n", "u", "tab", "capslock")) {
            driver.execute(Map.of("kind", "press-key", "key", key), temporaryDirectory);
        }

        String scripts = String.join("\n", commands.scripts());
        for (int code : List.of(3, 15, 45, 32, 48, 57)) {
            assertTrue(scripts.contains("CGEventCreateKeyboardEvent(null," + code), scripts);
        }
    }

    @Test
    void wheelInputMovesInsideAndRechecksTheExactGamePid() throws Exception {
        assumeTrue(Platform.current() == Platform.MAC);
        FakeCommands commands = new FakeCommands();
        MacDesktopSmokeDriver driver = driver(commands);
        driver.descriptor();
        DesktopSmokeDriver.ProcessTarget target = currentTarget();
        driver.attach(target);

        DesktopSmokeDriver.ActionResult result = driver.execute(Map.of(
                "kind", "scroll-wheel", "direction", "out", "clicks", 12),
                temporaryDirectory);

        String scripts = String.join("\n", commands.scripts());
        assertTrue(scripts.contains("CGEventPostToPid(" + target.pid()), scripts);
        assertTrue(scripts.contains("CGEventCreateScrollWheelEvent"), scripts);
        assertTrue(scripts.contains(",1,1)"), scripts);
        assertTrue(result.detail().contains("scrolled out 12 clicks"), result.detail());
    }

    @Test
    void nativeBridgeContractIsLoopbackAuthorizedAndClosed() {
        String token = "a".repeat(64);
        Map<String, Object> payload = MacDesktopSmokeDriver.bridgePayload(
                token, "click", 4_242L, "main-menu.continue");

        assertEquals(
                Set.of("protocol", "token", "operation", "pid", "argument"),
                payload.keySet());
        assertEquals(1, payload.get("protocol"));
        assertEquals(token, payload.get("token"));
        assertEquals(4_242L, payload.get("pid"));
        assertThrows(UnsupportedOperationException.class,
                () -> payload.put("operation", "arbitrary-script"));
        assertThrows(IllegalArgumentException.class, () -> new MacDesktopSmokeDriver(
                new FakeCommands(), Path.of("/usr/bin/osascript"),
                Path.of("/usr/sbin/screencapture"), "0.0.0.0:1234", token));
        assertThrows(IllegalArgumentException.class, () -> new MacDesktopSmokeDriver(
                new FakeCommands(), Path.of("/usr/bin/osascript"),
                Path.of("/usr/sbin/screencapture"), "127.0.0.1:1234", "short"));
    }

    @Test
    void nativeBridgeCredentialsNeverReachTheGameChild() {
        Map<String, String> environment = new java.util.HashMap<>();
        environment.put("PREFLIGHT_MAC_AUTOMATION_ENDPOINT", "127.0.0.1:4242");
        environment.put("PREFLIGHT_MAC_AUTOMATION_TOKEN", "secret");
        environment.put("PREFLIGHT_RUN_DIR", "/kept");

        MacDesktopSmokeDriver.removeBridgeCredentials(environment);

        assertEquals(Map.of("PREFLIGHT_RUN_DIR", "/kept"), environment);
    }

    @Test
    void attachmentClickObservationAndHoldRemainPidAddressed() throws Exception {
        assumeTrue(Platform.current() == Platform.MAC);
        FakeCommands commands = new FakeCommands();
        MacDesktopSmokeDriver driver = driver(commands);
        DesktopSmokeDriver.Descriptor descriptor = driver.descriptor();
        assertEquals("macos-system-events-pid", descriptor.id());
        assertTrue(descriptor.capabilities().contains("screen-capture"));

        long pid = ProcessHandle.current().pid();
        driver.attach(currentTarget());
        driver.execute(Map.of(
                "kind", "click",
                "target", "main-menu.continue"), temporaryDirectory);
        driver.execute(Map.of(
                "kind", "hold-key",
                "key", "w",
                "durationMillis", 50), temporaryDirectory);
        DesktopSmokeDriver.Observation observation = driver.observe();

        assertTrue(observation.detail().contains("frontmost=true"));
        assertTrue(commands.scripts().stream().allMatch(
                script -> script.contains("unix id is " + pid)
                        || script.contains("UI elements enabled")));
        assertTrue(commands.scripts().stream().anyMatch(script -> script.contains("* 0.775")));
        assertTrue(commands.scripts().stream().anyMatch(script -> script.contains("key down \"w\"")));
        assertTrue(commands.scripts().stream().anyMatch(script -> script.contains("key up \"w\"")));
    }

    @Test
    void activationWaitsForTheExactPidToPublishItsWindow() throws Exception {
        assumeTrue(Platform.current() == Platform.MAC);
        FakeCommands commands = new FakeCommands();
        commands.activationUnavailableCount = 2;
        commands.activationWindowUnavailableCount = 1;
        MacDesktopSmokeDriver driver = driver(commands);
        driver.descriptor();
        driver.attach(currentTarget());

        DesktopSmokeDriver.ActionResult result = driver.execute(
                Map.of("kind", "activate-window"), temporaryDirectory);

        assertTrue(result.detail().startsWith("ok; foregrounded test PID "), result.detail());
        assertTrue(result.detail().endsWith("frontmost=true"), result.detail());
        assertEquals(3, commands.scripts().stream()
                .filter(script -> script.contains("set frontmost of targetProcess to true"))
                .count());
    }

    @Test
    void verifiedExactProcessFocusDoesNotDependOnCarbonRegistration() throws Exception {
        assumeTrue(Platform.current() == Platform.MAC);
        FakeCommands commands = new FakeCommands();
        MacDesktopSmokeDriver driver = new MacDesktopSmokeDriver(
                commands, Path.of("/usr/bin/osascript"), Path.of("/usr/sbin/screencapture"),
                null, null, pid -> {
                    throw new DesktopSmokeDriver.UnavailableException(
                            "exact PID unavailable to ApplicationServices (status -600)");
                });
        driver.descriptor();
        driver.attach(currentTarget());

        DesktopSmokeDriver.ActionResult result = driver.execute(
                Map.of("kind", "activate-window"), temporaryDirectory);

        assertTrue(result.detail().contains("ApplicationServices fallback unavailable"),
                result.detail());
        assertTrue(result.detail().endsWith("frontmost=true"), result.detail());
    }

    @Test
    void rejectedDirectFocusUsesTheBoundedCompatibilityCallOnce() throws Exception {
        assumeTrue(Platform.current() == Platform.MAC);
        FakeCommands commands = new FakeCommands();
        commands.nonfrontmostObservationCount = 1;
        MacDesktopSmokeDriver driver = new MacDesktopSmokeDriver(
                commands, Path.of("/usr/bin/osascript"), Path.of("/usr/sbin/screencapture"),
                null, null, pid -> "direct focus returned success", true);
        driver.descriptor();
        driver.attach(currentTarget());

        DesktopSmokeDriver.ActionResult result = driver.execute(
                Map.of("kind", "activate-window"), temporaryDirectory);

        assertTrue(result.detail().contains("compatibility helper after frontmost verification"),
                result.detail());
        assertEquals(1, commands.commands.stream()
                .filter(command -> command.get(0).equals("/usr/bin/python3"))
                .count());
        assertTrue(result.detail().endsWith("frontmost=true"), result.detail());
    }

    @Test
    void screenshotIsRestrictedToFreshExactWindowBounds() throws Exception {
        assumeTrue(Platform.current() == Platform.MAC);
        FakeCommands commands = new FakeCommands();
        MacDesktopSmokeDriver driver = driver(commands);
        driver.descriptor();
        driver.attach(currentTarget());

        DesktopSmokeDriver.ActionResult result = driver.execute(
                Map.of("kind", "capture", "artifacts", List.of("screenshot")),
                temporaryDirectory);

        assertEquals(1, result.artifacts().size());
        assertEquals("screenshot", result.artifacts().get(0).kind());
        assertTrue(Files.size(result.artifacts().get(0).path()) > 0);
        assertTrue(commands.commands.stream().anyMatch(
                command -> command.contains("-R10,20,1974,1240")));
    }

    @Test
    void screenshotFallsBackToExactPidCoreGraphicsBoundsForOpenGlWindows() throws Exception {
        assumeTrue(Platform.current() == Platform.MAC);
        FakeCommands commands = new FakeCommands();
        commands.windowBoundsUnavailableCount = 1;
        MacDesktopSmokeDriver driver = driver(commands);
        driver.descriptor();
        driver.attach(currentTarget());

        DesktopSmokeDriver.ActionResult result = driver.execute(
                Map.of("kind", "capture", "artifacts", List.of("screenshot")),
                temporaryDirectory);

        assertEquals(1, result.artifacts().size());
        assertTrue(commands.scripts().stream()
                .anyMatch(script -> script.contains("CGWindowListCopyWindowInfo")));
        assertTrue(commands.commands.stream().anyMatch(
                command -> command.contains("-R10,20,1974,1240")));
    }

    @Test
    void nonzeroDesktopCommandsBecomeUnavailableInsteadOfFalsePasses() throws Exception {
        assumeTrue(Platform.current() == Platform.MAC);
        FakeCommands commands = new FakeCommands();
        commands.fail = true;
        MacDesktopSmokeDriver driver = driver(commands);

        assertThrows(DesktopSmokeDriver.UnavailableException.class, driver::descriptor);
    }

    private MacDesktopSmokeDriver driver(FakeCommands commands) {
        return new MacDesktopSmokeDriver(
                commands, Path.of("/usr/bin/osascript"), Path.of("/usr/sbin/screencapture"),
                null, null, pid -> "foregrounded test PID " + pid);
    }

    private static DesktopSmokeDriver.ProcessTarget currentTarget() {
        ProcessHandle current = ProcessHandle.current();
        Instant startedAt = current.info().startInstant().orElseThrow();
        return new DesktopSmokeDriver.ProcessTarget(current.pid(), startedAt);
    }

    private static final class FakeCommands implements DesktopCommandExecutor {
        private final List<List<String>> commands = new ArrayList<>();
        private boolean fail;
        private int activationUnavailableCount;
        private int activationWindowUnavailableCount;
        private int windowBoundsUnavailableCount;
        private int nonfrontmostObservationCount;

        @Override
        public Result run(
                List<String> command, Duration timeout) throws Exception {
            commands.add(List.copyOf(command));
            if (fail) return new Result(1, "permission denied");
            if (command.get(0).endsWith("screencapture")) {
                Files.writeString(Path.of(command.get(command.size() - 1)), "pixels");
                return new Result(0, "");
            }
            String script = command.get(command.size() - 1);
            if (script.contains("UI elements enabled")) {
                return new Result(0, "true\n");
            }
            if (script.contains("set frontmost of targetProcess to true")
                    && activationUnavailableCount-- > 0) {
                return new Result(1, "execution error: exact PID unavailable (1728)");
            }
            if (script.contains("set win to window 1 of targetProcess")
                    && script.contains("set frontmost of targetProcess to true")
                    && activationWindowUnavailableCount-- > 0) {
                return new Result(1, "execution error: Can’t get window 1 of application process "
                        + "\"java\". Invalid index. (-1719)");
            }
            if (script.contains("return (item 1 of winPosition")) {
                if (windowBoundsUnavailableCount-- > 0) {
                    return new Result(1, "execution error: Can’t get window 1 of application process "
                            + "\"java\". Invalid index. (-1719)");
                }
                return new Result(0, "10, 20, 1974, 1240\n");
            }
            if (script.contains("CGWindowListCopyWindowInfo")) {
                return new Result(0, "10, 20, 1974, 1240\n");
            }
            if (script.contains("frontmost=")) {
                return new Result(
                        0, "PID " + ProcessHandle.current().pid()
                                + " window 10,20,1974,1240 frontmost="
                                + (nonfrontmostObservationCount-- > 0 ? "false" : "true") + "\n");
            }
            return new Result(0, "ok\n");
        }

        private List<String> scripts() {
            return commands.stream()
                    .filter(command -> command.get(0).endsWith("osascript"))
                    .map(command -> command.get(command.size() - 1))
                    .toList();
        }
    }
}
