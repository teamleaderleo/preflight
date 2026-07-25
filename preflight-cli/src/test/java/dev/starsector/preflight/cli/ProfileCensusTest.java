package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.OptionalLong;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfileCensusTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void inventoriesEnabledModsAndTracksProbableOverrideWinner() throws Exception {
        Path mods = temporaryDirectory.resolve("mods");
        Path alpha = mods.resolve("Alpha");
        Path beta = mods.resolve("Beta");
        Files.createDirectories(alpha.resolve("graphics"));
        Files.createDirectories(alpha.resolve("src"));
        Files.createDirectories(beta.resolve("graphics"));
        Files.createDirectories(beta.resolve("sounds"));
        Files.createDirectories(beta.resolve("jars"));
        Files.writeString(alpha.resolve("mod_info.json"), "{\"id\":\"alpha\"}");
        Files.writeString(beta.resolve("mod_info.json"), "{\"id\":\"beta\"}");
        Files.writeString(alpha.resolve("graphics/shared.png"), "alpha");
        Files.writeString(beta.resolve("graphics/shared.png"), "beta image");
        Files.writeString(alpha.resolve("src/Example.java"), "class Example {}");
        Files.writeString(beta.resolve("sounds/hit.ogg"), "sound");
        Files.writeString(beta.resolve("jars/beta.jar"), "jar");
        Path enabled = mods.resolve("enabled_mods.json");
        Files.writeString(enabled, "{\"enabledMods\":[\"alpha\",\"beta\",\"missing\"]}");

        ProfileCensus.Result first = ProfileCensus.scan(temporaryDirectory);
        Map<String, Object> values = first.values();
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) values.get("totals");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> duplicates = (List<Map<String, Object>>) values.get("duplicateSamples");

        assertEquals(List.of("alpha", "beta", "missing"), values.get("enabledModIds"));
        assertEquals(List.of("missing"), values.get("missingModIds"));
        assertEquals(2L, totals.get("imageFiles"));
        assertEquals(1L, totals.get("looseJavaFiles"));
        assertEquals(1L, totals.get("jarFiles"));
        assertEquals(1L, values.get("duplicateLogicalPaths"));
        assertEquals("beta", duplicates.get(0).get("probableWinner"));
        assertTrue(first.toJson().contains("\"profileFingerprint\""));

        Files.writeString(enabled, "{\"enabledMods\":[\"beta\",\"alpha\"]}");
        ProfileCensus.Result reordered = ProfileCensus.scan(temporaryDirectory);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reorderedDuplicates =
                (List<Map<String, Object>>) reordered.values().get("duplicateSamples");

        assertNotEquals(values.get("profileFingerprint"), reordered.values().get("profileFingerprint"));
        assertEquals("alpha", reorderedDuplicates.get(0).get("probableWinner"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void breaksDownDecodedVramPerModFromExactImageDimensions() throws Exception {
        Path mods = temporaryDirectory.resolve("mods");
        Path big = mods.resolve("Big");
        Path small = mods.resolve("Small");
        Files.createDirectories(big.resolve("graphics"));
        Files.createDirectories(small.resolve("graphics"));
        Files.writeString(big.resolve("mod_info.json"), "{\"id\":\"big\"}");
        Files.writeString(small.resolve("mod_info.json"), "{\"id\":\"small\"}");
        // Big ships a 100x100 RGBA image (100*100*4 = 40000 decoded bytes).
        ImageIO.write(new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB), "png",
                big.resolve("graphics/hull.png").toFile());
        // Small ships a 10x10 RGB image (10*10*3 = 300 decoded bytes) plus one unmeasurable "image".
        ImageIO.write(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), "png",
                small.resolve("graphics/icon.png").toFile());
        Files.writeString(small.resolve("graphics/broken.png"), "not really a png");
        Files.writeString(mods.resolve("enabled_mods.json"), "{\"enabledMods\":[\"big\",\"small\"]}");

        Map<String, Object> values = ProfileCensus.scan(temporaryDirectory).values();
        Map<String, Object> totals = (Map<String, Object>) values.get("totals");
        assertEquals(40000L + 300L, totals.get("decodedImageBytes"));
        assertEquals(2L, totals.get("measuredImageFiles"));
        assertEquals(1L, totals.get("unmeasuredImageFiles"), "the fake PNG is counted, not guessed");

        // Decoded ranking puts Big first even though both are tiny on disk.
        List<Map<String, Object>> largestDecoded = (List<Map<String, Object>>) values.get("largestDecodedMods");
        assertEquals("big", largestDecoded.get(0).get("id"));
        assertEquals(40000L, largestDecoded.get(0).get("decodedImageBytes"));

        Map<String, Object> workingSet = (Map<String, Object>) values.get("decodedWorkingSet");
        assertEquals(40000L + 300L, workingSet.get("decodedImageBytes"));
        // No budget requested -> no verdict emitted.
        assertTrue(!workingSet.containsKey("budgetVerdict"), "verdict is opt-in via a budget");
        assertTrue(!workingSet.containsKey("reductionPlan"), "reduction plan is opt-in via a size cap");
    }

    @Test
    @SuppressWarnings("unchecked")
    void projectsWhatCappingOversizedTexturesWouldSave() throws Exception {
        Path mods = temporaryDirectory.resolve("mods");
        Path heavy = mods.resolve("Heavy");
        Files.createDirectories(heavy.resolve("graphics"));
        Files.writeString(heavy.resolve("mod_info.json"), "{\"id\":\"heavy\"}");
        // 512x256 RGBA = 524288 bytes. A 128 cap needs two halvings -> 128x64 = 32768 bytes.
        writeRgba(heavy.resolve("graphics/huge.png"), 512, 256);
        // 300x100 RGBA = 120000 bytes. Halving is conservative: 300 -> 150 -> 75, not a resample
        // to exactly 128. So 75x25 = 7500 bytes.
        writeRgba(heavy.resolve("graphics/odd.png"), 300, 100);
        // 64x64 RGBA = 16384 bytes, already under the cap and left alone.
        writeRgba(heavy.resolve("graphics/small.png"), 64, 64);
        Files.writeString(mods.resolve("enabled_mods.json"), "{\"enabledMods\":[\"heavy\"]}");

        Map<String, Object> values = ProfileCensus.scan(
                        temporaryDirectory,
                        new ProfileCensus.Options(OptionalLong.of(100_000), OptionalInt.of(128)))
                .values();
        Map<String, Object> workingSet = (Map<String, Object>) values.get("decodedWorkingSet");
        Map<String, Object> plan = (Map<String, Object>) workingSet.get("reductionPlan");

        assertEquals(128, plan.get("maxTextureSizePixels"));
        assertEquals(2L, plan.get("oversizedTextures"), "the 64x64 texture already fits");
        assertEquals(524288L + 120000L + 16384L, plan.get("currentFloorBytes"));
        assertEquals(32768L + 7500L + 16384L, plan.get("projectedFloorBytes"));
        assertEquals(524288L + 120000L - 32768L - 7500L, plan.get("savedBytes"));

        // The point of the plan: it says whether the cut is enough to clear the budget.
        assertEquals("over", ((Map<String, Object>) workingSet.get("budgetVerdict")).get("verdict"));
        Map<String, Object> projected = (Map<String, Object>) plan.get("projectedBudgetVerdict");
        assertEquals("under", projected.get("verdict"));
        assertEquals(56652L, projected.get("floorBytes"));

        List<Map<String, Object>> largest = (List<Map<String, Object>>) plan.get("largestReductions");
        assertEquals("graphics/huge.png", largest.get(0).get("path"));
        assertEquals("heavy", largest.get(0).get("modId"));
        assertEquals(128, largest.get(0).get("projectedWidth"));
        assertEquals(64, largest.get(0).get("projectedHeight"));
        assertEquals(524288L - 32768L, largest.get(0).get("savedBytes"));
        assertEquals("graphics/odd.png", largest.get(1).get("path"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void capsTheOverrideWinnerRatherThanEveryProviderOfALogicalPath() throws Exception {
        Path mods = temporaryDirectory.resolve("mods");
        Path low = mods.resolve("Low");
        Path high = mods.resolve("High");
        Files.createDirectories(low.resolve("graphics"));
        Files.createDirectories(high.resolve("graphics"));
        Files.writeString(low.resolve("mod_info.json"), "{\"id\":\"low\"}");
        Files.writeString(high.resolve("mod_info.json"), "{\"id\":\"high\"}");
        // The losing provider is the oversized one; the winner already fits the cap, so there is
        // nothing to cut -- shrinking a texture the game never loads would save no VRAM at all.
        writeRgba(low.resolve("graphics/shared.png"), 512, 512);
        writeRgba(high.resolve("graphics/shared.png"), 64, 64);
        Files.writeString(mods.resolve("enabled_mods.json"), "{\"enabledMods\":[\"low\",\"high\"]}");

        Map<String, Object> values = ProfileCensus.scan(
                        temporaryDirectory, new ProfileCensus.Options(OptionalLong.empty(), OptionalInt.of(128)))
                .values();
        Map<String, Object> workingSet = (Map<String, Object>) values.get("decodedWorkingSet");
        Map<String, Object> plan = (Map<String, Object>) workingSet.get("reductionPlan");

        assertEquals(0L, plan.get("oversizedTextures"));
        assertEquals(64L * 64L * 4L, plan.get("currentFloorBytes"));
        assertEquals(64L * 64L * 4L, plan.get("projectedFloorBytes"));
        assertEquals(0L, plan.get("savedBytes"));
        assertTrue(!plan.containsKey("projectedBudgetVerdict"), "no budget -> no projected verdict");
    }

    @Test
    void rejectsANonPositiveTextureCap() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProfileCensus.Options(OptionalLong.empty(), OptionalInt.of(0)));
        assertThrows(IllegalArgumentException.class,
                () -> new ProfileCensus.Options(OptionalLong.empty(), OptionalInt.of(-1)));
    }

    private static void writeRgba(Path file, int width, int height) throws Exception {
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", file.toFile());
    }

    @Test
    @SuppressWarnings("unchecked")
    void gradesTheDecodedWorkingSetAgainstAVramBudget() throws Exception {
        Path mods = temporaryDirectory.resolve("mods");
        Path only = mods.resolve("Only");
        Files.createDirectories(only.resolve("graphics"));
        Files.writeString(only.resolve("mod_info.json"), "{\"id\":\"only\"}");
        // One 100x100 RGBA image: floor = 40000 bytes; full-mip upper bound = 40000 + ceil(40000/3) = 53334.
        ImageIO.write(new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB), "png",
                only.resolve("graphics/hull.png").toFile());
        Files.writeString(mods.resolve("enabled_mods.json"), "{\"enabledMods\":[\"only\"]}");

        // Budget below the floor -> the base levels alone already exceed it.
        Map<String, Object> over = verdict(ProfileCensus.scan(temporaryDirectory, ProfileCensus.Options.vramBudget(30_000)));
        assertEquals("over", over.get("verdict"));
        assertEquals(30_000L - 40_000L, over.get("headroomBytes"));

        // Budget above the floor but below the full-mip upper bound -> at risk.
        Map<String, Object> atRisk = verdict(ProfileCensus.scan(temporaryDirectory, ProfileCensus.Options.vramBudget(50_000)));
        assertEquals("at-risk", atRisk.get("verdict"));
        assertEquals(53_334L, atRisk.get("fullMipChainUpperBoundBytes"));

        // Budget above even the upper bound -> comfortably under.
        Map<String, Object> under = verdict(ProfileCensus.scan(temporaryDirectory, ProfileCensus.Options.vramBudget(60_000)));
        assertEquals("under", under.get("verdict"));
        assertEquals(60_000L - 40_000L, under.get("headroomBytes"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolvesOverrideCollisionsToTheWinningProviderForTheDecodedFloor() throws Exception {
        Path mods = temporaryDirectory.resolve("mods");
        Path low = mods.resolve("Low");
        Path high = mods.resolve("High");
        Files.createDirectories(low.resolve("graphics"));
        Files.createDirectories(high.resolve("graphics"));
        Files.writeString(low.resolve("mod_info.json"), "{\"id\":\"low\"}");
        Files.writeString(high.resolve("mod_info.json"), "{\"id\":\"high\"}");
        // Both ship the SAME logical path graphics/shared.png; only the override winner loads.
        // "low" is enabled first (order 0) with a big 100x100 RGBA image (40000 decoded bytes);
        // "high" is enabled later (order 1, the winner) with a tiny 10x10 RGBA image (400 bytes).
        ImageIO.write(new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB), "png",
                low.resolve("graphics/shared.png").toFile());
        ImageIO.write(new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB), "png",
                high.resolve("graphics/shared.png").toFile());
        Files.writeString(mods.resolve("enabled_mods.json"), "{\"enabledMods\":[\"low\",\"high\"]}");

        Map<String, Object> values = ProfileCensus.scan(temporaryDirectory, ProfileCensus.Options.vramBudget(1_000)).values();
        Map<String, Object> workingSet = (Map<String, Object>) values.get("decodedWorkingSet");

        // All-providers counts both copies; winner-only counts just the loaded (high-order) one.
        assertEquals(40_000L + 400L, workingSet.get("decodedImageBytes"));
        assertEquals(400L, workingSet.get("winnerDecodedImageBytes"));
        assertEquals(1L, workingSet.get("winnerMeasuredImageFiles"));

        // The verdict grades the winner floor: 400 fits a 1000 budget even though all-providers is over.
        Map<String, Object> budgetVerdict = (Map<String, Object>) workingSet.get("budgetVerdict");
        assertEquals("under", budgetVerdict.get("verdict"));
        assertEquals(400L, budgetVerdict.get("floorBytes"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> verdict(ProfileCensus.Result result) {
        Map<String, Object> workingSet = (Map<String, Object>) result.values().get("decodedWorkingSet");
        return (Map<String, Object>) workingSet.get("budgetVerdict");
    }
}
