package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.starsector.preflight.core.ResourceIndex;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StaticReferenceRootAuthorityTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void duplicateHullIdMakesHullUniverseIncompleteInsteadOfFirstValueAuthoritative() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("duplicate-hull"));
        write(root, "data/hulls/duplicate.ship", "{\"hullId\":\"alpha\",\"hullId\":\"beta\"}");
        write(root, "data/variants/alpha.variant", "{\"variantId\":\"alpha_variant\",\"hullId\":\"alpha\"}");

        StaticReferenceCheck.Result result = StaticReferenceCheck.checkVariantHullLinks(
                index(root, List.of("data/hulls/duplicate.ship", "data/variants/alpha.variant")),
                ModMetadataCheck.ConversionMode.NORMAL);

        assertEquals(1, result.skipped());
        assertEquals(1, result.findings().size());
        assertEquals("static-reference.variant-hull-universe-incomplete", result.findings().get(0).code());
        assertEquals(SetupAnalysis.Severity.UNKNOWN, result.findings().get(0).severity());
    }

    @Test
    void duplicateSkinHullIdAlsoMakesHullUniverseIncomplete() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("duplicate-skin"));
        write(root, "data/hulls/skins/duplicate.skin", "{\"skinHullId\":\"alpha\",\"skinHullId\":\"beta\"}");
        write(root, "data/variants/alpha.variant", "{\"variantId\":\"alpha_variant\",\"hullId\":\"alpha\"}");

        StaticReferenceCheck.Result result = StaticReferenceCheck.checkVariantHullLinks(
                index(root, List.of("data/hulls/skins/duplicate.skin", "data/variants/alpha.variant")),
                ModMetadataCheck.ConversionMode.NORMAL);

        assertEquals(1, result.skipped());
        assertEquals(1, result.findings().size());
        assertEquals("static-reference.variant-hull-universe-incomplete", result.findings().get(0).code());
    }

    @Test
    void duplicateVariantHullIdIsNonAuthoritativeInsteadOfInventingMissingReference() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("duplicate-variant"));
        write(root, "data/hulls/alpha.ship", "{\"hullId\":\"alpha\"}");
        write(root, "data/variants/ambiguous.variant", "{\"hullId\":\"missing\",\"hullId\":\"alpha\"}");

        StaticReferenceCheck.Result result = StaticReferenceCheck.checkVariantHullLinks(
                index(root, List.of("data/hulls/alpha.ship", "data/variants/ambiguous.variant")),
                ModMetadataCheck.ConversionMode.NORMAL);

        assertEquals(1, result.skipped());
        assertEquals(List.of(), result.findings());
    }

    private static void write(Path root, String relative, String text) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private static ResourceIndex index(Path root, List<String> logicalPaths) throws Exception {
        Map<String, List<ResourceIndex.Provider>> entries = new LinkedHashMap<>();
        for (String logicalPath : logicalPaths) {
            Path file = root.resolve(logicalPath);
            entries.put(logicalPath, List.of(new ResourceIndex.Provider(
                    0,
                    logicalPath,
                    Files.size(file),
                    Math.max(0, Files.getLastModifiedTime(file).toMillis()))));
        }
        return new ResourceIndex(
                "b".repeat(64),
                List.of(new ResourceIndex.Root("fixture", root, false)),
                entries);
    }
}
