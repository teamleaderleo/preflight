package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CacheFormatNamespaceTest {
    @Test
    void establishedFormatKeepsExistingPath() {
        assertEquals(
                Path.of("cache", "packs"),
                CacheFormatNamespace.directory(Path.of("cache"), "packs", 2, 2));
        assertEquals(Path.of("cache", "blobs"), PreparedTextureIO.cacheDirectory(Path.of("cache")));
        assertEquals(
                Path.of("cache", "prepared-audio"),
                PreparedAudioCache.root(Path.of("cache")));
        assertEquals(
                Path.of("cache", "spec-store", "variant-json"),
                SpecStoreCacheDirectories.variantJson(Path.of("cache")));
        assertEquals(
                Path.of("cache", "spec-store", "rules-csv"),
                SpecStoreCacheDirectories.rulesCsv(Path.of("cache")));
        assertEquals(
                Path.of("cache", "classpath"),
                ClasspathCacheDirectories.root(Path.of("cache")));
        assertEquals(
                Path.of("cache", "generated-bytecode"),
                GeneratedBytecodeCache.root(Path.of("cache")));
    }

    @Test
    void laterFormatGetsIndependentNamespace() {
        assertEquals("packs-v3", CacheFormatNamespace.name("packs", 3, 2));
    }

    @Test
    void rejectsUnsafeNamesAndInvalidVersions() {
        assertThrows(IllegalArgumentException.class,
                () -> CacheFormatNamespace.name("../packs", 2, 2));
        assertThrows(IllegalArgumentException.class,
                () -> CacheFormatNamespace.name("packs", 1, 2));
        assertThrows(NullPointerException.class,
                () -> CacheFormatNamespace.directory(null, "packs", 2, 2));
    }
}
