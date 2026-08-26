package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LinuxDesktopSmokeDriverTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void selectsTheLargestVisibleWindowOwnedByOnlyTheExactPid() throws Exception {
        FakeCommands commands = new FakeCommands();
        LinuxDesktopSmokeDriver driver = new LinuxDesktopSmokeDriver(
                commands, "xdotool", "import", Map.of("DISPLAY", ":0"));
        ProcessHandle current = ProcessHandle.current();
        Instant startedAt = current.info().startInstant().orElseThrow();
        driver.attach(new DesktopSmokeDriver.ProcessTarget(current.pid(), startedAt));

        DesktopSmokeDriver.ActionResult result = driver.execute(Map.of(
                "kind", "click",
                "target", "main-menu.continue"), temporaryDirectory);

        assertTrue(result.detail().contains("X11 window 202"));
        assertTrue(commands.commands.stream().anyMatch(command -> command.equals(List.of(
                "xdotool", "search", "--onlyvisible", "--pid",
                Long.toString(current.pid())))));
        assertTrue(commands.commands.stream().anyMatch(command -> command.containsAll(List.of(
                "windowactivate", "202", "mousemove", "click"))));
    }

    @Test
    void holdAlwaysEmitsAKeyUp() throws Exception {
        FakeCommands commands = new FakeCommands();
        LinuxDesktopSmokeDriver driver = new LinuxDesktopSmokeDriver(
                commands, "xdotool", "import", Map.of("DISPLAY", ":0"));
        ProcessHandle current = ProcessHandle.current();
        driver.attach(new DesktopSmokeDriver.ProcessTarget(
                current.pid(), current.info().startInstant().orElseThrow()));

        driver.execute(Map.of(
                "kind", "hold-key",
                "key", "w",
                "durationMillis", 50), temporaryDirectory);

        assertTrue(commands.commands.stream().anyMatch(command -> command.contains("keydown")));
        assertTrue(commands.commands.stream().anyMatch(command -> command.contains("keyup")));
    }

    @Test
    void simulationNavigationUsesReviewedX11KeySymbols() throws Exception {
        FakeCommands commands = new FakeCommands();
        LinuxDesktopSmokeDriver driver = new LinuxDesktopSmokeDriver(
                commands, "xdotool", "import", Map.of("DISPLAY", ":0"));
        ProcessHandle current = ProcessHandle.current();
        driver.attach(new DesktopSmokeDriver.ProcessTarget(
                current.pid(), current.info().startInstant().orElseThrow()));

        for (String key : List.of("f", "r", "u", "n", "tab", "capslock")) {
            driver.execute(Map.of("kind", "press-key", "key", key), temporaryDirectory);
        }

        for (String symbol : List.of("f", "r", "u", "n", "Tab", "Caps_Lock")) {
            assertTrue(commands.commands.stream().anyMatch(command ->
                    command.contains("key") && command.contains(symbol)), symbol);
        }
    }

    @Test
    void wheelInputUsesTheExactOwnedWindow() throws Exception {
        FakeCommands commands = new FakeCommands();
        LinuxDesktopSmokeDriver driver = new LinuxDesktopSmokeDriver(
                commands, "xdotool", "import", Map.of("DISPLAY", ":0"));
        ProcessHandle current = ProcessHandle.current();
        driver.attach(new DesktopSmokeDriver.ProcessTarget(
                current.pid(), current.info().startInstant().orElseThrow()));

        driver.execute(Map.of(
                "kind", "scroll-wheel", "direction", "out", "clicks", 12),
                temporaryDirectory);

        assertTrue(commands.commands.stream().anyMatch(command ->
                command.containsAll(List.of("windowactivate", "202", "mousemove", "--repeat", "12", "5"))));
    }

    private static final class FakeCommands implements DesktopCommandExecutor {
        private final List<List<String>> commands = new ArrayList<>();

        @Override
        public Result run(List<String> command, Duration timeout) {
            commands.add(List.copyOf(command));
            if (command.contains("search")) return new Result(0, "101\n202\n");
            if (command.contains("getwindowpid")) {
                return new Result(0, Long.toString(ProcessHandle.current().pid()));
            }
            if (command.contains("getwindowgeometry")) {
                String id = command.get(command.size() - 1);
                return new Result(0, "X=10\nY=20\nWIDTH="
                        + ("202".equals(id) ? "1974\nHEIGHT=1240\n" : "400\nHEIGHT=300\n"));
            }
            if (command.contains("getwindowfocus")) return new Result(0, "202\n");
            return new Result(0, "ok\n");
        }
    }
}
