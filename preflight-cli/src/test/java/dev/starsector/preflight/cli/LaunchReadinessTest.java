package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.PreparedAudioCache;
import dev.starsector.preflight.core.PreparedAudioManifest;
import dev.starsector.preflight.core.PreparedAudioManifestIO;
import dev.starsector.preflight.core.ResourceIndex;
import dev.starsector.preflight.core.ResourceIndexIO;
import dev.starsector.preflight.core.TextureManifest;
import dev.starsector.preflight.core.TextureManifestIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LaunchReadinessTest {
    @TempDir
    Path temporaryDirectory;

    private Path game;
    private Path launcher;
    private Path cache;
    private ResourceIndex current;

    @BeforeEach
    void prepareCurrentTextureFixture() throws Exception {
        game = temporaryDirectory.resolve("game");
        launcher = game.resolve("starsector.exe");
        cache = temporaryDirectory.resolve("cache");
        Files.createDirectories(game.resolve("starsector-core/graphics"));
        Files.createDirectories(game.resolve("mods"));
        Files.writeString(game.resolve("starsector-core/graphics/test.png"), "texture");
        Files.writeString(game.resolve("mods/enabled_mods.json"), "{\"enabledMods\":[]}");
        Files.writeString(launcher, "fixture launcher");
        try (JarOutputStream ignored = new JarOutputStream(
                Files.newOutputStream(game.resolve("starsector-core/starfarer_obf.jar")))) {
            // A valid empty jar is enough for the identity fixture.
        }

        current = ResourceIndexBuilder.build(game).index();
        ResourceIndexIO.write(
                cache.resolve("resource-indexes/" + current.profileFingerprint() + ".spfi"),
                current);
        TextureManifestIO.write(
                cache.resolve("manifests/" + current.profileFingerprint() + ".spfm"),
                new TextureManifest(current.profileFingerprint(), Map.of()));
    }

    @Test
    void legacyAudioManifestMakesFullyPreparedRecommendedUnreadyThenCurrentAfterRefresh() throws Exception {
        Files.createDirectories(PreparedAudioCache.root(cache));

        LaunchReadiness.Report stale = inspect(recommended());

        assertFalse(stale.readyForMeasurement());
        assertEquals("current", component(stale, "prepared-textures").state());
        assertEquals("stale", component(stale, "prepared-audio").state());
        assertEquals("byte-hash", component(stale, "prepared-audio").fallbackState());
        assertEquals(List.of("prepared-audio"), stale.refreshActions().stream()
                .map(LaunchReadiness.RefreshAction::component)
                .toList());
        assertFalse(stale.launchCondition().intendedEqualsEffective());

        writeCurrentAudioManifest();
        LaunchReadiness.Report refreshed = inspect(recommended());

        assertTrue(refreshed.readyForMeasurement());
        assertEquals("current", component(refreshed, "prepared-audio").state());
        assertEquals(List.of(), refreshed.refreshActions());
        assertTrue(refreshed.launchCondition().intendedEqualsEffective());
        assertEquals(Boolean.TRUE,
                refreshed.launchCondition().effectivePolicies()
                        .get("windowsRecommendedValidatedPreparedAudio"));
    }

    @Test
    void fullyCurrentPreparedDataRequestsNoRefresh() throws Exception {
        writeCurrentAudioManifest();

        LaunchReadiness.Report report = inspect(recommended());

        assertTrue(report.readyForMeasurement());
        assertEquals(List.of(), report.refreshActions());
        assertEquals("current", component(report, "prepared-textures").state());
        assertEquals("current", component(report, "prepared-audio").state());
    }

    @Test
    void explicitPreparedAudioPartialConditionStaysReadyAndLabelled() throws Exception {
        Files.createDirectories(PreparedAudioCache.root(cache));
        CommandLine partial = CommandLine.parse(new String[] {
                "run",
                "--optimization-preset", "recommended",
                "--disable-optimization-domain", "prepared-audio",
                "--no-record",
                "--no-scan"
        }, 1);

        LaunchReadiness.Report report = inspect(partial);

        assertTrue(report.readyForMeasurement());
        LaunchReadiness.Component audio = component(report, "prepared-audio");
        assertEquals("disabled", audio.state());
        assertFalse(audio.required());
        assertTrue(audio.intentionalPartial());
        assertEquals(List.of(), report.refreshActions());
        assertTrue(report.launchCondition().intended().contains("prepared-audio-disabled-explicit"));
        assertTrue(report.launchCondition().intendedEqualsEffective());
    }

    private LaunchReadiness.Report inspect(CommandLine options) throws Exception {
        Path engine = temporaryDirectory.resolve("preflight.jar");
        if (!Files.exists(engine)) Files.writeString(engine, "fixture engine");
        RunIdentity identity = new RunIdentity(engine, Hashes.sha256(engine), Map.of());
        LaunchTarget target = new LaunchTarget(
                game, launcher, game, List.of(launcher.toString()), "fixture", 100, "test");
        return LaunchReadiness.inspect(Platform.WINDOWS, options, target, cache, identity);
    }

    private CommandLine recommended() {
        return CommandLine.parse(new String[] {
                "run", "--optimization-preset", "recommended", "--no-record", "--no-scan"
        }, 1);
    }

    private void writeCurrentAudioManifest() throws Exception {
        List<Path> jars = PrepareAudioCommand.jars(game);
        PreparedAudioManifestIO.write(
                PreparedAudioCache.manifestDirectory(cache)
                        .resolve(current.profileFingerprint() + ".spam"),
                new PreparedAudioManifest(
                        current.profileFingerprint(),
                        PrepareAudioCommand.starsectorBuildIdentity(jars),
                        PrepareAudioCommand.decoderPolicyIdentity(jars),
                        Map.of()));
    }

    private static LaunchReadiness.Component component(LaunchReadiness.Report report, String id) {
        return report.components().stream()
                .filter(component -> component.id().equals(id))
                .findFirst()
                .orElseThrow();
    }
}
