package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.agent.AdapterMode;
import dev.starsector.preflight.agent.RecordingMode;
import dev.starsector.preflight.agent.TextureAdapterMode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AgentInjectionTest {
    @Test
    void preservesExistingOptionsAndAppendsOneAgent() {
        String value = AgentInjection.append(
                "-Xmx4g -Dexample=true",
                Path.of("preflight.jar"),
                Path.of("startup.jfr"));

        assertTrue(value.startsWith("-Xmx4g -Dexample=true "));
        assertEquals(1, occurrences(value, "-javaagent:"));
        assertTrue(value.contains("dest64="));
        assertTrue(value.contains("adapter=off"));
        assertTrue(value.contains("textureMode=compatibility"));
    }

    @Test
    void onlyStatesRecordOffWhenTheCallerAsksForIt() {
        // The agent treats any value other than "off" as recording, so the absence of this
        // token has to mean recording -- an accidental token would silently drop the profile.
        String recording = AgentInjection.append(
                "", Path.of("preflight.jar"), Path.of("startup.jfr"), AdapterMode.ENABLED,
                Path.of("adapter.json"), null, Path.of("cache"), Path.of("m.spfm"),
                Path.of("i.spfi"), TextureAdapterMode.COMPATIBILITY, false, RecordingMode.FULL, false, false, 0L);
        assertFalse(recording.contains("record="));

        String silent = AgentInjection.append(
                "", Path.of("preflight.jar"), Path.of("startup.jfr"), AdapterMode.ENABLED,
                Path.of("adapter.json"), null, Path.of("cache"), Path.of("m.spfm"),
                Path.of("i.spfi"), TextureAdapterMode.COMPATIBILITY, false, RecordingMode.OFF, false, false, 0L);
        assertTrue(silent.contains(",record=off"));
        // The adapter still has to be configured; turning the profile off is not turning
        // Preflight off, and the caches are the whole point of the mode.
        assertTrue(silent.contains("adapter=enabled"));
        assertTrue(silent.contains("textureCache64="));
        assertTrue(silent.contains("textureManifest64="));

        // Sampling has to reach the agent as its own value. Falling back to either neighbour
        // would be quiet and wrong in opposite directions: "full" restores the 24% the profile
        // was meant to avoid, "off" produces a run with no recording to analyse at all.
        String sampled = AgentInjection.append(
                "", Path.of("preflight.jar"), Path.of("startup.jfr"), AdapterMode.ENABLED,
                Path.of("adapter.json"), null, Path.of("cache"), Path.of("m.spfm"),
                Path.of("i.spfi"), TextureAdapterMode.COMPATIBILITY, false, RecordingMode.SAMPLE, false, false, 0L);
        assertTrue(sampled.contains(",record=sample"), sampled);
        assertFalse(sampled.contains("record=off"), sampled);

        // The non-power-of-two escape hatch is a system property, not an agent option: the
        // runtime reads it per texture through Boolean.getBoolean. It has to land on the JVM
        // command line, so a test that only checked the agent argument string would pass while
        // the bridge went on declining every padded texture.
        String npot = AgentInjection.append(
                "", Path.of("preflight.jar"), Path.of("startup.jfr"), AdapterMode.ENABLED,
                Path.of("adapter.json"), null, Path.of("cache"), Path.of("m.spfm"),
                Path.of("i.spfi"), TextureAdapterMode.PREPARED_PIXELS, false,
                RecordingMode.OFF, true, false, 0L);
        assertTrue(npot.contains(" -Dpreflight.preparedPixels.coherentDirect=true"), npot);

        String withoutNpot = AgentInjection.append(
                "", Path.of("preflight.jar"), Path.of("startup.jfr"), AdapterMode.ENABLED,
                Path.of("adapter.json"), null, Path.of("cache"), Path.of("m.spfm"),
                Path.of("i.spfi"), TextureAdapterMode.PREPARED_PIXELS, false,
                RecordingMode.OFF, false, false, 0L);
        assertFalse(withoutNpot.contains("coherentDirect"), withoutNpot);

        // Padding removal is the other way to carry a padded texture, and it has to reach the
        // JVM the same way. It is what makes the prepared path serve true-size bytes while the
        // allocation shrinks to match; the two halves read this one property.
        String unpadded = AgentInjection.append(
                "", Path.of("preflight.jar"), Path.of("startup.jfr"), AdapterMode.ENABLED,
                Path.of("adapter.json"), null, Path.of("cache"), Path.of("m.spfm"),
                Path.of("i.spfi"), TextureAdapterMode.PREPARED_PIXELS, false,
                RecordingMode.OFF, false, true, 0L);
        assertTrue(unpadded.contains(" -Dpreflight.padding.unpadded=true"), unpadded);
        assertFalse(unpadded.contains("coherentDirect"), unpadded);

        // Auto-play is an agent option rather than a system property because the agent reads it
        // once at startup, and absent has to mean off: pressing a button in someone's game is not
        // something to do because a field defaulted.
        String driven = AgentInjection.append(
                "", Path.of("preflight.jar"), Path.of("startup.jfr"), AdapterMode.ENABLED,
                Path.of("adapter.json"), null, Path.of("cache"), Path.of("m.spfm"),
                Path.of("i.spfi"), TextureAdapterMode.COMPATIBILITY, false,
                RecordingMode.OFF, false, false, 120_000L);
        assertTrue(driven.contains(",autoPlayMs=120000"), driven);
        assertFalse(unpadded.contains("autoPlayMs"), unpadded);
    }

    @Test
    void includesProbeReportAndTargetPaths() {
        String value = AgentInjection.append(
                "",
                Path.of("preflight.jar"),
                Path.of("startup.jfr"),
                AdapterMode.PROBE,
                Path.of("adapter reports", "probe.json"),
                Path.of("adapter targets", "vanilla.txt"));

        assertTrue(value.contains("adapter=probe"));
        assertTrue(value.contains("adapterReport64="));
        assertTrue(value.contains("targets64="));
        assertEquals(1, occurrences(value, "-javaagent:"));
    }

    @Test
    void includesPreparedPixelModeAndTexturePaths() {
        String value = AgentInjection.append(
                "",
                Path.of("preflight.jar"),
                Path.of("startup.jfr"),
                AdapterMode.ENABLED,
                Path.of("adapter.json"),
                null,
                Path.of("cache"),
                Path.of("cache", "manifests", "profile.spfm"),
                Path.of("cache", "indexes", "profile.spfi"),
                TextureAdapterMode.PREPARED_PIXELS);

        assertTrue(value.contains("adapter=enabled"));
        assertTrue(value.contains("textureMode=prepared-pixels"));
        assertTrue(value.contains("textureCache64="));
        assertTrue(value.contains("textureManifest64="));
        assertTrue(value.contains("textureIndex64="));
    }

    @Test
    void quotesAgentPathsContainingSpaces() {
        Path agent = Path.of("preflight build", "preflight.jar").toAbsolutePath().normalize();
        String value = AgentInjection.append(
                "",
                agent,
                Path.of("startup trace.jfr"));

        assertTrue(value.startsWith("-javaagent:\"" + agent + "\"="));
    }

    @Test
    void rejectsDuplicatePreflightAgent() {
        assertThrows(
                IllegalArgumentException.class,
                () -> AgentInjection.append(
                        "-javaagent:preflight.jar",
                        Path.of("preflight.jar"),
                        Path.of("startup.jfr")));
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
