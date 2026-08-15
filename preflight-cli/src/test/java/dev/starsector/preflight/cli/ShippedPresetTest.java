package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.starsector.preflight.agent.AdapterMode;
import dev.starsector.preflight.agent.AdapterPlanScope;
import dev.starsector.preflight.agent.RecordingMode;
import dev.starsector.preflight.agent.TextureAdapterMode;
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Pins every launch choice the recommended preset makes.
 *
 * <p>The preset table is built by calling a 25-argument constructor positionally, twenty-one of
 * whose arguments are bare booleans. Swap two neighbours and it still compiles, still parses, and
 * still launches -- the only symptom is that the shipped preset quietly turns a different
 * optimization on or off, which is invisible until a player hits it. Individual flags were already
 * asserted across {@link CommandLineAdapterTest}, but three were read by no test at all, so a slip
 * into one of those slots had nothing to fail against.
 *
 * <p>So this asserts the whole row rather than the interesting parts of it, and
 * {@link #everyPresetChoiceIsPinned()} fails when the record grows a component this file does not
 * name -- a new launch choice arrives with a decision about the shipped preset already made, and
 * that decision should be written down here rather than inferred from an argument position.
 */
class ShippedPresetTest {
    /** Every component of the preset table, with the value `--fast` is expected to produce. */
    private static Map<String, Object> shippedChoices() {
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("summarize", true);
        expected.put("scan", true);
        expected.put("adapterMode", AdapterMode.ENABLED);
        expected.put("adapterPlanScope", AdapterPlanScope.FULL);
        expected.put("textureAuto", true);
        expected.put("textureAdapterMode", TextureAdapterMode.PREPARED_PIXELS);
        expected.put("exhaustiveFileReads", false);
        expected.put("recordingMode", RecordingMode.OFF);
        expected.put("singleChunkRecording", false);
        expected.put("npotDirect", false);
        expected.put("unpadded", true);
        expected.put("campaignEntityIndex", true);
        expected.put("startupPhaseProbe", false);
        expected.put("ruleTokenCache", true);
        expected.put("resourceProbeCache", false);
        expected.put("preparedAudio", true);
        expected.put("loadJsonMemo", true);
        expected.put("ruleCommandClassCache", true);
        expected.put("graphicsLibCompactReplay", true);
        expected.put("janinoBytecodeCache", true);
        expected.put("graphicsLibInsigniaManagerCache", true);
        expected.put("fileOnlyLogs", true);
        expected.put("quietLogs", false);
        expected.put("suppressAssetProgressLogs", true);
        expected.put("trustValidatedTextureIndex", true);
        return expected;
    }

    @Test
    void theRecommendedPresetMakesExactlyTheseLaunchChoices() throws Exception {
        CommandLine fast = CommandLine.parse(new String[] {"run", "--fast"}, 1);
        for (Map.Entry<String, Object> choice : shippedChoices().entrySet()) {
            Object actual = CommandLine.class
                    .getDeclaredMethod(choice.getKey())
                    .invoke(fast);
            assertEquals(choice.getValue(), actual, "recommended preset: " + choice.getKey());
        }
    }

    @Test
    void everyPresetChoiceIsPinned() throws Exception {
        Class<?> table = Class.forName("dev.starsector.preflight.cli.CommandLine$PresetConfiguration");
        List<String> components = java.util.Arrays.stream(table.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        assertEquals(
                new TreeSet<>(components),
                new TreeSet<>(shippedChoices().keySet()),
                "A launch choice was added to or removed from the preset table. Name it in "
                        + "shippedChoices() with the value the shipped preset should carry.");
    }
}
