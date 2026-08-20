package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.starsector.preflight.core.ResourceIndex;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StaticReferenceOptionalVariantIdTest {
    @TempDir
    Path temp;

    @Test
    void duplicateOptionalVariantIdCannotSuppressAuthoritativeMissingHullBlocker() throws Exception {
        Path root = Files.createDirectories(temp.resolve("mod"));
        ProviderFile hull = file(root, "data/hulls/present.ship", "{\"hullId\":\"present_hull\"}");
        ProviderFile variant = file(
                root,
                "data/variants/missing.variant",
                "{\"variantId\":\"first\",\"variantId\":\"second\",\"hullId\":\"missing_hull\"}");
        LinkedHashMap<String, List<ResourceIndex.Provider>> entries = new LinkedHashMap<>();
        entries.put(hull.relative(), List.of(hull.provider(0)));
        entries.put(variant.relative(), List.of(variant.provider(0)));
        ResourceIndex index = new ResourceIndex(
                "profile-fingerprint",
                List.of(new ResourceIndex.Root("example.mod", root, false)),
                entries);

        StaticReferenceCheck.Result result = StaticReferenceCheck.checkVariantHullLinks(
                index,
                ModMetadataCheck.ConversionMode.NORMAL);

        assertEquals(0, result.skipped());
        assertEquals(1, result.variants());
        assertEquals(1, result.findings().size());
        SetupAnalysis.Finding finding = result.findings().get(0);
        assertEquals("static-reference.variant-missing-hull", finding.code());
        assertEquals(SetupAnalysis.Severity.BLOCKING, finding.severity());
        assertEquals("missing_hull", finding.parameters().get("hullId"));
        assertFalse(finding.parameters().containsKey("variantId"));
    }

    private ProviderFile file(Path root, String relative, String content) throws Exception {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        Files.write(path, bytes);
        long modified = Math.max(0L, Files.getLastModifiedTime(path).toMillis());
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
