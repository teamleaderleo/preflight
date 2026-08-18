package dev.starsector.preflight.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

final class InstallCommand {
    private InstallCommand() {
    }

    static int execute(String[] args, int offset) throws Exception {
        Options options = Options.parse(args, offset);
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
            return RunCommand.doctor(CommandLine.parse(options.discoveryArguments(), 0));
        }

        PreflightHome preflight = PreflightHome.resolve(platform, home, System.getenv());
        Path preflightRoot = preflight.root().toAbsolutePath().normalize();
        if (Files.exists(preflightRoot, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(preflightRoot, LinkOption.NOFOLLOW_LINKS)) {
            System.err.println("Refusing to install engine because Preflight home is a symlink or alias: " + preflightRoot);
            return 1;
        }

        Path installedJar = preflight.installedJar();
        Path sourceJar = SelfJar.locate();
        if (!sourceJar.equals(installedJar)) {
            writeAtomicCopy(sourceJar, installedJar);
        }

        int installed = switch (platform) {
            case MAC -> installMac(preflight, installedJar, target.installRoot());
            case LINUX -> installLinux(preflight, installedJar, target.installRoot());
            case WINDOWS -> installWindows(preflight, installedJar, target.installRoot());
            case OTHER -> {
                System.err.println("Automatic launcher installation is unsupported on this operating system. Use: java -jar "
                        + installedJar + " run --fast --game " + target.installRoot());
                yield 4;
            }
        };
        if (installed == 0) {
            retireLegacyLaunchers(preflight);
            preflight.recordInstalledIntegrations();
        }
        if (installed != 0 || !options.prepare()) {
            return installed;
        }

        System.out.println("Preparing the exact current profile ("
                + options.textureStorage().optionValue() + " texture storage)...");
        return PrepareCommand.execute(options.preparationArguments(target.installRoot()), 0);
    }

    static void retireLegacyLaunchers(PreflightHome preflight) {
        for (PreflightHome.Integration integration : preflight.integrations()) {
            if (!integration.legacy() || !integration.present()) {
                continue;
            }
            if (!integration.isOwned()) {
                System.err.println("Preserved old launcher because it is not proven Preflight-owned: "
                        + integration.path());
                continue;
            }
            try {
                UninstallCommand.removeIntegration(integration);
                System.out.println("Removed renamed launcher: " + integration.path());
            } catch (IOException failure) {
                System.err.println("The new Preflight launcher is installed, but the old launcher at "
                        + integration.path() + " could not be removed: " + failure.getMessage());
            }
        }
    }

