package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Evidence categories report what they are made of, not only how large they are.
 *
 * <p>This is the check #471 did not have. A category total says what a directory is for; it cannot
 * say that one document inside it is present once per launch and read by nobody. Grouping by file
 * name across session directories turns that into a line somebody can look at and object to.
 */
class CacheFootprintArtifactsTest {
    @TempDir
    Path root;

    @Test
    void oneDocumentRepeatedPerSessionIsReportedAsOneLine() throws IOException {
        writeSession("20260101-000001", 400, 30);
        writeSession("20260101-000002", 400, 30);
        writeSession("20260101-000003", 400, 30);

        List<CacheFootprint.Artifact> artifacts = CacheFootprint.artifacts(root);

        assertEquals(2, artifacts.size(), artifacts.toString());
        CacheFootprint.Artifact largest = artifacts.get(0);
        assertEquals("contract.json", largest.name());
        assertEquals(1_200, largest.bytes());
        assertEquals(3, largest.files(), "three sessions each hold one copy");
        assertEquals("run.json", artifacts.get(1).name());
    }

    @Test
    void uniquelyNamedFilesCannotMakeTheReportUnbounded() throws IOException {
        Path session = Files.createDirectories(root.resolve("session"));
        for (int index = 0; index < 200; index++) {
            Files.write(session.resolve("unique-" + index + ".json"), new byte[index + 1]);
        }

        List<CacheFootprint.Artifact> artifacts = CacheFootprint.artifacts(root);

        assertEquals(13, artifacts.size(), "twelve names plus one remainder");
        CacheFootprint.Artifact remainder = artifacts.get(artifacts.size() - 1);
        assertEquals("(188 other names)", remainder.name());
        assertEquals(188, remainder.files());
        long total = artifacts.stream().mapToLong(CacheFootprint.Artifact::bytes).sum();
        assertEquals(CacheFootprint.usage(root).bytes(), total, "every byte is still accounted for");
    }

    @Test
    void anAbsentDirectoryReportsNothingRatherThanFailing() throws IOException {
        assertTrue(CacheFootprint.artifacts(root.resolve("never-created")).isEmpty());
    }

    private void writeSession(String name, int contractBytes, int runBytes) throws IOException {
        Path session = Files.createDirectories(root.resolve(name));
        Files.write(session.resolve("contract.json"), new byte[contractBytes]);
        Files.write(session.resolve("run.json"), new byte[runBytes]);
    }
}
