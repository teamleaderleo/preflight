package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.Json;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopSmokeEvidenceBoundedArtifactHashTest {
    private static final long MAX_ARTIFACT_BYTES = 256L * 1024 * 1024;
    private static final long MAX_TOTAL_ARTIFACT_BYTES = 512L * 1024 * 1024;

    @TempDir
    Path temporaryDirectory;

    @Test
    void exactCallerSuppliedHashLimitIsInclusiveAndKeepsSha256() throws Exception {
        byte[] bytes = "artifact".getBytes(StandardCharsets.UTF_8);

        String actual = DesktopSmokeEvidence.hashArtifact(
                new ByteArrayInputStream(bytes), bytes.length, "stable-artifact");

        assertEquals(MAX_ARTIFACT_BYTES, DesktopSmokeEvidence.artifactHashLimit(0));
        assertEquals(Hashes.sha256(bytes), actual);
    }

    @Test
    void initiallyOversizedArtifactIsRefusedBeforeHashing() throws Exception {
        Path run = Files.createDirectory(temporaryDirectory.resolve("oversized-run"));
        Path artifact = run.resolve("too-large.bin");
        try (FileChannel channel = FileChannel.open(
                artifact, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.position(MAX_ARTIFACT_BYTES);
            channel.write(ByteBuffer.wrap(new byte[] {1}));
        }
        assertEquals(MAX_ARTIFACT_BYTES + 1L, Files.size(artifact));
        Path request = writeRequest(run, artifact.getFileName().toString());
        Path output = run.resolve("smoke-evidence.json");

        IOException error = assertThrows(
                IOException.class,
                () -> DesktopSmokeEvidence.collect(scenario(), request, output));

        assertTrue(error.getMessage().contains("Smoke artifact exceeds " + MAX_ARTIFACT_BYTES),
                error.getMessage());
        assertFalse(Files.exists(output));
    }

    @Test
    void growthAfterHashingBeginsIsRejectedAtPerArtifactLimitPlusOne() throws Exception {
        Path artifact = temporaryDirectory.resolve("growing-artifact.bin");
        Files.write(artifact, new byte[] {1, 2, 3, 4});
        boolean[] appended = {false};

        try (InputStream raw = Files.newInputStream(artifact);
                InputStream growing = growing(raw, artifact, appended, (byte) 5)) {
            IOException error = assertThrows(
                    IOException.class,
                    () -> DesktopSmokeEvidence.hashArtifact(growing, 4, artifact.toString()));
            assertTrue(error.getMessage().contains("exceeds 4 bytes"), error.getMessage());
        }

        assertTrue(appended[0]);
        assertEquals(5L, Files.size(artifact));
    }

    @Test
    void aggregateRemainingBudgetBecomesTheNextHashReadLimit() throws Exception {
        long remaining = DesktopSmokeEvidence.artifactHashLimit(MAX_TOTAL_ARTIFACT_BYTES - 3);
        assertEquals(3L, remaining);

        byte[] fourBytes = {1, 2, 3, 4};
        IOException error = assertThrows(
                IOException.class,
                () -> DesktopSmokeEvidence.hashArtifact(
                        new ByteArrayInputStream(fourBytes), remaining, "aggregate-remainder"));
        assertTrue(error.getMessage().contains("exceeds 3 bytes"), error.getMessage());
    }

    @Test
    void growthAtRemainingAggregateBudgetIsRejectedAtLimitPlusOne() throws Exception {
        long remaining = DesktopSmokeEvidence.artifactHashLimit(MAX_TOTAL_ARTIFACT_BYTES - 3);
        Path artifact = temporaryDirectory.resolve("aggregate-growing.bin");
        Files.write(artifact, new byte[] {1, 2, 3});
        boolean[] appended = {false};

        try (InputStream raw = Files.newInputStream(artifact);
                InputStream growing = growing(raw, artifact, appended, (byte) 4)) {
            IOException error = assertThrows(
                    IOException.class,
                    () -> DesktopSmokeEvidence.hashArtifact(
                            growing, remaining, artifact.toString()));
            assertTrue(error.getMessage().contains("exceeds 3 bytes"), error.getMessage());
        }

        assertTrue(appended[0]);
        assertEquals(4L, Files.size(artifact));
    }

    @Test
    void finalComponentSymlinkArtifactIsNotReadable() throws Exception {
        Path run = Files.createDirectory(temporaryDirectory.resolve("symlink-run"));
        Path target = Files.writeString(run.resolve("target.bin"), "secret");
        Path alias = run.resolve("alias.bin");
        try {
            Files.createSymbolicLink(alias, target.getFileName());
        } catch (UnsupportedOperationException | IOException unavailable) {
            assumeTrue(false, "Symbolic links are unavailable: " + unavailable.getMessage());
        }
        Path request = writeRequest(run, alias.getFileName().toString());
        Path output = run.resolve("smoke-evidence.json");

        assertThrows(
                IOException.class,
                () -> DesktopSmokeEvidence.collect(scenario(), request, output));
        assertFalse(Files.exists(output));
    }

    private static InputStream growing(
            InputStream raw, Path artifact, boolean[] appended, byte extra) {
        return new FilterInputStream(raw) {
            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                int requested = appended[0] ? length : Math.min(1, length);
                int read = super.read(buffer, offset, requested);
                if (!appended[0] && read > 0) {
                    Files.write(artifact, new byte[] {extra}, StandardOpenOption.APPEND);
                    appended[0] = true;
                }
                return read;
            }
        };
    }

    private Path writeRequest(Path run, String artifactPath) throws Exception {
        Map<String, Object> driver = new LinkedHashMap<>();
        driver.put("id", "test-driver");
        driver.put("version", "1.0");
        driver.put("platform", "mac");
        driver.put("capabilities", List.of(
                "process-control", "semantic-state", "screen-capture"));

        Map<String, Object> step = new LinkedHashMap<>();
        step.put("id", "capture");
        step.put("status", "passed");
        step.put("startedAt", Instant.parse("2026-08-20T00:00:00Z"));
        step.put("completedAt", Instant.parse("2026-08-20T00:00:01Z"));
        step.put("detail", "test");
        step.put("artifacts", List.of(Map.of("kind", "screenshot", "path", artifactPath)));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("format", DesktopSmokeEvidence.DRIVER_RESULT_FORMAT);
        request.put("status", "passed");
        request.put("startedAt", Instant.parse("2026-08-20T00:00:00Z"));
        request.put("completedAt", Instant.parse("2026-08-20T00:00:01Z"));
        request.put("driver", driver);
        request.put("runDirectory", run.toAbsolutePath().toString());
        request.put("steps", List.of(step));
        request.put("diagnostics", List.of());

        Path path = temporaryDirectory.resolve("artifact-request-" + System.nanoTime() + ".json");
        Files.writeString(path, Json.object(request));
        return path;
    }

    private static DesktopSmokeScenario scenario() {
        return DesktopSmokeScenario.parse("""
                {
                  "format": "starsector-preflight-smoke-v1",
                  "name": "artifact-boundary",
                  "timeoutSeconds": 60,
                  "launch": {"preset": "fast", "textureStorage": "balanced", "profile": null},
                  "steps": [
                    {"id": "capture", "kind": "capture", "artifacts": ["screenshot"]}
                  ]
                }
                """);
    }
}
