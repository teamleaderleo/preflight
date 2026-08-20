package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.ResourceIndex;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StaticReferenceCheckTest {
    @TempDir
    Path temp;

    @Test
    void reportsWinningVariantThatReferencesMissingHullInNormalProfile() throws Exception {
        Path root = Files.createDirectories(temp.resolve("mod"));
        ProviderFile variant = file(root, "data/variants/broken.variant", """
                {"variantId":"broken_Variant","hullId":"missing_hull"}
                """);
        ResourceIndex index = index(
                List.of(new ResourceIndex.Root("example.mod", root, false)),
                Map.of("data/variants/broken.variant", List.of(variant.provider(0))));

        StaticReferenceCheck.Result result = StaticReferenceCheck.checkVariantHullLinks(
                index, ModMetadataCheck.ConversionMode.NORMAL);

        assertEquals(1, result.findings().size());
        SetupAnalysis.Finding finding = result.findings().get(0);
        assertEquals("static-reference.variant-missing-hull", finding.code());
        assertEquals(SetupAnalysis.Severity.BLOCKING, finding.severity());
        assertEquals("missing_hull", finding.parameters().get("hullId"));
        assertEquals("broken_Variant", finding.parameters().get("variantId"));
        assertEquals(List.of("example.mod"), finding.affectedModIds());
        assertFalse(finding.summary().contains(root.toString()));
        assertEquals(0, result.hulls());
        assertEquals(0, result.skins());
        assertEquals(1, result.variants());
        assertEquals(0, result.unknownContextReferences());
        assertTrue(result.bytes() > 0);
        assertTrue(result.elapsedNanos() > 0);
    }

    @Test
    void matchingWinningHullMakesVariantClean() throws Exception {
        Path root = Files.createDirectories(temp.resolve("mod"));
        ProviderFile hull = file(root, "data/hulls/example.ship", """
                {"hullId":"example_hull","hullName":"Example"}
                """);
        ProviderFile variant = file(root, "data/variants/example.variant", """
                {"variantId":"example_Variant","hullId":"example_hull"}
                """);
        ResourceIndex index = index(
                List.of(new ResourceIndex.Root("example.mod", root, false)),
                Map.of(
                        "data/hulls/example.ship", List.of(hull.provider(0)),
                        "data/variants/example.variant", List.of(variant.provider(0))));

        StaticReferenceCheck.Result result = StaticReferenceCheck.checkVariantHullLinks(
                index, ModMetadataCheck.ConversionMode.NORMAL);

        assertEquals(List.of(), result.findings());
        assertEquals(1, result.hulls());
        assertEquals(0, result.skins());
        assertEquals(1, result.variants());
    }

    @Test
    void winningSkinHullIdSatisfiesVariantHullReference() throws Exception {
        Path root = Files.createDirectories(temp.resolve("skin"));
        ProviderFile skin = file(root, "data/hulls/skins/example.skin", """
                {"baseHullId":"base_hull","skinHullId":"example_skin"}
                """);
        ProviderFile variant = file(root, "data/variants/example_skin.variant", """
                {"variantId":"example_skin_Standard","hullId":"example_skin"}
                """);
        ResourceIndex index = index(
                List.of(new ResourceIndex.Root("example.mod", root, false)),
                Map.of(
                        "data/hulls/skins/example.skin", List.of(skin.provider(0)),
                        "data/variants/example_skin.variant", List.of(variant.provider(0))));

        StaticReferenceCheck.Result result = StaticReferenceCheck.checkVariantHullLinks(
                index, ModMetadataCheck.ConversionMode.NORMAL);

        assertEquals(List.of(), result.findings());
        assertEquals(0, result.hulls());
        assertEquals(1, result.skins());
        assertEquals(1, result.variants());
    }

    @Test
    void totalConversionMayIntentionallySkipVariantWhoseHullDoesNotExist() throws Exception {
        Path root = Files.createDirectories(temp.resolve("total-conversion"));
        ProviderFile variant = file(root, "data/variants/vanilla-leftover.variant", """
                {"variantId":"vanilla_Leftover","hullId":"removed_vanilla_hull"}
                """);
        ResourceIndex index = index(
                List.of(new ResourceIndex.Root("conversion.mod", root, false)),
                Map.of("data/variants/vanilla-leftover.variant", List.of(variant.provider(0))));

        StaticReferenceCheck.Result result = StaticReferenceCheck.checkVariantHullLinks(
                index, ModMetadataCheck.ConversionMode.TOTAL_CONVERSION);

        assertEquals(List.of(), result.findings());
        assertEquals(1, result.totalConversionSkips());
        assertEquals(0, result.unknownContextReferences());
    }

    @Test
    void unknownConversionContextDoesNotBecomeAFalseBlockerOrFalsePass() throws Exception {
        Path root = Files.createDirectories(temp.resolve("unknown-conversion"));
        ProviderFile variant = file(root, "data/variants/unknown.variant", """
                {"variantId":"unknown_Variant","hullId":"missing_hull"}
                """);
        ResourceIndex index = index(
                List.of(new ResourceIndex.Root("example.mod", root, false)),
                Map.of("data/variants/unknown.variant", List.of(variant.provider(0))));

        StaticReferenceCheck.Result result = StaticReferenceCheck.checkVariantHullLinks(
                index, ModMetadataCheck.ConversionMode.UNKNOWN);

        assertEquals(1, result.findings().size());
        SetupAnalysis.Finding finding = result.findings().get(0);
        assertEquals("static-reference.variant-hull-context-unknown", finding.code());
        assertEquals(SetupAnalysis.Severity.UNKNOWN, finding.severity());
        assertEquals(1L, finding.parameters().get("references"));
        assertEquals(1, result.unknownContextReferences());
        assertEquals(0, result.totalConversionSkips());
    }

    @Test
    void losingBrokenVariantIsIgnoredWhenLaterProviderWins() throws Exception {
        Path first = Files.createDirectories(temp.resolve("first"));
        Path second = Files.createDirectories(temp.resolve("second"));
        ProviderFile hull = file(first, "data/hulls/example.ship", "{\"hullId\":\"example_hull\"}");
        ProviderFile broken = file(first, "data/variants/example.variant", """
                {"variantId":"example_Variant","hullId":"missing_hull"}
                """);
        ProviderFile winner = file(second, "data/variants/example.variant", """
                {"variantId":"example_Variant","hullId":"example_hull"}
                """);
        ResourceIndex index = index(
                List.of(
                        new ResourceIndex.Root("first.mod", first, false),
                        new ResourceIndex.Root("second.mod", second, false)),
                Map.of(
                        "data/hulls/example.ship", List.of(hull.provider(0)),
                        "data/variants/example.variant", List.of(broken.provider(0), winner.provider(1))));

        StaticReferenceCheck.Result result = StaticReferenceCheck.checkVariantHullLinks(
                index, ModMetadataCheck.ConversionMode.NORMAL);

        assertEquals(List.of(), result.findings(), "only the resolved winning variant is authoritative");
        assertEquals(1, result.variants(), "one logical variant should be analysed once");
    }

    @Test
    void losingHullDefinitionDoesNotSatisfyWinningProfile() throws Exception {
        Path first = Files.createDirectories(temp.resolve("first-hull"));
        Path second = Files.createDirectories(temp.resolve("second-hull"));
        ProviderFile oldHull = file(first, "data/hulls/example.ship", "{\"hullId\":\"old_hull\"}");
        ProviderFile newHull = file(second, "data/hulls/example.ship", "{\"hullId\":\"new_hull\"}");
        ProviderFile variant = file(second, "data/variants/example.variant", """
                {"variantId":"old_Variant","hullId":"old_hull"}
                """);
        ResourceIndex index = index(
                List.of(
                        new ResourceIndex.Root("first.mod", first, false),
                        new ResourceIndex.Root("second.mod", second, false)),
                Map.of(
                        "data/hulls/example.ship", List.of(oldHull.provider(0), newHull.provider(1)),
                        "data/variants/example.variant", List.of(variant.provider(1))));

        StaticReferenceCheck.Result result = StaticReferenceCheck.checkVariantHullLinks(
                index, ModMetadataCheck.ConversionMode.NORMAL);

        assertEquals(1, result.findings().size());
        assertEquals("old_hull", result.findings().get(0).parameters().get("hullId"));
    }

    @Test
    void missingFieldsStayUnknownInsteadOfBecomingMissingReferenceClaims() throws Exception {
        Path root = Files.createDirectories(temp.resolve("missing-fields"));
        ProviderFile hull = file(root, "data/hulls/example.ship", "{\"hullName\":\"No id\"}");
        ProviderFile variant = file(root, "data/variants/example.variant", "{\"variantId\":\"NoHullId\"}");
        ResourceIndex index = index(
                List.of(new ResourceIndex.Root("example.mod", root, false)),
                Map.of(
                        "data/hulls/example.ship", List.of(hull.provider(0)),
                        "data/variants/example.variant", List.of(variant.provider(0))));

        StaticReferenceCheck.Result result = StaticReferenceCheck.checkVariantHullLinks(
                index, ModMetadataCheck.ConversionMode.NORMAL);

        assertEquals(List.of(), result.findings());
    }

    @Test
    void malformedTextIsSkippedRatherThanCrashingOrInventingAReference() throws Exception {
        Path root = Files.createDirectories(temp.resolve("malformed-text"));
        ProviderFile variant = file(root, "data/variants/broken.variant", "{\"hullId\":\"unterminated");
        ResourceIndex index = index(
                List.of(new ResourceIndex.Root("example.mod", root, false)),
                Map.of("data/variants/broken.variant", List.of(variant.provider(0))));

        StaticReferenceCheck.Result result = StaticReferenceCheck.checkVariantHullLinks(
                index, ModMetadataCheck.ConversionMode.NORMAL);

        assertEquals(List.of(), result.findings());
        assertEquals(1, result.skipped());
    }

    @Test
    void unrelatedResourcesAreNotRead() throws Exception {
        Path root = Files.createDirectories(temp.resolve("unrelated"));
        ProviderFile unrelated = file(root, "data/config/settings.json", "not even json");
        ResourceIndex index = index(
                List.of(new ResourceIndex.Root("example.mod", root, false)),
                Map.of("data/config/settings.json", List.of(unrelated.provider(0))));

        StaticReferenceCheck.Result result = StaticReferenceCheck.checkVariantHullLinks(
                index, ModMetadataCheck.ConversionMode.NORMAL);

        assertEquals(0, result.hulls());
        assertEquals(0, result.skins());
        assertEquals(0, result.variants());
        assertEquals(0, result.bytes());
        assertEquals(List.of(), result.findings());
    }

    private ResourceIndex index(
            List<ResourceIndex.Root> roots,
            Map<String, List<ResourceIndex.Provider>> entries) {
        return new ResourceIndex("profile-fingerprint", roots, new LinkedHashMap<>(entries));
    }

    private ProviderFile file(Path root, String relative, String content) throws Exception {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        Files.write(path, bytes);
        long modified = Math.max(1L, Files.getLastModifiedTime(path).toMillis());
        OpenedFileGenerationAuthority.Generation generation = OpenedFileGenerationAuthority.capture(path);
        return new ProviderFile(
                relative,
                bytes.length,
                modified,
                generation.provider(),
                generation.token());
    }

    private record ProviderFile(
            String relative,
            long size,
            long modified,
            String generationProvider,
            String generationToken) {
        ResourceIndex.Provider provider(int rootIndex) {
            return new ResourceIndex.Provider(
                    rootIndex,
                    relative,
                    size,
                    modified,
                    generationProvider,
                    generationToken);
        }
    }
}
