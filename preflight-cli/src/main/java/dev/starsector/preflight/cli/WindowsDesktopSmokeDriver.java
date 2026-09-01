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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Windows desktop smoke adapter that resolves the game window only through its recorded PID. */
final class WindowsDesktopSmokeDriver implements DesktopSmokeDriver {
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration QUIT_GRACE = Duration.ofSeconds(8);
    private static final int LOG_TAIL_BYTES = 1024 * 1024;
    private static final Map<String, TargetPoint> TARGETS = targets();
    private static final Map<String, Integer> KEY_CODES = Map.ofEntries(
            Map.entry("a", 0x41),
            Map.entry("s", 0x53),
            Map.entry("d", 0x44),
            Map.entry("f", 0x46),
            Map.entry("w", 0x57),
            Map.entry("r", 0x52),
            Map.entry("u", 0x55),
            Map.entry("n", 0x4E),
            Map.entry("return", 0x0D),
            Map.entry("tab", 0x09),
            Map.entry("space", 0x20),
            Map.entry("escape", 0x1B),
            Map.entry("capslock", 0x14));

    private final DesktopCommandExecutor commands;
    private final String powerShell;
    private ProcessTarget target;

    WindowsDesktopSmokeDriver() {
        this(new SystemDesktopCommandExecutor(), "powershell.exe");
    }

    WindowsDesktopSmokeDriver(DesktopCommandExecutor commands, String powerShell) {
        this.commands = commands;
        this.powerShell = powerShell;
    }

