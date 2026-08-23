package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.agent.AdapterMode;
import dev.starsector.preflight.agent.AdapterPlanScope;
import dev.starsector.preflight.agent.RecordingMode;
import dev.starsector.preflight.agent.TextureAdapterMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class AgentInjectionTest {
    @Test
    void encodesTheFullNamedConfigInReviewedByteOrder() {
        Path agent = Path.of("agent.jar");
        Path recording = Path.of("run/startup.jfr");
        Path report = Path.of("run/adapter.json");
        Path targets = Path.of("targets.json");
        Path textureCache = Path.of("cache/textures");
        Path textureManifest = Path.of("cache/textures/manifest.sptm");
        Path textureIndex = Path.of("cache/resource-indexes/profile.spfi");
        String textureProfile = "ab".repeat(32);
        Path variant = Path.of("cache/variant.json");
        Path weapon = Path.of("cache/weapon.json");
        Path projectile = Path.of("cache/projectile.json");
        Path hull = Path.of("cache/hull.json");
        Path rules = Path.of("cache/rules.json");
        Path ruleCommands = Path.of("cache/rule-commands.json");
        Path audio = Path.of("cache/audio");
        Path audioManifest = Path.of("cache/audio/profile.spam");
        Path mergedRead = Path.of("cache/merged-read.json");
        Path magicPaintjobs = Path.of("cache/magic-paintjobs/profile.spmp");
        Path janino = Path.of("cache/janino");

        AgentLaunchConfig config = AgentLaunchConfig.builder(agent, recording)
                .adapterMode(AdapterMode.ENABLED)
                .adapterReport(report)
                .adapterTargets(targets)
                .textureCacheDirectory(textureCache)
                .textureProfile(textureProfile)
                .textureManifest(textureManifest)
                .textureIndex(textureIndex)
                .textureAdapterMode(TextureAdapterMode.PREPARED_PIXELS)
                .exhaustiveFileReads(true)
                .recordingMode(RecordingMode.SAMPLE)
                .npotDirect(true)
                .unpadded(true)
                .singleChunkRecording(true)
                .campaignEntityIndex(true)
                .startupPhaseProbe(true)
                .variantJsonCache(variant)
                .weaponJsonCache(weapon)
                .projectileJsonCache(projectile)
                .hullJsonCache(hull)
                .rulesCsvCache(rules)
                .ruleTokenCache(true)
                .ruleCommandClassCache(ruleCommands)
                .resourceProbeCache(true)
                .loadJsonMemo(true)
                .preparedAudioCache(audio)
                .audioDecoderIdentity("decoder-id")
                .preparedAudioManifest(audioManifest)
                .preparedAudioManifestIdentity("manifest-id")
                .mergedReadCache(mergedRead)
                .magicPaintjobCache(magicPaintjobs)
                .quietLogs(true)
                .graphicsLibCompactReplay(true)
                .janinoBytecodeCache(janino)
                .janinoBytecodeContext("janino-context")
                .graphicsLibInsigniaManagerCache(true)
                .adapterPlanScope(AdapterPlanScope.PORTABLE_STARTUP)
                .build();

        String expected = "-Dexisting=true -javaagent:"
                + agent.toAbsolutePath().normalize()
                + "=dest64=" + encodedPath(recording)
                + ",adapter=enabled"
                + ",planScope=portable-startup"
                + ",textureMode=prepared-pixels"
                + ",adapterReport64=" + encodedPath(report)
                + ",targets64=" + encodedPath(targets)
                + ",textureCache64=" + encodedPath(textureCache)
                + ",textureProfile=" + textureProfile
                + ",textureManifest64=" + encodedPath(textureManifest)
                + ",textureIndex64=" + encodedPath(textureIndex)
                + ",variantJsonCache64=" + encodedPath(variant)
                + ",weaponJsonCache64=" + encodedPath(weapon)
                + ",projectileJsonCache64=" + encodedPath(projectile)
                + ",hullJsonCache64=" + encodedPath(hull)
                + ",rulesCsvCache64=" + encodedPath(rules)
                + ",ruleCommandCache64=" + encodedPath(ruleCommands)
                + ",mergedReadCache64=" + encodedPath(mergedRead)
                + ",magicPaintjobCache64=" + encodedPath(magicPaintjobs)
                + ",janinoBytecodeCache64=" + encodedPath(janino)
                + ",janinoBytecodeContext=janino-context"
                + ",fileReads=all"
                + ",record=sample"
                + ",flush=0"
                + ",startupPhases=on"
                + ",ruleTokens=on"
                + ",resourceProbes=on"
                + ",loadJsonMemo=on"
                + ",quietLogs=on"
                + ",graphicsLibCompactReplay=on"
                + ",graphicsLibInsigniaManagerCache=on"
                + ",preparedAudioCache64=" + encodedPath(audio)
                + ",audioDecoder=decoder-id"
                + ",preparedAudioManifest64=" + encodedPath(audioManifest)
                + ",preparedAudioManifestIdentity=manifest-id"
                + " -Dpreflight.preparedPixels.coherentDirect=true"
                + " -Dpreflight.padding.unpadded=true"
                + " -XX:FlightRecorderOptions=memorysize=256m,maxchunksize=256m"
                + " -Dpreflight.campaign.entityIndex=true"
                + " -Dpreflight.deployment.iconCache=true"
                + " -Dpreflight.campaign.eventModMemo=true";

        assertEquals(expected, AgentInjection.append("-Dexisting=true", config));
    }

    @Test
    void preservesExistingOptionsAndAppendsOneAgent() {
        String value = append("-Xmx4g -Dexample=true", builder -> { });

        assertTrue(value.startsWith("-Xmx4g -Dexample=true "));
        assertEquals(1, occurrences(value, "-javaagent:"));
        assertTrue(value.contains("dest64="));
        assertTrue(value.contains("adapter=off"));
        assertTrue(value.contains("planScope=full"));
        assertTrue(value.contains("textureMode=compatibility"));
    }

    @Test
    void carriesTheEngineOwnedPlanScopeWithoutExposingPlanIds() {
        String value = append(builder -> builder
                .adapterMode(AdapterMode.ENABLED)
                .adapterReport(Path.of("adapter.json"))
                .recordingMode(RecordingMode.OFF)
                .adapterPlanScope(AdapterPlanScope.PORTABLE_STARTUP));

        assertTrue(value.contains("planScope=portable-startup"), value);
    }

    @Test
    void onlyStatesRecordOffWhenTheCallerAsksForIt() {
        // The agent treats any value other than "off" as recording, so the absence of this
        // token has to mean recording -- an accidental token would silently drop the profile.
        String recording = append(builder -> textureConfig(builder).recordingMode(RecordingMode.FULL));
        assertFalse(recording.contains("record="));

        String silent = append(builder -> textureConfig(builder).recordingMode(RecordingMode.OFF));
        assertTrue(silent.contains(",record=off"));
        // The adapter still has to be configured; turning the profile off is not turning
        // Preflight off, and the caches are the whole point of the mode.
        assertTrue(silent.contains("adapter=enabled"));
        assertTrue(silent.contains("textureCache64="));
        assertTrue(silent.contains("textureManifest64="));

        // Sampling has to reach the agent as its own value. Falling back to either neighbour
        // would be quiet and wrong in opposite directions: "full" restores the 24% the profile
        // was meant to avoid, "off" produces a run with no recording to analyse at all.
        String sampled = append(builder -> textureConfig(builder).recordingMode(RecordingMode.SAMPLE));
        assertTrue(sampled.contains(",record=sample"), sampled);
        assertFalse(sampled.contains("record=off"), sampled);

        // The non-power-of-two escape hatch is a system property, not an agent option: the
        // runtime reads it per texture through Boolean.getBoolean. It has to land on the JVM
        // command line, so a test that only checked the agent argument string would pass while
        // the bridge went on declining every padded texture.
        String npot = append(builder -> textureConfig(builder)
                .textureAdapterMode(TextureAdapterMode.PREPARED_PIXELS)
                .recordingMode(RecordingMode.OFF)
                .npotDirect(true));
        assertTrue(npot.contains(" -Dpreflight.preparedPixels.coherentDirect=true"), npot);

        String withoutNpot = append(builder -> textureConfig(builder)
                .textureAdapterMode(TextureAdapterMode.PREPARED_PIXELS)
                .recordingMode(RecordingMode.OFF));
        assertFalse(withoutNpot.contains("coherentDirect"), withoutNpot);

        // Padding removal is the other way to carry a padded texture, and it has to reach the
        // JVM the same way. It is what makes the prepared path serve true-size bytes while the
        // allocation shrinks to match; the two halves read this one property.
        String unpadded = append(builder -> textureConfig(builder)
                .textureAdapterMode(TextureAdapterMode.PREPARED_PIXELS)
                .recordingMode(RecordingMode.OFF)
                .unpadded(true));
        assertTrue(unpadded.contains(" -Dpreflight.padding.unpadded=true"), unpadded);
        assertFalse(unpadded.contains("coherentDirect"), unpadded);
    }

    @Test
    void singleChunkModeDisablesDumpRotationAndRaisesTheJfrChunkLimit() {
        String value = append(builder -> builder
                .recordingMode(RecordingMode.SAMPLE)
                .singleChunkRecording(true));

        assertTrue(value.contains(",record=sample"), value);
        assertTrue(value.contains(",flush=0"), value);
        assertTrue(value.contains(
                " -XX:FlightRecorderOptions=memorysize=256m,maxchunksize=256m"), value);
    }

    @Test
    void campaignEntityIndexBecomesAnEnabledAdapterSystemProperty() {
        String enabled = append(builder -> enabledAdapter(builder)
                .recordingMode(RecordingMode.OFF)
                .campaignEntityIndex(true));

        assertTrue(enabled.contains(" -Dpreflight.campaign.entityIndex=true"), enabled);
        assertTrue(enabled.contains(" -Dpreflight.deployment.iconCache=true"), enabled);
        assertTrue(enabled.contains(" -Dpreflight.campaign.eventModMemo=true"), enabled);
    }

    @Test
    void startupPhaseProbeBecomesAnExplicitAgentOption() {
        String enabled = append(builder -> enabledAdapter(builder)
                .recordingMode(RecordingMode.OFF)
                .startupPhaseProbe(true));

        assertTrue(enabled.contains(",startupPhases=on"), enabled);
    }

    @Test
    void variantJsonCacheReachesTheEnabledAdapter() {
        String enabled = append(builder -> enabledAdapter(builder)
                .recordingMode(RecordingMode.OFF)
                .variantJsonCache(Path.of("cache", "profile.spvj")));

        assertTrue(enabled.contains(",variantJsonCache64="), enabled);
    }

    @Test
    void weaponJsonCacheReachesTheEnabledAdapter() {
        String enabled = append(builder -> enabledAdapter(builder)
                .recordingMode(RecordingMode.OFF)
                .weaponJsonCache(Path.of("cache", "profile.spwj")));

        assertTrue(enabled.contains(",weaponJsonCache64="), enabled);
    }

    @Test
    void projectileJsonCacheReachesTheEnabledAdapter() {
        String enabled = append(builder -> enabledAdapter(builder)
                .recordingMode(RecordingMode.OFF)
                .projectileJsonCache(Path.of("cache", "profile.sppj")));

        assertTrue(enabled.contains(",projectileJsonCache64="), enabled);
    }

    @Test
    void hullJsonCacheReachesTheEnabledAdapter() {
        String enabled = append(builder -> enabledAdapter(builder)
                .recordingMode(RecordingMode.OFF)
                .hullJsonCache(Path.of("cache", "profile.sphj")));

        assertTrue(enabled.contains(",hullJsonCache64="), enabled);
    }

    @Test
    void rulesCsvCacheReachesTheEnabledAdapter() {
        String enabled = append(builder -> enabledAdapter(builder)
                .recordingMode(RecordingMode.OFF)
                .rulesCsvCache(Path.of("cache", "profile.sprc")));

        assertTrue(enabled.contains(",rulesCsvCache64="), enabled);
    }

    @Test
    void ruleCommandClassCacheReachesTheEnabledAdapter() {
        String enabled = append(builder -> enabledAdapter(builder)
                .recordingMode(RecordingMode.OFF)
                .ruleCommandClassCache(Path.of("cache", "profile.sprk")));

        assertTrue(enabled.contains(",ruleCommandCache64="), enabled);
    }

    @Test
    void graphicsLibCompactReplayReachesTheEnabledAdapter() {
        String enabled = append(builder -> enabledAdapter(builder)
                .recordingMode(RecordingMode.OFF)
                .graphicsLibCompactReplay(true));

        assertTrue(enabled.contains(",graphicsLibCompactReplay=on"), enabled);
    }

    @Test
    void graphicsLibInsigniaCacheReachesTheEnabledAdapter() {
        String enabled = append(builder -> enabledAdapter(builder)
                .recordingMode(RecordingMode.OFF)
                .graphicsLibInsigniaManagerCache(true));

        assertTrue(enabled.contains(",graphicsLibInsigniaManagerCache=on"), enabled);
    }

    @Test
    void janinoBytecodeCacheCarriesItsExactContext() {
        String token = String.join(".",
                "01".repeat(32), "02".repeat(32), "03".repeat(32), "04".repeat(32),
                "05".repeat(32), "06".repeat(32), "07".repeat(32));
        String enabled = append(builder -> enabledAdapter(builder)
                .recordingMode(RecordingMode.OFF)
                .janinoBytecodeCache(Path.of("cache"))
                .janinoBytecodeContext(token));

        assertTrue(enabled.contains(",janinoBytecodeCache64="), enabled);
        assertTrue(enabled.contains(",janinoBytecodeContext=" + token), enabled);
    }

    @Test
    void includesProbeReportAndTargetPaths() {
        String value = append(builder -> builder
                .adapterMode(AdapterMode.PROBE)
                .adapterReport(Path.of("adapter reports", "probe.json"))
                .adapterTargets(Path.of("adapter targets", "vanilla.txt")));

        assertTrue(value.contains("adapter=probe"));
        assertTrue(value.contains("adapterReport64="));
        assertTrue(value.contains("targets64="));
        assertEquals(1, occurrences(value, "-javaagent:"));
    }

    @Test
    void includesPreparedPixelModeAndTexturePaths() {
        String value = append(builder -> enabledAdapter(builder)
                .textureCacheDirectory(Path.of("cache"))
                .textureManifest(Path.of("cache", "manifests", "profile.spfm"))
                .textureIndex(Path.of("cache", "indexes", "profile.spfi"))
                .textureAdapterMode(TextureAdapterMode.PREPARED_PIXELS));

        assertTrue(value.contains("adapter=enabled"));
        assertTrue(value.contains("textureMode=prepared-pixels"));
        assertTrue(value.contains("textureCache64="));
        assertTrue(value.contains("textureManifest64="));
        assertTrue(value.contains("textureIndex64="));
    }

    @Test
    void quotesAgentPathsContainingSpaces() {
        Path agent = Path.of("preflight build", "preflight.jar").toAbsolutePath().normalize();
        AgentLaunchConfig config = AgentLaunchConfig.builder(agent, Path.of("startup trace.jfr")).build();

        String value = AgentInjection.append("", config);

        assertTrue(value.startsWith("-javaagent:\"" + agent + "\"="));
    }

    @Test
    void rejectsDuplicatePreflightAgent() {
        AgentLaunchConfig config =
                AgentLaunchConfig.builder(Path.of("preflight.jar"), Path.of("startup.jfr")).build();

        assertThrows(
                IllegalArgumentException.class,
                () -> AgentInjection.append("-javaagent:preflight.jar", config));
    }

    private static String append(Consumer<AgentLaunchConfig.Builder> configure) {
        return append("", configure);
    }

    private static String append(
            String existing,
            Consumer<AgentLaunchConfig.Builder> configure) {
        AgentLaunchConfig.Builder builder =
                AgentLaunchConfig.builder(Path.of("preflight.jar"), Path.of("startup.jfr"));
        configure.accept(builder);
        return AgentInjection.append(existing, builder.build());
    }

    private static AgentLaunchConfig.Builder enabledAdapter(AgentLaunchConfig.Builder builder) {
        return builder
                .adapterMode(AdapterMode.ENABLED)
                .adapterReport(Path.of("adapter.json"));
    }

    private static AgentLaunchConfig.Builder textureConfig(AgentLaunchConfig.Builder builder) {
        return enabledAdapter(builder)
                .textureCacheDirectory(Path.of("cache"))
                .textureManifest(Path.of("m.spfm"))
                .textureIndex(Path.of("i.spfi"))
                .textureAdapterMode(TextureAdapterMode.COMPATIBILITY);
    }

    private static String encodedPath(Path value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
    }

    private static int occurrences(String text, String token) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
    }
}
