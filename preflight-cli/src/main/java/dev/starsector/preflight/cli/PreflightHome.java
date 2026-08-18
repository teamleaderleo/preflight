package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every location Preflight writes to, named in one place.
 *
 * <p>This exists so that "what did this tool put on my machine, and how do I get rid of it" has a
 * single answer that cannot drift from the code that creates those files. {@link InstallCommand}
 * creates the integrations listed here and {@code UninstallCommand} removes exactly this list, so a
 * new integration that forgets to appear here is a leak that the uninstall test will catch.
 *
 * <p><b>Ordinary preparation and launch never write to the Starsector installation.</b> Preflight
 * reads and hashes the game's files; every cache artifact lands under {@link #root()} or in an
 * output directory the operator named. The one explicit exception is confirmed named-profile
 * activation, which stages and replaces {@code mods/enabled_mods.json} after saving the original
 * under {@link #profileBackups()}. The launch-settings command also updates Starsector's own small
 * cross-platform preference store after writing the selected old values under
 * {@link #launcherPreferenceBackups()}. An explicit heap-size change similarly backs up the exact
 * launcher file under {@link #launcherFileBackups()}. Uninstall does not restore these explicit
 * user choices.
 *
 * <p><b>On directory choice.</b> One dot-directory under the user's home is used on all three
 * platforms rather than the platform-idiomatic split (macOS {@code ~/Library/Caches} plus
 * {@code ~/Library/Application Support}, XDG {@code ~/.cache} plus {@code ~/.local/share}, Windows
 * {@code %LOCALAPPDATA%}). The artifacts here are not separable into "cache" and "data": a run
 * directory holds evidence the operator may want to keep next to the cache artifacts that produced
 * it, and a cache purge that silently discarded measurement evidence would be worse than an
 * unidiomatic path. The launcher integrations do follow each platform's convention, because those
 * are the files the operating system itself has to find.
 */
record PreflightHome(Path root, List<Integration> integrations) {
    static final String DIRECTORY_NAME = ".starsector-preflight";

    /** Identifies one integration so the installer and the uninstaller name the same path. */
    enum Id {
        MAC_APP,
        LEGACY_MAC_APP,
        LINUX_COMMAND,
        LINUX_DESKTOP_ENTRY,
        LEGACY_LINUX_COMMAND,
        LEGACY_LINUX_DESKTOP_ENTRY,
        WINDOWS_DIRECTORY,
        WINDOWS_COMMAND,
        LEGACY_WINDOWS_DIRECTORY,
        LEGACY_WINDOWS_COMMAND
    }

    /** A file or directory Preflight wrote outside {@link #root()} so the OS could find it. */
    record Integration(Id id, String label, Path path, boolean directory, boolean legacy) {
        boolean present() {
            return directory
                    ? Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                    : Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
        }

        /** True only when structural markers prove Preflight created this integration. */
        boolean isOwned() {
            return IntegrationOwnership.isOwned(this);
        }
    }

    static PreflightHome resolve(Platform platform, Path home, Map<String, String> environment) {
        Path root = home.resolve(DIRECTORY_NAME).toAbsolutePath().normalize();
        List<Integration> integrations = defaultIntegrations(platform, home, environment);
        Path receipt = root.resolve("integrations.json");
        if (Files.isRegularFile(receipt, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Map<String, Object> json = StrictJson.object(Files.readString(receipt, StandardCharsets.UTF_8));
                Object list = json.get("integrations");
                if (list instanceof List<?> entries) {
                    Map<Id, Path> recorded = new HashMap<>();
                    for (Object entry : entries) {
                        if (entry instanceof Map<?, ?> item) {
                            Object idVal = item.get("id");
                            Object pathVal = item.get("path");
                            if (idVal instanceof String idStr && pathVal instanceof String pathStr) {
                                try {
                                    Id id = Id.valueOf(idStr);
                                    recorded.put(id, Path.of(pathStr));
                                } catch (IllegalArgumentException ignored) {
                                }
                            }
                        }
                    }
                    if (!recorded.isEmpty()) {
                        List<Integration> updated = new ArrayList<>();
                        for (Integration integration : integrations) {
                            Path custom = recorded.get(integration.id());
                            if (custom != null && !custom.equals(integration.path())) {
                                updated.add(new Integration(
                                        integration.id(),
                                        integration.label(),
                                        custom,
                                        integration.directory(),
                                        integration.legacy()));
                            } else {
                                updated.add(integration);
                            }
                        }
                        integrations = updated;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return new PreflightHome(root, List.copyOf(integrations));
    }

    private static List<Integration> defaultIntegrations(
            Platform platform, Path home, Map<String, String> environment) {
        List<Integration> integrations = new ArrayList<>();
        switch (platform) {
            case MAC -> {
                integrations.add(new Integration(
                        Id.MAC_APP,
                        "macOS launcher app",
                        home.resolve("Applications").resolve("Preflight.app"),
                        true,
                        false));
                integrations.add(new Integration(
                        Id.LEGACY_MAC_APP,
                        "legacy macOS launcher app",
                        home.resolve("Applications").resolve("Starsector Preflight.app"),
                        true,
                        true));
            }
            case LINUX -> {
                integrations.add(new Integration(
                        Id.LINUX_COMMAND,
                        "command",
                        home.resolve(".local").resolve("bin").resolve("preflight"),
                        false,
                        false));
                integrations.add(new Integration(
                        Id.LINUX_DESKTOP_ENTRY,
                        "desktop entry",
                        home.resolve(".local").resolve("share").resolve("applications")
                                .resolve("preflight.desktop"),
                        false,
                        false));
                integrations.add(new Integration(
                        Id.LEGACY_LINUX_COMMAND,
                        "legacy command",
                        home.resolve(".local").resolve("bin").resolve("starsector-preflight"),
                        false,
                        true));
                integrations.add(new Integration(
                        Id.LEGACY_LINUX_DESKTOP_ENTRY,
                        "legacy desktop entry",
                        home.resolve(".local").resolve("share").resolve("applications")
                                .resolve("starsector-preflight.desktop"),
                        false,
                        true));
            }
            case WINDOWS -> {
                String localAppData = environment.get("LOCALAPPDATA");
                Path directory = localAppData == null || localAppData.isBlank()
                        ? home.resolve("AppData").resolve("Local").resolve("Preflight")
                        : Path.of(localAppData).resolve("Preflight");
                integrations.add(new Integration(
                        Id.WINDOWS_COMMAND,
                        "Windows launcher",
                        directory.resolve("Preflight.cmd"),
                        false,
                        false));
                integrations.add(new Integration(
                        Id.WINDOWS_DIRECTORY, "Windows launcher directory", directory, true, false));
                Path legacyDirectory = localAppData == null || localAppData.isBlank()
                        ? home.resolve("AppData").resolve("Local").resolve("Starsector Preflight")
                        : Path.of(localAppData).resolve("Starsector Preflight");
                integrations.add(new Integration(
                        Id.LEGACY_WINDOWS_COMMAND,
                        "legacy Windows launcher",
                        legacyDirectory.resolve("Starsector Preflight.cmd"),
                        false,
                        true));
                integrations.add(new Integration(
                        Id.LEGACY_WINDOWS_DIRECTORY,
                        "legacy Windows launcher directory",
                        legacyDirectory,
                        true,
                        true));
            }
            case OTHER -> {
                // installMac/installLinux/installWindows are the only writers, and none of them run
                // here; InstallCommand prints the manual java -jar invocation instead.
            }
        }
        return integrations;
    }

    /**
     * Persist the paths actually installed so uninstall can find them even if LOCALAPPDATA (or
     * equivalent) later differs from the install-time environment.
     *
     * <p>Best-effort: a receipt write failure must not turn a successful install into a hard error.
     * Absence of a receipt falls back to conventional paths plus structural ownership checks.
     */
    void recordInstalledIntegrations() {
        try {
            SafetyArtifactRetention.requireRealDirectory(root);
            Path receipt = root.resolve("integrations.json");
            List<Map<String, Object>> list = new ArrayList<>();
            for (Integration integration : integrations) {
                if (!integration.present()) {
                    continue;
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", integration.id().name());
                entry.put("path", integration.path().toAbsolutePath().normalize().toString());
                entry.put("label", integration.label());
                entry.put("directory", integration.directory());
                entry.put("legacy", integration.legacy());
                list.add(entry);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("format", "preflight-integrations-v1");
            data.put("integrations", list);
            String json = Json.object(data) + "\n";
            Path temporary = receipt.resolveSibling(
                    receipt.getFileName() + ".tmp-" + ProcessHandle.current().pid() + "-" + System.nanoTime());
            try {
                Files.writeString(
                        temporary,
                        json,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
                try {
                    Files.move(temporary, receipt, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, receipt, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException ignored) {
        }
    }

    /**
     * The integration with this id.
     *
     * <p>{@link InstallCommand} writes to these paths and {@code UninstallCommand} removes them, so
     * asking for them by id is what keeps the two from drifting apart. An id the current platform
     * does not define is a programming error, not a runtime condition.
     */
    Path pathOf(Id id) {
        return integration(id).path();
    }

    Integration integration(Id id) {
        for (Integration integration : integrations) {
            if (integration.id() == id) {
                return integration;
            }
        }
        throw new IllegalStateException("No " + id + " integration on this platform");
    }

    /** Current integrations plus any legacy launcher that is actually present. */
    List<Integration> reportedIntegrations() {
        return integrations.stream()
                .filter(integration -> !integration.legacy() || integration.present())
                .toList();
    }

    static PreflightHome current() {
        return resolve(
                Platform.current(),
                Path.of(System.getProperty("user.home")),
                System.getenv());
    }

    /** The installed copy of the jar, which the integrations invoke. */
    Path installedJar() {
        return root.resolve("bin").resolve("preflight.jar");
    }

    Path cache() {
        return root.resolve("cache");
    }

    Path runs() {
        return root.resolve("runs");
    }

    /** Cross-entrypoint ownership and recovery records. */
    Path state() {
        return root.resolve("state");
    }

    Path benchmarks() {
        return root.resolve("benchmarks");
    }

    Path profiles() {
        return root.resolve("profiles");
    }

    Path profileBackups() {
        return root.resolve("profile-backups");
    }

    /** Snapshots of the small preference set changed through the launch-settings UI. */
    Path launcherPreferenceBackups() {
        return root.resolve("launcher-preference-backups");
    }

    /** Exact original bytes of a launcher or VM-parameter file changed through the settings UI. */
    Path launcherFileBackups() {
        return root.resolve("launcher-file-backups");
    }
}
