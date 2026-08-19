package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.Json;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ProfileActivationPublicationRaceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void externalWriterWinsFinalPublicationAndPlayerGetsFreshReview() throws Exception {
        Fixture fixture = fixture("alpha");
        assertEquals(0, ProfileCommand.save(
                fixture.home(), fixture.game(), "Campaign", true, stream(new ByteArrayOutputStream())));
        writeEnabled(fixture.enabled(), "beta");
        FileTime reviewedMtime = Files.getLastModifiedTime(fixture.enabled());
        long reviewedSize = Files.size(fixture.enabled());

        ByteArrayOutputStream preview = new ByteArrayOutputStream();
        assertEquals(0, ProfileCommand.activate(
                fixture.home(), fixture.game(), "Campaign", false, true, stream(preview)));
        assertTrue(preview.toString(StandardCharsets.UTF_8).contains("\"applied\":false"));

        ByteArrayOutputStream refused = new ByteArrayOutputStream();
        assertEquals(2, ProfileCommand.activate(
                fixture.home(),
                fixture.game(),
                "Campaign",
                true,
                true,
                stream(refused),
                new EnabledModsActivationTransaction.Hook() {
                    @Override
                    public void beforeTargetPublication(Path target) throws java.io.IOException {
                        writeEnabled(target, "zeta");
                        assertEquals(reviewedSize, Files.size(target));
                        Files.setLastModifiedTime(target, reviewedMtime);
                    }
                }));

        assertEquals(List.of("zeta"), enabled(fixture.enabled()));
        assertEquals(reviewedMtime, Files.getLastModifiedTime(fixture.enabled()));
        String refusedJson = refused.toString(StandardCharsets.UTF_8);
        assertTrue(refusedJson.contains("\"sourceChanged\":true"));
        assertTrue(refusedJson.contains("\"reviewChanged\":true"));
        assertTrue(refusedJson.contains("\"applied\":false"));
        assertTrue(refusedJson.contains("\"enable\":[\"alpha\"]"));
        assertTrue(refusedJson.contains("\"disable\":[\"zeta\"]"));
        assertNoTransactionArtifacts(fixture.mods());

        ByteArrayOutputStream applied = new ByteArrayOutputStream();
        assertEquals(0, ProfileCommand.activate(
                fixture.home(), fixture.game(), "Campaign", true, true, stream(applied)));
        assertEquals(List.of("alpha"), enabled(fixture.enabled()));
        assertTrue(applied.toString(StandardCharsets.UTF_8).contains("\"applied\":true"));
        assertTrue(backupContains(fixture.home(), List.of("zeta")));
        assertNoTransactionArtifacts(fixture.mods());
    }

    @Test
    void desktopProfileReadRecoversCaptureInterruptedByRestart() throws Exception {
        Fixture fixture = fixture("alpha");
        assertEquals(0, ProfileCommand.save(
                fixture.home(), fixture.game(), "Campaign", true, stream(new ByteArrayOutputStream())));
        writeEnabled(fixture.enabled(), "beta");
        byte[] reviewed = Files.readAllBytes(fixture.enabled());
        byte[] replacement = enabledBytes("alpha");

        assertThrows(SimulatedCrash.class, () -> EnabledModsActivationTransaction.replace(
                fixture.enabled(),
                reviewed,
                replacement,
                new EnabledModsActivationTransaction.Hook() {
                    @Override
                    public void afterSourceCapture(Path captured) {
                        throw new SimulatedCrash();
                    }
                }));
        assertFalse(Files.exists(fixture.enabled()));

        Map<String, Object> profiles = ProfileCommand.describeList(fixture.home(), fixture.game());

        assertEquals(List.of("beta"), profiles.get("enabledMods"));
        assertEquals(List.of("beta"), enabled(fixture.enabled()));
        assertNoTransactionArtifacts(fixture.mods());
    }

    private Fixture fixture(String enabledMod) throws Exception {
        Path game = Files.createDirectories(temporaryDirectory.resolve("game"));
        Path mods = Files.createDirectories(game.resolve("mods"));
        Path enabled = mods.resolve("enabled_mods.json");
        writeEnabled(enabled, enabledMod);
        createMod(mods, "alpha");
        createMod(mods, "beta");
        createMod(mods, "zeta");
        Path root = temporaryDirectory.resolve("home").resolve(PreflightHome.DIRECTORY_NAME);
        return new Fixture(
                game.toAbsolutePath().normalize(),
                mods,
                enabled,
                new PreflightHome(root.toAbsolutePath().normalize(), List.of()));
    }

    private static void createMod(Path mods, String id) throws Exception {
        Path directory = Files.createDirectories(mods.resolve(id));
        Files.writeString(directory.resolve("mod_info.json"), "{\"id\":\"" + id + "\"}");
    }

    private static void writeEnabled(Path target, String mod) throws java.io.IOException {
        Files.write(target, enabledBytes(mod));
    }

    private static byte[] enabledBytes(String mod) {
        return Json.object(Map.of("enabledMods", List.of(mod))).getBytes(StandardCharsets.UTF_8);
    }

    private static List<String> enabled(Path file) throws Exception {
        return JsonText.stringArray(Files.readString(file), "enabledMods");
    }

    private static boolean backupContains(PreflightHome home, List<String> wanted) throws Exception {
        if (!Files.isDirectory(home.profileBackups())) return false;
        try (var backups = Files.list(home.profileBackups())) {
            for (Path backup : backups.filter(Files::isRegularFile).toList()) {
                if (backup.getFileName().toString().startsWith("enabled_mods-")
                        && enabled(backup).equals(wanted)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void assertNoTransactionArtifacts(Path mods) throws Exception {
        try (var entries = Files.list(mods)) {
            assertTrue(entries.noneMatch(path ->
                    path.getFileName().toString().startsWith(".preflight-enabled-mods-txn-")));
        }
    }

    private static PrintStream stream(ByteArrayOutputStream output) {
        return new PrintStream(output, true, StandardCharsets.UTF_8);
    }

    private record Fixture(Path game, Path mods, Path enabled, PreflightHome home) {}

    private static final class SimulatedCrash extends Error {
    }
}
