package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WindowsDesktopSmokeDriverTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void everyWindowScriptUsesOnlyTheExactNumericPidAndHwnd() {
        long pid = 4_242L;
        String script = WindowsDesktopSmokeDriver.windowScript(pid, "test body");

        assertTrue(script.contains("Get-Process -Id " + pid));
        assertTrue(script.contains("$process.MainWindowHandle"));
        assertTrue(script.contains("GetWindowRect($hwnd"));
        assertTrue(script.contains("test body"));
        assertFalse(script.toLowerCase(Locale.ROOT).contains("starsector"));
        assertFalse(script.contains("GetProcessesByName"));
        assertFalse(script.contains("AppActivate"));
    }

    @Test
    void invalidPidsNeverReachPowerShell() {
        assertThrows(IllegalArgumentException.class,
                () -> WindowsDesktopSmokeDriver.windowScript(0, "test"));
    }

    @Test
    void clickAndHeldInputStayInsideTheExactPidScriptAndReleaseTheKey() throws Exception {
        FakeCommands commands = new FakeCommands();
        WindowsDesktopSmokeDriver driver = new WindowsDesktopSmokeDriver(
                commands, "powershell.exe");
        ProcessHandle current = ProcessHandle.current();
        Instant startedAt = current.info().startInstant().orElseThrow();
        driver.attach(new DesktopSmokeDriver.ProcessTarget(current.pid(), startedAt));

        driver.execute(Map.of(
                "kind", "click",
                "target", "main-menu.continue"), temporaryDirectory);
        driver.execute(Map.of(
                "kind", "hold-key",
                "key", "w",
                "durationMillis", 50), temporaryDirectory);

        assertTrue(commands.scripts().stream().anyMatch(script ->
                script.contains("Get-Process -Id " + current.pid())
                        && script.contains("* 0.775")
                        && script.contains("mouse_event")));
        assertTrue(commands.scripts().stream().anyMatch(script ->
                script.contains("keybd_event(87,0,0")));
        assertTrue(commands.scripts().stream().anyMatch(script ->
                script.contains("keybd_event(87,0,2")));
        assertTrue(commands.scripts().stream().allMatch(script ->
                !script.toLowerCase(Locale.ROOT).contains("starsector")));
        assertTrue(commands.commands().stream().allMatch(command ->
                command.contains("-EncodedCommand") && !command.contains("-Command")));
        assertTrue(commands.scripts().stream().allMatch(script ->
                script.contains("[DllImport(\"user32.dll\")]")));
    }

    @Test
    void simulationNavigationUsesReviewedVirtualKeys() throws Exception {
        FakeCommands commands = new FakeCommands();
        WindowsDesktopSmokeDriver driver = new WindowsDesktopSmokeDriver(commands, "powershell.exe");
        ProcessHandle current = ProcessHandle.current();
        driver.attach(new DesktopSmokeDriver.ProcessTarget(
                current.pid(), current.info().startInstant().orElseThrow()));

        for (String key : List.of("f", "r", "u", "n", "tab", "capslock")) {
            driver.execute(Map.of("kind", "press-key", "key", key), temporaryDirectory);
        }

        String scripts = String.join("\n", commands.scripts());
        for (int code : List.of(0x46, 0x52, 0x55, 0x4E, 0x09, 0x14)) {
            assertTrue(scripts.contains("keybd_event(" + code + ",0,0"), scripts);
        }
    }

    @Test
    void wheelInputRemainsInsideTheExactPidScript() throws Exception {
        FakeCommands commands = new FakeCommands();
        WindowsDesktopSmokeDriver driver = new WindowsDesktopSmokeDriver(commands, "powershell.exe");
        ProcessHandle current = ProcessHandle.current();
        driver.attach(new DesktopSmokeDriver.ProcessTarget(
                current.pid(), current.info().startInstant().orElseThrow()));

        driver.execute(Map.of(
                "kind", "scroll-wheel", "direction", "out", "clicks", 12),
                temporaryDirectory);

        String scripts = String.join("\n", commands.scripts());
        assertTrue(scripts.contains("Get-Process -Id " + current.pid()), scripts);
        assertTrue(scripts.contains("SetCursorPos"), scripts);
        assertTrue(scripts.contains("mouse_event(0x0800"), scripts);
        assertTrue(scripts.contains("-120"), scripts);
    }

    private static final class FakeCommands implements DesktopCommandExecutor {
        private final List<List<String>> commands = new ArrayList<>();

        @Override
        public Result run(List<String> command, Duration timeout) {
            commands.add(List.copyOf(command));
            return new Result(0, "ok\n");
        }

        private List<String> scripts() {
            return commands.stream()
                    .map(command -> new String(
                            Base64.getDecoder().decode(command.get(command.size() - 1)),
                            java.nio.charset.StandardCharsets.UTF_16LE))
                    .toList();
        }

        private List<List<String>> commands() {
            return List.copyOf(commands);
        }
    }
}
