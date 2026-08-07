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
import java.util.Map;
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
            assertFalse(script.toLowerCase().contains("starsector"), script);
            assertFalse(script.contains("open -a"), script);
            assertFalse(script.contains("tell application \"Starsector\""), script);
        }
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
    void nonzeroDesktopCommandsBecomeUnavailableInsteadOfFalsePasses() throws Exception {
        assumeTrue(Platform.current() == Platform.MAC);
        FakeCommands commands = new FakeCommands();
        commands.fail = true;
        MacDesktopSmokeDriver driver = driver(commands);

        assertThrows(DesktopSmokeDriver.UnavailableException.class, driver::descriptor);
    }

    private MacDesktopSmokeDriver driver(FakeCommands commands) {
        return new MacDesktopSmokeDriver(
                commands, Path.of("/usr/bin/osascript"), Path.of("/usr/sbin/screencapture"));
    }

    private static DesktopSmokeDriver.ProcessTarget currentTarget() {
        ProcessHandle current = ProcessHandle.current();
        Instant startedAt = current.info().startInstant().orElseThrow();
        return new DesktopSmokeDriver.ProcessTarget(current.pid(), startedAt);
    }

    private static final class FakeCommands implements MacDesktopSmokeDriver.CommandExecutor {
        private final List<List<String>> commands = new ArrayList<>();
        private boolean fail;

        @Override
        public MacDesktopSmokeDriver.CommandResult run(
                List<String> command, Duration timeout) throws Exception {
            commands.add(List.copyOf(command));
            if (fail) return new MacDesktopSmokeDriver.CommandResult(1, "permission denied");
            if (command.get(0).endsWith("screencapture")) {
                Files.writeString(Path.of(command.get(command.size() - 1)), "pixels");
                return new MacDesktopSmokeDriver.CommandResult(0, "");
            }
            String script = command.get(command.size() - 1);
            if (script.contains("UI elements enabled")) {
                return new MacDesktopSmokeDriver.CommandResult(0, "true\n");
            }
            if (script.contains("return (item 1 of winPosition")) {
                return new MacDesktopSmokeDriver.CommandResult(0, "10, 20, 1974, 1240\n");
            }
            if (script.contains("frontmost=")) {
                return new MacDesktopSmokeDriver.CommandResult(
                        0, "PID " + ProcessHandle.current().pid()
                                + " window 10,20,1974,1240 frontmost=true\n");
            }
            return new MacDesktopSmokeDriver.CommandResult(0, "ok\n");
        }

        private List<String> scripts() {
            return commands.stream()
                    .filter(command -> command.get(0).endsWith("osascript"))
                    .map(command -> command.get(command.size() - 1))
                    .toList();
        }
    }
}
