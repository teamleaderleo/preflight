package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.starsector.preflight.core.PreparedAudioCache;
import dev.starsector.preflight.core.PreparedAudioManifest;
import dev.starsector.preflight.core.PreparedAudioManifestIO;
import dev.starsector.preflight.core.ResourceIndex;
import dev.starsector.preflight.core.ResourceIndexIO;
import dev.starsector.preflight.core.TextureManifest;
import dev.starsector.preflight.core.TextureManifestIO;
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
    void desktopInspectionSharesOneCurrentProfileAcrossInventoryAndHealth() throws Exception {
        PreflightHome home = PreflightHome.resolve(Platform.OTHER, directory, Map.of());
        String profile = "a".repeat(64);

        Map<String, Object> inspection = CacheCommand.inspect(
                home, new CacheCommand.CurrentProfile(profile, null, null, null));

        assertEquals("starsector-preflight-cache-inspection-v1", inspection.get("format"));
        Map<String, Object> cache = (Map<String, Object>) inspection.get("cache");
        Map<String, Object> health = (Map<String, Object>) inspection.get("health");
        assertEquals(profile, cache.get("currentProfileFingerprint"));
        assertEquals(profile, health.get("profileFingerprint"));
        assertEquals("cold", health.get("status"));
    }

    @Test
    void cacheHealthDistinguishesColdReadyAndProfileScopedRepair() throws Exception {
        PreflightHome home = PreflightHome.resolve(Platform.OTHER, directory, Map.of());
        String profile = "a".repeat(64);

        CacheHealth.Report cold = CacheHealth.inspect(home, profile);
        assertEquals("cold", cold.status());
        assertEquals(List.of(), cold.issues());

        Path index = ResourceIndexIO.directory(home.cache()).resolve(profile + ".spfi");
        Path manifest = TextureManifestIO.directory(home.cache()).resolve(profile + ".spfm");
        Path otherProfile = ResourceIndexIO.directory(home.cache()).resolve("b".repeat(64) + ".spfi");
        Path sharedBlob = home.cache().resolve("textures").resolve("shared.blob");
        ResourceIndexIO.write(index, new ResourceIndex(
                profile,
                List.of(new ResourceIndex.Root("core", Path.of("core"), true)),
                Map.of()));
        TextureManifestIO.write(manifest, new TextureManifest(profile, Map.of()));
        Files.writeString(otherProfile, "another profile stays intact");
        Files.createDirectories(sharedBlob.getParent());
        Files.writeString(sharedBlob, "shared content stays intact");
        assertEquals("ready", CacheHealth.inspect(home, profile).status());

        Files.writeString(manifest, "corrupt");
        CacheHealth.Report damaged = CacheHealth.inspect(home, profile);
        assertEquals("repair-needed", damaged.status());
        assertEquals(1, damaged.issues().size());
        assertEquals(Set.of("resource-index", "texture-manifest"), damaged.targets().stream()
                .map(CacheHealth.Target::artifact)
                .collect(java.util.stream.Collectors.toSet()));

        CacheHealth.Repair preview = CacheHealth.repair(home, profile, false);
        assertTrue(preview.safe());
        assertFalse(preview.applied());
        assertTrue(Files.exists(index));
        assertTrue(Files.exists(manifest));

        CacheHealth.Repair applied = CacheHealth.repair(home, profile, true);
        assertTrue(applied.applied());
        assertEquals(2, applied.files());
        assertFalse(Files.exists(index));
        assertFalse(Files.exists(manifest));
        assertEquals("another profile stays intact", Files.readString(otherProfile));
        assertEquals("shared content stays intact", Files.readString(sharedBlob));
        assertEquals("cold", CacheHealth.inspect(home, profile).status());
    }

    @Test
    void cacheRepairRefusesWhenTheCurrentProfileIsUnknown() throws Exception {
        PreflightHome home = PreflightHome.resolve(Platform.OTHER, directory, Map.of());
        CacheHealth.Repair repair = CacheHealth.repair(home, null, true);
        assertFalse(repair.safe());
        assertFalse(repair.applied());
        assertEquals(0, repair.files());
    }

    @Test
    void cacheHealthRejectsPreparedAudioThatLaunchWouldReject() throws Exception {
        PreflightHome home = PreflightHome.resolve(Platform.OTHER, directory, Map.of());
        String profile = "a".repeat(64);
        String currentBuild = "b".repeat(64);
        String staleBuild = "c".repeat(64);
        String decoder = "d".repeat(64);
        Path index = ResourceIndexIO.directory(home.cache()).resolve(profile + ".spfi");
        Path textureManifest = TextureManifestIO.directory(home.cache()).resolve(profile + ".spfm");
        ResourceIndexIO.write(index, new ResourceIndex(
                profile,
                List.of(new ResourceIndex.Root("core", Path.of("core"), true)),
                Map.of()));
        TextureManifestIO.write(textureManifest, new TextureManifest(profile, Map.of()));
        Path audioManifest = PreparedAudioCache.manifestDirectory(home.cache())
                .resolve(profile + ".spam");
        PreparedAudioManifestIO.write(audioManifest, new PreparedAudioManifest(
                profile, staleBuild, decoder, Map.of()));

        CacheHealth.Report unverifiable = CacheHealth.inspect(home, profile);
        assertEquals("unknown", unverifiable.status());
        assertEquals(List.of(), unverifiable.targets());

        CacheHealth.Report current = CacheHealth.inspect(
                home, profile, null, staleBuild, decoder);
        assertEquals("ready", current.status());
        assertEquals(List.of(), current.targets());

        CacheHealth.Report staleDecoder = CacheHealth.inspect(
                home, profile, null, staleBuild, "e".repeat(64));
        assertEquals("repair-needed", staleDecoder.status());
        assertEquals("prepared-audio-manifest", staleDecoder.issues().get(0).artifact());
        assertTrue(staleDecoder.issues().get(0).summary().contains("different decoder policy"));

        CacheHealth.Report stale = CacheHealth.inspect(
                home, profile, null, currentBuild, decoder);
        assertEquals("repair-needed", stale.status());
        assertEquals("prepared-audio-manifest", stale.issues().get(0).artifact());
        assertTrue(stale.issues().get(0).summary().contains("different Starsector build"));

        CacheHealth.Repair repaired = CacheHealth.repair(
                home, profile, true, currentBuild, decoder);
        assertTrue(repaired.safe());
        assertTrue(repaired.applied());
        assertEquals("ready", repaired.status());
        assertFalse(Files.exists(audioManifest));
        assertTrue(Files.exists(index));
        assertTrue(Files.exists(textureManifest));
    }

    @Test
    void cacheHealthRetainsTheBoundedProfileIdentityFailure() {
        PreflightHome home = PreflightHome.resolve(Platform.OTHER, directory, Map.of());
        String reason = "The selected installation doesn't contain a readable enabled_mods.json.";

        CacheHealth.Report report = CacheHealth.inspect(home, null, reason);

        assertEquals("unknown", report.status());
        assertEquals("profile-identity", report.issues().get(0).artifact());
        assertEquals(reason, report.issues().get(0).summary());
    }

    @Test
    void cacheRepairRefusesASymlinkedNamespaceAndPreservesTheOutsideFile() throws Exception {
        PreflightHome home = PreflightHome.resolve(Platform.OTHER, directory, Map.of());
        String profile = "c".repeat(64);
        Path outside = directory.resolve("outside-manifests");
        Files.createDirectories(outside);
        Path externalManifest = outside.resolve(profile + ".spfm");
        Files.writeString(externalManifest, "outside data must survive");
        Path manifests = TextureManifestIO.directory(home.cache());
        Files.createDirectories(manifests.getParent());
        try {
            Files.createSymbolicLink(manifests, outside);
        } catch (UnsupportedOperationException | SecurityException | java.io.IOException error) {
            assumeTrue(false, "Symbolic links aren't available in this test environment: " + error);
        }

        CacheHealth.Report report = CacheHealth.inspect(home, profile);
        assertEquals("unsafe", report.status());
        CacheHealth.Repair repair = CacheHealth.repair(home, profile, true);
        assertFalse(repair.safe());
        assertFalse(repair.applied());
        assertEquals("outside data must survive", Files.readString(externalManifest));
    }

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
        Files.createDirectories(home.root().resolve("history"));
        Files.writeString(home.cache().resolve("resource-indexes").resolve(current + ".spfi"),
                "current-index");
        Files.writeString(home.cache().resolve("manifests").resolve(current + ".spfm"),
                "current-manifest");
        Files.writeString(home.cache().resolve("resource-indexes").resolve(retained + ".spfi"),
                "retained-index");
        Files.writeString(home.cache().resolve("packs").resolve(current + ".spfp"), "pack");
        Files.writeString(home.runs().resolve("adapter.json"), "evidence");
        Files.writeString(home.root().resolve("history").resolve("launches.jsonl"), "launch");
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
        assertTrue(categories.stream().anyMatch(category -> "history".equals(category.get("path"))
                && "history".equals(category.get("group"))
                && ((Number) category.get("files")).longValue() == 1));
        List<Map<String, Object>> groups = (List<Map<String, Object>>) report.get("groups");
        assertTrue(groups.stream().anyMatch(group -> "acceleration".equals(group.get("id"))));
        assertTrue(groups.stream().anyMatch(group -> "evidence".equals(group.get("id"))));
        assertTrue(groups.stream().anyMatch(group -> "history".equals(group.get("id"))));
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
