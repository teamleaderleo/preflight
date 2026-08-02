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

class ProjectileJsonProfileIdentityBuilderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void hashesOnlyOrderedProjectileProvidersAndTheGameJar() throws Exception {
        Path install = temporaryDirectory.resolve("game");
        Path java = install.resolve("starsector-core");
        Path core = temporaryDirectory.resolve("core");
        Path mod = temporaryDirectory.resolve("mod");
        Files.createDirectories(java);
        Files.createDirectories(core.resolve("data/weapons/proj"));
        Files.createDirectories(mod.resolve("data/weapons/proj"));
        Files.createDirectories(mod.resolve("data/shipsystems/proj"));
        Files.writeString(java.resolve("starfarer_obf.jar"), "game");
        Files.writeString(core.resolve("data/weapons/proj/a.proj"), "core");
        Files.writeString(mod.resolve("data/weapons/proj/a.proj"), "override");
        Files.writeString(mod.resolve("data/shipsystems/proj/system.proj"), "system");
        Files.writeString(mod.resolve("unrelated.txt"), "one");

        ResourceIndex resources = index(core, mod);
        ProjectileJsonProfileIdentityBuilder.Result baseline =
                ProjectileJsonProfileIdentityBuilder.build(install, resources);
        assertEquals(2, baseline.logicalPaths());
        assertEquals(3, baseline.providerCount());
        assertEquals(baseline.identitySha256(),
                ProjectileJsonProfileIdentityBuilder.build(install, resources).identitySha256());

        Files.writeString(mod.resolve("unrelated.txt"), "two");
        assertEquals(baseline.identitySha256(),
                ProjectileJsonProfileIdentityBuilder.build(install, resources).identitySha256());

        Files.writeString(mod.resolve("data/weapons/proj/a.proj"), "changed!");
        ResourceIndex changed = index(core, mod);
        assertNotEquals(baseline.identitySha256(),
                ProjectileJsonProfileIdentityBuilder.build(install, changed).identitySha256());

        String beforeGameChange =
                ProjectileJsonProfileIdentityBuilder.build(install, changed).identitySha256();
        Files.writeString(java.resolve("starfarer_obf.jar"), "new-game");
        assertNotEquals(beforeGameChange,
                ProjectileJsonProfileIdentityBuilder.build(install, changed).identitySha256());
    }

    private static ResourceIndex index(Path core, Path mod) throws Exception {
        Path coreFile = core.resolve("data/weapons/proj/a.proj");
        Path override = mod.resolve("data/weapons/proj/a.proj");
        Path system = mod.resolve("data/shipsystems/proj/system.proj");
        return new ResourceIndex(
                "a".repeat(64),
                List.of(
                        new ResourceIndex.Root("core", core, true),
                        new ResourceIndex.Root("mod", mod, false)),
                Map.of(
                        "data/weapons/proj/a.proj", List.of(
                                provider(0, "data/weapons/proj/a.proj", coreFile),
                                provider(1, "data/weapons/proj/a.proj", override)),
                        "data/shipsystems/proj/system.proj", List.of(
                                provider(1, "data/shipsystems/proj/system.proj", system))));
    }

    private static ResourceIndex.Provider provider(int root, String relative, Path file) throws Exception {
        return new ResourceIndex.Provider(
                root, relative, Files.size(file), Files.getLastModifiedTime(file).toMillis());
    }
}
