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

class HullJsonProfileIdentityBuilderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void hashesOnlyOrderedHullProvidersAndTheGameJar() throws Exception {
        Path install = temporaryDirectory.resolve("game");
        Path java = install.resolve("starsector-core");
        Path core = temporaryDirectory.resolve("core");
        Path mod = temporaryDirectory.resolve("mod");
        Files.createDirectories(java);
        Files.createDirectories(core.resolve("data/hulls"));
        Files.createDirectories(mod.resolve("data/hulls"));
        Files.writeString(java.resolve("starfarer_obf.jar"), "game");
        Files.writeString(core.resolve("data/hulls/a.ship"), "core");
        Files.writeString(mod.resolve("data/hulls/a.ship"), "override");
        Files.writeString(mod.resolve("data/hulls/a.skin"), "unrelated");

        ResourceIndex resources = index(core, mod);
        HullJsonProfileIdentityBuilder.Result baseline =
                HullJsonProfileIdentityBuilder.build(install, resources);
        assertEquals(1, baseline.logicalPaths());
        assertEquals(2, baseline.providerCount());
        assertEquals(baseline.identitySha256(),
                HullJsonProfileIdentityBuilder.build(install, resources).identitySha256());

        Files.writeString(mod.resolve("data/hulls/a.skin"), "changed skin");
        assertEquals(baseline.identitySha256(),
                HullJsonProfileIdentityBuilder.build(install, resources).identitySha256());

        Files.writeString(mod.resolve("data/hulls/a.ship"), "changed!");
        ResourceIndex changed = index(core, mod);
        assertNotEquals(baseline.identitySha256(),
                HullJsonProfileIdentityBuilder.build(install, changed).identitySha256());

        String beforeGameChange = HullJsonProfileIdentityBuilder.build(install, changed).identitySha256();
        Files.writeString(java.resolve("starfarer_obf.jar"), "new-game");
        assertNotEquals(beforeGameChange,
                HullJsonProfileIdentityBuilder.build(install, changed).identitySha256());
    }

    private static ResourceIndex index(Path core, Path mod) throws Exception {
        Path coreFile = core.resolve("data/hulls/a.ship");
        Path override = mod.resolve("data/hulls/a.ship");
        Path skin = mod.resolve("data/hulls/a.skin");
        return new ResourceIndex(
                "a".repeat(64),
                List.of(
                        new ResourceIndex.Root("core", core, true),
                        new ResourceIndex.Root("mod", mod, false)),
                Map.of(
                        "data/hulls/a.ship", List.of(
                                provider(0, "data/hulls/a.ship", coreFile),
                                provider(1, "data/hulls/a.ship", override)),
                        "data/hulls/a.skin", List.of(provider(1, "data/hulls/a.skin", skin))));
    }

    private static ResourceIndex.Provider provider(int root, String relative, Path file) throws Exception {
        return new ResourceIndex.Provider(
                root, relative, Files.size(file), Files.getLastModifiedTime(file).toMillis());
    }
}
