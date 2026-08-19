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

class StaticReferenceBoundsTest {
    @TempDir
    Path temp;

    @Test
    void overlongHullIdIsNonAuthoritativeBeforePublicFindingConstruction() throws Exception {
        Path root = Files.createDirectories(temp.resolve("mod"));
        Path variant = root.resolve("data/variants/oversized.variant");
        Files.createDirectories(variant.getParent());
        String content = "{\"variantId\":\"oversized\",\"hullId\":\""
                + "x".repeat(257)
                + "\"}";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        Files.write(variant, bytes);
        long modified = Math.max(1L, Files.getLastModifiedTime(variant).toMillis());

        ResourceIndex.Provider provider = new ResourceIndex.Provider(
                0,
                "data/variants/oversized.variant",
                bytes.length,
                modified);
        ResourceIndex index = new ResourceIndex(
                "profile-fingerprint",
                List.of(new ResourceIndex.Root("example.mod", root, false)),
                new LinkedHashMap<>(Map.of(
                        "data/variants/oversized.variant", List.of(provider))));

        StaticReferenceCheck.Result result = StaticReferenceCheck.checkVariantHullLinks(
                index,
                ModMetadataCheck.ConversionMode.NORMAL);

        assertEquals(List.of(), result.findings());
        assertEquals(0, result.variants());
        assertEquals(1, result.skipped());
    }

    @Test
    void incompleteWinningHullUniverseCannotProduceFalseBlockingAbsence() throws Exception {
        Path root = Files.createDirectories(temp.resolve("incomplete-hulls"));
        ProviderFile malformedHull = file(
                root,
                "data/hulls/malformed.ship",
                "{\"hullName\":\"missing authoritative hullId\"}");
        ProviderFile variant = file(
                root,
                "data/variants/missing.variant",
                "{\"variantId\":\"missing_Variant\",\"hullId\":\"possibly_hidden_hull\"}");
        LinkedHashMap<String, List<ResourceIndex.Provider>> entries = new LinkedHashMap<>();
        entries.put(malformedHull.relative(), List.of(malformedHull.provider(0)));
        entries.put(variant.relative(), List.of(variant.provider(0)));
        ResourceIndex index = new ResourceIndex(
                "profile-fingerprint",
                List.of(new ResourceIndex.Root("example.mod", root, false)),
                entries);

        StaticReferenceCheck.Result result = StaticReferenceCheck.checkVariantHullLinks(
                index,
                ModMetadataCheck.ConversionMode.NORMAL);

        assertEquals(1, result.findings().size());
        SetupAnalysis.Finding finding = result.findings().get(0);
        assertEquals("static-reference.variant-hull-universe-incomplete", finding.code());
        assertEquals(SetupAnalysis.Severity.UNKNOWN, finding.severity());
        assertEquals(1L, finding.parameters().get("references"));
        assertEquals(1, result.skipped());
        assertFalse(result.findings().stream()
                .anyMatch(value -> value.severity() == SetupAnalysis.Severity.BLOCKING));
    }

    @Test
    void manyMissingVariantsStayWithinSharedFindingLimit() throws Exception {
        Path root = Files.createDirectories(temp.resolve("finding-limit"));
        LinkedHashMap<String, List<ResourceIndex.Provider>> entries = new LinkedHashMap<>();
        for (int index = 0; index < 300; index++) {
            String id = "missing_" + index;
            ProviderFile variant = file(
                    root,
                    "data/variants/" + id + ".variant",
                    "{\"variantId\":\"" + id + "_Variant\",\"hullId\":\"" + id + "\"}");
            entries.put(variant.relative(), List.of(variant.provider(0)));
        }
        ResourceIndex resourceIndex = new ResourceIndex(
                "profile-fingerprint",
                List.of(new ResourceIndex.Root("example.mod", root, false)),
                entries);

        StaticReferenceCheck.Result result = StaticReferenceCheck.checkVariantHullLinks(
                resourceIndex,
                ModMetadataCheck.ConversionMode.NORMAL);

        assertEquals(256, result.findings().size());
        assertEquals(
                "static-reference.variant-missing-hull-overflow",
                result.findings().get(result.findings().size() - 1).code());
        assertEquals(45L, result.findings().get(result.findings().size() - 1)
                .parameters().get("references"));
        // The shared result constructor is the actual downstream public bound.
        new SetupAnalysis.Result("installation", "profile-fingerprint", result.findings(), List.of());
    }

    private ProviderFile file(Path root, String relative, String content) throws Exception {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        Files.write(path, bytes);
        long modified = Math.max(1L, Files.getLastModifiedTime(path).toMillis());
        return new ProviderFile(relative, bytes.length, modified);
    }

    private record ProviderFile(String relative, long size, long modified) {
        ResourceIndex.Provider provider(int rootIndex) {
            return new ResourceIndex.Provider(rootIndex, relative, size, modified);
        }
    }
}
