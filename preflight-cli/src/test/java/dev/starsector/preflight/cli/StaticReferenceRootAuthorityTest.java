package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.starsector.preflight.core.ResourceIndex;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StaticReferenceRootAuthorityTest {
    @TempDir
    Path temp;

    @Test
    void nestedShipHullIdCannotSatisfyVariantAuthority() throws Exception {
        ResourceIndex index = index(Map.of(
                "data/hulls/nested.ship",
                "{\"metadata\":{\"hullId\":\"nested_hull\"}}",
                "data/variants/use-nested.variant",
                "{\"variantId\":\"use_nested\",\"hullId\":\"nested_hull\"}"));

        StaticReferenceCheck.Result result = StaticReferenceCheck.checkVariantHullLinks(
                index,
                ModMetadataCheck.ConversionMode.NORMAL);

        assertEquals(1, result.skipped());
        assertEquals(0, result.hulls());
        assertEquals(1, result.variants());
        assertEquals(1, result.findings().size());
        assertEquals("static-reference.variant-hull-universe-incomplete", result.findings().get(0).code());
        assertEquals(SetupAnalysis.Severity.UNKNOWN, result.findings().get(0).severity());
        assertFalse(result.findings().stream()
                .anyMatch(finding -> finding.severity() == SetupAnalysis.Severity.BLOCKING));
    }

    @Test
    void nestedSkinHullIdCannotSatisfyVariantAuthority() throws Exception {
        ResourceIndex index = index(Map.of(
                "data/hulls/skins/nested.skin",
                "{\"metadata\":{\"skinHullId\":\"nested_skin\"}}",
                "data/variants/use-nested.variant",
                "{\"variantId\":\"use_nested\",\"hullId\":\"nested_skin\"}"));

        StaticReferenceCheck.Result result = StaticReferenceCheck.checkVariantHullLinks(
                index,
                ModMetadataCheck.ConversionMode.NORMAL);

        assertEquals(1, result.skipped());
        assertEquals(0, result.skins());
        assertEquals(1, result.findings().size());
        assertEquals("static-reference.variant-hull-universe-incomplete", result.findings().get(0).code());
        assertEquals(SetupAnalysis.Severity.UNKNOWN, result.findings().get(0).severity());
    }

    @Test
    void nestedVariantHullIdDoesNotBecomeAStaticReference() throws Exception {
        ResourceIndex index = index(Map.of(
                "data/hulls/real.ship",
                "{\"hullId\":\"real_hull\"}",
                "data/variants/nested.variant",
                "{\"variantId\":\"nested_variant\",\"metadata\":{\"hullId\":\"missing_hull\"}}"));

        StaticReferenceCheck.Result result = StaticReferenceCheck.checkVariantHullLinks(
                index,
                ModMetadataCheck.ConversionMode.NORMAL);

        assertEquals(List.of(), result.findings());
        assertEquals(1, result.hulls());
        assertEquals(1, result.variants());
    }

    @Test
    void rootVariantHullIdWinsOverNestedLookalike() throws Exception {
        ResourceIndex index = index(Map.of(
                "data/hulls/real.ship",
                "{\"hullId\":\"real_hull\"}",
                "data/variants/root.variant",
                "{/* loose JSON stays supported */\"metadata\":{\"hullId\":\"missing_hull\"},\"hullId\":\"real_hull\",\"variantId\":\"root_variant\",}"));

        StaticReferenceCheck.Result result = StaticReferenceCheck.checkVariantHullLinks(
                index,
                ModMetadataCheck.ConversionMode.NORMAL);

        assertEquals(List.of(), result.findings());
        assertEquals(1, result.hulls());
        assertEquals(1, result.variants());
        assertEquals(0, result.skipped());
    }

    @Test
    void duplicateRootIdsRemainNonAuthoritative() throws Exception {
        ResourceIndex index = index(Map.of(
                "data/hulls/duplicate.ship",
                "{\"hullId\":\"first\",\"hullId\":\"second\"}",
                "data/variants/use-first.variant",
                "{\"hullId\":\"first\"}",
                "data/variants/duplicate.variant",
                "{\"hullId\":\"first\",\"hullId\":\"second\"}"));

        StaticReferenceCheck.Result result = StaticReferenceCheck.checkVariantHullLinks(
                index,
                ModMetadataCheck.ConversionMode.NORMAL);

        assertEquals(2, result.skipped());
        assertEquals(0, result.hulls());
        assertEquals(1, result.variants());
        assertEquals(1, result.findings().size());
        assertEquals("static-reference.variant-hull-universe-incomplete", result.findings().get(0).code());
        assertEquals(SetupAnalysis.Severity.UNKNOWN, result.findings().get(0).severity());
    }

    private ResourceIndex index(Map<String, String> documents) throws Exception {
        Path root = Files.createDirectories(temp.resolve("mod"));
        LinkedHashMap<String, List<ResourceIndex.Provider>> entries = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : documents.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList()) {
            String relative = entry.getKey();
            Path path = root.resolve(relative);
            Files.createDirectories(path.getParent());
            byte[] bytes = entry.getValue().getBytes(StandardCharsets.UTF_8);
            Files.write(path, bytes);
            long modified = Math.max(0L, Files.getLastModifiedTime(path).toMillis());
            entries.put(relative, List.of(new ResourceIndex.Provider(
                    0,
                    relative,
                    bytes.length,
                    modified)));
        }
        return new ResourceIndex(
                "profile-fingerprint",
                List.of(new ResourceIndex.Root("example.mod", root, false)),
                entries);
    }
}
