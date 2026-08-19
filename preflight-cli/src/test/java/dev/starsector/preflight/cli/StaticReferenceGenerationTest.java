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

class StaticReferenceGenerationTest {
    @TempDir
    Path temp;

    @Test
    void providerReplacedAfterIndexSnapshotIsNonAuthoritative() throws Exception {
        Path root = Files.createDirectories(temp.resolve("mod"));
        Path variant = root.resolve("data/variants/example.variant");
        Files.createDirectories(variant.getParent());
        byte[] indexedBytes = "{\"variantId\":\"example\",\"hullId\":\"old_hull\"}"
                .getBytes(StandardCharsets.UTF_8);
        Files.write(variant, indexedBytes);
        long indexedModified = Math.max(1L, Files.getLastModifiedTime(variant).toMillis());

        ResourceIndex.Provider indexed = new ResourceIndex.Provider(
                0,
                "data/variants/example.variant",
                indexedBytes.length,
                indexedModified);
        ResourceIndex index = new ResourceIndex(
                "profile-fingerprint",
                List.of(new ResourceIndex.Root("example.mod", root, false)),
                new LinkedHashMap<>(Map.of(
                        "data/variants/example.variant", List.of(indexed))));

        Files.writeString(
                variant,
                "{\"variantId\":\"example\",\"hullId\":\"new_missing_hull_with_different_size\"}",
                StandardCharsets.UTF_8);

        StaticReferenceCheck.Result result = StaticReferenceCheck.checkVariantHullLinks(
                index,
                ModMetadataCheck.ConversionMode.NORMAL);

        assertEquals(List.of(), result.findings());
        assertEquals(0, result.variants());
        assertEquals(1, result.skipped());
    }
}
