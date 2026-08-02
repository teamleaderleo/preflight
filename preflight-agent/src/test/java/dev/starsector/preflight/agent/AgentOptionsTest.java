package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentOptionsTest {
    @Test
    void parsesDestinationSettingsAndSafeAdapterDefaults() {
        AgentOptions options = AgentOptions.parse("dest=build/startup.jfr,settings=default");
        assertEquals(Path.of("build/startup.jfr"), options.destination());
        assertEquals("default", options.settings());
        assertEquals(AdapterMode.OFF, options.adapterMode());
        assertEquals(Path.of("build/adapter.json"), options.adapterReport());
        assertNull(options.adapterTargets());
        assertNull(options.textureCacheDirectory());
        assertNull(options.textureManifest());
        assertNull(options.textureIndex());
        assertEquals(TextureAdapterMode.COMPATIBILITY, options.textureAdapterMode());
        assertNull(options.variantJsonCache());
    }

    @Test
    void parsesEncodedDestinationContainingSpacesAndCommas() {
        String destination = "build/trace directory/startup,one.jfr";
        String encoded = encoded(destination);

        AgentOptions options = AgentOptions.parse("dest64=" + encoded);

        assertEquals(Path.of(destination), options.destination());
    }

    @Test
    void parsesProbeReportTargetsAndEvidenceDrivenCandidatePrefixes() {
        String report = "build/adapter reports/probe.json";
        String targets = "build/targets/vanilla.txt";
        AgentOptions options = AgentOptions.parse(
                "adapter=probe,adapterReport64=" + encoded(report) + ",targets64=" + encoded(targets));

        assertEquals(AdapterMode.PROBE, options.adapterMode());
        assertEquals(Path.of(report), options.adapterReport());
        assertEquals(Path.of(targets), options.adapterTargets());
        assertEquals(
                List.of("com/fs/starfarer/", "com/fs/graphics/"),
                options.candidatePrefixes());
    }

    @Test
    void parsesTextureCacheManifestIndexAndPreparedPixelMode() {
        AgentOptions options = AgentOptions.parse(
                "adapter=enabled"
                        + ",textureMode=prepared-pixels"
                        + ",textureCache64=" + encoded("build/cache")
                        + ",textureManifest64=" + encoded("build/cache/manifests/profile.spfm")
                        + ",textureIndex64=" + encoded("build/cache/indexes/profile.spfi"));

        assertEquals(Path.of("build/cache"), options.textureCacheDirectory());
        assertEquals(Path.of("build/cache/manifests/profile.spfm"), options.textureManifest());
        assertEquals(Path.of("build/cache/indexes/profile.spfi"), options.textureIndex());
        assertEquals(TextureAdapterMode.PREPARED_PIXELS, options.textureAdapterMode());
    }

    @Test
    void recordsStartupUnlessTheCallerTurnsItOff() {
        // Recording costs about 24% of startup on the reviewed installation, which is more
        // than the texture cache saves, so it has to be separable from the adapter. It stays
        // on by default: every analysis command in this repository reads what it produces.
        assertEquals(RecordingMode.FULL, AgentOptions.parse("adapter=enabled").recordingMode());
        assertEquals(RecordingMode.FULL, AgentOptions.parse("").recordingMode());
        assertEquals(RecordingMode.OFF, AgentOptions.parse("adapter=enabled,record=off").recordingMode());
        assertEquals(RecordingMode.OFF, AgentOptions.parse("record=OFF").recordingMode());
        assertTrue(AgentOptions.parse("").recordingMode().records());
        assertFalse(AgentOptions.parse("record=off").recordingMode().records());
    }

    @Test
    void samplingModeIsSelectableAndStillCountsAsRecording() {
        // The sampling profile exists because the full recording stack-traces every class load,
        // which inflates class loading -- the thing a profile is most often asked about.
        assertEquals(RecordingMode.SAMPLE, AgentOptions.parse("record=sample").recordingMode());
        assertEquals(RecordingMode.SAMPLE, AgentOptions.parse("record=SAMPLING").recordingMode());
        assertTrue(AgentOptions.parse("record=sample").recordingMode().records());
    }

    @Test
    void anUnrecognisedRecordValueKeepsRecordingRatherThanSilentlyLosingTheProfile() {
        // Failing open here means an extra recording; failing closed means a run whose
        // profile silently never existed, which is the more expensive mistake.
        assertEquals(RecordingMode.FULL, AgentOptions.parse("record=on").recordingMode());
        assertEquals(RecordingMode.FULL, AgentOptions.parse("record=yes").recordingMode());
        // Notably not SAMPLE: a typo must not quietly drop the class-load events an analysis
        // command may be relying on.
        assertEquals(RecordingMode.FULL, AgentOptions.parse("record=samp").recordingMode());
    }

    @Test
    void rejectsMalformedAndDuplicateOptions() {
        assertThrows(IllegalArgumentException.class, () -> AgentOptions.parse("dest"));
        assertThrows(IllegalArgumentException.class, () -> AgentOptions.parse("adapter=probe,adapter=enabled"));
        assertThrows(IllegalArgumentException.class, () -> AgentOptions.parse("adapter=maybe"));
        assertThrows(IllegalArgumentException.class, () -> AgentOptions.parse("textureMode=unknown"));
    }

    @Test
    void startupPhaseProbeIsExplicitlyOptIn() {
        assertFalse(AgentOptions.parse("adapter=enabled").startupPhaseProbe());
        assertTrue(AgentOptions.parse("adapter=enabled,startupPhases=on").startupPhaseProbe());
    }

    @Test
    void parsesVariantJsonCacheArtifact() {
        AgentOptions options = AgentOptions.parse(
                "adapter=enabled,variantJsonCache64=" + encoded("build/cache/profile.spvj"));
        assertEquals(Path.of("build/cache/profile.spvj"), options.variantJsonCache());
    }

    @Test
    void parsesWeaponJsonCacheArtifact() {
        AgentOptions options = AgentOptions.parse(
                "adapter=enabled,weaponJsonCache64=" + encoded("build/cache/profile.spwj"));
        assertEquals(Path.of("build/cache/profile.spwj"), options.weaponJsonCache());
    }

    @Test
    void parsesProjectileJsonCacheArtifact() {
        AgentOptions options = AgentOptions.parse(
                "adapter=enabled,projectileJsonCache64=" + encoded("build/cache/profile.sppj"));
        assertEquals(Path.of("build/cache/profile.sppj"), options.projectileJsonCache());
    }

    @Test
    void parsesHullJsonCacheArtifact() {
        AgentOptions options = AgentOptions.parse(
                "adapter=enabled,hullJsonCache64=" + encoded("build/cache/profile.sphj"));
        assertEquals(Path.of("build/cache/profile.sphj"), options.hullJsonCache());
    }

    @Test
    void parsesRulesCsvCacheArtifact() {
        AgentOptions options = AgentOptions.parse(
                "adapter=enabled,rulesCsvCache64=" + encoded("build/cache/profile.sprc"));
        assertEquals(Path.of("build/cache/profile.sprc"), options.rulesCsvCache());
    }

    private static String encoded(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
