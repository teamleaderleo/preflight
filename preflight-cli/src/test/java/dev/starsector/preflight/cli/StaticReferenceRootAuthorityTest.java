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
    void nestedHullIdCannotPopulateTheAuthoritativeHullUniverse() throws Exception {
        Path root = Files.createDirectories(temp.resolve("hull"));
        ProviderFile hull = file(root, "data/hulls/example.ship", """
                {"metadata":{"hullId":"claimed_hull"},"hullName":"Example"}
                """);
        ProviderFile variant = file(root, "data/variants/example.variant", """
                {"variantId":"example_Variant","hullId":"claimed_hull"}
                """);
        ResourceIndex index = index(
                List.of(new ResourceIndex.Root("example.mod", root, false)),
                Map.of(
                        "data/hulls/example.ship", List.of(hull.provider(0)),
                        "data/variants/example.variant", List.of(variant.provider(0))));

        StaticReferenceCheck.Result result = StaticReferenceCheck.checkVariantHullLinks(
                index, ModMetadataCheck.ConversionMode.NORMAL);

        assertEquals(0, result.hulls());
        assertEquals(1, result.skipped());
        assertEquals(1, result.findings().size());
        SetupAnalysis.Finding finding = result.findings().get(0);
        assertEquals("static-reference.variant-hull-universe-incomplete", finding.code());
        assertEquals(SetupAnalysis.Severity.UNKNOWN, finding.severity());
        assertEquals(1L, finding.parameters().get("references"));
    }

    @Test
    void nestedSkinHullIdCannotPopulateTheAuthoritativeHullUniverse() throws Exception {
        Path root = Files.createDirectories(temp.resolve("skin"));
        ProviderFile skin = file(root, "data/hulls/skins/example.skin", """
                {"baseHullId":"base_hull","metadata":{"skinHullId":"claimed_skin"}}
                """);
        ProviderFile variant = file(root, "data/variants/example.variant", """
                {"variantId":"example_Variant","hullId":"claimed_skin"}
                """);
        ResourceIndex index = index(
                List.of(new ResourceIndex.Root("example.mod", root, false)),
                Map.of(
                        "data/hulls/skins/example.skin", List.of(skin.provider(0)),
                        "data/variants/example.variant", List.of(variant.provider(0))));

        StaticReferenceCheck.Result result = StaticReferenceCheck.checkVariantHullLinks(
                index, ModMetadataCheck.ConversionMode.NORMAL);

        assertEquals(0, result.skins());
        assertEquals(1, result.skipped());
        assertEquals(1, result.findings().size());
        assertEquals("static-reference.variant-hull-universe-incomplete", result.findings().get(0).code());
    }

    @Test
    void nestedVariantHullIdDoesNotCreateADeclaredReference() throws Exception {
        Path root = Files.createDirectories(temp.resolve("variant-hull"));
        ProviderFile variant = file(root, "data/variants/example.variant", """
                {"variantId":"example_Variant","metadata":{"hullId":"missing_hull"}}
                """);
        ResourceIndex index = index(
                List.of(new ResourceIndex.Root("example.mod", root, false)),
                Map.of("data/variants/example.variant", List.of(variant.provider(0))));

        StaticReferenceCheck.Result result = StaticReferenceCheck.checkVariantHullLinks(
                index, ModMetadataCheck.ConversionMode.NORMAL);

        assertEquals(List.of(), result.findings());
        assertEquals(1, result.variants());
        assertEquals(0, result.skipped());
    }

    @Test
    void nestedVariantIdDoesNotEnterMissingHullEvidence() throws Exception {
        Path root = Files.createDirectories(temp.resolve("variant-id"));
        ProviderFile variant = file(root, "data/variants/example.variant", """
                {"metadata":{"variantId":"nested_Variant"},"hullId":"missing_hull"}
                """);
        ResourceIndex index = index(
                List.of(new ResourceIndex.Root("example.mod", root, false)),
                Map.of("data/variants/example.variant", List.of(variant.provider(0))));

        StaticReferenceCheck.Result result = StaticReferenceCheck.checkVariantHullLinks(
                index, ModMetadataCheck.ConversionMode.NORMAL);

        assertEquals(1, result.findings().size());
        SetupAnalysis.Finding finding = result.findings().get(0);
        assertEquals("static-reference.variant-missing-hull", finding.code());
        assertFalse(finding.parameters().containsKey("variantId"));
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
