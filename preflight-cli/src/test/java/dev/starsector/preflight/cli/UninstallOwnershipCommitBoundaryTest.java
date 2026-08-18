package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UninstallOwnershipCommitBoundaryTest {
    @TempDir
    Path home;

    @Test
    void regularIntegrationReplacementAfterReviewIsPreservedEvenWithSameSizeAndMtime() throws Exception {
        PreflightHome preflight = linuxHome();
        Path command = preflight.pathOf(PreflightHome.Id.LINUX_COMMAND);
        writeOwnedLinuxCommand(command);
        long size = Files.size(command);
        FileTime modified = Files.getLastModifiedTime(command);
        String original = Files.readString(command);
        String replacement = original.replace(IntegrationOwnership.POSIX_MARKER,
                "# external-integration: dev.starsector.preflight.launcher-v1");
        replacement = padToBytes(replacement, size);

        try (UninstallCommand.RemovalTestHookScope ignored = UninstallCommand.installRemovalTestHook(
                (event, integration, path) -> {
                    if (event == UninstallCommand.RemovalEvent.AFTER_REVIEW
                            && integration.id() == PreflightHome.Id.LINUX_COMMAND) {
                        Files.delete(command);
                        Files.writeString(command, replacement, StandardCharsets.UTF_8);
                        Files.setLastModifiedTime(command, modified);
                    }
                })) {
            assertEquals(1, UninstallCommand.run(preflight, false, true, quiet()));
        }

        assertTrue(Files.isRegularFile(command));
        assertEquals(replacement, Files.readString(command));
        assertEquals(size, Files.size(command));
        assertEquals(modified, Files.getLastModifiedTime(command));
    }

    @Test
    void changedMacBundleAfterReviewIsRestoredUntouched() throws Exception {
        PreflightHome preflight = macHome();
        Path app = preflight.pathOf(PreflightHome.Id.MAC_APP);
        writeOwnedMacBundle(app, false);
        Path external = app.resolve("external.txt");

        try (UninstallCommand.RemovalTestHookScope ignored = UninstallCommand.installRemovalTestHook(
                (event, integration, path) -> {
                    if (event == UninstallCommand.RemovalEvent.AFTER_REVIEW
                            && integration.id() == PreflightHome.Id.MAC_APP) {
                        Files.writeString(external, "external generation");
                    }
                })) {
            assertEquals(1, UninstallCommand.run(preflight, false, true, quiet()));
        }

        assertTrue(Files.isRegularFile(external));
        assertEquals("external generation", Files.readString(external));
        assertTrue(Files.isRegularFile(app.resolve("Contents/MacOS/preflight")));
    }

    @Test
    void unexpectedChildInsideAnchoredBundleIsNeverRecursivelyDeleted() throws Exception {
        PreflightHome preflight = macHome();
        Path app = preflight.pathOf(PreflightHome.Id.MAC_APP);
        writeOwnedMacBundle(app, false);
        Path[] preserved = new Path[1];

        try (UninstallCommand.RemovalTestHookScope ignored = UninstallCommand.installRemovalTestHook(
                (event, integration, path) -> {
                    if (event == UninstallCommand.RemovalEvent.BEFORE_DELETE
                            && integration.id() == PreflightHome.Id.MAC_APP) {
                        preserved[0] = path.resolve("external.txt");
                        Files.writeString(preserved[0], "keep me");
                    }
                })) {
            assertEquals(1, UninstallCommand.run(preflight, false, true, quiet()));
        }

        assertTrue(preserved[0] != null && Files.isRegularFile(preserved[0]));
        assertEquals("keep me", Files.readString(preserved[0]));
    }

    @Test
    void legacyRetirementPreservesReplacementThatWinsAfterOwnershipCheck() throws Exception {
        PreflightHome preflight = macHome();
        Path legacy = preflight.pathOf(PreflightHome.Id.LEGACY_MAC_APP);
        writeOwnedMacBundle(legacy, true);
        Path executable = legacy.resolve("Contents/MacOS/starsector-preflight");

        try (UninstallCommand.RemovalTestHookScope ignored = UninstallCommand.installRemovalTestHook(
                (event, integration, path) -> {
                    if (event == UninstallCommand.RemovalEvent.AFTER_REVIEW
                            && integration.id() == PreflightHome.Id.LEGACY_MAC_APP) {
                        Files.writeString(executable, "#!/bin/sh\necho external\n");
                    }
                })) {
            InstallCommand.retireLegacyLaunchers(preflight);
        }

        assertTrue(Files.isRegularFile(executable));
        assertEquals("#!/bin/sh\necho external\n", Files.readString(executable));
    }

    @Test
    void partialMultiIntegrationRemovalReportsRemovedAndPreservedTargetsExactly() throws Exception {
        PreflightHome preflight = linuxHome();
        Path command = preflight.pathOf(PreflightHome.Id.LINUX_COMMAND);
        Path desktop = preflight.pathOf(PreflightHome.Id.LINUX_DESKTOP_ENTRY);
        writeOwnedLinuxCommand(command);
        writeOwnedDesktopEntry(desktop);
        ByteArrayOutputStream captured = new ByteArrayOutputStream();

        try (UninstallCommand.RemovalTestHookScope ignored = UninstallCommand.installRemovalTestHook(
                (event, integration, path) -> {
                    if (event == UninstallCommand.RemovalEvent.AFTER_REVIEW
                            && integration.id() == PreflightHome.Id.LINUX_DESKTOP_ENTRY) {
                        Files.writeString(desktop, "[Desktop Entry]\nName=External\nExec=/bin/true\n");
                    }
                });
                PrintStream out = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
            assertEquals(1, UninstallCommand.run(
                    preflight, UninstallCommand.Scope.LAUNCHER, true, false, out));
        }

        String output = captured.toString(StandardCharsets.UTF_8);
        assertFalse(Files.exists(command));
        assertTrue(Files.isRegularFile(desktop));
        assertTrue(output.contains("Removal incomplete:"), output);
        assertTrue(output.contains("Removed before the refusal:"), output);
        assertTrue(output.contains("command"), output);
        assertTrue(output.contains("desktop entry"), output);
        assertTrue(output.contains("reviewed owned generation changed"), output);
    }

    private PreflightHome macHome() {
        return PreflightHome.resolve(Platform.MAC, home, Map.of());
    }

    private PreflightHome linuxHome() {
        return PreflightHome.resolve(Platform.LINUX, home, Map.of());
    }

    private static void writeOwnedLinuxCommand(Path command) throws Exception {
        Files.createDirectories(command.getParent());
        Files.writeString(command,
                "#!/bin/sh\n"
                        + IntegrationOwnership.POSIX_MARKER
                        + "\nexec java -jar /tmp/preflight.jar run --fast --game /games/Starsector \"$@\"\n");
    }

    private static void writeOwnedDesktopEntry(Path desktop) throws Exception {
        Files.createDirectories(desktop.getParent());
        Files.writeString(desktop,
                "[Desktop Entry]\n"
                        + "Name=Preflight\n"
                        + IntegrationOwnership.DESKTOP_MARKER
                        + "\nExec=/home/test/.local/bin/preflight\n");
    }

    private static void writeOwnedMacBundle(Path app, boolean legacy) throws Exception {
        String executableName = legacy ? "starsector-preflight" : "preflight";
        Path contents = app.resolve("Contents");
        Path macos = contents.resolve("MacOS");
        Files.createDirectories(macos);
        Files.writeString(contents.resolve("Info.plist"),
                "<plist><dict>"
                        + "<key>CFBundleIdentifier</key><string>dev.starsector.preflight.launcher</string>"
                        + "<key>CFBundleExecutable</key><string>" + executableName + "</string>"
                        + "</dict></plist>");
        Files.writeString(macos.resolve(executableName),
                "#!/bin/sh\n"
                        + IntegrationOwnership.POSIX_MARKER
                        + "\nexec java -jar /tmp/preflight.jar run --fast --game /games/Starsector \"$@\"\n");
    }

    private static String padToBytes(String value, long size) {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > size) {
            throw new IllegalArgumentException("replacement exceeds original fixture size");
        }
        return value + " ".repeat(Math.toIntExact(size - encoded.length));
    }

    private static PrintStream quiet() {
        return new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    }
}
