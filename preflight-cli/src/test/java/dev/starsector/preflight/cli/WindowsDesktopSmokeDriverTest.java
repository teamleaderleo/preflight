package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
    }

    private static final class FakeCommands implements DesktopCommandExecutor {
        private final List<List<String>> commands = new ArrayList<>();

        @Override
        public Result run(List<String> command, Duration timeout) {
            commands.add(List.copyOf(command));
            return new Result(0, "ok\n");
        }

        private List<String> scripts() {
            return commands.stream().map(command -> command.get(command.size() - 1)).toList();
        }
    }
}
