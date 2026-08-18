package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.ResourceIndex;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfileIdentityContextTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void hashesEveryProviderInRequestOrderNoMatterHowManyThreadsRanIt() throws Exception {
        // Order is the entire safety argument for hashing in parallel: callers feed these strings
        // into a digest one after another, so a permuted result would silently change every
        // identity and orphan every cache artifact already on disk.
        Layout layout = Layout.create(temporaryDirectory.resolve("ordered"), 500);
        try (ProfileIdentityContext context = ProfileIdentityContext.of(layout.game, layout.index())) {
            List<ResourceIndex.Provider> providers = layout.providers(context.resources());
            List<Path> sources = context.resolveAll(providers);

            List<String> serial = new ArrayList<>();
            for (Path source : sources) {
                serial.add(Hashes.sha256(source));
            }
            assertEquals(serial, context.sha256All(sources));
            assertTrue(serial.size() > 1, "the parallel branch needs more than one source");
            assertEquals(serial.size(), serial.stream().distinct().count(),
                    "distinct contents must produce distinct hashes for this to prove anything");
        }
    }

    @Test
    void theSharedContextProducesTheSameIdentitiesAsSixIndependentOnes() throws Exception {
        Layout layout = Layout.create(temporaryDirectory.resolve("shared"), 40);
        ResourceIndex resources = layout.index();

        String variant;
        String weapon;
        String projectile;
        String hull;
        String rules;
        String commands;
        try (ProfileIdentityContext context = ProfileIdentityContext.of(layout.game, resources)) {
            variant = VariantJsonProfileIdentityBuilder.build(context).identitySha256();
            weapon = WeaponJsonProfileIdentityBuilder.build(context).identitySha256();
            projectile = ProjectileJsonProfileIdentityBuilder.build(context).identitySha256();
            hull = HullJsonProfileIdentityBuilder.build(context).identitySha256();
            rules = RulesCsvProfileIdentityBuilder.build(context).identitySha256();
            commands = RuleCommandClassProfileIdentityBuilder.build(context).identitySha256();
        }

        assertEquals(variant,
                VariantJsonProfileIdentityBuilder.build(layout.game, resources).identitySha256());
        assertEquals(weapon,
                WeaponJsonProfileIdentityBuilder.build(layout.game, resources).identitySha256());
        assertEquals(projectile,
                ProjectileJsonProfileIdentityBuilder.build(layout.game, resources).identitySha256());
        assertEquals(hull,
                HullJsonProfileIdentityBuilder.build(layout.game, resources).identitySha256());
        assertEquals(rules,
                RulesCsvProfileIdentityBuilder.build(layout.game, resources).identitySha256());
        assertEquals(commands,
                RuleCommandClassProfileIdentityBuilder.build(layout.game, resources).identitySha256());

        // Six corpora over one install must not collide, or a cache would read another's artifact.
        assertEquals(6, List.of(variant, weapon, projectile, hull, rules, commands)
                .stream().distinct().count());
    }

    @Test
    void theSharedContextProducesTheSameIdentitiesWhenCorporaRunConcurrently() throws Exception {
        Layout layout = Layout.create(temporaryDirectory.resolve("concurrent-shared"), 80);
        ResourceIndex resources = layout.index();

        List<String> serial;
        try (ProfileIdentityContext context = ProfileIdentityContext.of(layout.game, resources)) {
            serial = List.of(
                    VariantJsonProfileIdentityBuilder.build(context).identitySha256(),
                    WeaponJsonProfileIdentityBuilder.build(context).identitySha256(),
                    ProjectileJsonProfileIdentityBuilder.build(context).identitySha256(),
                    HullJsonProfileIdentityBuilder.build(context).identitySha256(),
                    RulesCsvProfileIdentityBuilder.build(context).identitySha256(),
                    RuleCommandClassProfileIdentityBuilder.build(context).identitySha256(),
                    MergedReadProfileIdentityBuilder.build(context).identitySha256());
        }

        ExecutorService executor = Executors.newFixedThreadPool(4);
        try (ProfileIdentityContext context = ProfileIdentityContext.of(layout.game, resources)) {
            List<Future<String>> concurrent = List.of(
                    executor.submit(() -> VariantJsonProfileIdentityBuilder.build(context).identitySha256()),
                    executor.submit(() -> WeaponJsonProfileIdentityBuilder.build(context).identitySha256()),
                    executor.submit(() -> ProjectileJsonProfileIdentityBuilder.build(context).identitySha256()),
                    executor.submit(() -> HullJsonProfileIdentityBuilder.build(context).identitySha256()),
                    executor.submit(() -> RulesCsvProfileIdentityBuilder.build(context).identitySha256()),
                    executor.submit(() -> RuleCommandClassProfileIdentityBuilder.build(context).identitySha256()),
                    executor.submit(() -> MergedReadProfileIdentityBuilder.build(context).identitySha256()));
            assertEquals(serial, concurrent.stream().map(ProfileIdentityContextTest::get).toList());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void aContentChangeStillMovesTheIdentity() throws Exception {
        // The content memo belongs to one preparation context only: every new launch still hashes
        // every byte, which is what lets a mod update invalidate a cache.
        Layout layout = Layout.create(temporaryDirectory.resolve("content"), 8);
        String before = VariantJsonProfileIdentityBuilder
                .build(layout.game, layout.index()).identitySha256();

        Path edited = layout.mod.resolve("data/variants/variant-3.variant");
        Files.writeString(edited, Files.readString(edited).toUpperCase(java.util.Locale.ROOT));
        assertNotEquals(before, VariantJsonProfileIdentityBuilder
                .build(layout.game, layout.index()).identitySha256());
    }

    @Test
    void memoisesAResolvedFilesHashAcrossCorporaForOnePreparation() throws Exception {
        Layout layout = Layout.create(temporaryDirectory.resolve("hash-memo"), 8);
        ResourceIndex resources = layout.index();
        ResourceIndex.Provider provider = resources.entries()
                .get("data/variants/variant-3.variant").get(0);
        Path source;
        String first;
        try (ProfileIdentityContext context = ProfileIdentityContext.of(layout.game, resources)) {
            source = context.resolve(provider);
            first = context.sha256All(List.of(source, source)).get(0);
            assertEquals(first, context.sha256All(List.of(source, source)).get(1));

            Files.writeString(source, "{\"variantId\":\"changed-during-preparation\"}");
            assertEquals(first, context.sha256All(List.of(source)).get(0),
                    "overlapping identities must reuse the digest already computed this launch");
        }

        try (ProfileIdentityContext nextLaunch =
                     ProfileIdentityContext.of(layout.game, resources)) {
            assertNotEquals(first, nextLaunch.sha256All(List.of(source)).get(0),
                    "a new launch must observe the changed bytes");
        }
    }

    @Test
    void resolvingAgreesWithTheUnmemoisedContainmentCheck() throws Exception {
        Layout layout = Layout.create(temporaryDirectory.resolve("agreement"), 12);
        try (ProfileIdentityContext context = ProfileIdentityContext.of(layout.game, layout.index())) {
            for (ResourceIndex.Provider provider : layout.providers(context.resources())) {
                Path memoised = context.resolve(provider);
                Path direct = dev.starsector.preflight.core.PathContainment.existingInside(
                        context.resources().roots().get(provider.rootIndex()).path(),
                        context.resources().resolve(provider));
                assertEquals(direct, memoised);
            }
        }
    }

    @Test
    void memoisesAProviderResolutionForOnePreparation() throws Exception {
        Layout layout = Layout.create(temporaryDirectory.resolve("resolve-memo"), 4);
        ResourceIndex resources = layout.index();
        ResourceIndex.Provider provider = resources.entries()
                .get("data/variants/variant-2.variant").get(0);
        try (ProfileIdentityContext context = ProfileIdentityContext.of(layout.game, resources)) {
            Path resolved = context.resolve(provider);
            Files.delete(resolved);
            assertEquals(resolved, context.resolve(provider),
                    "overlapping identities must reuse the containment decision this launch");
        }

        try (ProfileIdentityContext nextLaunch =
                     ProfileIdentityContext.of(layout.game, resources)) {
            assertThrows(IOException.class, () -> nextLaunch.resolve(provider),
                    "a new launch must revalidate the provider");
        }
    }

    @Test
    void aSymlinkOutOfTheRootIsStillRejected() throws Exception {
        Layout layout = Layout.create(temporaryDirectory.resolve("escape"), 4);
        Path outside = temporaryDirectory.resolve("outside.variant");
        Files.writeString(outside, "{\"escaped\":true}");
        Path link = layout.mod.resolve("data/variants/escape.variant");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException unsupported) {
            return; // No symlink support here; the containment check is exercised elsewhere.
        }

        ResourceIndex resources = layout.indexIncluding("data/variants/escape.variant", link);
        try (ProfileIdentityContext context = ProfileIdentityContext.of(layout.game, resources)) {
            ResourceIndex.Provider escaping = resources.entries()
                    .get("data/variants/escape.variant").get(0);
            assertThrows(IllegalArgumentException.class, () -> context.resolve(escaping));
        }
    }

    @Test
    void theGameJarIsHashedOnceForEveryCacheThatNeedsIt() throws Exception {
        Layout layout = Layout.create(temporaryDirectory.resolve("jar"), 4);
        try (ProfileIdentityContext context = ProfileIdentityContext.of(layout.game, layout.index())) {
            assertSame(context.gameJarSha256(), context.gameJarSha256());
            assertEquals(Hashes.sha256(context.gameJar()), context.gameJarSha256());
        }
    }

    @Test
    void constructorSeedsGameJarDigestIntoSharedContentMemo() throws Exception {
        Layout layout = Layout.create(temporaryDirectory.resolve("seeded-jar"), 4);
        try (ProfileIdentityContext context = ProfileIdentityContext.of(layout.game, layout.index())) {
            String initial = context.gameJarSha256();
            Files.writeString(context.gameJar(), "changed-during-the-same-preparation");
            assertEquals(initial, context.sha256All(List.of(context.gameJar())).get(0));
        }
    }

    @Test
    void providerMutationDuringHashRefusesTheIdentityAndDoesNotMemoize() throws Exception {
        Layout layout = Layout.create(temporaryDirectory.resolve("mutation-during-hash"), 4);
        ResourceIndex resources = layout.index();
        ResourceIndex.Provider provider = resources.entries()
                .get("data/variants/variant-1.variant").get(0);

        try (ProfileIdentityContext context = ProfileIdentityContext.of(
                layout.game, resources, path -> {
                    if (path.toString().endsWith("variant-1.variant")) {
                        Files.writeString(path, "{\"variantId\":\"raced-mutation\"}");
                    }
                    return Hashes.sha256(path);
                }, 1)) {
            Path source = context.resolve(provider);
            assertThrows(IOException.class, () -> context.sha256All(List.of(source)));
        }
    }

    @Test
    void subMillisecondProviderMutationDuringHashIsRefusedWhenFilesystemPreservesPrecision()
            throws Exception {
        Layout layout = Layout.create(temporaryDirectory.resolve("submillisecond-mutation"), 4);
        Path file = layout.mod.resolve("data/variants/variant-1.variant");
        Files.writeString(file, "AAAA");
        FileTime requestedBefore = FileTime.from(Instant.ofEpochSecond(1_700_000_000L, 100_000));
        FileTime requestedAfter = FileTime.from(Instant.ofEpochSecond(1_700_000_000L, 900_000));
        Files.setLastModifiedTime(file, requestedBefore);
        FileTime observedBefore = Files.getLastModifiedTime(file);
        Files.setLastModifiedTime(file, requestedAfter);
        FileTime observedAfter = Files.getLastModifiedTime(file);
        if (observedBefore.equals(observedAfter) || observedBefore.toMillis() != observedAfter.toMillis()) {
            return;
        }
        Files.setLastModifiedTime(file, observedBefore);
        if (!observedBefore.equals(Files.getLastModifiedTime(file))) {
            return;
        }

        ResourceIndex resources = layout.index();
        ResourceIndex.Provider provider = resources.entries()
                .get("data/variants/variant-1.variant").get(0);
        try (ProfileIdentityContext context = ProfileIdentityContext.of(
                layout.game, resources, path -> {
                    if (path.toString().endsWith("variant-1.variant")) {
                        Files.writeString(path, "BBBB");
                        Files.setLastModifiedTime(path, observedAfter);
                    }
                    return Hashes.sha256(path);
                }, 1)) {
            Path source = context.resolve(provider);
            assertThrows(IOException.class, () -> context.sha256All(List.of(source)));
        }
    }

    @Test
    void providerChangedAfterIndexCreationRefusesHashing() throws Exception {
        Layout layout = Layout.create(temporaryDirectory.resolve("changed-after-index"), 4);
        ResourceIndex resources = layout.index();
        ResourceIndex.Provider provider = resources.entries()
                .get("data/variants/variant-1.variant").get(0);

        Path file = layout.mod.resolve("data/variants/variant-1.variant");
        Files.writeString(file, "{\"variantId\":\"changed-after-index\"}");

        try (ProfileIdentityContext context = ProfileIdentityContext.of(layout.game, resources)) {
            Path source = context.resolve(provider);
            assertThrows(IOException.class, () -> context.sha256All(List.of(source)));
        }
    }

    @Test
    void concurrentRequestsForOneStableFilePerformOneContentRead() throws Exception {
        Layout layout = Layout.create(temporaryDirectory.resolve("single-content-read"), 4);
        ResourceIndex resources = layout.index();
        ResourceIndex.Provider provider = resources.entries()
                .get("data/variants/variant-1.variant").get(0);

        java.util.concurrent.atomic.AtomicInteger variantReadCount = new java.util.concurrent.atomic.AtomicInteger(0);
        try (ProfileIdentityContext context = ProfileIdentityContext.of(
                layout.game, resources, path -> {
                    if (path.toString().endsWith("variant-1.variant")) {
                        variantReadCount.incrementAndGet();
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    return Hashes.sha256(path);
                }, 4)) {
            Path source = context.resolve(provider);
            ExecutorService executor = Executors.newFixedThreadPool(8);
            try {
                List<Future<List<String>>> futures = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    futures.add(executor.submit(() -> context.sha256All(List.of(source))));
                }
                for (Future<List<String>> f : futures) {
                    List<String> res = f.get();
                    assertEquals(1, res.size());
                }
                assertEquals(1, variantReadCount.get(), "stable content must only be read and hashed once");
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void failedHashAttemptDoesNotPoisonSubsequentStableRead() throws Exception {
        Layout layout = Layout.create(temporaryDirectory.resolve("unpoisoned-memo"), 4);
        ResourceIndex resources = layout.index();
        ResourceIndex.Provider provider = resources.entries()
                .get("data/variants/variant-1.variant").get(0);

        java.util.concurrent.atomic.AtomicBoolean failFirst = new java.util.concurrent.atomic.AtomicBoolean(true);
        try (ProfileIdentityContext context = ProfileIdentityContext.of(
                layout.game, resources, path -> {
                    if (path.toString().endsWith("variant-1.variant") && failFirst.getAndSet(false)) {
                        throw new IOException("Simulated transient I/O fault");
                    }
                    return Hashes.sha256(path);
                }, 1)) {
            Path source = context.resolve(provider);
            assertThrows(IOException.class, () -> context.sha256All(List.of(source)));
            // Subsequent read should succeed rather than recalling the exception
            List<String> result = context.sha256All(List.of(source));
            assertEquals(1, result.size());
            assertEquals(Hashes.sha256(source), result.get(0));
        }
    }

    private static String get(Future<String> future) {
        try {
            return future.get();
        } catch (Exception error) {
            throw new AssertionError("Concurrent identity calculation failed", error);
        }
    }

    private record Layout(Path game, Path core, Path mod) {
        static Layout create(Path game, int files) throws IOException {
            Path core = game.resolve("Contents/Resources/Java");
            Path mod = game.resolveSibling(game.getFileName() + "-mod");
            Files.createDirectories(core.resolve("data/config"));
            Files.createDirectories(mod.resolve("data/variants"));
            Files.createDirectories(mod.resolve("data/weapons/proj"));
            Files.createDirectories(mod.resolve("data/hulls"));
            Files.createDirectories(mod.resolve("data/campaign"));
            Files.createDirectories(mod.resolve("data/config"));
            Files.createDirectories(mod.resolve("jars"));

            Files.writeString(core.resolve("starfarer_obf.jar"), "game-v1");
            Files.writeString(core.resolve("data/config/settings.json"),
                    "{\"ruleCommandPackages\":[\"a\"]}");
            Files.writeString(mod.resolve("data/config/settings.json"),
                    "{\"ruleCommandPackages\":[\"a\",\"b\"]}");
            Files.writeString(mod.resolve("data/campaign/rules.csv"), "id,trigger\nrow,always\n");
            Files.writeString(mod.resolve("jars/mod.jar"), "MODJAR-v1");
            for (int index = 0; index < files; index++) {
                Files.writeString(mod.resolve("data/variants/variant-" + index + ".variant"),
                        "{\"variantId\":\"v" + index + "\"}");
                Files.writeString(mod.resolve("data/weapons/weapon-" + index + ".wpn"),
                        "{\"id\":\"w" + index + "\"}");
                Files.writeString(mod.resolve("data/weapons/proj/proj-" + index + ".proj"),
                        "{\"id\":\"p" + index + "\"}");
                Files.writeString(mod.resolve("data/hulls/hull-" + index + ".ship"),
                        "{\"hullId\":\"h" + index + "\"}");
            }
            return new Layout(game, core, mod);
        }

        ResourceIndex index() throws IOException {
            return indexIncluding(null, null);
        }

        ResourceIndex indexIncluding(String extraLogicalPath, Path extraFile) throws IOException {
            List<ResourceIndex.Root> roots = List.of(
                    new ResourceIndex.Root("core", core, true),
                    new ResourceIndex.Root("mod", mod, false));
            Map<String, List<ResourceIndex.Provider>> entries = new LinkedHashMap<>();
            entries.put("data/config/settings.json", List.of(
                    provider(0, "data/config/settings.json", core),
                    provider(1, "data/config/settings.json", mod)));
            entries.put("jars/mod.jar", List.of(provider(1, "jars/mod.jar", mod)));
            entries.put("data/campaign/rules.csv",
                    List.of(provider(1, "data/campaign/rules.csv", mod)));
            try (var stream = Files.walk(mod.resolve("data"))) {
                for (Path file : stream.filter(Files::isRegularFile).toList()) {
                    String relative = mod.relativize(file).toString().replace('\\', '/');
                    if (entries.containsKey(relative) || Files.isSymbolicLink(file)) {
                        continue;
                    }
                    entries.put(relative, List.of(provider(1, relative, mod)));
                }
            }
            if (extraLogicalPath != null) {
                entries.put(extraLogicalPath, List.of(new ResourceIndex.Provider(
                        1, extraLogicalPath, Files.size(extraFile),
                        Files.getLastModifiedTime(extraFile).toMillis())));
            }
            return new ResourceIndex("b".repeat(64), roots, entries);
        }

        List<ResourceIndex.Provider> providers(ResourceIndex resources) {
            List<ResourceIndex.Provider> flattened = new ArrayList<>();
            resources.entries().values().forEach(flattened::addAll);
            return flattened;
        }

        private static ResourceIndex.Provider provider(int root, String relative, Path base)
                throws IOException {
            Path file = base.resolve(relative);
            return new ResourceIndex.Provider(
                    root, relative, Files.size(file), Files.getLastModifiedTime(file).toMillis());
        }
    }
}
