package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CacheCommandTest {
    @TempDir
    Path directory;

    @Test
    @SuppressWarnings("unchecked")
    void jsonReportIsAStableMachineReadableStorageAndProfileSnapshot() throws Exception {
        PreflightHome home = PreflightHome.resolve(Platform.MAC, directory, Map.of());
        String current = "a".repeat(64);
        String retained = "b".repeat(64);
        Files.createDirectories(home.cache().resolve("resource-indexes"));
        Files.createDirectories(home.cache().resolve("manifests"));
        Files.createDirectories(home.cache().resolve("packs"));
        Files.createDirectories(home.runs());
        Files.writeString(home.cache().resolve("resource-indexes").resolve(current + ".spfi"),
                "current-index");
        Files.writeString(home.cache().resolve("manifests").resolve(current + ".spfm"),
                "current-manifest");
        Files.writeString(home.cache().resolve("resource-indexes").resolve(retained + ".spfi"),
                "retained-index");
        Files.writeString(home.cache().resolve("packs").resolve(current + ".spfp"), "pack");
        Files.writeString(home.runs().resolve("adapter.json"), "evidence");
        Files.writeString(home.root().resolve("future-cache-format"), "future");

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertEquals(0, CacheCommand.reportJson(
                home, current, new PrintStream(bytes, true, StandardCharsets.UTF_8)));
        Map<String, Object> report = StrictJson.object(bytes.toString(StandardCharsets.UTF_8));

        assertEquals("starsector-preflight-cache-v1", report.get("format"));
        assertEquals(home.root().toString(), report.get("root"));
        assertEquals(Boolean.TRUE, report.get("present"));
        Map<String, Object> total = (Map<String, Object>) report.get("total");
        assertTrue(((Number) total.get("bytes")).longValue() > 0);
        assertTrue(((Number) total.get("files")).longValue() >= 4);

        List<Map<String, Object>> categories =
                (List<Map<String, Object>>) report.get("categories");
        assertTrue(categories.stream().anyMatch(category -> "runs".equals(category.get("path"))
                && "evidence".equals(category.get("group"))
                && ((Number) category.get("files")).longValue() == 1));
        assertTrue(categories.stream().anyMatch(category -> "cache/packs".equals(category.get("path"))
                && "acceleration".equals(category.get("group"))
                && ((Number) category.get("files")).longValue() == 1));
        List<Map<String, Object>> groups = (List<Map<String, Object>>) report.get("groups");
        assertTrue(groups.stream().anyMatch(group -> "acceleration".equals(group.get("id"))));
        assertTrue(groups.stream().anyMatch(group -> "evidence".equals(group.get("id"))));
        assertEquals(6L, ((Number) report.get("uncategorizedBytes")).longValue());

        List<Map<String, Object>> profiles =
                (List<Map<String, Object>>) report.get("profiles");
        assertEquals(2, profiles.size());
        assertTrue(profiles.stream().anyMatch(profile -> current.equals(profile.get("fingerprint"))
                && Boolean.TRUE.equals(profile.get("current"))));
        assertTrue(profiles.stream().anyMatch(profile -> retained.equals(profile.get("fingerprint"))
                && Boolean.FALSE.equals(profile.get("current"))));
        assertFalse(((List<?>) report.get("integrations")).isEmpty());
    }

    @Test
    void jsonReportStillHasACompleteShapeWhenNothingExists() throws Exception {
        PreflightHome home = PreflightHome.resolve(Platform.OTHER, directory, Map.of());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        assertEquals(0, CacheCommand.reportJson(
                home, null, new PrintStream(bytes, true, StandardCharsets.UTF_8)));
        Map<String, Object> report = StrictJson.object(bytes.toString(StandardCharsets.UTF_8));

        assertEquals(Boolean.FALSE, report.get("present"));
        assertEquals(List.of(), report.get("profiles"));
        assertEquals(List.of(), report.get("integrations"));
        assertNull(report.get("currentProfileFingerprint"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void cleanupJsonSummarizesEveryRemovalAndBoundsThePathSample() {
        List<CachePrune.Removal> removals = IntStream.range(0, 105)
                .mapToObj(index -> new CachePrune.Removal(
                        directory.resolve("stale-" + index),
                        10,
                        index < 100 ? "unreferenced blob" : "stale profile"))
                .toList();
        CachePrune.Plan plan = new CachePrune.Plan(removals, List.of(), 12, 3);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        CacheCommand.emitPruneJson(
                "a".repeat(64),
                Set.of("a".repeat(64)),
                plan,
                true,
                false,
                List.of(),
                new PrintStream(bytes, true, StandardCharsets.UTF_8));
        Map<String, Object> report = StrictJson.object(bytes.toString(StandardCharsets.UTF_8));

        assertEquals(105L, ((Number) report.get("files")).longValue());
        assertEquals(1_050L, ((Number) report.get("bytes")).longValue());
        assertEquals(Boolean.TRUE, report.get("removalsTruncated"));
        assertEquals(100, ((List<?>) report.get("removals")).size());
        List<Map<String, Object>> groups = (List<Map<String, Object>>) report.get("groups");
        assertTrue(groups.stream().anyMatch(group -> "unreferenced blob".equals(group.get("reason"))
                && ((Number) group.get("files")).longValue() == 100));
        assertTrue(groups.stream().anyMatch(group -> "stale profile".equals(group.get("reason"))
                && ((Number) group.get("bytes")).longValue() == 50));
    }
}
