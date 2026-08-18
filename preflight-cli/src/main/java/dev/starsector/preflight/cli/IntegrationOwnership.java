package dev.starsector.preflight.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Proves external launcher integrations are Preflight-owned before destructive removal.
 *
 * <p>Path-name coincidence alone is not ownership. Unrelated files/directories that happen to sit
 * at conventional launcher locations must survive uninstall.
 */
final class IntegrationOwnership {
    private static final int MAX_SCRIPT_SCAN_BYTES = 64 * 1024;
    static final String POSIX_MARKER = "# preflight-integration: dev.starsector.preflight.launcher-v1";
    static final String WINDOWS_MARKER = "REM preflight-integration: dev.starsector.preflight.launcher-v1";
    static final String DESKTOP_MARKER = "X-Preflight-Integration=dev.starsector.preflight.launcher-v1";
    private static final String MAC_BUNDLE_ID =
            "<key>CFBundleIdentifier</key><string>dev.starsector.preflight.launcher</string>";
    private static final String MAC_EXECUTABLE =
            "<key>CFBundleExecutable</key><string>%s</string>";

    private IntegrationOwnership() {}

    static boolean isOwned(PreflightHome.Integration integration) {
        Path path = integration.path().toAbsolutePath().normalize();
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            return false;
        }
        return switch (integration.id()) {
            case MAC_APP -> isOwnedMacApp(path, "preflight");
            case LEGACY_MAC_APP -> isOwnedMacApp(path, "starsector-preflight");
            case LINUX_COMMAND, LEGACY_LINUX_COMMAND -> isOwnedLinuxCommand(path);
            case LINUX_DESKTOP_ENTRY, LEGACY_LINUX_DESKTOP_ENTRY -> isOwnedLinuxDesktop(path);
            case WINDOWS_COMMAND, LEGACY_WINDOWS_COMMAND -> isOwnedWindowsCommand(path);
            case WINDOWS_DIRECTORY, LEGACY_WINDOWS_DIRECTORY -> isOwnedWindowsDirectory(path);
        };
    }

    static boolean isOwnedMacApp(Path app, String executableName) {
        if (!Files.isDirectory(app, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        Path contents = app.resolve("Contents");
        Path infoPlist = contents.resolve("Info.plist");
        Path macos = contents.resolve("MacOS");
        if (!Files.isRegularFile(infoPlist, LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(macos, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            try (Stream<Path> stream = Files.list(app)) {
                for (Path child : stream.toList()) {
                    String name = child.getFileName().toString();
                    if (name.equals("Contents")) continue;
                    if (name.equals(".DS_Store") && isBenignMetadataFile(child)) continue;
                    return false;
                }
            }
            String plist = readBounded(infoPlist);
            if (!plist.contains(MAC_BUNDLE_ID)
                    || !plist.contains(MAC_EXECUTABLE.formatted(executableName))) {
                return false;
            }
            boolean foundExecutable = false;
            try (Stream<Path> stream = Files.list(macos)) {
                List<Path> execs = stream.toList();
                for (Path exec : execs) {
                    String name = exec.getFileName().toString();
                    if (name.equals(".DS_Store")) {
                        if (!isBenignMetadataFile(exec)) return false;
                        continue;
                    }
                    if (!name.equals(executableName)) {
                        return false;
                    }
                    if (!Files.isRegularFile(exec, LinkOption.NOFOLLOW_LINKS)
                            || Files.isSymbolicLink(exec)) {
                        return false;
                    }
                    if (!isPreflightLauncherScript(readBounded(exec))) {
                        return false;
                    }
                    foundExecutable = true;
                }
            }
            if (!foundExecutable) return false;
            try (Stream<Path> stream = Files.list(contents)) {
                for (Path child : stream.toList()) {
                    String name = child.getFileName().toString();
                    if (name.equals("MacOS") || name.equals("Info.plist")) continue;
                    if (name.equals(".DS_Store") && isBenignMetadataFile(child)) continue;
                    return false;
                }
            }
            return true;
        } catch (IOException unreadable) {
            return false;
        }
    }

    static boolean isOwnedLinuxCommand(Path file) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
            return false;
        }
        try {
            return isPreflightLauncherScript(readBounded(file));
        } catch (IOException unreadable) {
            return false;
        }
    }

    static boolean isOwnedLinuxDesktop(Path file) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
            return false;
        }
        try {
            String content = readBounded(file);
            return content.startsWith("[Desktop Entry]\n")
                    && content.lines().anyMatch(DESKTOP_MARKER::equals)
                    && content.lines().anyMatch(line -> line.startsWith("Exec="));
        } catch (IOException unreadable) {
            return false;
        }
    }

    static boolean isOwnedWindowsCommand(Path file) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
            return false;
        }
        try {
            return isPreflightWindowsScript(readBounded(file));
        } catch (IOException unreadable) {
            return false;
        }
    }

    static boolean isOwnedWindowsDirectory(Path dir) {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(dir)) {
            return false;
        }
        boolean foundLauncher = false;
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> entries = stream.toList();
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (name.equals("Preflight.cmd") || name.equals("Starsector Preflight.cmd")) {
                    if (!isOwnedWindowsCommand(entry)) {
                        return false;
                    }
                    foundLauncher = true;
                } else if (name.equals("desktop.ini") || name.equals(".DS_Store")) {
                    if (!isBenignMetadataFile(entry)) return false;
                } else {
                    return false;
                }
            }
            return foundLauncher;
        } catch (IOException unreadable) {
            return false;
        }
    }

    static boolean isPreflightLauncherScript(String script) {
        return (script.startsWith("#!/bin/sh\n") || script.startsWith("#!/bin/bash\n"))
                && script.lines().anyMatch(POSIX_MARKER::equals)
                && script.contains(" run --fast --game ")
                && script.endsWith(" \"$@\"\n");
    }

    static boolean isPreflightWindowsScript(String script) {
        String lower = script.toLowerCase(Locale.ROOT);
        return lower.startsWith("@echo off\r\n")
                && script.lines().anyMatch(WINDOWS_MARKER::equals)
                && script.contains(" run --fast --game ")
                && script.endsWith(" %*\r\n");
    }

    private static boolean isBenignMetadataFile(Path file) {
        return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file);
    }

    private static String readBounded(Path file) throws IOException {
        long size = Files.size(file);
        if (size > MAX_SCRIPT_SCAN_BYTES) {
            return "";
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
