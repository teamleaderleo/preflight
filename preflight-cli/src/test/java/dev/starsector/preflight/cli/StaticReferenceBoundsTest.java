package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.starsector.preflight.core.ResourceIndex;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
    void excessiveMissingHullFindingsStayBlockingAndBounded() throws Exception {
        Path root = Files.createDirectories(temp.resolve("many-missing"));
        Map<String, List<ResourceIndex.Provider>> entries = new LinkedHashMap<>();
        List<Path> files = new ArrayList<>();
        for (int index = 0; index < 257; index++) {
            String relative = "data/variants/missing-" + String.format("%03d", index) + ".variant";
            Path file = root.resolve(relative);
            Files.createDirectories(file.getParent());
            byte[] bytes = ("{\"variantId\":\"variant_" + index + "\",\"hullId\":\"missing_" + index + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            Files.write(file, bytes);
            files.add(file);
            long modified = Math.max(1L, Files.getLastModifiedTime(file).toMillis());
            entries.put(relative, List.of(new ResourceIndex.Provider(0, relative, bytes.length, modified)));
        }
        ResourceIndex resourceIndex = new ResourceIndex(
                "profile-fingerprint",
                List.of(new ResourceIndex.Root("example.mod", root, false)),
                entries);

        StaticReferenceCheck.Result result = StaticReferenceCheck.checkVariantHullLinks(
                resourceIndex,
                ModMetadataCheck.ConversionMode.NORMAL);

        assertEquals(256, result.findings().size());
        assertEquals(255, result.findings().stream()
                .filter(finding -> finding.code().equals("static-reference.variant-missing-hull"))
                .count());
        SetupAnalysis.Finding truncated = result.findings().stream()
                .filter(finding -> finding.code().equals("static-reference.findings-truncated"))
                .findFirst()
                .orElseThrow();
        assertEquals(SetupAnalysis.Severity.BLOCKING, truncated.severity());
        assertEquals(2L, truncated.parameters().get("omittedReferences"));
        assertEquals(257, result.variants());
    }
}
