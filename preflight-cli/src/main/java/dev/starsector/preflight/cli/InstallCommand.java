package dev.starsector.preflight.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

final class InstallCommand {
    private InstallCommand() {
    }

    static int execute(CommandLine options) throws Exception {
        Platform platform = Platform.current();
        Path home = Path.of(System.getProperty("user.home"));
        DiscoveryResult discovery = StarsectorDiscovery.discover(
                platform,
                home,
                Path.of(System.getProperty("user.dir")),
                System.getenv(),
                options.game(),
                options.launcher());
        LaunchTarget target = discovery.selected();
        if (target == null) {
            return RunCommand.doctor(options);
        }

        PreflightHome preflight = PreflightHome.resolve(platform, home, System.getenv());
        Path installedJar = preflight.installedJar();
        Files.createDirectories(installedJar.getParent());
        Path sourceJar = SelfJar.locate();
        if (!sourceJar.equals(installedJar)) {
            Files.copy(
                    sourceJar,
                    installedJar,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES);
        }

        return switch (platform) {
            case MAC -> installMac(preflight, installedJar, target.installRoot());
            case LINUX -> installLinux(preflight, installedJar, target.installRoot());
            case WINDOWS -> installWindows(preflight, installedJar, target.installRoot());
            case OTHER -> {
                System.err.println("Automatic launcher installation is unsupported on this operating system. Use: java -jar "
                        + installedJar + " run --game " + target.installRoot());
                yield 4;
            }
        };
    }

    private static int installMac(PreflightHome preflight, Path jar, Path game) throws IOException {
        Path app = preflight.pathOf(PreflightHome.Id.MAC_APP);
        Path macos = app.resolve("Contents").resolve("MacOS");
        Files.createDirectories(macos);
        Path executable = macos.resolve("starsector-preflight");
        String script = "#!/bin/sh\nexec "
                + shellQuote(javaExecutable())
                + " -jar "
                + shellQuote(jar.toString())
                + " run --game "
                + shellQuote(game.toString())
                + " \"$@\"\n";
        Files.writeString(executable, script, StandardCharsets.UTF_8);
        makeExecutable(executable);

        String plist = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0"><dict>
                  <key>CFBundleName</key><string>Starsector Preflight</string>
                  <key>CFBundleDisplayName</key><string>Starsector Preflight</string>
                  <key>CFBundleIdentifier</key><string>dev.starsector.preflight.launcher</string>
                  <key>CFBundleVersion</key><string>1</string>
                  <key>CFBundlePackageType</key><string>APPL</string>
                  <key>CFBundleExecutable</key><string>starsector-preflight</string>
                  <key>LSUIElement</key><true/>
                </dict></plist>
                """;
        Files.writeString(app.resolve("Contents").resolve("Info.plist"), plist, StandardCharsets.UTF_8);
        System.out.println("Installed macOS launcher: " + app);
        return 0;
    }

    private static int installLinux(PreflightHome preflight, Path jar, Path game) throws IOException {
        Path launcher = preflight.pathOf(PreflightHome.Id.LINUX_COMMAND);
        Files.createDirectories(launcher.getParent());
        String script = "#!/bin/sh\nexec "
                + shellQuote(javaExecutable())
                + " -jar "
                + shellQuote(jar.toString())
                + " run --game "
                + shellQuote(game.toString())
                + " \"$@\"\n";
        Files.writeString(launcher, script, StandardCharsets.UTF_8);
        makeExecutable(launcher);

        Path desktop = preflight.pathOf(PreflightHome.Id.LINUX_DESKTOP_ENTRY);
        Files.createDirectories(desktop.getParent());
        String desktopFile = "[Desktop Entry]\n"
                + "Type=Application\n"
                + "Name=Starsector Preflight\n"
                + "Exec=" + launcher + "\n"
                + "Terminal=false\n"
                + "Categories=Game;Utility;\n";
        Files.writeString(desktop, desktopFile, StandardCharsets.UTF_8);
        System.out.println("Installed command: " + launcher);
        System.out.println("Installed desktop entry: " + desktop);
        return 0;
    }

    private static int installWindows(PreflightHome preflight, Path jar, Path game)
            throws IOException {
        Path command = preflight.pathOf(PreflightHome.Id.WINDOWS_COMMAND);
        Files.createDirectories(preflight.pathOf(PreflightHome.Id.WINDOWS_DIRECTORY));
        String content = "@echo off\r\n\""
                + javaExecutable()
                + "\" -jar \""
                + jar
                + "\" run --game \""
                + game
                + "\" %*\r\n";
        Files.writeString(command, content, StandardCharsets.UTF_8);
        System.out.println("Installed Windows launcher: " + command);
        return 0;
    }

    private static String javaExecutable() {
        String executable = Platform.current() == Platform.WINDOWS ? "java.exe" : "java";
        Path bundled = Path.of(System.getProperty("java.home")).resolve("bin").resolve(executable);
        return Files.isRegularFile(bundled) ? bundled.toString() : executable;
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static void makeExecutable(Path file) throws IOException {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);
            EnumSet<PosixFilePermission> updated = EnumSet.copyOf(permissions);
            updated.add(PosixFilePermission.OWNER_EXECUTE);
            updated.add(PosixFilePermission.GROUP_EXECUTE);
            updated.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(file, updated);
        } catch (UnsupportedOperationException ignored) {
            file.toFile().setExecutable(true, false);
        }
    }
}
