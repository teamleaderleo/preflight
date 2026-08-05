package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.GeneratedBytecodeBundle;
import dev.starsector.preflight.core.GeneratedBytecodeCache;
import dev.starsector.preflight.core.GeneratedBytecodePack;
import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.PreparedTexture;
import dev.starsector.preflight.core.TextureManifest;
import dev.starsector.preflight.core.TextureManifestIO;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CachePruneTest {
    @TempDir
    Path home;

    @Test
    void aBlobSharedWithASurvivingProfileIsKept() throws Exception {
        // This is the whole reason prune reads manifests instead of deleting by filename. Blobs are
        // content-addressed, so the same payload is routinely referenced by several profiles; the
        // name says nothing about who still needs it.
        PreflightHome preflight = home();
        String kept = "a".repeat(64);
        String discarded = "b".repeat(64);
        writeBlob(preflight, "shared");
        writeBlob(preflight, "only-the-old-one");
        writeProfile(preflight, kept, List.of("shared"));
        writeProfile(preflight, discarded, List.of("shared", "only-the-old-one"));

        CachePrune.Plan plan = CachePrune.plan(preflight, Set.of(kept), Set.of());
        assertTrue(plan.safe());
        assertEquals(1, plan.reachableBlobs());

        assertTrue(removedNames(plan).contains(blobName("only-the-old-one")),
                "a blob no survivor references should go: " + removedNames(plan));
        assertFalse(removedNames(plan).contains(blobName("shared")),
                "a blob the surviving profile still references must stay");

        CachePrune.apply(plan);
        assertTrue(Files.isRegularFile(blobPath(preflight, "shared")));
        assertFalse(Files.exists(blobPath(preflight, "only-the-old-one")));
        assertTrue(Files.isRegularFile(
                preflight.cache().resolve("manifests").resolve(kept + ".spfm")));
        assertFalse(Files.exists(
                preflight.cache().resolve("manifests").resolve(discarded + ".spfm")));
        assertFalse(Files.exists(
                preflight.cache().resolve("resource-indexes").resolve(discarded + ".spfi")));
    }

    @Test
    void anUnreadableSurvivingManifestRefusesToPlanAnything() throws Exception {
        // An incomplete reachable set would delete blobs a live profile needs, and that failure is
        // silent until the next launch misses on a texture it was supposed to have prepared.
        PreflightHome preflight = home();
        String kept = "c".repeat(64);
        String discarded = "d".repeat(64);
        writeBlob(preflight, "payload");
        writeProfile(preflight, discarded, List.of("payload"));
        Files.createDirectories(preflight.cache().resolve("manifests"));
        Files.writeString(preflight.cache().resolve("manifests").resolve(kept + ".spfm"), "corrupt");
        Files.createDirectories(preflight.cache().resolve("resource-indexes"));
        Files.writeString(preflight.cache().resolve("resource-indexes").resolve(kept + ".spfi"), "x");

        CachePrune.Plan plan = CachePrune.plan(preflight, Set.of(kept), Set.of());
        assertFalse(plan.safe(), "an unreadable survivor must abort the plan");
        assertTrue(plan.removals().stream().noneMatch(
                        removal -> "unreferenced blob".equals(removal.reason())),
                "no blob may be planned for removal when the reachable set is incomplete");
        assertTrue(plan.refusals().get(0).contains(kept.substring(0, 16)), plan.refusals().toString());
    }

    @Test
    void aSurvivorWithNoManifestIsNotAnError() throws Exception {
        // A profile can be indexed without ever having had its textures prepared. That contributes
        // no blobs rather than making the plan unsafe.
        PreflightHome preflight = home();
        String kept = "e".repeat(64);
        Files.createDirectories(preflight.cache().resolve("resource-indexes"));
        Files.writeString(preflight.cache().resolve("resource-indexes").resolve(kept + ".spfi"), "x");

        CachePrune.Plan plan = CachePrune.plan(preflight, Set.of(kept), Set.of());
        assertTrue(plan.safe());
        assertTrue(plan.removals().isEmpty());
    }

    @Test
    void specStoreArtifactsAreLeftAloneWhenTheLiveIdentitiesAreUnknown() throws Exception {
        // Spec-store artifacts are keyed by dependency identity, not profile fingerprint, so an
        // empty keep-set means "could not work it out" -- keeping stale megabytes beats guessing.
        PreflightHome preflight = home();
        String kept = "f".repeat(64);
        Path corpus = preflight.cache().resolve("spec-store/variant-json");
        Files.createDirectories(corpus);
        Path live = corpus.resolve("1".repeat(64) + ".spvj");
        Path stale = corpus.resolve("2".repeat(64) + ".spvj");
        Files.writeString(live, "live");
        Files.writeString(stale, "stale");

        assertTrue(CachePrune.plan(preflight, Set.of(kept), Set.of()).removals().isEmpty(),
                "an unknown identity set must leave spec-store alone");

        CachePrune.Plan known =
                CachePrune.plan(preflight, Set.of(kept), Set.of("1".repeat(64)));
        assertEquals(List.of(stale.getFileName().toString()), removedNames(known));
        CachePrune.apply(known);
        assertTrue(Files.isRegularFile(live));
        assertFalse(Files.exists(stale));
    }

    @Test
    void stalePreparedRuleTokensArePrunedByTheirRulesIdentity() throws Exception {
        PreflightHome preflight = home();
        String liveIdentity = "3".repeat(64);
        String staleIdentity = "4".repeat(64);
        Path corpus = preflight.cache().resolve("spec-store/rules-csv");
        Files.createDirectories(corpus);
        Path live = corpus.resolve(liveIdentity + ".sprt");
        Path stale = corpus.resolve(staleIdentity + ".sprt");
        Files.writeString(live, "live");
        Files.writeString(stale, "stale");

        CachePrune.Plan plan = CachePrune.plan(
                preflight, Set.of("5".repeat(64)), Set.of(liveIdentity));

        assertTrue(removedNames(plan).contains(stale.getFileName().toString()));
        assertFalse(removedNames(plan).contains(live.getFileName().toString()));
    }

    @Test
    void staleJaninoContextsAndBundlesProvenRedundantByThePackArePruned() throws Exception {
        PreflightHome preflight = home();
        String liveContext = "6".repeat(64);
        String staleContext = "7".repeat(64);
        byte[] bytecode = classBytes(JaninoFixture.class);
        GeneratedBytecodeBundle bundle = new GeneratedBytecodeBundle(
                liveContext,
                JaninoFixture.class.getName(),
                Map.of(JaninoFixture.class.getName(), bytecode));
        GeneratedBytecodeCache.write(preflight.cache(), bundle);
        Path separate = GeneratedBytecodeCache.bundlePath(
                preflight.cache(), liveContext, JaninoFixture.class.getName());
        GeneratedBytecodePack.Builder builder = new GeneratedBytecodePack.Builder(liveContext);
        assertTrue(builder.record(bundle.requestedClassName(), bundle.classes()));
        Path pack = GeneratedBytecodePack.path(preflight.cache(), liveContext);
        GeneratedBytecodePack.write(pack, builder.build());

        Path stale = preflight.cache().resolve("generated-bytecode/77")
                .resolve(staleContext).resolve("old.spjb");
        Files.createDirectories(stale.getParent());
        Files.writeString(stale, "old context");

        CachePrune.Plan plan = CachePrune.plan(
                preflight, Set.of("8".repeat(64)), Set.of(), Set.of(liveContext));

        assertTrue(plan.removals().stream().anyMatch(removal -> removal.path().equals(separate)
                && "redundant generated-bytecode bundle".equals(removal.reason())));
        assertTrue(plan.removals().stream().anyMatch(removal -> removal.path().equals(stale)
                && removal.reason().startsWith("stale generated-bytecode context ")));
        assertFalse(plan.removals().stream().anyMatch(removal -> removal.path().equals(pack)));

        CachePrune.apply(plan);
        assertTrue(Files.isRegularFile(pack));
        assertFalse(Files.exists(separate));
        assertFalse(Files.exists(stale));
    }

    private PreflightHome home() {
        return PreflightHome.resolve(Platform.MAC, home, Map.of());
    }

    private static List<String> removedNames(CachePrune.Plan plan) {
        return plan.removals().stream()
                .map(removal -> removal.path().getFileName().toString())
                .toList();
    }

    private static String blobName(String content) {
        return Hashes.sha256(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ".sptx";
    }

    private static String blobRelativePath(String content) {
        String name = blobName(content);
        return "blobs/" + name.substring(0, 2) + "/" + name;
    }

    private static Path blobPath(PreflightHome preflight, String content) {
        return preflight.cache().resolve(blobRelativePath(content));
    }

    private static void writeBlob(PreflightHome preflight, String content) throws IOException {
        Path blob = blobPath(preflight, content);
        Files.createDirectories(blob.getParent());
        Files.writeString(blob, content);
    }

    private static void writeProfile(PreflightHome preflight, String fingerprint, List<String> blobs)
            throws IOException {
        Files.createDirectories(preflight.cache().resolve("resource-indexes"));
        Files.writeString(
                preflight.cache().resolve("resource-indexes").resolve(fingerprint + ".spfi"),
                "index for " + fingerprint);

        Map<String, TextureManifest.Entry> entries = new LinkedHashMap<>();
        for (int index = 0; index < blobs.size(); index++) {
            String content = blobs.get(index);
            entries.put("graphics/texture-" + index + ".png", new TextureManifest.Entry(
                    Hashes.sha256(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    PreparedTexture.Transformation.IDENTITY,
                    blobRelativePath(content),
                    2, 2, 4, 16));
        }
        Files.createDirectories(preflight.cache().resolve("manifests"));
        TextureManifestIO.write(
                preflight.cache().resolve("manifests").resolve(fingerprint + ".spfm"),
                new TextureManifest(fingerprint, entries));
    }

    private static byte[] classBytes(Class<?> type) throws IOException {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream input = type.getResourceAsStream(resource)) {
            if (input == null) throw new IOException("Missing class bytes for " + type.getName());
            return input.readAllBytes();
        }
    }

    private static final class JaninoFixture {
    }
}
