package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.ResourceIndexIO;
import dev.starsector.preflight.core.TextureManifestIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CacheFootprintTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void measureMatchesLegacyPerDirectorySemanticsFromOneRootSnapshot() throws Exception {
        PreflightHome home = new PreflightHome(temporaryDirectory, List.of());
        Path root = home.root();

        write(root.resolve("runs/session-a/summary.json"), 3);
        write(root.resolve("runs/session-b/summary.json"), 5);
        write(root.resolve("runs/session-b/console.txt"), 7);
        write(root.resolve("history/launches.json"), 11);
        write(root.resolve("elsewhere/unclassified.bin"), 13);
        Files.createDirectories(root.resolve("benchmarks"));

        String fingerprint = "a".repeat(64);
        Path index = ResourceIndexIO.directory(root.resolve("cache")).resolve(fingerprint + ".spfi");
        Path manifest = TextureManifestIO.directory(root.resolve("cache")).resolve(fingerprint + ".spfm");
        write(index, 17);
        write(manifest, 19);
        Files.setLastModifiedTime(index, FileTime.fromMillis(1_000));
        Files.setLastModifiedTime(manifest, FileTime.fromMillis(2_000));

        CacheFootprint.Report report = CacheFootprint.measure(home);

        assertTrue(report.present());
        assertEquals(CacheFootprint.usage(root), report.whole());
        long categorizedBytes = 0;
        for (CacheFootprint.Entry entry : report.entries()) {
            Path category = root.resolve(entry.path());
            assertEquals(CacheFootprint.usage(category), entry.usage(), entry.path());
            if ("evidence".equals(entry.group())) {
                assertEquals(CacheFootprint.artifacts(category), entry.artifacts(), entry.path());
            }
            categorizedBytes += entry.usage().bytes();
        }
        assertEquals(report.whole().bytes() - categorizedBytes, report.uncategorizedBytes());
        assertEquals(13, report.uncategorizedBytes());

        CacheFootprint.Entry emptyBenchmark = report.entries().stream()
                .filter(entry -> "benchmarks".equals(entry.path()))
                .findFirst()
                .orElseThrow();
        assertEquals(new CacheFootprint.Usage(0, 0), emptyBenchmark.usage());

        CacheFootprint.Entry runs = report.entries().stream()
                .filter(entry -> "runs".equals(entry.path()))
                .findFirst()
                .orElseThrow();
        assertEquals(new CacheFootprint.Usage(15, 3), runs.usage());
        assertEquals(List.of(
                new CacheFootprint.Artifact("summary.json", 8, 2),
                new CacheFootprint.Artifact("console.txt", 7, 1)), runs.artifacts());

        assertEquals(1, report.profiles().size());
        CacheFootprint.Profile profile = report.profiles().get(0);
        assertEquals(fingerprint, profile.fingerprint());
        assertEquals(17, profile.indexBytes());
        assertEquals(19, profile.manifestBytes());
        assertEquals(36, profile.bytes());
        assertEquals(2_000, profile.lastModifiedMillis());
    }

    @Test
    void missingRootRemainsAnEmptyAbsentReport() throws Exception {
        PreflightHome home = new PreflightHome(temporaryDirectory.resolve("missing"), List.of());

        CacheFootprint.Report report = CacheFootprint.measure(home);

        assertFalse(report.present());
        assertEquals(new CacheFootprint.Usage(0, 0), report.whole());
        assertEquals(0, report.uncategorizedBytes());
        assertTrue(report.entries().isEmpty());
        assertTrue(report.profiles().isEmpty());
    }

    private static void write(Path file, int bytes) throws Exception {
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[bytes]);
    }
}
