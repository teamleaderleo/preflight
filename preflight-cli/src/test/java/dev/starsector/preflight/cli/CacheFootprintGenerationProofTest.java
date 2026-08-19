package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.TextureSourceGenerationProofIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CacheFootprintGenerationProofTest {
    @TempDir
    Path directory;

    @Test
    void sourceGenerationProofBytesAreReportedAsAccelerationData() throws Exception {
        PreflightHome home = PreflightHome.resolve(Platform.OTHER, directory, Map.of());
        Path proofs = TextureSourceGenerationProofIO.directory(home.cache());
        Files.createDirectories(proofs);
        Files.write(proofs.resolve("profile.sptg"), new byte[] {1, 2, 3, 4});

        CacheFootprint.Report report = CacheFootprint.measure(home);
        CacheFootprint.Entry entry = report.entries().stream()
                .filter(candidate -> "cache/texture-source-generations-v1".equals(candidate.path()))
                .findFirst()
                .orElseThrow();

        assertEquals("acceleration", entry.group());
        assertEquals(4L, entry.usage().bytes());
        assertEquals(1L, entry.usage().files());
        assertTrue(entry.description().contains("source-generation proofs"));
        assertEquals(0L, report.uncategorizedBytes());
    }
}
