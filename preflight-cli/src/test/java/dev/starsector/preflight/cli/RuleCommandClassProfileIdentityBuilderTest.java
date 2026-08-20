package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import dev.starsector.preflight.core.ResourceIndex;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuleCommandClassProfileIdentityBuilderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void coversTheDeclaredPackagesAndTheCodeThatCouldWinThem() throws Exception {
        Layout layout = Layout.create(temporaryDirectory.resolve("game"));

        RuleCommandClassProfileIdentityBuilder.Result first =
                RuleCommandClassProfileIdentityBuilder.build(layout.game, layout.index(false));
        assertEquals(2L, first.settingsProviderCount());
        assertEquals(2L, first.jarCount());

        // Editing settings.json can reorder ruleCommandPackages, which changes which package wins.
        Files.writeString(layout.modSettings, "{\"ruleCommandPackages\":[\"b\",\"a\"]}");
        assertNotEquals(first.identitySha256(), rebuild(layout));

        Files.writeString(layout.modSettings, "{\"ruleCommandPackages\":[\"a\",\"b\"]}");
        assertEquals(first.identitySha256(), rebuild(layout));
    }

    @Test
    void aJarWhoseContentsChangedIsADifferentProfile() throws Exception {
        Layout layout = Layout.create(temporaryDirectory.resolve("jar-game"));
        String first = rebuild(layout);

        // This is the only way the cache can go silently stale: a mod update that adds a same-named
        // class to an already-declared earlier package moves the winner without touching any list.
        // Same byte count, different bytes, so only a content hash can see it.
        Files.writeString(layout.modJar, "MODJAR-v2");
        assertNotEquals(first, rebuild(layout), "mod code must be hashed, not stamped");
    }

    @Test
    void unrelatedResourcesAndTheGameJarBehaveAsExpected() throws Exception {
        Layout layout = Layout.create(temporaryDirectory.resolve("mixed-game"));
        String first = rebuild(layout);

        Files.writeString(layout.unrelated, "a completely different sprite");
        assertEquals(first, rebuild(layout), "nothing outside settings.json and code may matter");

        Files.writeString(layout.gameJar, "game-v2");
        assertNotEquals(first, rebuild(layout));
    }

    @Test
    void providerOrderIsPartOfTheIdentity() throws Exception {
        Layout layout = Layout.create(temporaryDirectory.resolve("ordered-game"));

        assertNotEquals(
                RuleCommandClassProfileIdentityBuilder
                        .build(layout.game, layout.index(false)).identitySha256(),
                RuleCommandClassProfileIdentityBuilder
                        .build(layout.game, layout.index(true)).identitySha256());
    }

    private static String rebuild(Layout layout) throws Exception {
        return RuleCommandClassProfileIdentityBuilder
                .build(layout.game, layout.index(false)).identitySha256();
    }

    private record Layout(
            Path game, Path core, Path mod,
            Path gameJar, Path coreSettings, Path modSettings,
            Path coreJar, Path modJar, Path unrelated) {

        static Layout create(Path game) throws Exception {
            Path core = game.resolve("Contents/Resources/Java");
            Path mod = game.resolveSibling(game.getFileName() + "-mod");
            Files.createDirectories(core.resolve("data/config"));
            Files.createDirectories(core.resolve("jars"));
            Files.createDirectories(mod.resolve("data/config"));
            Files.createDirectories(mod.resolve("jars"));
            Files.createDirectories(mod.resolve("graphics"));

            Layout layout = new Layout(game, core, mod,
                    core.resolve("starfarer_obf.jar"),
                    core.resolve("data/config/settings.json"),
                    mod.resolve("data/config/settings.json"),
                    core.resolve("jars/core.jar"),
                    mod.resolve("jars/mod.jar"),
                    mod.resolve("graphics/sprite.png"));
            Files.writeString(layout.gameJar, "game-v1");
            Files.writeString(layout.coreSettings, "{\"ruleCommandPackages\":[\"a\"]}");
            Files.writeString(layout.modSettings, "{\"ruleCommandPackages\":[\"a\",\"b\"]}");
            Files.writeString(layout.coreJar, "COREJAR-v1");
            Files.writeString(layout.modJar, "MODJAR-v1");
            Files.writeString(layout.unrelated, "a sprite");
            return layout;
        }

        ResourceIndex index(boolean reverse) throws Exception {
            List<ResourceIndex.Root> roots = reverse
                    ? List.of(new ResourceIndex.Root("mod", mod, false),
                            new ResourceIndex.Root("core", core, true))
                    : List.of(new ResourceIndex.Root("core", core, true),
                            new ResourceIndex.Root("mod", mod, false));
            int coreRoot = reverse ? 1 : 0;
            int modRoot = reverse ? 0 : 1;

            Map<String, List<ResourceIndex.Provider>> entries = new LinkedHashMap<>();
            entries.put("data/config/settings.json", ordered(reverse,
                    provider(coreRoot, "data/config/settings.json", coreSettings),
                    provider(modRoot, "data/config/settings.json", modSettings)));
            entries.put("jars/core.jar",
                    List.of(provider(coreRoot, "jars/core.jar", coreJar)));
            entries.put("jars/mod.jar",
                    List.of(provider(modRoot, "jars/mod.jar", modJar)));
            entries.put("graphics/sprite.png",
                    List.of(provider(modRoot, "graphics/sprite.png", unrelated)));
            return new ResourceIndex("a".repeat(64), roots, entries);
        }

        private static List<ResourceIndex.Provider> ordered(
                boolean reverse, ResourceIndex.Provider first, ResourceIndex.Provider second) {
            return reverse ? List.of(second, first) : List.of(first, second);
        }

        private static ResourceIndex.Provider provider(int root, String relative, Path file)
                throws Exception {
            OpenedFileGenerationAuthority.Generation generation = OpenedFileGenerationAuthority.capture(file);
            return new ResourceIndex.Provider(
                    root,
                    relative,
                    Files.size(file),
                    Files.getLastModifiedTime(file).toMillis(),
                    generation.provider(),
                    generation.token());
        }
    }
}