    static int installMac(PreflightHome preflight, Path jar, Path game) throws IOException {
        checkNotUnownedCollision(preflight, PreflightHome.Id.MAC_APP);
        Path app = preflight.pathOf(PreflightHome.Id.MAC_APP).toAbsolutePath().normalize();
        requireRealDirectory(app, "macOS app");
        Path macos = app.resolve("Contents").resolve("MacOS");
        requireRealDirectory(macos, "macOS bundle directory");
        Path executable = macos.resolve("preflight");
        String script = "#!/bin/sh\n"
                + IntegrationOwnership.POSIX_MARKER
                + "\nexec "
                + shellQuote(javaExecutable())
                + " -jar "
                + shellQuote(jar.toString())
                + " run --fast --game "
                + shellQuote(game.toString())
                + " \"$@\"\n";
        writeAtomicFile(executable, script, true);

        String plist = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0"><dict>
                  <key>CFBundleName</key><string>Preflight</string>
                  <key>CFBundleDisplayName</key><string>Preflight</string>
                  <key>CFBundleIdentifier</key><string>dev.starsector.preflight.launcher</string>
                  <key>CFBundleVersion</key><string>1</string>
                  <key>CFBundlePackageType</key><string>APPL</string>
                  <key>CFBundleExecutable</key><string>preflight</string>
                  <key>LSUIElement</key><true/>
                </dict></plist>
                """;
        Path plistFile = app.resolve("Contents").resolve("Info.plist");
        writeAtomicFile(plistFile, plist, false);
        System.out.println("Installed macOS launcher: " + app);
        return 0;
    }

    static int installLinux(PreflightHome preflight, Path jar, Path game) throws IOException {
        checkNotUnownedCollision(preflight, PreflightHome.Id.LINUX_COMMAND);
        checkNotUnownedCollision(preflight, PreflightHome.Id.LINUX_DESKTOP_ENTRY);
        Path launcher = preflight.pathOf(PreflightHome.Id.LINUX_COMMAND).toAbsolutePath().normalize();
        String script = "#!/bin/sh\n"
                + IntegrationOwnership.POSIX_MARKER
                + "\nexec "
                + shellQuote(javaExecutable())
                + " -jar "
                + shellQuote(jar.toString())
                + " run --fast --game "
                + shellQuote(game.toString())
                + " \"$@\"\n";
        writeAtomicFile(launcher, script, true);

        Path desktop = preflight.pathOf(PreflightHome.Id.LINUX_DESKTOP_ENTRY).toAbsolutePath().normalize();
        String desktopFile = "[Desktop Entry]\n"
                + "Type=Application\n"
                + "Name=Preflight\n"
                + "Exec=" + desktopExecArgument(launcher.toString()) + "\n"
                + IntegrationOwnership.DESKTOP_MARKER + "\n"
                + "Terminal=false\n"
                + "Categories=Game;Utility;\n";
        writeAtomicFile(desktop, desktopFile, false);
        System.out.println("Installed command: " + launcher);
        System.out.println("Installed desktop entry: " + desktop);
        return 0;
    }

    static int installWindows(PreflightHome preflight, Path jar, Path game)
            throws IOException {
        checkNotUnownedCollision(preflight, PreflightHome.Id.WINDOWS_DIRECTORY);
        checkNotUnownedCollision(preflight, PreflightHome.Id.WINDOWS_COMMAND);
        Path directory = preflight.pathOf(PreflightHome.Id.WINDOWS_DIRECTORY).toAbsolutePath().normalize();
        requireRealDirectory(directory, "Windows launcher directory");
        Path command = preflight.pathOf(PreflightHome.Id.WINDOWS_COMMAND).toAbsolutePath().normalize();
        String content = "@echo off\r\n"
                + IntegrationOwnership.WINDOWS_MARKER
                + "\r\n\""
                + windowsBatchLiteral(javaExecutable())
                + "\" -jar \""
                + windowsBatchLiteral(jar.toString())
                + "\" run --fast --game \""
                + windowsBatchLiteral(game.toString())
                + "\" %*\r\n";
        writeAtomicFile(command, content, false);
        System.out.println("Installed Windows launcher: " + command);
        return 0;
    }

    static void checkNotUnownedCollision(PreflightHome preflight, PreflightHome.Id id)
            throws IOException {
        PreflightHome.Integration integration = preflight.integration(id);
        if (integration.present() && !integration.isOwned()) {
            throw new IOException("Refusing to replace an existing path that is not proven Preflight-owned: "
                    + integration.path());
        }
    }

    static void validateNotSymlink(Path path, String description) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(normalized)
                    || (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)
                            && !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS))) {
                throw new IOException("Refusing to install launcher over symlink or alias (" + description + "): " + normalized);
            }
        }
    }

    static void requireRealDirectory(Path directory, String description) throws IOException {
        Path normalized = directory.toAbsolutePath().normalize();
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(normalized) || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Refusing to install launcher into symlink or alias (" + description + "): " + normalized);
            }
        }
        Files.createDirectories(normalized);
        if (Files.isSymbolicLink(normalized) || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Refusing to install launcher into symlink or alias (" + description + "): " + normalized);
        }
    }

    static void writeAtomicFile(Path target, String content, boolean executable) throws IOException {
        Path normalized = target.toAbsolutePath().normalize();
        validateNotSymlink(normalized, "target");
        Path parent = normalized.getParent();
        if (parent != null) {
            requireRealDirectory(parent, "parent directory");
        }
        Path temporary = normalized.resolveSibling(
                normalized.getFileName() + ".tmp-" + ProcessHandle.current().pid() + "-" + System.nanoTime());
        try {
            Files.writeString(
                    temporary,
                    content,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            if (executable) {
                makeExecutable(temporary);
            }
            try {
                Files.move(temporary, normalized, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, normalized, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static void writeAtomicCopy(Path source, Path target) throws IOException {
        Path normalized = target.toAbsolutePath().normalize();
        validateNotSymlink(normalized, "target");
        Path parent = normalized.getParent();
        if (parent != null) {
            requireRealDirectory(parent, "parent directory");
        }
        Path temporary = normalized.resolveSibling(
                normalized.getFileName() + ".tmp-" + ProcessHandle.current().pid() + "-" + System.nanoTime());
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            try {
                Files.move(temporary, normalized, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, normalized, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String javaExecutable() {
        String executable = Platform.current() == Platform.WINDOWS ? "java.exe" : "java";
        Path bundled = Path.of(System.getProperty("java.home")).resolve("bin").resolve(executable);
        return Files.isRegularFile(bundled) ? bundled.toString() : executable;
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /** Quotes one executable path under the freedesktop Desktop Entry Exec grammar. */
    static String desktopExecArgument(String value) {
        if (value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Desktop launcher path contains NUL");
        }
        StringBuilder quoted = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                // Exec quoting and the desktop-file string layer both consume backslashes.
                case '\\' -> quoted.append("\\\\\\\\");
                case '"' -> quoted.append("\\\\\\\"");
                case '`' -> quoted.append("\\\\`");
                case '$' -> quoted.append("\\\\$");
                // A literal percent must not become a desktop-entry field code such as %f.
                case '%' -> quoted.append("%%");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                default -> quoted.append(character);
            }
        }
        return quoted.append('"').toString();
    }

    /** Prevents a literal percent in an installed path from becoming cmd.exe expansion. */
    static String windowsBatchLiteral(String value) {
        if (value.indexOf('\0') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Windows launcher path contains a control character");
        }
        return value.replace("%", "%%");
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

    record Options(
            Path game,
            Path launcher,
            boolean prepare,
            TextureStoragePolicy textureStorage,
            Integer workers,
            Long memoryMib) {
        static Options parse(String[] args, int offset) {
            Path game = null;
            Path launcher = null;
            boolean prepare = false;
            TextureStoragePolicy textureStorage = TextureStoragePolicy.DEFAULT;
            boolean textureStorageSpecified = false;
            Integer workers = null;
            Long memoryMib = null;
            for (int i = offset; i < args.length; i++) {
                switch (args[i]) {
                    case "--game" -> game = Path.of(requireValue(args, ++i, "--game"));
                    case "--launcher" -> launcher = Path.of(requireValue(args, ++i, "--launcher"));
                    case "--prepare" -> prepare = true;
                    case "--texture-storage" -> {
                        textureStorage = TextureStoragePolicy.parse(
                                requireValue(args, ++i, "--texture-storage"));
                        textureStorageSpecified = true;
                    }
                    case "--workers" -> workers = parseInt(
                            requireValue(args, ++i, "--workers"), "worker count");
                    case "--memory-mb" -> memoryMib = parseLong(
                            requireValue(args, ++i, "--memory-mb"), "memory budget");
                    default -> throw new IllegalArgumentException("Unknown install option: " + args[i]);
                }
            }
            if (!prepare && (textureStorageSpecified || workers != null || memoryMib != null)) {
                throw new IllegalArgumentException(
                        "--texture-storage, --workers, and --memory-mb require --prepare");
            }
            if (workers != null && (workers < 1 || workers > 64)) {
                throw new IllegalArgumentException("Texture workers must be between 1 and 64");
            }
            if (memoryMib != null && (memoryMib < 16 || memoryMib > 65_536)) {
                throw new IllegalArgumentException(
                        "Texture memory budget must be between 16 and 65536 MiB");
            }
            return new Options(game, launcher, prepare, textureStorage, workers, memoryMib);
        }

        String[] preparationArguments(Path installRoot) {
            java.util.List<String> values = new java.util.ArrayList<>();
            values.add("--game");
            values.add(installRoot.toString());
            values.add("--texture-storage");
            values.add(textureStorage.optionValue());
            if (workers != null) {
                values.add("--workers");
                values.add(Integer.toString(workers));
            }
            if (memoryMib != null) {
                values.add("--memory-mb");
                values.add(Long.toString(memoryMib));
            }
            return values.toArray(String[]::new);
        }

        String[] discoveryArguments() {
            java.util.List<String> values = new java.util.ArrayList<>();
            if (game != null) {
                values.add("--game");
                values.add(game.toString());
            }
            if (launcher != null) {
                values.add("--launcher");
                values.add(launcher.toString());
            }
            return values.toArray(String[]::new);
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            return args[index];
        }

        private static int parseInt(String raw, String kind) {
            try {
                return Integer.parseInt(raw);
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("Invalid " + kind + ": " + raw, error);
            }
        }

        private static long parseLong(String raw, String kind) {
            try {
                return Long.parseLong(raw);
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("Invalid " + kind + ": " + raw, error);
            }
        }
    }
}
