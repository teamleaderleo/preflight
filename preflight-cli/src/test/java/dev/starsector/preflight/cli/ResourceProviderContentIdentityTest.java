package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.ResourceIndex;
import dev.starsector.preflight.core.ResourceProviderComparison;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResourceProviderContentIdentityTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void directComparisonHashesOnlyMultiProviderPaths() throws Exception {
        Path core = Files.createDirectories(temporaryDirectory.resolve("only-overlaps/core"));
        Path mod = Files.createDirectories(temporaryDirectory.resolve("only-overlaps/mod"));
        Files.createDirectories(core.resolve("graphics"));
        Files.createDirectories(mod.resolve("graphics"));
        Files.writeString(core.resolve("graphics/shared.png"), "same-bytes");
        Files.writeString(mod.resolve("graphics/shared.png"), "same-bytes");

        Map<String, List<ResourceIndex.Provider>> entries = new LinkedHashMap<>();
        entries.put("graphics/shared.png", List.of(
                provider(0, core, "graphics/shared.png"),
                provider(1, mod, "graphics/shared.png")));
        // A full second scan would touch this. Provider comparison has no reason to: one provider
        // cannot overlap anything, and the missing file must therefore have no effect on the result.
        entries.put("data/single.json", List.of(
                new ResourceIndex.Provider(1, "data/single.json", 99, 1)));
        ResourceIndex index = index(core, mod, entries);

        ResourceProviderComparison.Result result = ResourceProviderComparison.analyze(
                index, ResourceProviderContentIdentity.direct(index));

        assertEquals(1, result.multiProviderLogicalPaths());
        assertEquals(1, result.identicalDuplicates());
        assertEquals(0, result.ambiguousComparisons());
    }

    @Test
    void directComparisonUsesHashesForSameLengthDifferentBytes() throws Exception {
        Path core = Files.createDirectories(temporaryDirectory.resolve("different/core"));
        Path mod = Files.createDirectories(temporaryDirectory.resolve("different/mod"));
        Files.writeString(core.resolve("shared.bin"), "AAAA");
        Files.writeString(mod.resolve("shared.bin"), "BBBB");
        ResourceIndex index = index(core, mod, Map.of(
                "shared.bin", List.of(
                        provider(0, core, "shared.bin"),
                        provider(1, mod, "shared.bin"))));

        ResourceProviderComparison.Result result = ResourceProviderComparison.analyze(
                index, ResourceProviderContentIdentity.direct(index));

        assertEquals(1, result.differingOverrides());
        assertEquals("mod", result.overrideSamples().get(0).winnerProviderId());
    }

    @Test
    void stalePersistedMetadataMakesComparisonAmbiguous() throws Exception {
        Path core = Files.createDirectories(temporaryDirectory.resolve("stale/core"));
        Path mod = Files.createDirectories(temporaryDirectory.resolve("stale/mod"));
        Files.writeString(core.resolve("shared.bin"), "same");
        Files.writeString(mod.resolve("shared.bin"), "same");
        ResourceIndex.Provider first = provider(0, core, "shared.bin");
        ResourceIndex.Provider current = provider(1, mod, "shared.bin");
        ResourceIndex.Provider stale = new ResourceIndex.Provider(
                current.rootIndex(), current.relativePath(), current.size(), current.modifiedMillis() + 1);
        ResourceIndex index = index(core, mod, Map.of("shared.bin", List.of(first, stale)));

        ResourceProviderComparison.Result result = ResourceProviderComparison.analyze(
                index, ResourceProviderContentIdentity.direct(index));

        assertEquals(1, result.ambiguousComparisons());
        assertEquals(ResourceProviderComparison.ContentEvidence.STALE,
                result.ambiguousSamples().get(0).providers().get(1).evidence());
    }

    @Test
    void nonRegularProviderIsExplicitlyUnreadable() throws Exception {
        Path core = Files.createDirectories(temporaryDirectory.resolve("unreadable/core"));
        Path mod = Files.createDirectories(temporaryDirectory.resolve("unreadable/mod"));
        Files.writeString(core.resolve("shared.bin"), "same");
        Files.createDirectories(mod.resolve("shared.bin"));
        ResourceIndex index = index(core, mod, Map.of("shared.bin", List.of(
                provider(0, core, "shared.bin"),
                new ResourceIndex.Provider(1, "shared.bin", 0, 1))));

        ResourceProviderComparison.Result result = ResourceProviderComparison.analyze(
                index, ResourceProviderContentIdentity.direct(index));

        assertEquals(1, result.ambiguousComparisons());
        assertEquals(ResourceProviderComparison.ContentEvidence.UNREADABLE,
                result.ambiguousSamples().get(0).providers().get(1).evidence());
    }

    @Test
    void symlinkEscapeIsExplicitlyInvalidWhenSupported() throws Exception {
        Path core = Files.createDirectories(temporaryDirectory.resolve("escape/core"));
        Path mod = Files.createDirectories(temporaryDirectory.resolve("escape/mod"));
        Files.writeString(core.resolve("shared.bin"), "same");
        Path outside = Files.writeString(temporaryDirectory.resolve("outside.bin"), "same");
        Path link = mod.resolve("shared.bin");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException unsupported) {
            return;
        }
        ResourceIndex index = index(core, mod, Map.of("shared.bin", List.of(
                provider(0, core, "shared.bin"),
                new ResourceIndex.Provider(
                        1, "shared.bin", Files.size(outside), Files.getLastModifiedTime(outside).toMillis()))));

        ResourceProviderComparison.Result result = ResourceProviderComparison.analyze(
                index, ResourceProviderContentIdentity.direct(index));

        assertEquals(1, result.ambiguousComparisons());
        assertEquals(ResourceProviderComparison.ContentEvidence.INVALID_PATH,
                result.ambiguousSamples().get(0).providers().get(1).evidence());
    }

    @Test
    void sharedProfileContextReusesHashesAlreadyPaidForInTheSamePreparation() throws Exception {
        Path game = temporaryDirectory.resolve("cached/game");
        Path core = Files.createDirectories(game.resolve("Contents/Resources/Java"));
        Path mod = Files.createDirectories(temporaryDirectory.resolve("cached/mod"));
        Files.writeString(core.resolve("starfarer_obf.jar"), "game-v1");
        Files.writeString(core.resolve("shared.bin"), "same");
        Files.writeString(mod.resolve("shared.bin"), "same");
        ResourceIndex index = index(core, mod, Map.of("shared.bin", List.of(
                provider(0, core, "shared.bin"),
                provider(1, mod, "shared.bin"))));

        try (ProfileIdentityContext context = ProfileIdentityContext.of(game, index)) {
            List<ResourceIndex.Provider> providers = index.providers("shared.bin");
            List<Path> sources = context.resolveAll(providers);
            context.sha256All(sources);
            Files.writeString(mod.resolve("shared.bin"), "different-bytes");

            ResourceProviderComparison.Result reused = ResourceProviderComparison.analyze(
                    index, ResourceProviderContentIdentity.cached(context));
            assertEquals(1, reused.identicalDuplicates(),
                    "comparison inside one preparation must reuse the content digest already cached");
        }

        ResourceIndex refreshedIndex = index(core, mod, Map.of("shared.bin", List.of(
                provider(0, core, "shared.bin"),
                provider(1, mod, "shared.bin"))));
        try (ProfileIdentityContext nextPreparation = ProfileIdentityContext.of(game, refreshedIndex)) {
            ResourceProviderComparison.Result refreshed = ResourceProviderComparison.analyze(
                    refreshedIndex, ResourceProviderContentIdentity.cached(nextPreparation));
            assertEquals(1, refreshed.differingOverrides(),
                    "a fresh preparation must read the changed bytes");
        }
    }

    @Test
    void threeIdenticalDirectProvidersRemainIdentical() throws Exception {
        Path first = Files.createDirectories(temporaryDirectory.resolve("three/first"));
        Path second = Files.createDirectories(temporaryDirectory.resolve("three/second"));
        Path third = Files.createDirectories(temporaryDirectory.resolve("three/third"));
        Files.writeString(first.resolve("shared.bin"), "identical");
        Files.writeString(second.resolve("shared.bin"), "identical");
        Files.writeString(third.resolve("shared.bin"), "identical");
        List<ResourceIndex.Root> roots = List.of(
                new ResourceIndex.Root("first", first, true),
                new ResourceIndex.Root("second", second, false),
                new ResourceIndex.Root("third", third, false));
        ResourceIndex index = new ResourceIndex("d".repeat(64), roots, Map.of("shared.bin", List.of(
                provider(0, first, "shared.bin"),
                provider(1, second, "shared.bin"),
                provider(2, third, "shared.bin"))));

        ResourceProviderComparison.Result result = ResourceProviderComparison.analyze(
                index, ResourceProviderContentIdentity.direct(index));

        assertEquals(1, result.identicalDuplicates());
        assertEquals("third", result.identicalSamples().get(0).winnerProviderId());
        assertTrue(result.identicalSamples().get(0).providers().get(2).winner());
    }

    private static ResourceIndex index(
            Path core,
            Path mod,
            Map<String, List<ResourceIndex.Provider>> entries) {
        return new ResourceIndex("c".repeat(64), List.of(
                new ResourceIndex.Root("core", core, true),
                new ResourceIndex.Root("mod", mod, false)), entries);
    }

    private static ResourceIndex.Provider provider(int rootIndex, Path root, String relative)
            throws IOException {
        Path file = root.resolve(relative);
        return new ResourceIndex.Provider(
                rootIndex,
                relative,
                Files.size(file),
                Math.max(0, Files.getLastModifiedTime(file).toMillis()));
    }
}
