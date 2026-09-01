package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.ResourceIndex;
import dev.starsector.preflight.core.ResourceIndexValidator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResourceIndexBuilderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void buildsCoreAndEnabledModProviderOrder() throws Exception {
        Path core = temporaryDirectory.resolve("starsector-core");
        Path mods = temporaryDirectory.resolve("mods");
        Path alpha = mods.resolve("Alpha");
        Path beta = mods.resolve("Beta");
        Files.createDirectories(core.resolve("graphics"));
        Files.createDirectories(alpha.resolve("graphics"));
        Files.createDirectories(beta.resolve("graphics"));
        Files.writeString(core.resolve("graphics/shared.png"), "core");
        Files.writeString(alpha.resolve("graphics/shared.png"), "alpha");
        Files.writeString(beta.resolve("graphics/shared.png"), "beta");
        Files.writeString(alpha.resolve("mod_info.json"), "{\"id\":\"alpha\"}");
        Files.writeString(beta.resolve("mod_info.json"), "{\"id\":\"beta\"}");
        Path enabled = mods.resolve("enabled_mods.json");
        Files.writeString(enabled, "{\"enabledMods\":[\"alpha\",\"beta\"]}");

        ResourceIndexBuilder.BuildResult first = ResourceIndexBuilder.build(temporaryDirectory);
        ResourceIndex index = first.index();

        assertEquals(List.of("core", "alpha", "beta"), index.roots().stream().map(ResourceIndex.Root::id).toList());
        assertEquals(3, index.providers("graphics/shared.png").size());
        assertEquals("beta", index.roots().get(index.winner("graphics/shared.png").orElseThrow().rootIndex()).id());
        assertTrue(index.winningFile("graphics/shared.png").orElseThrow().endsWith(Path.of("graphics", "shared.png")));

        Files.writeString(enabled, "{\"enabledMods\":[\"beta\",\"alpha\"]}");
        ResourceIndexBuilder.BuildResult reordered = ResourceIndexBuilder.build(temporaryDirectory);

        assertNotEquals(index.profileFingerprint(), reordered.index().profileFingerprint());
        assertEquals(
                "alpha",
                reordered.index().roots().get(
                        reordered.index().winner("graphics/shared.png").orElseThrow().rootIndex()).id());
    }

    @Test
    void walkingRootsInParallelProducesTheSameIndexAsWalkingThemInOrder() throws Exception {
        Path installRoot = profileWithManyRoots(12, 8);

        ResourceIndexBuilder.BuildResult serial = ResourceIndexBuilder.build(installRoot, 1);
        ResourceIndexBuilder.BuildResult parallel = ResourceIndexBuilder.build(installRoot, 8);

        // Against a serial reference rather than a recorded constant: a constant would only prove
        // the two agreed on the day it was written. The fingerprint is the load-bearing assertion --
        // it names every prepared artifact on disk, so a single reordered byte anywhere in the walk
        // would orphan the whole cache rather than fail visibly.
        assertEquals(serial.index().profileFingerprint(), parallel.index().profileFingerprint());
        assertEquals(serial.index().roots(), parallel.index().roots());
        assertEquals(serial.index().entries(), parallel.index().entries());
        assertEquals(serial.diagnostics(), parallel.diagnostics());
    }

    @Test
    void keepsResolutionOrderAcrossRootsWhenTheyAreWalkedAtOnce() throws Exception {
        Path installRoot = profileWithManyRoots(12, 2);

        ResourceIndex index = ResourceIndexBuilder.build(installRoot, 8).index();

        // Every root provides shared.png, so the winner is whichever mod is last in enabled order.
        // This is the property a permuted merge would break silently: the index would still be
        // well-formed and would name a different mod's file for every shared path.
        List<ResourceIndex.Provider> providers = index.providers("graphics/shared.png");
        assertEquals(13, providers.size());
        assertEquals(
                List.copyOf(index.roots().stream().map(ResourceIndex.Root::id).toList()),
                providers.stream().map(provider -> index.roots().get(provider.rootIndex()).id()).toList());
    }

    /** A core root plus {@code mods} enabled mods, each holding one shared and several own files. */
    private Path profileWithManyRoots(int mods, int filesPerMod) throws IOException {
        Path core = temporaryDirectory.resolve("starsector-core");
        Files.createDirectories(core.resolve("graphics"));
        Files.writeString(core.resolve("graphics/shared.png"), "core");
        Path modsDirectory = temporaryDirectory.resolve("mods");
        StringBuilder enabled = new StringBuilder("{\"enabledMods\":[");
        for (int i = 0; i < mods; i++) {
            String id = "mod" + i;
            Path directory = modsDirectory.resolve(id);
            Files.createDirectories(directory.resolve("graphics/nested/deeper"));
            Files.writeString(directory.resolve("mod_info.json"), "{\"id\":\"" + id + "\"}");
            Files.writeString(directory.resolve("graphics/shared.png"), id);
            for (int f = 0; f < filesPerMod; f++) {
                Files.writeString(directory.resolve("graphics/nested/deeper/file" + f + ".png"), id + f);
            }
            enabled.append(i == 0 ? "" : ",").append('"').append(id).append('"');
        }
        enabled.append("]}");
        Files.writeString(modsDirectory.resolve("enabled_mods.json"), enabled.toString());
        return temporaryDirectory;
    }

    @Test
    void reportsMissingModsAndBuildsAvailableRoots() throws Exception {
        Path mods = temporaryDirectory.resolve("mods");
        Path alpha = mods.resolve("Alpha");
        Files.createDirectories(alpha);
        Files.writeString(alpha.resolve("mod_info.json"), "{\"id\":\"alpha\"}");
        Files.writeString(alpha.resolve("data.json"), "{}");
        Files.writeString(mods.resolve("enabled_mods.json"), "{\"enabledMods\":[\"alpha\",\"missing\"]}");

        ResourceIndexBuilder.BuildResult result = ResourceIndexBuilder.build(temporaryDirectory);

        assertEquals(1, result.index().roots().size());
        assertEquals("alpha", result.index().roots().get(0).id());
        assertTrue(result.diagnostics().stream().anyMatch(value -> value.contains("missing")));
        assertTrue(result.diagnostics().stream().anyMatch(value -> value.contains("mod-only")));
    }

    @Test
    void recognizesMacBundleJavaDirectoryAsCoreResourceRoot() throws Exception {
        Path app = temporaryDirectory.resolve("Starsector.app");
        Path core = app.resolve("Contents/Resources/Java");
        Path mods = app.resolve("mods");
        Files.createDirectories(core.resolve("graphics"));
        Files.createDirectories(core.resolve("data"));
        Files.createDirectories(mods);
        Files.writeString(core.resolve("graphics/core.png"), "core");
        Files.writeString(mods.resolve("enabled_mods.json"), "{\"enabledMods\":[]}");

        ResourceIndexBuilder.BuildResult result = ResourceIndexBuilder.build(app);

        assertEquals(List.of("core"), result.index().roots().stream().map(ResourceIndex.Root::id).toList());
        assertEquals(core.toRealPath(), result.index().roots().get(0).path());
        assertTrue(result.index().winner("graphics/core.png").isPresent());
        assertTrue(result.diagnostics().stream().noneMatch(value -> value.contains("mod-only")));
    }

    @Test
    void indexesInternalLinksAndSkipsLinksOutsideTheCanonicalRoot() throws Exception {
        Path core = temporaryDirectory.resolve("starsector-core");
        Path graphics = core.resolve("graphics");
        Path mods = temporaryDirectory.resolve("mods");
        Files.createDirectories(graphics);
        Files.createDirectories(mods);
        Files.writeString(mods.resolve("enabled_mods.json"), "{\"enabledMods\":[]}");
        Path internal = graphics.resolve("internal.png");
        Path outside = temporaryDirectory.resolve("outside.png");
        Files.writeString(internal, "inside");
        Files.writeString(outside, "outside");
        createSymbolicLinkOrSkip(graphics.resolve("alias.png"), internal);
        createSymbolicLinkOrSkip(graphics.resolve("escaped.png"), outside);

        ResourceIndexBuilder.BuildResult result = ResourceIndexBuilder.build(temporaryDirectory);

        assertTrue(result.index().winner("graphics/alias.png").isPresent());
        assertFalse(result.index().winner("graphics/escaped.png").isPresent());
        assertTrue(result.diagnostics().stream().anyMatch(value -> value.contains("escapes its root")));
    }

    @Test
    void excludesRuntimeLogsSoLogMutationCannotStaleTheIndex() throws Exception {
        Path core = temporaryDirectory.resolve("starsector-core");
        Path mods = temporaryDirectory.resolve("mods");
        Files.createDirectories(core.resolve("graphics"));
        Files.createDirectories(mods);
        Files.writeString(mods.resolve("enabled_mods.json"), "{\"enabledMods\":[]}");
        Files.writeString(core.resolve("graphics/core.png"), "core");
        // A mod writes its log into the core directory at runtime.
        Path runtimeLog = core.resolve("stelnet.log");
        Files.writeString(runtimeLog, "startup line\n");
        Files.writeString(core.resolve("starsector.log.1"), "rotated\n");

        ResourceIndexBuilder.BuildResult result = ResourceIndexBuilder.build(temporaryDirectory);
        ResourceIndex index = result.index();

        // Runtime logs are never providers and the game texture stays indexed.
        assertTrue(index.winner("graphics/core.png").isPresent());
        assertFalse(index.winner("stelnet.log").isPresent());
        assertFalse(index.winner("starsector.log.1").isPresent());
        assertTrue(result.diagnostics().stream()
                .anyMatch(value -> value.contains("Excluded runtime-generated file")
                        && value.contains("stelnet.log")));
        assertTrue(ResourceIndexValidator.validate(index).valid());

        // Simulate the mod appending to its log with a later mtime, as it does during startup.
        Files.writeString(runtimeLog, "startup line\nmenu reached\n");
        Files.setLastModifiedTime(runtimeLog, FileTime.fromMillis(System.currentTimeMillis() + 60_000));

        // The pinned index must remain valid, and a rebuild must produce the same fingerprint.
        assertTrue(ResourceIndexValidator.validate(index).valid(),
                "runtime log mutation must not stale the resource index");
        ResourceIndexBuilder.BuildResult rebuilt = ResourceIndexBuilder.build(temporaryDirectory);
        assertEquals(index.profileFingerprint(), rebuilt.index().profileFingerprint());
    }

    @Test
    void recognizesRuntimeGeneratedResourceNames() {
        assertTrue(ResourceIndexBuilder.isRuntimeGeneratedResource("stelnet.log"));
        assertTrue(ResourceIndexBuilder.isRuntimeGeneratedResource("starsector.LOG"));
        assertTrue(ResourceIndexBuilder.isRuntimeGeneratedResource("starsector.log.1"));
        assertTrue(ResourceIndexBuilder.isRuntimeGeneratedResource("app.log.lck"));
        assertFalse(ResourceIndexBuilder.isRuntimeGeneratedResource("ship.png"));
        assertFalse(ResourceIndexBuilder.isRuntimeGeneratedResource("catalog.json"));
        assertFalse(ResourceIndexBuilder.isRuntimeGeneratedResource("changelog.txt"));
        assertFalse(ResourceIndexBuilder.isRuntimeGeneratedResource("data.log.backup"));
    }

    private static void createSymbolicLinkOrSkip(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target.toAbsolutePath());
        } catch (UnsupportedOperationException | SecurityException | IOException error) {
            Assumptions.assumeTrue(false, "symbolic links are unavailable: " + error.getMessage());
        }
    }
}
