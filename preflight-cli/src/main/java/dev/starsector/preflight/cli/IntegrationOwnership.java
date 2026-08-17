package dev.starsector.preflight.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Proves external launcher integrations are Preflight-owned before destructive removal.
 *
 * <p>Path-name coincidence alone is not ownership. Unrelated files/directories that happen to sit
 * at conventional launcher locations must survive uninstall.
 */
final class IntegrationOwnership {
    private static final int MAX_SCRIPT_SCAN_BYTES = 64 * 1024;

    private IntegrationOwnership() {}

    static boolean isOwned(PreflightHome.Integration integration) {
        Path path = integration.path().toAbsolutePath().normalize();
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            return false;
        }
        return switch (integration.id()) {
            case MAC_APP, LEGACY_MAC_APP -> isOwnedMacApp(path);
            case LINUX_COMMAND, LEGACY_LINUX_COMMAND -> isOwnedLinuxCommand(path);
            case LINUX_DESKTOP_ENTRY, LEGACY_LINUX_DESKTOP_ENTRY -> isOwnedLinuxDesktop(path);
            case WINDOWS_COMMAND, LEGACY_WINDOWS_COMMAND -> isOwnedWindowsCommand(path);
            case WINDOWS_DIRECTORY, LEGACY_WINDOWS_DIRECTORY -> isOwnedWindowsDirectory(path);
        };
    }

    static boolean isOwnedMacApp(Path app) {
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
            String plist = readBounded(infoPlist);
            if (!plist.contains("dev.starsector.preflight")
                    && !plist.contains("CFBundleExecutable")) {
                return false;
            }
            try (Stream<Path> stream = Files.list(macos)) {
                List<Path> execs = stream.toList();
                if (execs.isEmpty()) {
                    return false;
                }
                for (Path exec : execs) {
                    String name = exec.getFileName().toString();
                    if (name.equals(".DS_Store")) {
                        continue;
                    }
                    if (!name.equals("preflight") && !name.equals("starsector-preflight")) {
                        return false;
                    }
                    if (!Files.isRegularFile(exec, LinkOption.NOFOLLOW_LINKS)) {
                        return false;
                    }
                    if (!isPreflightLauncherScript(readBounded(exec))) {
                        return false;
                    }
                }
            }
            try (Stream<Path> stream = Files.list(contents)) {
                for (Path child : stream.toList()) {
                    String name = child.getFileName().toString();
                    if (!name.equals("MacOS")
                            && !name.equals("Info.plist")
                            && !name.equals("Resources")
                            && !name.equals(".DS_Store")) {
                        return false;
                    }
                }
            }
            return true;
        } catch (IOException unreadable) {
            return false;
        }
    }

    static boolean isOwnedLinuxCommand(Path file) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            return isPreflightLauncherScript(readBounded(file));
        } catch (IOException unreadable) {
            return false;
        }
    }

    static boolean isOwnedLinuxDesktop(Path file) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            String content = readBounded(file);
            return content.contains("[Desktop Entry]")
                    && (content.contains("Preflight")
                            || content.contains("starsector-preflight")
                            || content.contains("dev.starsector.preflight"));
        } catch (IOException unreadable) {
            return false;
        }
    }

    static boolean isOwnedWindowsCommand(Path file) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            return isPreflightWindowsScript(readBounded(file));
        } catch (IOException unreadable) {
            return false;
        }
    }

    static boolean isOwnedWindowsDirectory(Path dir) {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> entries = stream.toList();
            if (entries.isEmpty()) {
                // Empty directory at the conventional path is not strong ownership proof.
                return false;
            }
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (name.equals("Preflight.cmd") || name.equals("Starsector Preflight.cmd")) {
                    if (!isOwnedWindowsCommand(entry)) {
                        return false;
                    }
                } else if (name.equals("desktop.ini") || name.equals(".DS_Store")) {
                    // Benign platform metadata
                } else {
                    return false;
                }
            }
            return true;
        } catch (IOException unreadable) {
            return false;
        }
    }

    static boolean isPreflightLauncherScript(String script) {
        return (script.startsWith("#!/bin/sh") || script.startsWith("#!/bin/bash"))
                && (script.contains("preflight")
                        || script.contains("starsector")
                        || script.contains("run --fast"));
    }

    static boolean isPreflightWindowsScript(String script) {
        String head = script.length() >= 10 ? script.substring(0, 10) : script;
        return (head.equalsIgnoreCase("@echo off") || script.toLowerCase().startsWith("@echo off"))
                && (script.contains("preflight")
                        || script.contains("starsector")
                        || script.contains("run --fast"));
    }

    private static String readBounded(Path file) throws IOException {
        long size = Files.size(file);
        if (size > MAX_SCRIPT_SCAN_BYTES) {
            return "";
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
