package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