    @Override
    public Descriptor descriptor() throws Exception {
        if (Platform.current() != Platform.WINDOWS) {
            throw new UnavailableException(
                    "The PID-addressed Windows driver is unavailable on this platform");
        }
        DesktopCommandExecutor.Result probe = command(
                "$ErrorActionPreference='Stop'; "
                        + "[Console]::Out.Write($PSVersionTable.PSVersion.Major)");
        if (probe.output().trim().isEmpty()) {
            throw new UnavailableException("Windows PowerShell returned no version");
        }
        return new Descriptor(
                "windows-user32-pid",
                "1",
                "windows",
                Set.of("process-control", "window-control", "screen-capture", "evidence-read"),
                List.of("The exact HWND and screen capture are verified on first use"));
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
                    "Unsupported Windows smoke action: " + kind);
        };
    }

    @Override
    public Observation observe() throws Exception {
        ProcessTarget attached = attached();
        requireSameLifetime(attached);
        return new Observation(command(windowScript(attached.pid(), """
                $foreground = [PreflightNative]::GetForegroundWindow()
                [Console]::Out.Write("PID %d window {0},{1},{2},{3} frontmost={4}" -f `
                    $rect.Left,$rect.Top,($rect.Right-$rect.Left),($rect.Bottom-$rect.Top),($foreground -eq $hwnd))
                """.formatted(attached.pid()))).output().trim());
    }

    @Override
    public void shutdown() throws Exception {
        ProcessTarget attached = target;
        if (attached == null || !sameLifetime(attached)) return;
        quit(attached);
    }

    private ActionResult activate(ProcessTarget attached) throws Exception {
        String output = command(windowScript(attached.pid(), """
                [PreflightNative]::ShowWindowAsync($hwnd, 9) | Out-Null
                if (-not [PreflightNative]::SetForegroundWindow($hwnd)) {
                    throw 'Windows refused to focus the exact game window'
                }
                [Console]::Out.Write('activated PID %d')
                """.formatted(attached.pid()))).output().trim();
        return ActionResult.completed(output);
    }

    private ActionResult click(ProcessTarget attached, String name) throws Exception {
        TargetPoint point = TARGETS.get(name);
        if (point == null) {
            throw new IllegalArgumentException("Unsupported Windows smoke target: " + name);
        }
        String body = """
                [PreflightNative]::ShowWindowAsync($hwnd, 9) | Out-Null
                if (-not [PreflightNative]::SetForegroundWindow($hwnd)) { throw 'focus refused' }
                $x = $rect.Left + [Math]::Round(($rect.Right-$rect.Left) * %s)
                $y = $rect.Top + [Math]::Round(($rect.Bottom-$rect.Top) * %s)
                if (-not [PreflightNative]::SetCursorPos($x, $y)) { throw 'cursor move failed' }
                [PreflightNative]::mouse_event(0x0002,0,0,0,[UIntPtr]::Zero)
                [PreflightNative]::mouse_event(0x0004,0,0,0,[UIntPtr]::Zero)
                [Console]::Out.Write('clicked %s at ' + $x + ',' + $y)
                """.formatted(point.x(), point.y(), point.name());
        return ActionResult.completed(command(windowScript(attached.pid(), body)).output().trim());
    }

    private ActionResult pressKey(ProcessTarget attached, String key) throws Exception {
        int code = keyCode(key);
        String body = """
                if (-not [PreflightNative]::SetForegroundWindow($hwnd)) { throw 'focus refused' }
                [PreflightNative]::keybd_event(%d,0,0,[UIntPtr]::Zero)
                [PreflightNative]::keybd_event(%d,0,2,[UIntPtr]::Zero)
                [Console]::Out.Write('pressed virtual key %d')
                """.formatted(code, code, code);
        return ActionResult.completed(command(windowScript(attached.pid(), body)).output().trim());
    }

    private ActionResult holdKey(ProcessTarget attached, String key, int durationMillis)
            throws Exception {
        int code = keyCode(key);
        command(windowScript(attached.pid(), """
                if (-not [PreflightNative]::SetForegroundWindow($hwnd)) { throw 'focus refused' }
                [PreflightNative]::keybd_event(%d,0,0,[UIntPtr]::Zero)
                """.formatted(code)));
        boolean interrupted = false;
        try {
            Thread.sleep(durationMillis);
        } catch (InterruptedException error) {
            interrupted = true;
            throw error;
        } finally {
            if (interrupted) Thread.interrupted();
            try {
                command(nativeScript("""
                        [PreflightNative]::keybd_event(%d,0,2,[UIntPtr]::Zero)
                        """.formatted(code)));
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
        return ActionResult.completed("held " + normalizedKey(key) + " for "
                + durationMillis + " ms");
    }

    private ActionResult scrollWheel(ProcessTarget attached, String direction, int clicks)
            throws Exception {
        if (clicks < 1 || clicks > 24) {
            throw new IllegalArgumentException("Windows scroll clicks must be in 1..24");
        }
        int delta = switch (direction.toLowerCase(Locale.ROOT)) {
            case "in" -> 120;
            case "out" -> -120;
            default -> throw new IllegalArgumentException(
                    "Unsupported Windows scroll direction: " + direction);
        };
        String body = """
                if (-not [PreflightNative]::SetForegroundWindow($hwnd)) { throw 'focus refused' }
                $x = $rect.Left + [Math]::Round(($rect.Right-$rect.Left) / 2)
                $y = $rect.Top + [Math]::Round(($rect.Bottom-$rect.Top) / 2)
                if (-not [PreflightNative]::SetCursorPos($x, $y)) { throw 'cursor move failed' }
                1..%d | ForEach-Object {
                    [PreflightNative]::mouse_event(0x0800,0,0,[uint32][int32]%d,[UIntPtr]::Zero)
                }
                [Console]::Out.Write('scrolled %s %d clicks')
                """.formatted(clicks, delta, direction, clicks);
        return ActionResult.completed(command(windowScript(attached.pid(), body)).output().trim());
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
                        "The Windows adapter doesn't yet provide bounded audio capture");
                default -> throw new IllegalArgumentException(
                        "Unsupported capture artifact: " + artifact);
            }
        }
        return new ActionResult("captured " + artifacts.size() + " artifact(s)",
                List.copyOf(artifacts));
    }

    private Artifact screenshot(ProcessTarget attached, Path runDirectory) throws Exception {
        Path destination = runDirectory.resolve("desktop-smoke.png");
        String body = """
                Add-Type -AssemblyName System.Drawing
                $width=$rect.Right-$rect.Left; $height=$rect.Bottom-$rect.Top
                if ($width -le 0 -or $height -le 0) { throw 'empty window bounds' }
                $bitmap=New-Object Drawing.Bitmap $width,$height
                try {
                  $graphics=[Drawing.Graphics]::FromImage($bitmap)
                  try { $graphics.CopyFromScreen($rect.Left,$rect.Top,0,0,$bitmap.Size) }
                  finally { $graphics.Dispose() }
                  $bitmap.Save('%s',[Drawing.Imaging.ImageFormat]::Png)
                } finally { $bitmap.Dispose() }
                """.formatted(powerShellQuote(destination.toString()));
        command(windowScript(attached.pid(), body));
        if (!Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)
                || Files.size(destination) == 0) {
            throw new UnavailableException(
                    "Windows didn't produce a bounded game-window capture");
        }
        return new Artifact("screenshot", destination);
    }

    private ActionResult quit(ProcessTarget attached) throws Exception {
        if (!sameLifetime(attached)) return ActionResult.completed("process already stopped");
        try {
            command(windowScript(attached.pid(),
                    "[PreflightNative]::PostMessage($hwnd,0x0010,[IntPtr]::Zero,[IntPtr]::Zero)"
                            + " | Out-Null"));
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

    static String windowScript(long pid, String body) {
        if (pid <= 0) throw new IllegalArgumentException("PID must be positive");
        return nativeScript("""
                $process=Get-Process -Id %d -ErrorAction Stop
                $hwnd=$process.MainWindowHandle
                if ($hwnd -eq [IntPtr]::Zero) { throw 'exact PID has no main window' }
                $rect=New-Object PreflightNative+RECT
                if (-not [PreflightNative]::GetWindowRect($hwnd,[ref]$rect)) {
                    throw 'window bounds unavailable'
                }
                %s
                """.formatted(pid, body));
    }

    private static String nativeScript(String body) {
        return "$ErrorActionPreference='Stop'\n" + """
                if (-not ('PreflightNative' -as [type])) {
                  Add-Type -TypeDefinition @'
                using System;
                using System.Runtime.InteropServices;
                public static class PreflightNative {
                  [StructLayout(LayoutKind.Sequential)] public struct RECT {
                    public int Left; public int Top; public int Right; public int Bottom;
                  }
                  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
                  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
                  [DllImport("user32.dll")] public static extern bool ShowWindowAsync(IntPtr h, int n);
                  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
                  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
                  [DllImport("user32.dll")] public static extern void mouse_event(uint f,uint x,uint y,uint d,UIntPtr e);
                  [DllImport("user32.dll")] public static extern void keybd_event(byte k,byte s,uint f,UIntPtr e);
                  [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr h,uint m,IntPtr w,IntPtr l);
                }
                '@
                }
                """ + body;
    }

    private DesktopCommandExecutor.Result command(String script) throws Exception {
        DesktopCommandExecutor.Result result;
        try {
            result = commands.run(List.of(
                    powerShell, "-NoLogo", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-Command", script), COMMAND_TIMEOUT);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        }
        if (result.exitCode() != 0) {
            throw new UnavailableException("Windows desktop command failed (exit "
                    + result.exitCode() + "): " + bounded(result.output()));
        }
        return result;
    }

    private ProcessTarget attached() {
        if (target == null) throw new IllegalStateException("The Windows driver isn't attached");
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
                .map(startedAt -> RuntimeProcessIdentity.sameStartInstant(
                        Platform.WINDOWS, expected.startedAt(), startedAt))
                .orElse(false);
    }

    private static void waitForExit(ProcessTarget target, Duration duration)
            throws InterruptedException {
        long deadline = System.nanoTime() + duration.toNanos();
        while (sameLifetime(target) && System.nanoTime() < deadline) Thread.sleep(25L);
    }

    private static int keyCode(String key) {
        Integer code = KEY_CODES.get(normalizedKey(key));
        if (code == null) throw new IllegalArgumentException("Unsupported Windows smoke key: " + key);
        return code;
    }

    private static String normalizedKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        if (!KEY_CODES.containsKey(normalized)) {
            throw new IllegalArgumentException("Unsupported Windows smoke key: " + key);
        }
        return normalized;
    }

    private static String powerShellQuote(String value) {
        return value.replace("'", "''");
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
}
