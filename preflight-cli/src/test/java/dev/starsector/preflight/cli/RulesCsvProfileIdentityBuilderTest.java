package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import dev.starsector.preflight.core.ResourceIndex;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RulesCsvProfileIdentityBuilderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void hashesOnlyTheGameJarAndOrderedRulesProviders() throws Exception {
        Path game = temporaryDirectory.resolve("game");
        Path core = game.resolve("Contents/Resources/Java");
        Path mod = temporaryDirectory.resolve("mod");
        Files.createDirectories(core.resolve("data/campaign"));
        Files.createDirectories(mod.resolve("data/campaign"));
        Files.writeString(core.resolve("starfarer_obf.jar"), "game-v1");
        Files.writeString(core.resolve("data/campaign/rules.csv"), "id,trigger\ncore,start");
        Files.writeString(mod.resolve("data/campaign/rules.csv"), "id,trigger\nmod,start");
        Files.writeString(mod.resolve("data/campaign/other.csv"), "ignored");

        ResourceIndex index = index(core, mod, false);
        RulesCsvProfileIdentityBuilder.Result first = RulesCsvProfileIdentityBuilder.build(game, index);
        assertEquals(2L, first.providerCount());

        Files.writeString(mod.resolve("data/campaign/other.csv"), "still ignored");
        ResourceIndex unrelated = index(core, mod, false);
        assertEquals(first.identitySha256(),
                RulesCsvProfileIdentityBuilder.build(game, unrelated).identitySha256());

        Files.writeString(mod.resolve("data/campaign/rules.csv"), "id,trigger\nchanged,start");
        ResourceIndex changed = index(core, mod, false);
        assertNotEquals(first.identitySha256(),
                RulesCsvProfileIdentityBuilder.build(game, changed).identitySha256());
    }

    @Test
    void providerOrderAndGameJarArePartOfTheIdentity() throws Exception {
        Path game = temporaryDirectory.resolve("ordered-game");
        Path core = game.resolve("Contents/Resources/Java");
        Path mod = temporaryDirectory.resolve("ordered-mod");
        Files.createDirectories(core.resolve("data/campaign"));
        Files.createDirectories(mod.resolve("data/campaign"));
        Files.writeString(core.resolve("starfarer_obf.jar"), "game-v1");
        Files.writeString(core.resolve("data/campaign/rules.csv"), "core");
        Files.writeString(mod.resolve("data/campaign/rules.csv"), "mod");

        ResourceIndex forward = index(core, mod, false);
        ResourceIndex reverse = index(core, mod, true);
        String first = RulesCsvProfileIdentityBuilder.build(game, forward).identitySha256();
        assertNotEquals(first, RulesCsvProfileIdentityBuilder.build(game, reverse).identitySha256());

        Files.writeString(core.resolve("starfarer_obf.jar"), "game-v2");
        assertNotEquals(first, RulesCsvProfileIdentityBuilder.build(game, forward).identitySha256());
    }

    private static ResourceIndex index(Path core, Path mod, boolean reverse) throws Exception {
        Path coreRules = core.resolve("data/campaign/rules.csv");
        Path modRules = mod.resolve("data/campaign/rules.csv");
        Path other = mod.resolve("data/campaign/other.csv");
        List<ResourceIndex.Root> roots = reverse
                ? List.of(new ResourceIndex.Root("mod", mod, false),
                        new ResourceIndex.Root("core", core, true))
                : List.of(new ResourceIndex.Root("core", core, true),
                        new ResourceIndex.Root("mod", mod, false));
        int coreIndex = reverse ? 1 : 0;
        int modIndex = reverse ? 0 : 1;
        List<ResourceIndex.Provider> rules = reverse
                ? List.of(provider(modIndex, "data/campaign/rules.csv", modRules),
                        provider(coreIndex, "data/campaign/rules.csv", coreRules))
                : List.of(provider(coreIndex, "data/campaign/rules.csv", coreRules),
                        provider(modIndex, "data/campaign/rules.csv", modRules));
        Map<String, List<ResourceIndex.Provider>> entries = Files.isRegularFile(other)
                ? Map.of(
                        "data/campaign/rules.csv", rules,
                        "data/campaign/other.csv",
                        List.of(provider(modIndex, "data/campaign/other.csv", other)))
                : Map.of("data/campaign/rules.csv", rules);
        return new ResourceIndex("a".repeat(64), roots, entries);
    }

    private static ResourceIndex.Provider provider(int root, String relative, Path file) throws Exception {
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
