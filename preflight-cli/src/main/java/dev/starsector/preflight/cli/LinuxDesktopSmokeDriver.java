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

/** Linux smoke adapter that discovers X11/XWayland windows only from the recorded process ID. */
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
            Map.entry("1", "1"),
            Map.entry("3", "3"),
            Map.entry("4", "4"),
            Map.entry("return", "Return"),
            Map.entry("tab", "Tab"),
            Map.entry("space", "space"),
            Map.entry("escape", "Escape"),
            Map.entry("capslock", "Caps_Lock"));
    private static final Map<String, Integer> WAYLAND_KEYS = Map.ofEntries(
            Map.entry("a", 30),
            Map.entry("s", 31),
            Map.entry("d", 32),
            Map.entry("f", 33),
            Map.entry("w", 17),
            Map.entry("r", 19),
            Map.entry("u", 22),
            Map.entry("n", 49),
            Map.entry("1", 2),
            Map.entry("3", 4),
            Map.entry("4", 5),
            Map.entry("tab", 15),
            Map.entry("return", 28),
            Map.entry("space", 57),
            Map.entry("escape", 1),
            Map.entry("capslock", 58));
    private static final String WAYLAND_WINDOW_PROBE = """
            import sys, gi
            gi.require_version('Gdk', '3.0')
            gi.require_version('GdkX11', '3.0')
            gi.require_version('Wnck', '3.0')
            from gi.repository import Gdk, GdkX11, Wnck
            pid = int(sys.argv[1])
            screen = Wnck.Screen.get_default()
            screen.force_update()
            display = Gdk.Display.get_default()
            for window in screen.get_windows():
                if window.get_pid() != pid:
                    continue
                foreign = GdkX11.X11Window.foreign_new_for_display(display, window.get_xid())
                if foreign is not None:
                    print(f'{window.get_xid()} {foreign.get_scale_factor()}')
            """;
    private static final String WAYLAND_CAPTURE = """
            import sys, gi
            gi.require_version('Gdk', '3.0')
            gi.require_version('GdkX11', '3.0')
            gi.require_version('GdkPixbuf', '2.0')
            gi.require_version('Wnck', '3.0')
            from gi.repository import Gdk, GdkX11, GdkPixbuf, Wnck
            pid = int(sys.argv[1])
            output = sys.argv[2]
            screen = Wnck.Screen.get_default()
            screen.force_update()
            display = Gdk.Display.get_default()
            candidates = []
            for window in screen.get_windows():
                if window.get_pid() != pid:
                    continue
                foreign = GdkX11.X11Window.foreign_new_for_display(display, window.get_xid())
                if foreign is None:
                    continue
                _, _, width, height = foreign.get_geometry()
                candidates.append((width * height, window, foreign, width, height))
            if not candidates:
                raise SystemExit('exact PID has no capturable XWayland window')
            _, window, foreign, width, height = max(candidates, key=lambda value: value[0])
            pixels = Gdk.pixbuf_get_from_window(foreign, 0, 0, width, height)
            if pixels is None:
                raise SystemExit('XWayland window capture failed')
            pixels.savev(output, 'png', [], [])
            print(f'{window.get_xid()} {width}x{height}')
            """;
    private static final String WAYLAND_CLICK = """
            import ctypes, ctypes.util, math, subprocess, sys, time, gi
            gi.require_version('Gdk', '3.0')
            gi.require_version('GdkX11', '3.0')
            gi.require_version('Gio', '2.0')
            gi.require_version('Wnck', '3.0')
            from gi.repository import Gdk, GdkX11, Gio, GLib, Wnck
            pid = int(sys.argv[1])
            target_x = int(sys.argv[2])
            target_y = int(sys.argv[3])
            ydotool = sys.argv[4]
            screen = Wnck.Screen.get_default()
            screen.force_update()
            display = Gdk.Display.get_default()
            candidates = []
            for window in screen.get_windows():
                if window.get_pid() != pid:
                    continue
                foreign = GdkX11.X11Window.foreign_new_for_display(display, window.get_xid())
                if foreign is None:
                    continue
                _, _, logical_width, logical_height = foreign.get_geometry()
                _, _, physical_width, physical_height = window.get_geometry()
                candidates.append((physical_width * physical_height, window,
                    physical_width / logical_width,
                    physical_height / logical_height))
            if not candidates:
                raise SystemExit('exact PID has no clickable XWayland window')
            _, window, scale_x, scale_y = max(
                candidates, key=lambda value: value[0])

            lib = ctypes.CDLL(ctypes.util.find_library('X11'))
            lib.XOpenDisplay.argtypes = [ctypes.c_char_p]
            lib.XOpenDisplay.restype = ctypes.c_void_p
            lib.XDefaultScreen.argtypes = [ctypes.c_void_p]
            lib.XRootWindow.argtypes = [ctypes.c_void_p, ctypes.c_int]
            lib.XRootWindow.restype = ctypes.c_ulong
            lib.XCloseDisplay.argtypes = [ctypes.c_void_p]
            lib.XQueryPointer.argtypes = [ctypes.c_void_p, ctypes.c_ulong,
                ctypes.POINTER(ctypes.c_ulong), ctypes.POINTER(ctypes.c_ulong),
                ctypes.POINTER(ctypes.c_int), ctypes.POINTER(ctypes.c_int),
                ctypes.POINTER(ctypes.c_int), ctypes.POINTER(ctypes.c_int),
                ctypes.POINTER(ctypes.c_uint)]
            def pointer_position():
                connection = lib.XOpenDisplay(None)
                if not connection:
                    raise SystemExit('could not connect to X display')
                root = lib.XRootWindow(connection, lib.XDefaultScreen(connection))
                root_return = ctypes.c_ulong()
                child_return = ctypes.c_ulong()
                root_px = ctypes.c_int()
                root_py = ctypes.c_int()
                window_px = ctypes.c_int()
                window_py = ctypes.c_int()
                mask = ctypes.c_uint()
                lib.XQueryPointer(connection, root, ctypes.byref(root_return),
                    ctypes.byref(child_return), ctypes.byref(root_px), ctypes.byref(root_py),
                    ctypes.byref(window_px), ctypes.byref(window_py), ctypes.byref(mask))
                lib.XCloseDisplay(connection)
                return root_px.value, root_py.value

            bus = Gio.bus_get_sync(Gio.BusType.SESSION, None)
            root_path = '/org/gnome/Mutter/RemoteDesktop'
            root_iface = 'org.gnome.Mutter.RemoteDesktop'
            session_iface = 'org.gnome.Mutter.RemoteDesktop.Session'
            try:
                session = bus.call_sync(root_iface, root_path, root_iface, 'CreateSession', None,
                    GLib.VariantType.new('(o)'), Gio.DBusCallFlags.NONE, -1, None).unpack()[0]
            except GLib.Error:
                for _ in range(500):
                    actual_x, actual_y = pointer_position()
                    physical_dx = target_x - actual_x
                    physical_dy = target_y - actual_y
                    if max(abs(physical_dx), abs(physical_dy)) <= max(4, scale_x * 4):
                        break
                    logical_dx = max(-8, min(8, round(physical_dx / scale_x)))
                    logical_dy = max(-8, min(8, round(physical_dy / scale_y)))
                    subprocess.run([ydotool, 'mousemove', '-x', str(logical_dx), '-y',
                        str(logical_dy)], check=True, stdout=subprocess.DEVNULL)
                    time.sleep(0.008)
                actual_x, actual_y = pointer_position()
                if abs(actual_x - target_x) > max(4, scale_x * 4) or \
                        abs(actual_y - target_y) > max(4, scale_y * 4):
                    raise SystemExit(f'fallback pointer verification failed: {actual_x},{actual_y}')
                subprocess.run([ydotool, 'click', '--next-delay', '120', '0xC0'], check=True,
                    stdout=subprocess.DEVNULL)
                print(f'{window.get_xid()} {round(target_x)} {round(target_y)} ydotool')
                raise SystemExit(0)
            def remote(method, parameters=None):
                return bus.call_sync(root_iface, session, session_iface, method, parameters, None,
                    Gio.DBusCallFlags.NONE, -1, None)
            try:
                remote('Start')
                for _ in range(8):
                    current_x, current_y = pointer_position()
                    logical_dx = (target_x - current_x) / scale_x
                    logical_dy = (target_y - current_y) / scale_y
                    if max(abs(logical_dx), abs(logical_dy)) <= 4:
                        break
                    steps = max(1, math.ceil(max(abs(logical_dx), abs(logical_dy)) / 8))
                    for _ in range(steps):
                        remote('NotifyPointerMotionRelative', GLib.Variant('(dd)',
                            (logical_dx / steps, logical_dy / steps)))
                        time.sleep(0.008)
                    time.sleep(0.05)
                actual_x, actual_y = pointer_position()
                if abs(actual_x - target_x) > max(4, scale_x * 4) or \
                        abs(actual_y - target_y) > max(4, scale_y * 4):
                    raise SystemExit(f'pointer verification failed: {actual_x},{actual_y}')
                remote('NotifyPointerButton', GLib.Variant('(ib)', (0x110, True)))
                time.sleep(0.06)
                remote('NotifyPointerButton', GLib.Variant('(ib)', (0x110, False)))
            finally:
                remote('Stop')
            print(f'{window.get_xid()} {round(target_x)} {round(target_y)}')
            """;
    private static final Map<String, TargetPoint> TARGETS = targets();

    private final DesktopCommandExecutor commands;
    private final String xdotool;
    private final String imageMagickImport;
    private final String ydotool;
    private final String wmctrl;
    private final String python3;
    private final Map<String, String> environment;
    private ProcessTarget target;

    LinuxDesktopSmokeDriver() {
        this(new SystemDesktopCommandExecutor(), "xdotool", "import", "ydotool", "wmctrl",
                "python3", System.getenv());
    }

    LinuxDesktopSmokeDriver(
            DesktopCommandExecutor commands,
            String xdotool,
            String imageMagickImport,
            Map<String, String> environment) {
        this(commands, xdotool, imageMagickImport, "ydotool", "wmctrl", "python3", environment);
    }

    LinuxDesktopSmokeDriver(
            DesktopCommandExecutor commands,
            String xdotool,
            String imageMagickImport,
            String ydotool,
            String wmctrl,
            String python3,
            Map<String, String> environment) {
        this.commands = commands;
        this.xdotool = xdotool;
        this.imageMagickImport = imageMagickImport;
        this.ydotool = ydotool;
        this.wmctrl = wmctrl;
        this.python3 = python3;
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
        if (environment.getOrDefault("DISPLAY", "").isBlank()) {
            throw new UnavailableException("Linux desktop automation requires an X11/XWayland DISPLAY");
        }
        try {
            command(List.of(xdotool, "version"), "xdotool");
            if ("wayland".equals(session)) {
                command(List.of(python3, "-c", WAYLAND_WINDOW_PROBE, "0"), "GNOME XWayland bridge");
                command(List.of(ydotool, "--help"), "ydotool");
                command(List.of(wmctrl, "-m"), "wmctrl");
            } else {
                command(List.of(imageMagickImport, "-version"), "ImageMagick import");
            }
        } catch (IOException unavailable) {
            throw new UnavailableException(
                    "Linux desktop automation dependencies are unavailable", unavailable);
        }
        boolean wayland = "wayland".equals(session);
        return new Descriptor(
                wayland ? "linux-gnome-wayland-pid" : "linux-xdotool-pid",
                wayland ? "1" : "2",
                "linux",
                Set.of("process-control", "window-control", "screen-capture", "evidence-read"),
                List.of(wayland
                        ? "GNOME resolves the exact PID through XRes/Wnck; Mutter RemoteDesktop sends verified logical pointer input"
                        : "X11 window ownership and capture are verified on first use"));
    }

    @Override
    public void attach(ProcessTarget target) throws Exception {
        if (target == null || target.pid() <= 0 || target.startedAt() == null) {
            throw new IllegalArgumentException("A PID and process start instant are required");
        }
        requireSameLifetime(target);
        this.target = target;
        Window window = waitForWindow(target, Duration.ofSeconds(8));
        focusForStartup(target, window);
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
        activate(window);
        verifyWindowOwner(window.id(), attached.pid());
        return ActionResult.completed("activated Linux window " + window.id()
                + " for PID " + attached.pid());
    }

    private ActionResult click(ProcessTarget attached, String name) throws Exception {
        TargetPoint point = TARGETS.get(name);
        if (point == null) {
            throw new IllegalArgumentException("Unsupported Linux smoke target: " + name);
        }
        Window window = window(attached.pid());
        int x;
        int y;
        if (wayland()) {
            activate(window);
            int physicalX = (int) Math.round(window.x() + window.width() * point.x());
            int physicalY = (int) Math.round(window.y() + window.height() * point.y());
            x = (int) Math.round((double) physicalX / window.scale());
            y = (int) Math.round((double) physicalY / window.scale());
            command(List.of(python3, "-c", WAYLAND_CLICK, Long.toString(attached.pid()),
                    Integer.toString(physicalX), Integer.toString(physicalY), ydotool),
                    "GNOME RemoteDesktop click");
            // GNOME may focus the window for the click but immediately return focus to the
            // controller. Starsector throttles an inactive loading screen to 1 FPS, so assert the
            // game again after the transition-triggering click.
            activate(window);
            waitUntilFrontmost(window.id(), Duration.ofSeconds(2));
            if (clickReturnActivation(name)) {
                command(List.of(ydotool, "key", "28:1"), "ydotool Return keydown");
                Thread.sleep(120L);
                command(List.of(ydotool, "key", "28:0"), "ydotool Return keyup");
            }
        } else {
            x = (int) Math.round(window.width() * point.x());
            y = (int) Math.round(window.height() * point.y());
            command(List.of(
                    xdotool,
                    "windowactivate", "--sync", Long.toString(window.id()),
                    "mousemove", "--window", Long.toString(window.id()),
                    Integer.toString(x), Integer.toString(y),
                    "click", "1"), "xdotool");
        }
        verifyWindowOwner(window.id(), attached.pid());
        return ActionResult.completed("clicked " + point.name() + " at " + x + "," + y
                + " in Linux window " + window.id()
                + (wayland() && clickReturnActivation(name)
                        ? " with post-focus Return activation" : ""));
    }

    private ActionResult pressKey(ProcessTarget attached, String key) throws Exception {
        String normalized = keySymbol(key);
        Window window = window(attached.pid());
        if (wayland()) {
            activate(window);
            int code = waylandKey(key);
            command(List.of(ydotool, "key", code + ":1", code + ":0"), "ydotool");
        } else {
            command(List.of(
                    xdotool, "windowactivate", "--sync", Long.toString(window.id()),
                    "key", normalized), "xdotool");
        }
        verifyWindowOwner(window.id(), attached.pid());
        return ActionResult.completed("pressed " + normalized + " in X11 window " + window.id());
    }

    private ActionResult holdKey(ProcessTarget attached, String key, int durationMillis)
            throws Exception {
        String normalized = keySymbol(key);
        Window window = window(attached.pid());
        if (wayland()) {
            activate(window);
            command(List.of(ydotool, "key", waylandKey(key) + ":1"), "ydotool");
        } else {
            command(List.of(
                    xdotool, "windowactivate", "--sync", Long.toString(window.id()),
                    "keydown", normalized), "xdotool");
        }
        boolean interrupted = false;
        try {
            Thread.sleep(durationMillis);
        } catch (InterruptedException error) {
            interrupted = true;
            throw error;
        } finally {
            if (interrupted) Thread.interrupted();
            try {
                if (wayland()) {
                    command(List.of(ydotool, "key", waylandKey(key) + ":0"), "ydotool");
                } else {
                    command(List.of(xdotool, "keyup", normalized), "xdotool");
                }
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
        if (wayland()) {
            command(List.of(python3, "-c", WAYLAND_CAPTURE, Long.toString(attached.pid()),
                    destination.toString()), "GNOME XWayland capture");
        } else {
            command(List.of(
                    imageMagickImport, "-window", Long.toString(window.id()),
                    "png:" + destination), "ImageMagick import");
        }
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
            if (wayland()) {
                command(List.of(wmctrl, "-ic", "0x" + Long.toHexString(window.id())), "wmctrl");
            } else {
                command(List.of(xdotool, "windowclose", Long.toString(window.id())), "xdotool");
            }
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
        if (wayland()) return waylandWindow(pid);
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
            candidates.add(geometry(id, 1));
        }
        return candidates.stream()
                .filter(candidate -> candidate.width() >= 100 && candidate.height() >= 100)
                .max(Comparator.comparingLong(
                        candidate -> (long) candidate.width() * candidate.height()))
                .orElseThrow(() -> new UnavailableException(
                        "The exact PID has no usable visible X11 window"));
    }

    private Window waitForWindow(ProcessTarget expected, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        UnavailableException last = null;
        do {
            requireSameLifetime(expected);
            try {
                return window(expected.pid());
            } catch (UnavailableException unavailable) {
                last = unavailable;
            }
            Thread.sleep(50L);
        } while (System.nanoTime() < deadline);
        throw new UnavailableException("The exact PID did not expose a usable Linux window in "
                + timeout.toSeconds() + " seconds", last);
    }

    private Window waylandWindow(long pid) throws Exception {
        DesktopCommandExecutor.Result found = command(List.of(
                python3, "-c", WAYLAND_WINDOW_PROBE, Long.toString(pid)),
                "GNOME XWayland bridge");
        List<Window> candidates = new ArrayList<>();
        for (String line : found.output().lines().toList()) {
            if (line.isBlank()) continue;
            String[] fields = line.trim().split("\\s+");
            if (fields.length != 2) {
                throw new UnavailableException("GNOME bridge returned an invalid window: " + line);
            }
            try {
                long id = Long.parseLong(fields[0]);
                int scale = Integer.parseInt(fields[1]);
                if (scale < 1 || scale > 8) throw new NumberFormatException("invalid scale");
                candidates.add(geometry(id, scale));
            } catch (NumberFormatException invalid) {
                throw new UnavailableException("GNOME bridge returned an invalid window: " + line);
            }
        }
        return candidates.stream()
                .filter(candidate -> candidate.width() >= 100 && candidate.height() >= 100)
                .max(Comparator.comparingLong(
                        candidate -> (long) candidate.width() * candidate.height()))
                .orElseThrow(() -> new UnavailableException(
                        "The exact PID has no usable visible XWayland window"));
    }

    private Window geometry(long id, int scale) throws Exception {
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
                    values.get("WIDTH"), values.get("HEIGHT"), scale);
        } catch (NullPointerException missing) {
            throw new UnavailableException("xdotool returned incomplete window geometry: " + output);
        }
    }

    private void verifyWindowOwner(long window, long pid) throws Exception {
        if (wayland()) {
            if (waylandWindow(pid).id() != window) {
                throw new UnavailableException(
                        "XWayland window ownership changed before the desktop action");
            }
            return;
        }
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
        Path installRoot = Path.of(value).toAbsolutePath().normalize();
        Path source = installRoot.resolve("starsector.log");
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            source = installRoot.resolve("logs/starsector.log");
        }
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

    private int waylandKey(String key) {
        Integer code = WAYLAND_KEYS.get(key.toLowerCase(Locale.ROOT));
        if (code == null) {
            throw new IllegalArgumentException("Unsupported GNOME smoke key: " + key);
        }
        return code;
    }

    private boolean wayland() {
        return "wayland".equals(environment.getOrDefault("XDG_SESSION_TYPE", "")
                .toLowerCase(Locale.ROOT));
    }

    private static boolean clickReturnActivation(String target) {
        return "main-menu.continue".equals(target) || "main-menu.new-game".equals(target);
    }

    private void activate(Window window) throws Exception {
        if (wayland()) {
            command(List.of(wmctrl, "-ia", "0x" + Long.toHexString(window.id())), "wmctrl");
        } else {
            command(List.of(xdotool, "windowactivate", "--sync", Long.toString(window.id())),
                    "xdotool");
        }
    }

    private void waitUntilFrontmost(long windowId, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        do {
            String focused = command(List.of(xdotool, "getwindowfocus"), "xdotool")
                    .output().trim();
            if (Long.toString(windowId).equals(focused)) return;
            Thread.sleep(25L);
        } while (System.nanoTime() < deadline);
        throw new IOException("Linux game window did not become frontmost before input: "
                + windowId);
    }

    private void focusForStartup(ProcessTarget attached, Window window) throws Exception {
        activate(window);
        if (wayland()) {
            int physicalX = window.x() + window.width() / 2;
            int physicalY = window.y() + window.height() / 2;
            command(List.of(python3, "-c", WAYLAND_CLICK, Long.toString(attached.pid()),
                    Integer.toString(physicalX), Integer.toString(physicalY), ydotool),
                    "GNOME RemoteDesktop startup focus");
        }
        waitUntilFrontmost(window.id(), Duration.ofSeconds(2));
    }

    private static Map<String, TargetPoint> targets() {
        Map<String, TargetPoint> values = new LinkedHashMap<>();
        // Starsector's decorated XWayland geometry is vertically offset from its captured client
        // pixels on GNOME HiDPI. These fractions are calibrated against verified physical pointer
        // receipts for the 2048x1280 direct-launch window.
        values.put("main-menu.continue", new TargetPoint("main-menu.continue", 0.782, 0.201));
        values.put("main-menu.new-game", new TargetPoint("main-menu.new-game", 0.782, 0.337));
        values.put("main-menu.load-game", new TargetPoint("main-menu.load-game", 0.782, 0.377));
        values.put("new-game.fleet.carrier-small",
                new TargetPoint("new-game.fleet.carrier-small", 0.603, 0.488));
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

    private record Window(long id, int x, int y, int width, int height, int scale) {
    }
}
